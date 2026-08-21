package dev.shinsou.kmp.search

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.DerivedRightsCleanupTarget
import dev.shinsou.kmp.rights.ProtectionAuthorization
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsHint
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

public data class DerivedSearchCleanupResult(
    val documentsExamined: Int,
    val documentsPurged: Int,
)

/**
 * Restart-safe local derived full-text index hosted by the platform-owned content SQLite driver.
 *
 * The normalized token table is an inverted index that behaves consistently on Android, iOS and
 * Desktop without relying on an optional SQLite FTS word breaker. Plaintext is still derived and
 * rebuildable, but it is deliberately durable so global search does not depend on opening a reader
 * in the current process. Every count/search operation re-resolves the current rights grant first;
 * denied or undecodable rows are physically removed together with their tokens.
 */
public class DerivedLocalFullTextIndex(
    private val driver: SqlDriver,
    private val operationGate: ContentOperationGate,
    json: Json = LocalFullTextIndexJson,
) : LocalFullTextIndex {
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val transactions = object : TransacterImpl(driver) {}
    private val indexMutex = SynchronousLock()

    /** Non-serializable provider sessions intentionally disappear at process restart. */
    private val liveAuthorizations = LinkedHashMap<String, ProtectionAuthorization>()

    init {
        LocalFullTextIndexSchema.create(driver).value
        val formatVersion = driver.executeQuery(
            identifier = null,
            sql = "SELECT format_version FROM $TABLE_SEARCH_SCHEMA WHERE singleton_id = 1",
            mapper = { cursor ->
                check(cursor.next().value) { "Local search schema marker is missing" }
                QueryResult.Value(requireNotNull(cursor.getLong(0)))
            },
            parameters = 0,
        ).value
        require(formatVersion == LocalFullTextIndexSchema.version) {
            "Unsupported local search schema version $formatVersion"
        }
    }

    override val documentCount: Int
        get() = withIndexLock {
            purgeUnauthorizedLocked()
            scalarLong("SELECT COUNT(*) FROM $TABLE_SEARCH_DOCUMENTS").toBoundedInt(
                "Search document count",
            )
        }

    override fun upsert(document: SearchableTextDocument) {
        val prepared = prepareUpsert(document)
        withIndexLock { upsertLocked(prepared) }
    }

    /** Foreground-safe variant: lock contention suspends the caller coroutine instead of failing. */
    public suspend fun upsertForeground(document: SearchableTextDocument) {
        // Reader hydration is cancellable: leaving the reader must stop tokenization before it
        // reaches the SQLite transaction, even for a multi-megabyte plain-text resource.
        val prepared = prepareUpsertInBackground(document)
        withIndexLockSuspending { upsertLocked(prepared) }
    }

    /** Cancellable background writer used by reconciliation one locator block at a time. */
    internal suspend fun upsertInBackground(document: SearchableTextDocument) {
        val prepared = prepareUpsertInBackground(document)
        withIndexLockSuspending { upsertLocked(prepared) }
    }

    private fun prepareUpsert(document: SearchableTextDocument): PreparedSearchUpsert {
        require(document.text.length <= MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH) {
            "Search document must be split into bounded derived segments"
        }
        val access = document.access.withActualSearchTextCharacters(document.canonicalDocumentUtf16Length)
        operationGate.requireAllowed(access, ContentOperation.SEARCH_INDEX)
        val source = document.copy(access = access)
        val tokens = CjkLatinFullTextTokenizer.tokenize(source.text)
        val encoded = codec.encodeToString(PersistedSearchMetadata.from(source))
        require(encoded.length <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        return PreparedSearchUpsert(source, tokens, encoded)
    }

    private suspend fun prepareUpsertInBackground(
        document: SearchableTextDocument,
    ): PreparedSearchUpsert {
        require(document.text.length <= MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH) {
            "Search document must be split into bounded derived segments"
        }
        val access = document.access.withActualSearchTextCharacters(document.canonicalDocumentUtf16Length)
        operationGate.requireAllowed(access, ContentOperation.SEARCH_INDEX)
        val source = document.copy(access = access)
        val tokens = CjkLatinFullTextTokenizer.tokenizeCancellable(source.text)
        val encoded = codec.encodeToString(PersistedSearchMetadata.from(source))
        require(encoded.length <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        return PreparedSearchUpsert(source, tokens, encoded)
    }

    private fun upsertLocked(prepared: PreparedSearchUpsert) {
        val source = prepared.source
        // Re-check after any suspending lock wait and immediately before the SQLite side effect.
        operationGate.execute(source.access, ContentOperation.SEARCH_INDEX) {
            transactions.transactionWithResult<Unit>(noEnclosing = false) {
                deleteDocumentRowsLocked(source.documentId)
                driver.execute(
                    identifier = null,
                    sql = """
                        INSERT INTO $TABLE_SEARCH_DOCUMENTS(
                          document_id, publication_id, acquisition_id, unit_id,
                          content_revision, resource_id, block_id, base_offset_utf16,
                          canonical_length_utf16, metadata_json, body_text
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = 11,
                ) {
                    bindString(0, source.documentId)
                    bindString(1, source.scope.publicationId.value)
                    bindString(2, source.scope.acquisitionId)
                    bindString(3, source.scope.unitId.value)
                    bindLong(4, source.scope.contentRevision)
                    bindString(5, source.resourceId)
                    bindString(6, source.blockId)
                    bindLong(7, source.baseOffsetUtf16.toLong())
                    bindLong(8, source.canonicalDocumentUtf16Length.toLong())
                    bindString(9, prepared.encodedMetadata)
                    bindString(10, source.text)
                }.value
                prepared.tokens.chunked(SEARCH_TOKEN_INSERT_BATCH_SIZE).forEach { batch ->
                    val values = batch.joinToString(",") { "(?, ?, ?, ?)" }
                    driver.execute(
                        identifier = null,
                        sql = """
                            INSERT INTO $TABLE_SEARCH_TOKENS(
                              document_id, token, start_utf16, end_utf16
                            ) VALUES $values
                        """.trimIndent(),
                        parameters = batch.size * 4,
                    ) {
                        batch.forEachIndexed { index, token ->
                            val offset = index * 4
                            bindString(offset, source.documentId)
                            bindString(offset + 1, token.value)
                            bindLong(offset + 2, token.startUtf16.toLong())
                            bindLong(offset + 3, token.endUtf16.toLong())
                        }
                    }.value
                }
            }
            source.access.protectionAuthorization?.let { authorization ->
                liveAuthorizations[source.documentId] = authorization
            } ?: liveAuthorizations.remove(source.documentId)
        }
    }

    override fun remove(documentId: String): Boolean {
        requireSearchIdentifier(documentId, "Search document id")
        return withIndexLock { removeLocked(documentId) }
    }

    internal suspend fun removeInBackground(documentId: String): Boolean {
        requireSearchIdentifier(documentId, "Search document id")
        return withIndexLockSuspending { removeLocked(documentId) }
    }

    private fun removeLocked(documentId: String): Boolean =
        transactions.transactionWithResult(noEnclosing = false) {
            val existed = documentExistsLocked(documentId)
            deleteDocumentRowsLocked(documentId)
            liveAuthorizations.remove(documentId)
            existed
        }

    override fun clear() {
        withIndexLock {
            transactions.transactionWithResult<Unit>(noEnclosing = false) {
                driver.execute(null, "DELETE FROM $TABLE_SEARCH_TOKENS", 0).value
                driver.execute(null, "DELETE FROM $TABLE_SEARCH_DOCUMENTS", 0).value
                liveAuthorizations.clear()
            }
        }
    }

    /** Removes derived rows that are no longer reachable from the complete durable content graph. */
    public fun retainOnly(documentIds: Set<String>): Int {
        documentIds.forEach { requireSearchIdentifier(it, "Search document id") }
        return withIndexLock { retainOnlyLocked(documentIds) }
    }

    internal suspend fun retainOnlyInBackground(documentIds: Set<String>): Int {
        documentIds.forEach { requireSearchIdentifier(it, "Search document id") }
        var afterDocumentId: String? = null
        var removed = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = withIndexLockSuspending {
                loadDocumentIdsBatchLocked(afterDocumentId, BACKGROUND_DOCUMENT_SCAN_BATCH_SIZE)
            }
            if (batch.isEmpty()) return removed
            afterDocumentId = batch.last()
            batch.filterNot(documentIds::contains).forEach { documentId ->
                if (removeInBackground(documentId)) removed++
                yield()
            }
            if (batch.size < BACKGROUND_DOCUMENT_SCAN_BATCH_SIZE) return removed
            yield()
        }
    }

    private fun retainOnlyLocked(documentIds: Set<String>): Int {
        val stale = loadAccessRowsLocked().map(SearchAccessRow::documentId).filterNot(documentIds::contains)
        deleteDocumentsLocked(stale)
        return stale.size
    }

    /** Metadata-only reconciliation probe; it never selects or hydrates persisted body text. */
    internal suspend fun isCurrent(
        documentId: String,
        scope: ReadingScope,
        resourceId: String,
        blockId: String,
        baseOffsetUtf16: Int,
        canonicalDocumentUtf16Length: Int,
        access: ContentAccessRequest,
        epubAnchor: EpubSearchAnchor? = null,
    ): Boolean {
        requireSearchIdentifier(documentId, "Search document id")
        scope.validate()
        requireSearchIdentifier(resourceId, "Search resource id")
        requireSearchIdentifier(blockId, "Search block id")
        require(baseOffsetUtf16 >= 0 && canonicalDocumentUtf16Length >= baseOffsetUtf16) {
            "Search reconciliation offsets are invalid"
        }
        val exactAccess = access.withActualSearchTextCharacters(canonicalDocumentUtf16Length)
        if (operationGate.decide(exactAccess, ContentOperation.SEARCH_INDEX) != RightsDecision.ALLOW) {
            return false
        }
        return withIndexLockSuspending {
            val row = loadAccessRowLocked(documentId) ?: return@withIndexLockSuspending false
            val decoded = runCatching { row.decodeAccess() }.getOrNull()
                ?: return@withIndexLockSuspending false
            decoded.first == scope && decoded.second == exactAccess &&
                row.resourceId == resourceId && row.blockId == blockId &&
                row.baseOffsetUtf16 == baseOffsetUtf16.toLong() &&
                row.canonicalLengthUtf16 == canonicalDocumentUtf16Length.toLong() &&
                row.decodeMetadata().epubAnchor == epubAnchor
        }
    }

    override fun purgeUnauthorized(): Int = withIndexLock { purgeUnauthorizedLocked() }

    /** Rights cleanup for the open unit; reader entry must never scan the entire library. */
    public suspend fun purgeUnauthorizedForeground(scope: ReadingScope): Int {
        scope.validate()
        currentCoroutineContext().ensureActive()
        yield()
        currentCoroutineContext().ensureActive()
        return withIndexLockSuspending {
            purgeUnauthorizedLocked(SearchDocumentScope(scope = scope, resourceId = null))
        }
    }

    /**
     * Metadata-only, identity-ordered cleanup for revoked/expired host policy.
     *
     * Each SQLite lock/transaction sees at most [pageSize] rows and the coroutine yields between
     * pages. Callers must run this only from the app's cancellable background actor; reader entry
     * continues to use [purgeUnauthorizedForeground] and never walks the complete library.
     */
    internal suspend fun purgeUnauthorizedInBackground(
        target: DerivedRightsCleanupTarget,
        pageSize: Int = BACKGROUND_RIGHTS_CLEANUP_PAGE_SIZE,
    ): DerivedSearchCleanupResult {
        require(pageSize in 1..BACKGROUND_DOCUMENT_SCAN_BATCH_SIZE) {
            "Search rights-cleanup page size is invalid"
        }
        var afterDocumentId: String? = null
        var examined = 0
        var purged = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            yield()
            currentCoroutineContext().ensureActive()
            val page = withIndexLockSuspending {
                val rows = loadRightsCleanupPageLocked(target, afterDocumentId, pageSize)
                val denied = rows.mapNotNull { row ->
                    val access = runCatching { row.decodeAccess().second }.getOrNull()
                    val belongsToTarget = when (target) {
                        DerivedRightsCleanupTarget.All -> true
                        is DerivedRightsCleanupTarget.Publication ->
                            row.publicationId == target.publicationId.value
                        is DerivedRightsCleanupTarget.Grant -> when {
                            access != null -> access.grantReference == target.reference
                            target.lastKnownScope != null -> true
                            else -> false
                        }
                    }
                    row.documentId.takeIf {
                        belongsToTarget && (access == null ||
                            operationGate.decide(access, ContentOperation.SEARCH_INDEX) !=
                            RightsDecision.ALLOW)
                    }
                }
                deleteDocumentsLocked(denied)
                SearchRightsCleanupPage(
                    lastDocumentId = rows.lastOrNull()?.documentId,
                    rowCount = rows.size,
                    purgedCount = denied.size,
                )
            }
            examined += page.rowCount
            purged += page.purgedCount
            val next = page.lastDocumentId
            if (next == null || page.rowCount < pageSize) {
                return DerivedSearchCleanupResult(examined, purged)
            }
            afterDocumentId = next
        }
    }

    override fun search(query: String, limit: Int): List<FullTextSearchHit> {
        val request = prepareSearch(query, limit)
        if (request.queryTokens.isEmpty()) return emptyList()
        return withIndexLock { searchLocked(request) }
    }

    /** Foreground-safe query; contention suspends its coroutine instead of surfacing as an error. */
    public suspend fun searchForeground(
        query: String,
        limit: Int = DEFAULT_FOREGROUND_SEARCH_LIMIT,
    ): List<FullTextSearchHit> {
        val request = prepareSearch(query, limit)
        if (request.queryTokens.isEmpty()) return emptyList()
        // Global search validates only SQL candidates for this query. A full derived-row rights
        // sweep belongs to the cancellable background actor and must not delay an interactive key.
        return withIndexLockSuspending { searchLocked(request) }
    }

    /**
     * Foreground reader query restricted before SQL ranking/limit is applied.
     *
     * Filtering a global result list in the UI is incorrect: one hundred higher-scoring matches
     * from other books could otherwise hide every hit from the open resource. The complete reading
     * scope also prevents a stale revision which reused a resource id from entering the candidates.
     */
    public suspend fun searchForegroundInResource(
        query: String,
        scope: ReadingScope,
        resourceId: String,
        limit: Int = DEFAULT_FOREGROUND_SEARCH_LIMIT,
    ): List<FullTextSearchHit> {
        scope.validate()
        requireSearchIdentifier(resourceId, "Search resource id")
        val request = prepareSearch(query, limit)
        if (request.queryTokens.isEmpty()) return emptyList()
        currentCoroutineContext().ensureActive()
        // Give reader disposal/navigation cancellation a boundary before any synchronous driver IO.
        yield()
        currentCoroutineContext().ensureActive()
        val documentScope = SearchDocumentScope(scope, resourceId)
        return withIndexLockSuspending { searchLocked(request, documentScope) }
    }

    /** Reader query across every EPUB spine resource in one exact content revision. */
    public suspend fun searchForegroundInScope(
        query: String,
        scope: ReadingScope,
        limit: Int = DEFAULT_FOREGROUND_SEARCH_LIMIT,
    ): List<FullTextSearchHit> {
        scope.validate()
        val request = prepareSearch(query, limit)
        if (request.queryTokens.isEmpty()) return emptyList()
        currentCoroutineContext().ensureActive()
        yield()
        currentCoroutineContext().ensureActive()
        return withIndexLockSuspending {
            searchLocked(request, SearchDocumentScope(scope = scope, resourceId = null))
        }
    }

    private fun prepareSearch(query: String, limit: Int): PreparedSearchQuery {
        require(query.length in 1..MAX_SEARCH_QUERY_CHARS && isWellFormedSearchText(query)) {
            "Search query is invalid"
        }
        require(limit in 1..MAX_SEARCH_RESULT_LIMIT) { "Search result limit is invalid" }
        val queryTokens = CjkLatinFullTextTokenizer.tokenize(query, MAX_SEARCH_QUERY_TOKENS)
            .map(FullTextToken::value)
            .distinct()
        return PreparedSearchQuery(queryTokens, limit)
    }

    private fun searchLocked(
        request: PreparedSearchQuery,
        documentScope: SearchDocumentScope? = null,
    ): List<FullTextSearchHit> {
        // Rights are checked for every SQL candidate before its body can become a hit. Unrelated
        // rows are deliberately left for the paged background sweep instead of turning a search
        // key into a complete-library policy scan.
        val hits = ArrayList<FullTextSearchHit>(request.limit)
        val corrupt = mutableListOf<String>()
        val candidates = loadCandidateIdsLocked(request.queryTokens, documentScope)
        for (candidate in candidates) {
            if (hits.size >= request.limit) break
            val source = runCatching { loadDocumentLocked(candidate.documentId) }.getOrNull()
            if (source == null ||
                operationGate.decide(source.access, ContentOperation.SEARCH_INDEX) != RightsDecision.ALLOW
            ) {
                corrupt += candidate.documentId
                continue
            }
            val matchedTokens = runCatching {
                loadMatchedTokensLocked(candidate.documentId, request.queryTokens, source.text)
            }.getOrNull()
            if (matchedTokens == null || request.queryTokens.any { queryToken ->
                    matchedTokens.none { it.value == queryToken }
                }
            ) {
                corrupt += candidate.documentId
                continue
            }
            hits += source.toSearchHit(request.queryTokens, matchedTokens)
        }
        deleteDocumentsLocked(corrupt.distinct())
        return hits
    }

    private fun purgeUnauthorizedLocked(documentScope: SearchDocumentScope? = null): Int {
        val denied = loadAccessRowsLocked(documentScope).mapNotNull { row ->
            val access = runCatching { row.decodeAccess().second }.getOrNull()
            row.documentId.takeIf {
                access == null ||
                    operationGate.decide(access, ContentOperation.SEARCH_INDEX) != RightsDecision.ALLOW
            }
        }
        deleteDocumentsLocked(denied)
        return denied.size
    }

    private fun loadAccessRowsLocked(
        documentScope: SearchDocumentScope? = null,
    ): List<SearchAccessRow> {
        val scopePredicate = if (documentScope == null) "" else buildString {
            append(
                "WHERE publication_id = ? AND acquisition_id = ? AND unit_id = ? " +
                    "AND content_revision = ?",
            )
            if (documentScope.resourceId != null) append(" AND resource_id = ?")
        }
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT document_id, publication_id, acquisition_id, unit_id,
                       content_revision, resource_id, block_id, base_offset_utf16,
                       canonical_length_utf16, metadata_json
                FROM $TABLE_SEARCH_DOCUMENTS
                $scopePredicate
                ORDER BY document_id
            """.trimIndent(),
            mapper = { cursor ->
                val rows = mutableListOf<SearchAccessRow>()
                while (cursor.next().value) {
                    rows += SearchAccessRow(
                        documentId = requireNotNull(cursor.getString(0)),
                        publicationId = requireNotNull(cursor.getString(1)),
                        acquisitionId = requireNotNull(cursor.getString(2)),
                        unitId = requireNotNull(cursor.getString(3)),
                        contentRevision = requireNotNull(cursor.getLong(4)),
                        resourceId = requireNotNull(cursor.getString(5)),
                        blockId = requireNotNull(cursor.getString(6)),
                        baseOffsetUtf16 = requireNotNull(cursor.getLong(7)),
                        canonicalLengthUtf16 = requireNotNull(cursor.getLong(8)),
                        encodedMetadata = requireNotNull(cursor.getString(9)),
                    )
                }
                QueryResult.Value(rows)
            },
            parameters = when {
                documentScope == null -> 0
                documentScope.resourceId == null -> 4
                else -> 5
            },
            binders = {
                documentScope?.let { exact ->
                    bindString(0, exact.scope.publicationId.value)
                    bindString(1, exact.scope.acquisitionId)
                    bindString(2, exact.scope.unitId.value)
                    bindLong(3, exact.scope.contentRevision)
                    exact.resourceId?.let { bindString(4, it) }
                }
            },
        ).value
    }

    private fun loadDocumentIdsBatchLocked(afterDocumentId: String?, limit: Int): List<String> {
        require(limit in 1..BACKGROUND_DOCUMENT_SCAN_BATCH_SIZE)
        val sql = if (afterDocumentId == null) {
            "SELECT document_id FROM $TABLE_SEARCH_DOCUMENTS ORDER BY document_id LIMIT ?"
        } else {
            "SELECT document_id FROM $TABLE_SEARCH_DOCUMENTS " +
                "WHERE document_id > ? ORDER BY document_id LIMIT ?"
        }
        return driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                val ids = mutableListOf<String>()
                while (cursor.next().value) ids += requireNotNull(cursor.getString(0))
                QueryResult.Value(ids)
            },
            parameters = if (afterDocumentId == null) 1 else 2,
            binders = {
                if (afterDocumentId == null) {
                    bindLong(0, limit.toLong())
                } else {
                    bindString(0, afterDocumentId)
                    bindLong(1, limit.toLong())
                }
            },
        ).value
    }

    private fun loadRightsCleanupPageLocked(
        target: DerivedRightsCleanupTarget,
        afterDocumentId: String?,
        limit: Int,
    ): List<SearchAccessRow> {
        val predicates = buildList {
            if (afterDocumentId != null) add("document_id > ?")
            when (target) {
                DerivedRightsCleanupTarget.All -> Unit
                is DerivedRightsCleanupTarget.Publication -> add("publication_id = ?")
                is DerivedRightsCleanupTarget.Grant -> target.lastKnownScope?.let { scope ->
                    add("publication_id = ?")
                    add("acquisition_id = ?")
                    if (scope.unitId != null) add("unit_id = ?")
                    if (scope.contentRevision != null) add("content_revision = ?")
                }
            }
        }
        val where = if (predicates.isEmpty()) "" else
            predicates.joinToString(prefix = " WHERE ", separator = " AND ")
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT document_id, publication_id, acquisition_id, unit_id,
                       content_revision, resource_id, block_id, base_offset_utf16,
                       canonical_length_utf16, metadata_json
                FROM $TABLE_SEARCH_DOCUMENTS$where
                ORDER BY document_id LIMIT ?
            """.trimIndent(),
            mapper = { cursor ->
                val rows = mutableListOf<SearchAccessRow>()
                while (cursor.next().value) {
                    rows += SearchAccessRow(
                        documentId = requireNotNull(cursor.getString(0)),
                        publicationId = requireNotNull(cursor.getString(1)),
                        acquisitionId = requireNotNull(cursor.getString(2)),
                        unitId = requireNotNull(cursor.getString(3)),
                        contentRevision = requireNotNull(cursor.getLong(4)),
                        resourceId = requireNotNull(cursor.getString(5)),
                        blockId = requireNotNull(cursor.getString(6)),
                        baseOffsetUtf16 = requireNotNull(cursor.getLong(7)),
                        canonicalLengthUtf16 = requireNotNull(cursor.getLong(8)),
                        encodedMetadata = requireNotNull(cursor.getString(9)),
                    )
                }
                QueryResult.Value(rows)
            },
            parameters = predicates.size + 1,
            binders = {
                var index = 0
                afterDocumentId?.let { bindString(index++, it) }
                when (target) {
                    DerivedRightsCleanupTarget.All -> Unit
                    is DerivedRightsCleanupTarget.Publication ->
                        bindString(index++, target.publicationId.value)
                    is DerivedRightsCleanupTarget.Grant -> target.lastKnownScope?.let { scope ->
                        bindString(index++, scope.publicationId.value)
                        bindString(index++, scope.acquisitionId)
                        scope.unitId?.let { bindString(index++, it.value) }
                        scope.contentRevision?.let { bindLong(index++, it) }
                    }
                }
                bindLong(index, limit.toLong())
            },
        ).value
    }

    private fun loadAccessRowLocked(documentId: String): SearchAccessRow? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT document_id, publication_id, acquisition_id, unit_id,
                   content_revision, resource_id, block_id, base_offset_utf16,
                   canonical_length_utf16, metadata_json
            FROM $TABLE_SEARCH_DOCUMENTS WHERE document_id = ?
        """.trimIndent(),
        mapper = { cursor ->
            if (!cursor.next().value) {
                QueryResult.Value(null)
            } else {
                QueryResult.Value(
                    SearchAccessRow(
                        documentId = requireNotNull(cursor.getString(0)),
                        publicationId = requireNotNull(cursor.getString(1)),
                        acquisitionId = requireNotNull(cursor.getString(2)),
                        unitId = requireNotNull(cursor.getString(3)),
                        contentRevision = requireNotNull(cursor.getLong(4)),
                        resourceId = requireNotNull(cursor.getString(5)),
                        blockId = requireNotNull(cursor.getString(6)),
                        baseOffsetUtf16 = requireNotNull(cursor.getLong(7)),
                        canonicalLengthUtf16 = requireNotNull(cursor.getLong(8)),
                        encodedMetadata = requireNotNull(cursor.getString(9)),
                    ),
                )
            }
        },
        parameters = 1,
        binders = { bindString(0, documentId) },
    ).value

    private fun loadDocumentLocked(documentId: String): SearchableTextDocument? = driver.executeQuery(
        identifier = null,
        sql = """
            SELECT document_id, publication_id, acquisition_id, unit_id,
                   content_revision, resource_id, block_id, base_offset_utf16,
                   canonical_length_utf16, metadata_json, body_text
            FROM $TABLE_SEARCH_DOCUMENTS WHERE document_id = ?
        """.trimIndent(),
        mapper = { cursor ->
            if (!cursor.next().value) {
                QueryResult.Value(null)
            } else {
                val row = SearchAccessRow(
                    documentId = requireNotNull(cursor.getString(0)),
                    publicationId = requireNotNull(cursor.getString(1)),
                    acquisitionId = requireNotNull(cursor.getString(2)),
                    unitId = requireNotNull(cursor.getString(3)),
                    contentRevision = requireNotNull(cursor.getLong(4)),
                    resourceId = requireNotNull(cursor.getString(5)),
                    blockId = requireNotNull(cursor.getString(6)),
                    baseOffsetUtf16 = requireNotNull(cursor.getLong(7)),
                    canonicalLengthUtf16 = requireNotNull(cursor.getLong(8)),
                    encodedMetadata = requireNotNull(cursor.getString(9)),
                )
                val metadata = row.decodeMetadata()
                val access = row.decodeAccess(metadata)
                QueryResult.Value(
                    SearchableTextDocument(
                        documentId = row.documentId,
                        scope = access.first,
                        resourceId = row.resourceId,
                        blockId = row.blockId,
                        text = requireNotNull(cursor.getString(10)),
                        access = access.second,
                        baseOffsetUtf16 = row.baseOffsetUtf16.toBoundedInt("Search block offset"),
                        canonicalDocumentUtf16Length = row.canonicalLengthUtf16.toBoundedInt(
                            "Search canonical length",
                        ),
                        epubAnchor = metadata.epubAnchor,
                    ),
                )
            }
        },
        parameters = 1,
        binders = { bindString(0, documentId) },
    ).value

    private fun SearchAccessRow.decodeMetadata(): PersistedSearchMetadata {
        require(encodedMetadata.length <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        val metadata = codec.decodeFromString<PersistedSearchMetadata>(encodedMetadata)
        metadata.validate()
        check(documentId == metadata.documentId) { "Search document id/body mismatch" }
        check(publicationId == metadata.scope.publicationId.value) { "Search publication/body mismatch" }
        check(acquisitionId == metadata.scope.acquisitionId) { "Search acquisition/body mismatch" }
        check(unitId == metadata.scope.unitId.value) { "Search unit/body mismatch" }
        check(contentRevision == metadata.scope.contentRevision) { "Search revision/body mismatch" }
        check(resourceId == metadata.resourceId) { "Search resource/body mismatch" }
        check(blockId == metadata.blockId) { "Search block/body mismatch" }
        check(baseOffsetUtf16 == metadata.baseOffsetUtf16.toLong()) { "Search offset/body mismatch" }
        check(canonicalLengthUtf16 == metadata.canonicalDocumentUtf16Length.toLong()) {
            "Search canonical length/body mismatch"
        }
        return metadata
    }

    private fun SearchAccessRow.decodeAccess(
        metadata: PersistedSearchMetadata = decodeMetadata(),
    ): Pair<ReadingScope, ContentAccessRequest> {
        val authorization = if (metadata.hadProtectionAuthorization) {
            liveAuthorizations[documentId]
                ?: throw IllegalStateException("Persisted protected search row has no live authorization")
        } else {
            null
        }
        val access = metadata.access.toRequest(authorization)
        requireSearchScopeMatches(metadata.scope, access)
        return metadata.scope to access
    }

    private fun loadCandidateIdsLocked(
        queryTokens: List<String>,
        documentScope: SearchDocumentScope?,
    ): List<SearchCandidate> {
        val placeholders = queryTokens.joinToString(",") { "?" }
        val scopedJoin = if (documentScope == null) "" else
            "JOIN $TABLE_SEARCH_DOCUMENTS AS documents ON documents.document_id = tokens.document_id"
        val scopedPredicate = if (documentScope == null) "" else buildString {
            append(
                " AND documents.publication_id = ?" +
                    " AND documents.acquisition_id = ?" +
                    " AND documents.unit_id = ?" +
                    " AND documents.content_revision = ?",
            )
            if (documentScope.resourceId != null) append(" AND documents.resource_id = ?")
        }
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT tokens.document_id
                FROM $TABLE_SEARCH_TOKENS AS tokens
                $scopedJoin
                WHERE tokens.token IN ($placeholders)
                $scopedPredicate
                GROUP BY tokens.document_id
                HAVING COUNT(DISTINCT tokens.token) = ?
                ORDER BY COUNT(*) DESC, tokens.document_id ASC
            """.trimIndent(),
            mapper = { cursor ->
                val rows = mutableListOf<SearchCandidate>()
                while (cursor.next().value) {
                    rows += SearchCandidate(documentId = requireNotNull(cursor.getString(0)))
                }
                QueryResult.Value(rows)
            },
            parameters = queryTokens.size + when {
                documentScope == null -> 1
                documentScope.resourceId == null -> 5
                else -> 6
            },
            binders = {
                queryTokens.forEachIndexed { index, token -> bindString(index, token) }
                var index = queryTokens.size
                if (documentScope != null) {
                    bindString(index++, documentScope.scope.publicationId.value)
                    bindString(index++, documentScope.scope.acquisitionId)
                    bindString(index++, documentScope.scope.unitId.value)
                    bindLong(index++, documentScope.scope.contentRevision)
                    documentScope.resourceId?.let { bindString(index++, it) }
                }
                bindLong(index, queryTokens.size.toLong())
            },
        ).value
    }

    private fun loadMatchedTokensLocked(
        documentId: String,
        queryTokens: List<String>,
        body: String,
    ): List<FullTextToken> {
        val placeholders = queryTokens.joinToString(",") { "?" }
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT token, start_utf16, end_utf16
                FROM $TABLE_SEARCH_TOKENS
                WHERE document_id = ? AND token IN ($placeholders)
                ORDER BY start_utf16, end_utf16, token
            """.trimIndent(),
            mapper = { cursor ->
                val rows = mutableListOf<FullTextToken>()
                while (cursor.next().value) {
                    val token = FullTextToken(
                        value = requireNotNull(cursor.getString(0)),
                        startUtf16 = requireNotNull(cursor.getLong(1)).toBoundedInt("Search token start"),
                        endUtf16 = requireNotNull(cursor.getLong(2)).toBoundedInt("Search token end"),
                    )
                    check(token.endUtf16 <= body.length &&
                        body.substring(token.startUtf16, token.endUtf16).lowercase() == token.value
                    ) { "Search token/body mismatch" }
                    rows += token
                }
                QueryResult.Value(rows)
            },
            parameters = queryTokens.size + 1,
            binders = {
                bindString(0, documentId)
                queryTokens.forEachIndexed { index, token -> bindString(index + 1, token) }
            },
        ).value
    }

    private fun SearchableTextDocument.toSearchHit(
        queryTokens: List<String>,
        matchedTokens: List<FullTextToken>,
    ): FullTextSearchHit {
        val first = requireNotNull(matchedTokens.minByOrNull(FullTextToken::startUtf16))
        val absoluteOffset = baseOffsetUtf16 + first.startUtf16
        val snippetStart = safeSearchBoundaryAtOrBefore(text, maxOf(0, first.startUtf16 - SEARCH_SNIPPET_CONTEXT))
        val snippetEnd = safeSearchBoundaryAtOrAfter(
            text,
            minOf(text.length, first.endUtf16 + SEARCH_SNIPPET_CONTEXT),
        )
        val boundedSnippetEnd = safeSearchBoundaryAtOrBefore(
            text,
            minOf(snippetEnd, snippetStart + MAX_SEARCH_SNIPPET_CHARS),
        )
        return FullTextSearchHit(
            documentId = documentId,
            locator = locatorForSearchMatch(
                absoluteOffset = absoluteOffset,
                quote = text.searchQuoteAt(first.startUtf16, first.endUtf16),
            ),
            snippet = text.substring(snippetStart, boundedSnippetEnd),
            matchedTerms = queryTokens,
            score = matchedTokens.size,
        )
    }

    private fun documentExistsLocked(documentId: String): Boolean = driver.executeQuery(
        identifier = null,
        sql = "SELECT 1 FROM $TABLE_SEARCH_DOCUMENTS WHERE document_id = ?",
        mapper = { cursor -> QueryResult.Value(cursor.next().value) },
        parameters = 1,
        binders = { bindString(0, documentId) },
    ).value

    private fun deleteDocumentsLocked(documentIds: List<String>) {
        if (documentIds.isEmpty()) return
        transactions.transactionWithResult<Unit>(noEnclosing = false) {
            documentIds.forEach { documentId ->
                deleteDocumentRowsLocked(documentId)
                liveAuthorizations.remove(documentId)
            }
        }
    }

    private fun deleteDocumentRowsLocked(documentId: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_SEARCH_TOKENS WHERE document_id = ?",
            parameters = 1,
        ) { bindString(0, documentId) }.value
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_SEARCH_DOCUMENTS WHERE document_id = ?",
            parameters = 1,
        ) { bindString(0, documentId) }.value
    }

    private fun scalarLong(sql: String): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = 0,
    ).value

    private inline fun <T> withIndexLock(block: () -> T): T {
        return indexMutex.withLock(block)
    }

    private suspend inline fun <T> withIndexLockSuspending(crossinline block: () -> T): T =
        indexMutex.withLock { block() }

    private data class SearchAccessRow(
        val documentId: String,
        val publicationId: String,
        val acquisitionId: String,
        val unitId: String,
        val contentRevision: Long,
        val resourceId: String,
        val blockId: String,
        val baseOffsetUtf16: Long,
        val canonicalLengthUtf16: Long,
        val encodedMetadata: String,
    )

    private data class SearchCandidate(val documentId: String)
    private data class SearchDocumentScope(
        val scope: ReadingScope,
        val resourceId: String?,
    )
    private data class PreparedSearchQuery(val queryTokens: List<String>, val limit: Int)
    private data class PreparedSearchUpsert(
        val source: SearchableTextDocument,
        val tokens: List<FullTextToken>,
        val encodedMetadata: String,
    )
    private data class SearchRightsCleanupPage(
        val lastDocumentId: String?,
        val rowCount: Int,
        val purgedCount: Int,
    )
}

