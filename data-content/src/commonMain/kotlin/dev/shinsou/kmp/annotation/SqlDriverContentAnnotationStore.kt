package dev.shinsou.kmp.annotation

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import dev.shinsou.kmp.annotation.sql.ContentAnnotationDatabase
import dev.shinsou.kmp.annotation.sql.ContentAnnotationQueries
import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.content.SchemaComponent
import dev.shinsou.kmp.content.SchemaMigrator
import dev.shinsou.kmp.content.tableExists
import dev.shinsou.kmp.domain.model.ReadingScope
import dev.shinsou.kmp.rights.DerivedRightsCleanupTarget
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Restart-safe annotation CAS store hosted by the same platform SQLite driver as content metadata. */
public class SqlDriverContentAnnotationStore(
    private val driver: SqlDriver,
    json: Json = AnnotationJson,
) : ContentAnnotationStore {
    private val codec = Json(json) {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = false
    }
    private val transactions = object : TransacterImpl(driver) {}
    private val queries = ContentAnnotationQueries(driver)
    private val mutex = SynchronousLock()

    init {
        ContentAnnotationSchema.create(driver).value
    }

    override fun find(annotationId: String): ContentAnnotation? = withStoreLock {
        requireAnnotationUuid(annotationId)
        findLocked(annotationId)
    }

    override fun list(includeTombstones: Boolean): List<ContentAnnotation> = withStoreLock {
        loadAllLocked().filter { includeTombstones || it.state == ContentAnnotationState.ACTIVE }
    }

    override fun listPage(
        afterAnnotationIdExclusive: String?,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ContentAnnotation> = withStoreLock {
        require(limit in 1..MAX_ANNOTATION_PAGE_SIZE) { "Annotation page limit is invalid" }
        queryPage(afterAnnotationIdExclusive, limit, includeTombstones).map { row ->
            row.decodeAndVerify()
        }
    }

    override suspend fun listRightsCleanupPage(
        target: DerivedRightsCleanupTarget,
        afterAnnotationIdExclusive: String?,
        limit: Int,
    ): List<ContentAnnotation> = mutex.withLock {
        require(limit in 1..MAX_ANNOTATION_PAGE_SIZE) { "Annotation cleanup page limit is invalid" }
        queryPage(
            afterAnnotationIdExclusive = afterAnnotationIdExclusive,
            limit = limit,
            includeTombstones = true,
            rightsCleanupTarget = target,
        ).map { row -> row.decodeAndVerify() }
    }

    override fun list(scope: ReadingScope, includeTombstones: Boolean): List<ContentAnnotation> = withStoreLock {
        scope.validate()
        loadScopeLocked(scope, includeTombstones).map { row -> row.decodeAndVerify() }
    }

    override fun put(annotation: ContentAnnotation, expectedUpdatedAtEpochMillis: Long?) {
        putInternal(annotation, expectedUpdatedAtEpochMillis, allowCreationTimeRepair = false)
    }

    override suspend fun putInBackground(
        annotation: ContentAnnotation,
        expectedUpdatedAtEpochMillis: Long?,
    ) {
        annotation.validate()
        mutex.withLock {
            putInternalLocked(annotation, expectedUpdatedAtEpochMillis, allowCreationTimeRepair = false)
        }
    }

    override fun putFromVerifiedReplica(
        annotation: ContentAnnotation,
        expectedUpdatedAtEpochMillis: Long?,
    ) {
        putInternal(annotation, expectedUpdatedAtEpochMillis, allowCreationTimeRepair = true)
    }

    private fun putInternal(
        annotation: ContentAnnotation,
        expectedUpdatedAtEpochMillis: Long?,
        allowCreationTimeRepair: Boolean,
    ) {
        annotation.validate()
        withStoreLock {
            putInternalLocked(annotation, expectedUpdatedAtEpochMillis, allowCreationTimeRepair)
        }
    }

    private fun putInternalLocked(
        annotation: ContentAnnotation,
        expectedUpdatedAtEpochMillis: Long?,
        allowCreationTimeRepair: Boolean,
    ) {
        transactions.transactionWithResult<Unit>(noEnclosing = false) {
            val existing = findLocked(annotation.annotationId)
            validateCas(
                existing,
                annotation,
                expectedUpdatedAtEpochMillis,
                allowCreationTimeRepair,
            )
            val encoded = codec.encodeToString(ContentAnnotation.serializer(), annotation)
            require(encoded.length <= MAX_ANNOTATION_JSON_CHARS) { "Annotation payload is too large" }
            if (existing == null) {
                insertLocked(annotation, encoded)
            } else {
                val affected = queries.updateIfUnchanged(
                    publicationId = annotation.scope.publicationId.value,
                    acquisitionId = annotation.scope.acquisitionId,
                    unitId = annotation.scope.unitId.value,
                    contentRevision = annotation.scope.contentRevision,
                    state = annotation.state.name,
                    createdAt = annotation.createdAtEpochMillis,
                    updatedAt = annotation.updatedAtEpochMillis,
                    annotationJson = encoded,
                    annotationId = annotation.annotationId,
                    expectedUpdatedAt = requireNotNull(expectedUpdatedAtEpochMillis),
                ).value
                if (affected != 1L) {
                    throw AnnotationConflictException("Annotation changed concurrently")
                }
            }
        }
    }

    /**
     * Replaces the complete portable annotation set inside the caller's enclosing SQLite
     * transaction. This deliberately bypasses per-row CAS because a verified backup restore owns
     * the complete destination set; ordinary interactive edits continue to use [put].
     */
    public fun replaceAllAtomically(restored: List<ContentAnnotation>) {
        restored.forEach(ContentAnnotation::validate)
        require(restored.map(ContentAnnotation::annotationId).distinct().size == restored.size) {
            "Restored annotations must have unique ids"
        }
        val ordered = restored.sortedWith(
            compareBy(ContentAnnotation::createdAtEpochMillis, ContentAnnotation::annotationId),
        )
        withStoreLock {
            transactions.transactionWithResult<Unit>(noEnclosing = false) {
                queries.deleteAll().value
                ordered.forEach { annotation ->
                    val encoded = codec.encodeToString(ContentAnnotation.serializer(), annotation)
                    require(encoded.length <= MAX_ANNOTATION_JSON_CHARS) {
                        "Annotation payload is too large"
                    }
                    insertLocked(annotation, encoded)
                }
            }
        }
    }

    private fun insertLocked(annotation: ContentAnnotation, encoded: String) {
        queries.insert(
            annotation_id = annotation.annotationId,
            publication_id = annotation.scope.publicationId.value,
            acquisition_id = annotation.scope.acquisitionId,
            unit_id = annotation.scope.unitId.value,
            content_revision = annotation.scope.contentRevision,
            state = annotation.state.name,
            created_at = annotation.createdAtEpochMillis,
            updated_at = annotation.updatedAtEpochMillis,
            annotation_json = encoded,
        ).value
    }

    private fun validateCas(
        existing: ContentAnnotation?,
        annotation: ContentAnnotation,
        expectedUpdatedAtEpochMillis: Long?,
        allowCreationTimeRepair: Boolean,
    ) {
        when {
            existing == null && expectedUpdatedAtEpochMillis != null ->
                throw AnnotationConflictException("Annotation does not exist")
            existing != null && expectedUpdatedAtEpochMillis == null ->
                throw AnnotationConflictException("Annotation already exists")
            existing != null && existing.updatedAtEpochMillis != expectedUpdatedAtEpochMillis ->
                throw AnnotationConflictException("Annotation changed concurrently")
            existing != null && !allowCreationTimeRepair &&
                annotation.createdAtEpochMillis != existing.createdAtEpochMillis ->
                throw AnnotationConflictException("Annotation creation time is immutable")
            existing != null && annotation.updatedAtEpochMillis < existing.updatedAtEpochMillis ->
                throw AnnotationConflictException("Annotation update time regressed")
        }
    }

    private fun findLocked(annotationId: String): ContentAnnotation? =
        queries.find(annotationId, ::Row).executeAsList().singleOrNull()?.decodeAndVerify()

    private fun loadAllLocked(): List<ContentAnnotation> =
        queries.all(::Row).executeAsList().map { row -> row.decodeAndVerify() }

    private fun loadScopeLocked(
        scope: ReadingScope,
        includeTombstones: Boolean,
    ): List<Row> = if (includeTombstones) {
        queries.inScope(
            scope.publicationId.value,
            scope.acquisitionId,
            scope.unitId.value,
            scope.contentRevision,
            ::Row,
        )
    } else {
        queries.inScopeExceptState(
            scope.publicationId.value,
            scope.acquisitionId,
            scope.unitId.value,
            scope.contentRevision,
            ContentAnnotationState.TOMBSTONE.name,
            ::Row,
        )
    }.executeAsList()

    /** Its filters vary with the call (paging, rights cleanup), so this statement is written here, not generated. */
    private fun queryPage(
        afterAnnotationIdExclusive: String?,
        limit: Int,
        includeTombstones: Boolean,
        rightsCleanupTarget: DerivedRightsCleanupTarget? = null,
    ): List<Row> {
        val predicates = buildList {
            if (afterAnnotationIdExclusive != null) add("annotation_id > ?")
            if (!includeTombstones) add("state != ?")
            when (rightsCleanupTarget) {
                null,
                DerivedRightsCleanupTarget.All,
                -> Unit
                is DerivedRightsCleanupTarget.Publication -> add("publication_id = ?")
                is DerivedRightsCleanupTarget.Grant -> rightsCleanupTarget.lastKnownScope?.let { scope ->
                    add("publication_id = ?")
                    add("acquisition_id = ?")
                    if (scope.unitId != null) add("unit_id = ?")
                    if (scope.contentRevision != null) add("content_revision = ?")
                }
            }
        }
        val where = if (predicates.isEmpty()) {
            ""
        } else {
            predicates.joinToString(prefix = " WHERE ", separator = " AND ")
        }
        return driver.executeQuery(
            identifier = null,
            sql = """
                SELECT annotation_id, publication_id, acquisition_id, unit_id,
                       content_revision, state, created_at, updated_at, annotation_json
                FROM $TABLE_ANNOTATIONS$where ORDER BY annotation_id LIMIT ?
            """.trimIndent(),
            mapper = { cursor ->
                val rows = mutableListOf<Row>()
                while (cursor.next().value) {
                    rows += cursor.readRow()
                }
                QueryResult.Value(rows)
            },
            parameters = predicates.size + 1,
        ) {
            var index = 0
            afterAnnotationIdExclusive?.let { bindString(index++, it) }
            if (!includeTombstones) bindString(index++, ContentAnnotationState.TOMBSTONE.name)
            when (rightsCleanupTarget) {
                null,
                DerivedRightsCleanupTarget.All,
                -> Unit
                is DerivedRightsCleanupTarget.Publication ->
                    bindString(index++, rightsCleanupTarget.publicationId.value)
                is DerivedRightsCleanupTarget.Grant -> rightsCleanupTarget.lastKnownScope?.let { scope ->
                    bindString(index++, scope.publicationId.value)
                    bindString(index++, scope.acquisitionId)
                    scope.unitId?.let { bindString(index++, it.value) }
                    scope.contentRevision?.let { bindLong(index++, it) }
                }
            }
            bindLong(index, limit.toLong())
        }.value
    }

    private fun app.cash.sqldelight.db.SqlCursor.readRow(): Row = Row(
        annotationId = requireNotNull(getString(0)),
        publicationId = requireNotNull(getString(1)),
        acquisitionId = requireNotNull(getString(2)),
        unitId = requireNotNull(getString(3)),
        contentRevision = requireNotNull(getLong(4)),
        state = requireNotNull(getString(5)),
        createdAt = requireNotNull(getLong(6)),
        updatedAt = requireNotNull(getLong(7)),
        encoded = requireNotNull(getString(8)),
    )

    private fun Row.decodeAndVerify(): ContentAnnotation {
        val annotation = codec.decodeFromString(ContentAnnotation.serializer(), encoded)
        annotation.validate()
        check(annotation.annotationId == annotationId) { "Annotation id/body mismatch" }
        check(annotation.scope.publicationId.value == publicationId) { "Annotation publication/body mismatch" }
        check(annotation.scope.acquisitionId == acquisitionId) { "Annotation acquisition/body mismatch" }
        check(annotation.scope.unitId.value == unitId) { "Annotation unit/body mismatch" }
        check(annotation.scope.contentRevision == contentRevision) { "Annotation revision/body mismatch" }
        check(annotation.state.name == state) { "Annotation state/body mismatch" }
        check(annotation.createdAtEpochMillis == createdAt) { "Annotation creation time/body mismatch" }
        check(annotation.updatedAtEpochMillis == updatedAt) { "Annotation update time/body mismatch" }
        return annotation
    }

    private inline fun <T> withStoreLock(block: () -> T): T {
        return mutex.withLock(block)
    }

    private data class Row(
        val annotationId: String,
        val publicationId: String,
        val acquisitionId: String,
        val unitId: String,
        val contentRevision: Long,
        val state: String,
        val createdAt: Long,
        val updatedAt: Long,
        val encoded: String,
    )
}

/** Independent version marker; the table shares the host driver's enclosing SQLite transactions. */
public object ContentAnnotationSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version: Long = 1

    internal val component: SchemaComponent = SchemaComponent(
        name = "content_annotations",
        version = version,
        // The generated DDL (ContentAnnotation.sq); its own Schema version is not used.
        create = { driver -> ContentAnnotationDatabase.Schema.create(driver).value },
        unrecordedVersion = { driver -> if (tableExists(driver, TABLE_ANNOTATIONS)) 1 else null },
    )

    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        SchemaMigrator.migrate(driver, component)
        return QueryResult.Unit
    }

    override fun migrate(
        driver: SqlDriver,
        oldVersion: Long,
        newVersion: Long,
        vararg callbacks: AfterVersion,
    ): QueryResult.Value<Unit> {
        require(oldVersion <= newVersion) { "Annotation database cannot migrate backwards" }
        create(driver)
        callbacks.sortedBy(AfterVersion::afterVersion).forEach { callback ->
            if (callback.afterVersion in oldVersion until newVersion) callback.block(driver)
        }
        return QueryResult.Unit
    }
}

private fun requireAnnotationUuid(value: String) {
    require(ANNOTATION_UUID.matches(value) && value != NIL_ANNOTATION_UUID) {
        "Annotation id must be a lowercase non-NIL UUID"
    }
}

private const val TABLE_ANNOTATIONS: String = "content_annotations"
private const val MAX_ANNOTATION_JSON_CHARS: Int = 128_000
private const val MAX_ANNOTATION_PAGE_SIZE: Int = 1_024
private const val NIL_ANNOTATION_UUID: String = "00000000-0000-0000-0000-000000000000"
private val ANNOTATION_UUID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
private val AnnotationJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
