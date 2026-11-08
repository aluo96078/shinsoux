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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

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

    private var removals: Long = 0L

    /** Advances whenever indexed rows are removed (purge, cleanup, clear); read under the index lock. */
    internal val removalRevision: Long get() = withIndexLock { removals }

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
        return prepared(source, tokens)
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
        return prepared(source, tokens)
    }

    private fun prepared(source: SearchableTextDocument, tokens: List<FullTextToken>): PreparedSearchUpsert {
        // Validates the complete persisted shape exactly as before the storage split.
        val metadata = PersistedSearchMetadata.from(source)
        val context = codec.encodeToString(PersistedSearchContext.from(metadata))
        require(context.length <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        val anchor = metadata.epubAnchor?.let { codec.encodeToString(it) }
        require((anchor?.length ?: 0) <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        val postings = tokens.groupBy(FullTextToken::value).map { (token, occurrences) ->
            SearchPosting(token, occurrences.size, encodeSearchPositions(token, occurrences))
        }
        return PreparedSearchUpsert(source, postings, context, anchor)
    }

    /** Shared row for every block of one resource revision with the same access request. */
    private fun contextKeyLocked(source: SearchableTextDocument, encodedContext: String): Long {
        driver.execute(
            identifier = null,
            sql = """
                INSERT OR IGNORE INTO $TABLE_SEARCH_CONTEXTS(
                  publication_id, acquisition_id, unit_id, content_revision, resource_id,
                  canonical_length_utf16, context_json
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 7,
        ) {
            bindString(0, source.scope.publicationId.value)
            bindString(1, source.scope.acquisitionId)
            bindString(2, source.scope.unitId.value)
            bindLong(3, source.scope.contentRevision)
            bindString(4, source.resourceId)
            bindLong(5, source.canonicalDocumentUtf16Length.toLong())
            bindString(6, encodedContext)
        }.value
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT context_key FROM $TABLE_SEARCH_CONTEXTS WHERE context_json = ?",
            mapper = { cursor ->
                check(cursor.next().value) { "Search context row is missing" }
                QueryResult.Value(requireNotNull(cursor.getLong(0)))
            },
            parameters = 1,
            binders = { bindString(0, encodedContext) },
        ).value
    }

    private fun upsertLocked(prepared: PreparedSearchUpsert) {
        val source = prepared.source
        // Re-check after any suspending lock wait and immediately before the SQLite side effect.
        operationGate.execute(source.access, ContentOperation.SEARCH_INDEX) {
            transactions.transactionWithResult<Unit>(noEnclosing = false) {
                deleteDocumentRowsLocked(source.documentId)
                val contextKey = contextKeyLocked(source, prepared.encodedContext)
                driver.execute(
                    identifier = null,
                    sql = """
                        INSERT INTO $TABLE_SEARCH_DOCUMENTS(
                          document_id, context_key, block_id, base_offset_utf16, epub_anchor_json, body_text
                        ) VALUES (?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = 6,
                ) {
                    bindString(0, source.documentId)
                    bindLong(1, contextKey)
                    bindString(2, source.blockId)
                    bindLong(3, source.baseOffsetUtf16.toLong())
                    bindString(4, prepared.encodedEpubAnchor)
                    bindString(5, source.text)
                }.value
                val documentKey = scalarLong("SELECT last_insert_rowid()")
                // One row per distinct token of the block, its occurrences packed into one value:
                // a row per occurrence (keyed by the 64-character document id, and indexed a
                // second time by token) cost about 170 bytes per CJK character of the library.
                prepared.postings.chunked(SEARCH_TOKEN_INSERT_BATCH_SIZE).forEach { batch ->
                    val values = batch.joinToString(",") { "(?, ?, ?, ?)" }
                    driver.execute(
                        identifier = null,
                        sql = """
                            INSERT INTO $TABLE_SEARCH_POSTINGS(token, doc_key, hits, positions)
                            VALUES $values
                        """.trimIndent(),
                        parameters = batch.size * 4,
                    ) {
                        batch.forEachIndexed { index, posting ->
                            val offset = index * 4
                            bindString(offset, posting.token)
                            bindLong(offset + 1, documentKey)
                            bindLong(offset + 2, posting.hits.toLong())
                            bindString(offset + 3, posting.positions)
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
                driver.execute(null, "DELETE FROM $TABLE_SEARCH_POSTINGS", 0).value
                driver.execute(null, "DELETE FROM $TABLE_SEARCH_DOCUMENTS", 0).value
                driver.execute(null, "DELETE FROM $TABLE_SEARCH_CONTEXTS", 0).value
                removals++
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

    /**
     * [isCurrent] for every plain-text segment of one resource with a single query. Checking each
     * paragraph separately (lock, query and JSON decode per row) made an unchanged library take
     * about four minutes per reconciliation on a phone.
     */
    internal suspend fun currentSegments(
        scope: ReadingScope,
        resourceId: String,
        canonicalDocumentUtf16Length: Int,
        access: ContentAccessRequest,
        segments: List<FullTextDocumentSegment>,
    ): List<Boolean> {
        scope.validate()
        requireSearchIdentifier(resourceId, "Search resource id")
        segments.forEach { requireSearchIdentifier(it.documentId, "Search document id") }
        val exactAccess = access.withActualSearchTextCharacters(canonicalDocumentUtf16Length)
        if (operationGate.decide(exactAccess, ContentOperation.SEARCH_INDEX) != RightsDecision.ALLOW) {
            return segments.map { false }
        }
        return withIndexLockSuspending {
            val rows = loadAccessRowsLocked(SearchDocumentScope(scope, resourceId))
                .associateBy(SearchAccessRow::documentId)
            // Every block of the resource shares one context row: decode it once, not per block.
            val sharedContexts = HashMap<String, PersistedSearchContext?>()
            segments.map { segment ->
                val row = rows[segment.documentId] ?: return@map false
                val context = sharedContexts.getOrPut(row.encodedContext) {
                    runCatching { decodeSharedContext(row.encodedContext) }.getOrNull()
                } ?: return@map false
                if (!context.hadProtectionAuthorization) {
                    return@map context.scope == scope &&
                        context.access.toRequest(null) == exactAccess &&
                        context.resourceId == resourceId && row.resourceId == resourceId &&
                        row.blockId == segment.blockId &&
                        row.baseOffsetUtf16 == segment.startUtf16.toLong() &&
                        segment.startUtf16 <= canonicalDocumentUtf16Length &&
                        context.canonicalDocumentUtf16Length == canonicalDocumentUtf16Length &&
                        row.canonicalLengthUtf16 == canonicalDocumentUtf16Length.toLong() &&
                        row.encodedEpubAnchor == null
                }
                val metadata = runCatching { row.decodeMetadata() }.getOrNull() ?: return@map false
                val decoded = runCatching { row.decodeAccess(metadata) }.getOrNull() ?: return@map false
                decoded.first == scope && decoded.second == exactAccess &&
                    row.resourceId == resourceId && row.blockId == segment.blockId &&
                    row.baseOffsetUtf16 == segment.startUtf16.toLong() &&
                    row.canonicalLengthUtf16 == canonicalDocumentUtf16Length.toLong() &&
                    metadata.epubAnchor == null
            }
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
        // Blocks of one resource share their context row, so its access request and decision are
        // the same for all of them: decide once per context instead of once per paragraph (rows
        // come in document-id order, which interleaves every resource). A decision is reused for
        // at most a second, so a grant expiring during a long sweep is still seen. Protected
        // content keeps its per-document live authorization and is decided row by row.
        val sharedDecisions = HashMap<String, SharedSearchDecision>()
        while (true) {
            currentCoroutineContext().ensureActive()
            yield()
            currentCoroutineContext().ensureActive()
            val page = withIndexLockSuspending {
                val rows = loadRightsCleanupPageLocked(target, afterDocumentId, pageSize)
                val denied = rows.mapNotNull { row ->
                    // A shared decision covers the context; the row's own fields are still checked,
                    // so a damaged row is purged as before.
                    val shared = sharedDecisions[row.encodedContext]
                        ?.takeIf { it.decidedAt.elapsedNow() < SHARED_DECISION_LIFETIME }
                        ?.takeIf { row.hasValidOwnFields() }
                    val (access, allowed) = shared?.let { it.access to it.allowed } ?: run {
                        val decoded = runCatching { row.decodeAccess().second }.getOrNull()
                        val decision = decoded != null &&
                            operationGate.decide(decoded, ContentOperation.SEARCH_INDEX) == RightsDecision.ALLOW
                        if (decoded != null && decoded.protectionAuthorization == null) {
                            sharedDecisions[row.encodedContext] =
                                SharedSearchDecision(decoded, decision, TimeSource.Monotonic.markNow())
                        }
                        decoded to decision
                    }
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
                    row.documentId.takeIf { belongsToTarget && (access == null || !allowed) }
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
                "WHERE c.publication_id = ? AND c.acquisition_id = ? AND c.unit_id = ? " +
                    "AND c.content_revision = ?",
            )
            if (documentScope.resourceId != null) append(" AND c.resource_id = ?")
        }
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT $SEARCH_ACCESS_COLUMNS
                FROM $SEARCH_DOCUMENTS_JOIN
                $scopePredicate
                ORDER BY d.document_id
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
                        encodedContext = requireNotNull(cursor.getString(9)),
                        encodedEpubAnchor = cursor.getString(10),
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
            if (afterDocumentId != null) add("d.document_id > ?")
            when (target) {
                DerivedRightsCleanupTarget.All -> Unit
                is DerivedRightsCleanupTarget.Publication -> add("c.publication_id = ?")
                is DerivedRightsCleanupTarget.Grant -> target.lastKnownScope?.let { scope ->
                    add("c.publication_id = ?")
                    add("c.acquisition_id = ?")
                    if (scope.unitId != null) add("c.unit_id = ?")
                    if (scope.contentRevision != null) add("c.content_revision = ?")
                }
            }
        }
        val where = if (predicates.isEmpty()) "" else
            predicates.joinToString(prefix = " WHERE ", separator = " AND ")
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT $SEARCH_ACCESS_COLUMNS
                FROM $SEARCH_DOCUMENTS_JOIN$where
                ORDER BY d.document_id LIMIT ?
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
                        encodedContext = requireNotNull(cursor.getString(9)),
                        encodedEpubAnchor = cursor.getString(10),
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
            SELECT $SEARCH_ACCESS_COLUMNS
            FROM $SEARCH_DOCUMENTS_JOIN WHERE d.document_id = ?
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
                        encodedContext = requireNotNull(cursor.getString(9)),
                        encodedEpubAnchor = cursor.getString(10),
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
            SELECT $SEARCH_ACCESS_COLUMNS, d.body_text
            FROM $SEARCH_DOCUMENTS_JOIN WHERE d.document_id = ?
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
                    encodedContext = requireNotNull(cursor.getString(9)),
                    encodedEpubAnchor = cursor.getString(10),
                )
                val metadata = row.decodeMetadata()
                val access = row.decodeAccess(metadata)
                QueryResult.Value(
                    SearchableTextDocument(
                        documentId = row.documentId,
                        scope = access.first,
                        resourceId = row.resourceId,
                        blockId = row.blockId,
                        text = requireNotNull(cursor.getString(11)),
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

    /** The per-block columns [decodeMetadata] would validate, without decoding the shared context. */
    private fun SearchAccessRow.hasValidOwnFields(): Boolean = runCatching {
        requireSearchIdentifier(documentId, "Search document id")
        requireSearchIdentifier(blockId, "Search block id")
        require(baseOffsetUtf16 in 0..canonicalLengthUtf16) { "Search metadata offsets are invalid" }
        encodedEpubAnchor?.let { encoded ->
            require(encoded.length <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
            codec.decodeFromString<EpubSearchAnchor>(encoded).validate()
        }
    }.isSuccess

    /** The shared part of a block's metadata, validated as [PersistedSearchMetadata] would be. */
    private fun decodeSharedContext(encoded: String): PersistedSearchContext {
        require(encoded.length <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        val context = codec.decodeFromString<PersistedSearchContext>(encoded)
        context.scope.validate()
        requireSearchIdentifier(context.resourceId, "Search resource id")
        require(context.canonicalDocumentUtf16Length >= 0) { "Search metadata offsets are invalid" }
        context.access.validate()
        requireSearchScopeMatches(context.scope, context.access.toRequest(null))
        return context
    }

    private fun SearchAccessRow.decodeMetadata(): PersistedSearchMetadata {
        require(encodedContext.length <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        require((encodedEpubAnchor?.length ?: 0) <= MAX_SEARCH_METADATA_CHARS) { "Search metadata is too large" }
        val context = codec.decodeFromString<PersistedSearchContext>(encodedContext)
        val metadata = PersistedSearchMetadata(
            schemaVersion = SEARCH_METADATA_SCHEMA_VERSION,
            documentId = documentId,
            scope = context.scope,
            resourceId = context.resourceId,
            blockId = blockId,
            baseOffsetUtf16 = baseOffsetUtf16.toBoundedInt("Search block offset"),
            canonicalDocumentUtf16Length = context.canonicalDocumentUtf16Length,
            access = context.access,
            hadProtectionAuthorization = context.hadProtectionAuthorization,
            epubAnchor = encodedEpubAnchor?.let { codec.decodeFromString<EpubSearchAnchor>(it) },
        )
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
            "JOIN $TABLE_SEARCH_CONTEXTS AS c ON c.context_key = d.context_key"
        val scopedPredicate = if (documentScope == null) "" else buildString {
            append(
                " AND c.publication_id = ?" +
                    " AND c.acquisition_id = ?" +
                    " AND c.unit_id = ?" +
                    " AND c.content_revision = ?",
            )
            if (documentScope.resourceId != null) append(" AND c.resource_id = ?")
        }
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT d.document_id
                FROM $TABLE_SEARCH_POSTINGS AS p
                JOIN $TABLE_SEARCH_DOCUMENTS AS d ON d.doc_key = p.doc_key
                $scopedJoin
                WHERE p.token IN ($placeholders)
                $scopedPredicate
                GROUP BY p.doc_key
                HAVING COUNT(DISTINCT p.token) = ?
                ORDER BY SUM(p.hits) DESC, d.document_id ASC
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
                SELECT p.token, p.hits, p.positions
                FROM $TABLE_SEARCH_POSTINGS AS p
                JOIN $TABLE_SEARCH_DOCUMENTS AS d ON d.doc_key = p.doc_key
                WHERE d.document_id = ? AND p.token IN ($placeholders)
            """.trimIndent(),
            mapper = { cursor ->
                val rows = mutableListOf<FullTextToken>()
                while (cursor.next().value) {
                    val value = requireNotNull(cursor.getString(0))
                    val tokens = decodeSearchPositions(value, requireNotNull(cursor.getString(2)))
                    check(tokens.size.toLong() == requireNotNull(cursor.getLong(1))) { "Search posting count mismatch" }
                    tokens.forEach { token ->
                        check(token.endUtf16 <= body.length &&
                            body.substring(token.startUtf16, token.endUtf16).lowercase() == token.value
                        ) { "Search token/body mismatch" }
                    }
                    rows += tokens
                }
                QueryResult.Value(
                    rows.sortedWith(compareBy(FullTextToken::startUtf16, FullTextToken::endUtf16, FullTextToken::value)),
                )
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
        val keys = driver.executeQuery(
            identifier = null,
            sql = "SELECT doc_key, context_key FROM $TABLE_SEARCH_DOCUMENTS WHERE document_id = ?",
            mapper = { cursor ->
                QueryResult.Value(
                    if (cursor.next().value) requireNotNull(cursor.getLong(0)) to requireNotNull(cursor.getLong(1)) else null,
                )
            },
            parameters = 1,
            binders = { bindString(0, documentId) },
        ).value ?: return
        removals++
        val (documentKey, contextKey) = keys
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_SEARCH_POSTINGS WHERE doc_key = ?",
            parameters = 1,
        ) { bindLong(0, documentKey) }.value
        driver.execute(
            identifier = null,
            sql = "DELETE FROM $TABLE_SEARCH_DOCUMENTS WHERE doc_key = ?",
            parameters = 1,
        ) { bindLong(0, documentKey) }.value
        driver.execute(
            identifier = null,
            sql = """
                DELETE FROM $TABLE_SEARCH_CONTEXTS WHERE context_key = ?
                AND NOT EXISTS (SELECT 1 FROM $TABLE_SEARCH_DOCUMENTS WHERE context_key = ?)
            """.trimIndent(),
            parameters = 2,
        ) {
            bindLong(0, contextKey)
            bindLong(1, contextKey)
        }.value
    }

    /**
     * Removes the format-1 tables (about 170 bytes per indexed CJK character) a little at a time;
     * the reconciler rebuilds the rows in the compact format. A single DROP of a table this size
     * would hold the shared database for a long time.
     */
    internal suspend fun dropLegacyTablesInBackground() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val done = withIndexLockSuspending { dropLegacyBatchLocked() }
            if (done) return
            yield()
        }
    }

    private fun dropLegacyBatchLocked(): Boolean {
        val legacy = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN (?, ?)",
            mapper = { cursor ->
                val names = mutableSetOf<String>()
                while (cursor.next().value) names += requireNotNull(cursor.getString(0))
                QueryResult.Value(names)
            },
            parameters = 2,
            binders = {
                bindString(0, LEGACY_TABLE_SEARCH_DOCUMENTS)
                bindString(1, LEGACY_TABLE_SEARCH_TOKENS)
            },
        ).value
        if (legacy.isEmpty()) return true
        transactions.transactionWithResult<Unit>(noEnclosing = false) {
            if (LEGACY_TABLE_SEARCH_DOCUMENTS in legacy) {
                if (LEGACY_TABLE_SEARCH_TOKENS in legacy) {
                    driver.execute(
                        identifier = null,
                        sql = """
                            DELETE FROM $LEGACY_TABLE_SEARCH_TOKENS WHERE document_id IN (
                              SELECT document_id FROM $LEGACY_TABLE_SEARCH_DOCUMENTS
                              ORDER BY document_id LIMIT $LEGACY_DROP_BATCH_DOCUMENTS
                            )
                        """.trimIndent(),
                        parameters = 0,
                    ).value
                }
                driver.execute(
                    identifier = null,
                    sql = """
                        DELETE FROM $LEGACY_TABLE_SEARCH_DOCUMENTS WHERE document_id IN (
                          SELECT document_id FROM $LEGACY_TABLE_SEARCH_DOCUMENTS
                          ORDER BY document_id LIMIT $LEGACY_DROP_BATCH_DOCUMENTS
                        )
                    """.trimIndent(),
                    parameters = 0,
                ).value
            }
            val documentsLeft = LEGACY_TABLE_SEARCH_DOCUMENTS in legacy &&
                scalarLong("SELECT EXISTS(SELECT 1 FROM $LEGACY_TABLE_SEARCH_DOCUMENTS)") != 0L
            if (!documentsLeft) {
                // Tokens without a document row are unreachable; the table is dropped whole.
                driver.execute(null, "DROP TABLE IF EXISTS $LEGACY_TABLE_SEARCH_TOKENS", 0).value
                driver.execute(null, "DROP TABLE IF EXISTS $LEGACY_TABLE_SEARCH_DOCUMENTS", 0).value
            }
        }
        return false
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
        val encodedContext: String,
        val encodedEpubAnchor: String?,
    )

    private data class SearchCandidate(val documentId: String)
    private data class SearchDocumentScope(
        val scope: ReadingScope,
        val resourceId: String?,
    )
    private data class PreparedSearchQuery(val queryTokens: List<String>, val limit: Int)
    private data class PreparedSearchUpsert(
        val source: SearchableTextDocument,
        val postings: List<SearchPosting>,
        val encodedContext: String,
        val encodedEpubAnchor: String?,
    )
    private data class SearchPosting(val token: String, val hits: Int, val positions: String)
    private class SharedSearchDecision(
        val access: ContentAccessRequest,
        val allowed: Boolean,
        val decidedAt: TimeSource.Monotonic.ValueTimeMark,
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

/** Everything a block shares with the other blocks of its resource revision; stored once. */
@Serializable
private data class PersistedSearchContext(
    val scope: ReadingScope,
    val resourceId: String,
    val canonicalDocumentUtf16Length: Int,
    val access: PersistedSearchAccess,
    val hadProtectionAuthorization: Boolean,
) {
    companion object {
        fun from(metadata: PersistedSearchMetadata): PersistedSearchContext = PersistedSearchContext(
            scope = metadata.scope,
            resourceId = metadata.resourceId,
            canonicalDocumentUtf16Length = metadata.canonicalDocumentUtf16Length,
            access = metadata.access,
            hadProtectionAuthorization = metadata.hadProtectionAuthorization,
        )
    }
}

/**
 * Occurrences of one token in one block: comma-separated starts, with ":end" only where the
 * matched text is not exactly as long as the token (case folding can change the length).
 */
private fun encodeSearchPositions(token: String, occurrences: List<FullTextToken>): String =
    occurrences.joinToString(",") { occurrence ->
        if (occurrence.endUtf16 - occurrence.startUtf16 == token.length) {
            occurrence.startUtf16.toString()
        } else {
            "${occurrence.startUtf16}:${occurrence.endUtf16}"
        }
    }

private fun decodeSearchPositions(token: String, encoded: String): List<FullTextToken> {
    require(encoded.isNotEmpty() && encoded.length <= MAX_SEARCH_POSITIONS_CHARS) { "Search positions are invalid" }
    return encoded.split(',').map { entry ->
        val start = entry.substringBefore(':').toInt()
        val end = if (':' in entry) entry.substringAfter(':').toInt() else start + token.length
        require(start >= 0 && end >= start) { "Search positions are invalid" }
        FullTextToken(value = token, startUtf16 = start, endUtf16 = end)
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

/**
 * Independent schema marker because these tables are rebuildable and not part of wire state.
 *
 * Format 2 uses new table names: format 1 stored about 170 bytes per indexed CJK character (a
 * row per occurrence keyed by a 64-character id, indexed twice, and a kilobyte of JSON per
 * paragraph). An older app keeps its own format-1 marker and tables and simply re-indexes.
 */
public object LocalFullTextIndexSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 2

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        listOf(
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_SCHEMA(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  format_version INTEGER NOT NULL
                )
            """,
            "INSERT OR IGNORE INTO $TABLE_SEARCH_SCHEMA(singleton_id, format_version) VALUES (1, $version)",
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_CONTEXTS(
                  context_key INTEGER PRIMARY KEY,
                  publication_id TEXT NOT NULL,
                  acquisition_id TEXT NOT NULL,
                  unit_id TEXT NOT NULL,
                  content_revision INTEGER NOT NULL,
                  resource_id TEXT NOT NULL,
                  canonical_length_utf16 INTEGER NOT NULL,
                  context_json TEXT NOT NULL UNIQUE
                )
            """,
            """
                CREATE INDEX IF NOT EXISTS ${TABLE_SEARCH_CONTEXTS}_scope
                ON $TABLE_SEARCH_CONTEXTS(publication_id, acquisition_id, unit_id, content_revision, resource_id)
            """,
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_DOCUMENTS(
                  doc_key INTEGER PRIMARY KEY,
                  document_id TEXT NOT NULL UNIQUE,
                  context_key INTEGER NOT NULL,
                  block_id TEXT NOT NULL,
                  base_offset_utf16 INTEGER NOT NULL,
                  epub_anchor_json TEXT,
                  body_text TEXT NOT NULL
                )
            """,
            """
                CREATE INDEX IF NOT EXISTS ${TABLE_SEARCH_DOCUMENTS}_context
                ON $TABLE_SEARCH_DOCUMENTS(context_key)
            """,
            """
                CREATE TABLE IF NOT EXISTS $TABLE_SEARCH_POSTINGS(
                  token TEXT NOT NULL,
                  doc_key INTEGER NOT NULL,
                  hits INTEGER NOT NULL,
                  positions TEXT NOT NULL,
                  PRIMARY KEY(token, doc_key)
                ) WITHOUT ROWID
            """,
            """
                CREATE INDEX IF NOT EXISTS ${TABLE_SEARCH_POSTINGS}_document
                ON $TABLE_SEARCH_POSTINGS(doc_key)
            """,
        ).forEach { sql -> driver.execute(null, sql.trimIndent(), 0).value }
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

private const val TABLE_SEARCH_SCHEMA = "content_search_v2_schema"
private const val TABLE_SEARCH_CONTEXTS = "content_search_v2_contexts"
private const val TABLE_SEARCH_DOCUMENTS = "content_search_v2_documents"
private const val TABLE_SEARCH_POSTINGS = "content_search_v2_postings"
private const val LEGACY_TABLE_SEARCH_DOCUMENTS = "content_search_documents"
private const val LEGACY_TABLE_SEARCH_TOKENS = "content_search_tokens"
private const val LEGACY_DROP_BATCH_DOCUMENTS = 500
private val SHARED_DECISION_LIFETIME = 1.seconds
private const val MAX_SEARCH_POSITIONS_CHARS = 4_000_000
private const val SEARCH_ACCESS_COLUMNS =
    "d.document_id, c.publication_id, c.acquisition_id, c.unit_id, c.content_revision, c.resource_id, " +
        "d.block_id, d.base_offset_utf16, c.canonical_length_utf16, c.context_json, d.epub_anchor_json"
private const val SEARCH_DOCUMENTS_JOIN =
    "$TABLE_SEARCH_DOCUMENTS AS d JOIN $TABLE_SEARCH_CONTEXTS AS c ON c.context_key = d.context_key"
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