@Serializable
private data class PersistedSearchMetadata(
    val schemaVersion: Int,
    val documentId: String,
    val scope: ReadingScope,
    val resourceId: String,
    val blockId: String,
    val baseOffsetUtf16: Int,
    val canonicalDocumentUtf16Length: Int,
    val access: PersistedSearchAccess,
    val hadProtectionAuthorization: Boolean,
    val epubAnchor: EpubSearchAnchor? = null,
) {
    fun validate() {
        require(schemaVersion in 1..SEARCH_METADATA_SCHEMA_VERSION) { "Unsupported search metadata version" }
        requireSearchIdentifier(documentId, "Search document id")
        scope.validate()
        requireSearchIdentifier(resourceId, "Search resource id")
        requireSearchIdentifier(blockId, "Search block id")
        require(baseOffsetUtf16 >= 0 &&
            canonicalDocumentUtf16Length >= baseOffsetUtf16
        ) { "Search metadata offsets are invalid" }
        access.validate()
        epubAnchor?.validate()
    }

    companion object {
        fun from(document: SearchableTextDocument): PersistedSearchMetadata = PersistedSearchMetadata(
            schemaVersion = SEARCH_METADATA_SCHEMA_VERSION,
            documentId = document.documentId,
            scope = document.scope,
            resourceId = document.resourceId,
            blockId = document.blockId,
            baseOffsetUtf16 = document.baseOffsetUtf16,
            canonicalDocumentUtf16Length = document.canonicalDocumentUtf16Length,
            access = PersistedSearchAccess.from(document.access),
            hadProtectionAuthorization = document.access.protectionAuthorization != null,
            epubAnchor = document.epubAnchor,
        ).also(PersistedSearchMetadata::validate)
    }
}

@Serializable
private data class PersistedSearchAccess(
    val grantReference: RightsGrantRef?,
    val scope: RightsScope,
    val offlineBytes: Long?,
    val textCharacters: Long?,
    val watermarkApplied: Boolean,
    val hint: RightsHint?,
) {
    fun validate() {
        grantReference?.validate()
        scope.validate()
        RightsOperationContext(offlineBytes, textCharacters, watermarkApplied)
        hint?.validate()
    }

    fun toRequest(authorization: ProtectionAuthorization?): ContentAccessRequest = ContentAccessRequest(
        grantReference = grantReference,
        scope = scope,
        context = RightsOperationContext(offlineBytes, textCharacters, watermarkApplied),
        hint = hint,
        protectionAuthorization = authorization,
    )

    companion object {
        fun from(access: ContentAccessRequest): PersistedSearchAccess = PersistedSearchAccess(
            grantReference = access.grantReference,
            scope = access.scope,
            offlineBytes = access.context.offlineBytes,
            textCharacters = access.context.textCharacters,
            watermarkApplied = access.context.watermarkApplied,
            hint = access.hint,
        ).also(PersistedSearchAccess::validate)
    }
}

/** Independent schema marker because this table is rebuildable and not part of wire state. */
public object LocalFullTextIndexSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(
            null,
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_SCHEMA(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL
                )
            """.trimIndent(),
            0,
        ).value
        driver.execute(
            null,
            "INSERT OR IGNORE INTO $TABLE_SEARCH_SCHEMA(singleton_id, format_version) VALUES (1, $version)",
            0,
        ).value
        driver.execute(
            null,
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_DOCUMENTS(
                  document_id TEXT NOT NULL PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_id TEXT NOT NULL,
                  unit_id TEXT NOT NULL,
                  content_revision INTEGER NOT NULL,
                  resource_id TEXT NOT NULL,
                  block_id TEXT NOT NULL,
                  base_offset_utf16 INTEGER NOT NULL,
                  canonical_length_utf16 INTEGER NOT NULL,
                  metadata_json TEXT NOT NULL,
                  body_text TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            0,
        ).value
        driver.execute(
            null,
            """
                CREATE INDEX IF NOT EXISTS content_search_documents_scope
                ON $TABLE_SEARCH_DOCUMENTS(
                  publication_id, acquisition_id, unit_id, content_revision, resource_id, block_id
                )
            """.trimIndent(),
            0,
        ).value
        driver.execute(
            null,
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_TOKENS(
                  document_id TEXT NOT NULL,
                  token TEXT NOT NULL,
                  start_utf16 INTEGER NOT NULL,
                  end_utf16 INTEGER NOT NULL,
                  PRIMARY KEY(document_id, token, start_utf16, end_utf16)
                ) WITHOUT ROWID
            """.trimIndent(),
            0,
        ).value
        driver.execute(
            null,
            """
                CREATE INDEX IF NOT EXISTS content_search_tokens_lookup
                ON $TABLE_SEARCH_TOKENS(token, document_id)
            """.trimIndent(),
            0,
        ).value
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Local search database cannot migrate backwards" }
        if (oldVersion < version && newVersion >= version) create(driver)
        callbacks.sortedBy(AfterVersion::afterVersion).forEach { callback ->
            if (callback.afterVersion in oldVersion until newVersion) callback.block(driver)
        }
        return QueryResult.Unit
    }
}

private fun ContentAccessRequest.withActualSearchTextCharacters(length: Int): ContentAccessRequest = copy(
    context = RightsOperationContext(
        offlineBytes = context.offlineBytes,
        textCharacters = length.toLong(),
        watermarkApplied = context.watermarkApplied,
    ),
)

private fun requireSearchScopeMatches(scope: ReadingScope, access: ContentAccessRequest) {
    val rights = access.scope
    require(rights.publicationId == scope.publicationId &&
        rights.acquisitionId == scope.acquisitionId &&
        rights.unitId == scope.unitId &&
        rights.contentRevision == scope.contentRevision
    ) { "Search document and rights scope do not match" }
}

private fun String.searchQuoteAt(start: Int, end: Int): TextQuote {
    val prefixStart = safeSearchBoundaryAtOrAfter(this, maxOf(0, start - SEARCH_QUOTE_CONTEXT))
    val suffixEnd = safeSearchBoundaryAtOrBefore(this, minOf(length, end + SEARCH_QUOTE_CONTEXT))
    return TextQuote(
        exact = substring(start, end),
        prefix = substring(prefixStart, start),
        suffix = substring(end, suffixEnd),
    )
}

private fun safeSearchBoundaryAtOrBefore(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (index in 1 until text.length &&
        text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
    ) index--
    return index
}

private fun safeSearchBoundaryAtOrAfter(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (index in 1 until text.length &&
        text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()
    ) index++
    return index
}

private fun isWellFormedSearchText(value: String): Boolean {
    for (index in value.indices) {
        val character = value[index]
        if (character == '\u0000' || (character.isISOControl() && character !in "\n\r\t")) return false
        if (character.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
        } else if (character.isLowSurrogate()) {
            if (index == 0 || !value[index - 1].isHighSurrogate()) return false
        }
    }
    return true
}

private fun requireSearchIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_SEARCH_IDENTIFIER_LENGTH &&
        value.none(Char::isWhitespace) && value.none(Char::isISOControl)
    ) { "$label must be bounded and printable" }
}

private fun Long.toBoundedInt(label: String): Int {
    require(this in 0..Int.MAX_VALUE.toLong()) { "$label is outside the supported range" }
    return toInt()
}

private const val TABLE_SEARCH_SCHEMA = "content_search_index_schema"
private const val TABLE_SEARCH_DOCUMENTS = "content_search_documents"
private const val TABLE_SEARCH_TOKENS = "content_search_tokens"
private const val SEARCH_METADATA_SCHEMA_VERSION = 2
private const val MAX_SEARCH_METADATA_CHARS = 256_000
private const val MAX_SEARCH_IDENTIFIER_LENGTH = 512
private const val MAX_SEARCH_QUERY_CHARS = 512
private const val MAX_SEARCH_QUERY_TOKENS = 64
private const val MAX_SEARCH_RESULT_LIMIT = 100
private const val DEFAULT_FOREGROUND_SEARCH_LIMIT = 25
private const val SEARCH_TOKEN_INSERT_BATCH_SIZE = 128
private const val BACKGROUND_DOCUMENT_SCAN_BATCH_SIZE = 128
private const val BACKGROUND_RIGHTS_CLEANUP_PAGE_SIZE = 64
private const val SEARCH_QUOTE_CONTEXT = 64
private const val SEARCH_SNIPPET_CONTEXT = 96
private const val MAX_SEARCH_SNIPPET_CHARS = 256

private val LocalFullTextIndexJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
