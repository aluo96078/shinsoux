package dev.shinsou.kmp.annotation

import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.DerivedRightsCleanupTarget
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsOperationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable

@Serializable
public enum class ContentAnnotationKind {
    HIGHLIGHT,
    NOTE,
    BOOKMARK,
}

@Serializable
public enum class ContentAnnotationState {
    ACTIVE,
    TOMBSTONE,
}

/** Portable paragraph/range annotation; tombstones retain identity for sync convergence. */
@Serializable
public data class ContentAnnotation(
    val schemaVersion: Int,
    val annotationId: String,
    val kind: ContentAnnotationKind,
    val range: ReadingRange,
    val body: String? = null,
    val colorArgb: Long? = null,
    val state: ContentAnnotationState = ContentAnnotationState.ACTIVE,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val tombstoneReason: String? = null,
) {
    init { validate() }

    public val scope: ReadingScope get() = range.start.scope

    public fun validate() {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported annotation schema version $schemaVersion"
        }
        requireUuid(annotationId, "Annotation id")
        range.validate()
        body?.let {
            require(it.length <= MAX_BODY_CHARS && isWellFormedText(it)) { "Annotation body is invalid" }
        }
        require(state == ContentAnnotationState.TOMBSTONE ||
            kind != ContentAnnotationKind.NOTE || !body.isNullOrBlank()) {
            "A note annotation needs a body"
        }
        require(colorArgb == null || colorArgb in 0..0xffff_ffffL) { "Annotation color is invalid" }
        require(createdAtEpochMillis >= 0 && updatedAtEpochMillis >= createdAtEpochMillis) {
            "Annotation timestamps are invalid"
        }
        when (state) {
            ContentAnnotationState.ACTIVE -> require(tombstoneReason == null) {
                "An active annotation cannot carry a tombstone reason"
            }
            ContentAnnotationState.TOMBSTONE -> {
                require(!tombstoneReason.isNullOrBlank()) { "A tombstone needs a reason" }
                require(tombstoneReason.length <= MAX_REASON_LENGTH &&
                    tombstoneReason.none(Char::isISOControl)) { "Annotation tombstone reason is invalid" }
            }
        }
        val startQuote = when (val start = range.start) {
            is ReadingLocator.Text -> start.quote
            is ReadingLocator.Epub -> start.quote
            is ReadingLocator.Image -> null
        }
        if (state == ContentAnnotationState.ACTIVE && range.start !is ReadingLocator.Image) {
            require(range.quote != null || startQuote != null) {
                "Text and EPUB annotations require quote fallback"
            }
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

public class AnnotationConflictException(message: String) : IllegalStateException(message)

/** Durable implementations use compare-and-set on updatedAt to avoid overwriting remote edits. */
public interface ContentAnnotationStore {
    public fun find(annotationId: String): ContentAnnotation?
    public fun list(includeTombstones: Boolean = false): List<ContentAnnotation>
    /** Bounded identity-ordered scan used by low-priority restart-safe reconciliation. */
    public fun listPage(
        afterAnnotationIdExclusive: String?,
        limit: Int,
        includeTombstones: Boolean = false,
    ): List<ContentAnnotation> {
        require(limit > 0) { "Annotation page limit must be positive" }
        return list(includeTombstones)
            .asSequence()
            .filter { afterAnnotationIdExclusive == null || it.annotationId > afterAnnotationIdExclusive }
            .sortedBy(ContentAnnotation::annotationId)
            .take(limit)
            .toList()
    }
    public fun list(scope: ReadingScope, includeTombstones: Boolean = false): List<ContentAnnotation> =
        list(includeTombstones).filter { it.scope == scope }
    public fun put(annotation: ContentAnnotation, expectedUpdatedAtEpochMillis: Long? = null)

    /**
     * Scope-filtered page used only by cancellable background rights cleanup. Production SQLite
     * overrides this so a grant/publication purge never walks unrelated annotation rows.
     */
    public suspend fun listRightsCleanupPage(
        target: DerivedRightsCleanupTarget,
        afterAnnotationIdExclusive: String?,
        limit: Int,
    ): List<ContentAnnotation> {
        require(limit > 0) { "Annotation cleanup page limit must be positive" }
        return list(includeTombstones = true)
            .asSequence()
            .filter { annotation -> target.mayContain(annotation.scope) }
            .filter { afterAnnotationIdExclusive == null || it.annotationId > afterAnnotationIdExclusive }
            .sortedBy(ContentAnnotation::annotationId)
            .take(limit)
            .toList()
    }

    /** Suspends on the production store lock instead of failing a low-priority cleanup on contention. */
    public suspend fun putInBackground(
        annotation: ContentAnnotation,
        expectedUpdatedAtEpochMillis: Long?,
    ) {
        put(annotation, expectedUpdatedAtEpochMillis)
    }

    /**
     * CAS replacement for a complete, verified sync-replica winner. Unlike an interactive [put],
     * this may repair a conflicting creation timestamp, but it must still reject a raced local
     * update and timestamp regression.
     */
    public fun putFromVerifiedReplica(
        annotation: ContentAnnotation,
        expectedUpdatedAtEpochMillis: Long?,
    ) {
        put(annotation, expectedUpdatedAtEpochMillis)
    }
}

public class InMemoryContentAnnotationStore : ContentAnnotationStore {
    private val annotations = LinkedHashMap<String, ContentAnnotation>()

    override fun find(annotationId: String): ContentAnnotation? {
        requireUuid(annotationId, "Annotation id")
        return annotations[annotationId]
    }

    override fun list(includeTombstones: Boolean): List<ContentAnnotation> = annotations.values
        .asSequence()
        .filter { includeTombstones || it.state == ContentAnnotationState.ACTIVE }
        .sortedWith(compareBy(ContentAnnotation::createdAtEpochMillis, ContentAnnotation::annotationId))
        .toList()

    override fun listPage(
        afterAnnotationIdExclusive: String?,
        limit: Int,
        includeTombstones: Boolean,
    ): List<ContentAnnotation> {
        require(limit > 0) { "Annotation page limit must be positive" }
        return annotations.values.asSequence()
            .filter { includeTombstones || it.state == ContentAnnotationState.ACTIVE }
            .filter { afterAnnotationIdExclusive == null || it.annotationId > afterAnnotationIdExclusive }
            .sortedBy(ContentAnnotation::annotationId)
            .take(limit)
            .toList()
    }

    override suspend fun listRightsCleanupPage(
        target: DerivedRightsCleanupTarget,
        afterAnnotationIdExclusive: String?,
        limit: Int,
    ): List<ContentAnnotation> {
        require(limit > 0) { "Annotation cleanup page limit must be positive" }
        return annotations.values.asSequence()
            .filter { annotation -> target.mayContain(annotation.scope) }
            .filter { afterAnnotationIdExclusive == null || it.annotationId > afterAnnotationIdExclusive }
            .sortedBy(ContentAnnotation::annotationId)
            .take(limit)
            .toList()
    }

    override fun put(annotation: ContentAnnotation, expectedUpdatedAtEpochMillis: Long?) {
        putInternal(annotation, expectedUpdatedAtEpochMillis, allowCreationTimeRepair = false)
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
        val existing = annotations[annotation.annotationId]
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
        annotations[annotation.annotationId] = annotation
    }
}

public enum class AnnotationReanchorStatus {
    UNCHANGED,
    REANCHORED,
    TOMBSTONED,
}

public data class AnnotationReanchorResult(
    val status: AnnotationReanchorStatus,
    val annotation: ContentAnnotation,
)

public data class AnnotationDerivedDataCleanupResult(
    val annotationsExamined: Int,
    val annotationsRedacted: Int,
    val concurrentChangesDeferred: Int,
)

/** Host service that gates every mutation and performs deterministic quote-based re-anchoring. */
public class RightsEnforcedAnnotationService(
    private val operationGate: ContentOperationGate,
    private val store: ContentAnnotationStore,
    /** Must remain non-blocking; production uses it only to signal background reconciliation. */
    private val onMutationCommitted: () -> Unit = {},
) {
    public fun find(annotationId: String, access: ContentAccessRequest): ContentAnnotation? =
        operationGate.execute(access, ContentOperation.ANNOTATE) {
            store.find(annotationId)?.also { requireScopeMatches(it.scope, access) }
        }

    public fun list(
        scope: ReadingScope,
        access: ContentAccessRequest,
        includeTombstones: Boolean = false,
    ): List<ContentAnnotation> {
        requireScopeMatches(scope, access)
        return operationGate.execute(access, ContentOperation.ANNOTATE) {
            store.list(scope, includeTombstones)
        }
    }

    public fun create(
        annotationId: String,
        kind: ContentAnnotationKind,
        range: ReadingRange,
        access: ContentAccessRequest,
        nowEpochMillis: Long,
        body: String? = null,
        colorArgb: Long? = null,
    ): ContentAnnotation {
        requireRangeMatchesAccess(range, access)
        val annotation = ContentAnnotation(
            schemaVersion = ContentAnnotation.CURRENT_SCHEMA_VERSION,
            annotationId = annotationId,
            kind = kind,
            range = range,
            body = body,
            colorArgb = colorArgb,
            createdAtEpochMillis = nowEpochMillis,
            updatedAtEpochMillis = nowEpochMillis,
        )
        val effective = access.withActualTextCharacters(range.estimatedTextCharacters())
        return operationGate.execute(effective, ContentOperation.ANNOTATE) {
            store.put(annotation)
            onMutationCommitted()
            annotation
        }
    }

    public fun update(
        annotationId: String,
        access: ContentAccessRequest,
        nowEpochMillis: Long,
        body: String?,
        colorArgb: Long?,
    ): ContentAnnotation {
        // Authorize the caller before resolving the row. Besides failing closed, this prevents
        // the missing/existing error shape from becoming an annotation-identity oracle. The
        // exact range length is checked again immediately before the mutation below.
        operationGate.requireAllowed(access, ContentOperation.ANNOTATE)
        val existing = requireNotNull(store.find(annotationId)) { "Annotation does not exist" }
        requireRangeMatchesAccess(existing.range, access)
        require(nowEpochMillis >= existing.updatedAtEpochMillis) { "Annotation update time regressed" }
        val updated = existing.copy(
            body = body,
            colorArgb = colorArgb,
            state = ContentAnnotationState.ACTIVE,
            updatedAtEpochMillis = nowEpochMillis,
            tombstoneReason = null,
        )
        val effective = access.withActualTextCharacters(existing.range.estimatedTextCharacters())
        return operationGate.execute(effective, ContentOperation.ANNOTATE) {
            store.put(updated, existing.updatedAtEpochMillis)
            onMutationCommitted()
            updated
        }
    }

    public fun tombstone(
        annotationId: String,
        access: ContentAccessRequest,
        nowEpochMillis: Long,
        reason: String = USER_DELETED_REASON,
    ): ContentAnnotation {
        // Do not read a private annotation, or disclose whether it exists, before the host gate.
        // The later execute call deliberately remains so a concurrent revocation still wins.
        operationGate.requireAllowed(access, ContentOperation.ANNOTATE)
        val existing = requireNotNull(store.find(annotationId)) { "Annotation does not exist" }
        requireRangeMatchesAccess(existing.range, access)
        require(nowEpochMillis >= existing.updatedAtEpochMillis) { "Annotation update time regressed" }
        val tombstone = existing.copy(
            body = null,
            state = ContentAnnotationState.TOMBSTONE,
            updatedAtEpochMillis = nowEpochMillis,
            tombstoneReason = reason,
        )
        return operationGate.execute(access, ContentOperation.ANNOTATE) {
            store.put(tombstone, existing.updatedAtEpochMillis)
            onMutationCommitted()
            tombstone
        }
    }

    /**
     * Re-anchor a text annotation to a new content revision.  Failure creates a tombstone instead
     * of silently attaching the note to unrelated text.
     */
    public fun reanchorText(
        annotationId: String,
        newScope: ReadingScope,
        newText: String,
        access: ContentAccessRequest,
        nowEpochMillis: Long,
    ): AnnotationReanchorResult {
        require(newText.length <= MAX_DOCUMENT_CHARS && isWellFormedText(newText)) {
            "Annotation re-anchor text is invalid"
        }
        // Re-anchoring needs the old quote/body, so the initial authorization must precede the
        // store lookup. The final write remains independently gated with its actual range size.
        operationGate.requireAllowed(access, ContentOperation.ANNOTATE)
        val existing = requireNotNull(store.find(annotationId)) { "Annotation does not exist" }
        require(existing.state == ContentAnnotationState.ACTIVE) { "A tombstone cannot be re-anchored" }
        require(nowEpochMillis >= existing.updatedAtEpochMillis) { "Annotation update time regressed" }
        requireScopeMatches(newScope, access)
        val currentStart = existing.range.start as? ReadingLocator.Text
        val currentEnd = existing.range.end as? ReadingLocator.Text
        if (currentStart == null || currentEnd == null) {
            return tombstoneForReanchorFailure(existing, access, nowEpochMillis)
        }
        val quote = existing.range.quote ?: currentStart.quote
        val resolvedStart = quote?.findIn(newText)
        if (resolvedStart == null) {
            return tombstoneForReanchorFailure(existing, access, nowEpochMillis)
        }
        val selectedLength = when {
            existing.range.quote != null -> existing.range.quote.exact.length
            currentEnd.offset >= currentStart.offset -> currentEnd.offset - currentStart.offset
            else -> 0
        }
        val resolvedEnd = safeBoundaryAtOrBefore(newText, minOf(newText.length, resolvedStart + selectedLength))
        if (resolvedEnd < resolvedStart) {
            return tombstoneForReanchorFailure(existing, access, nowEpochMillis)
        }
        val exactEnd = if (resolvedEnd == resolvedStart) {
            safeBoundaryAtOrAfter(newText, minOf(newText.length, resolvedStart + 1))
        } else {
            resolvedEnd
        }
        if (exactEnd <= resolvedStart) {
            return tombstoneForReanchorFailure(existing, access, nowEpochMillis)
        }
        val replacementQuote = newText.quoteAt(
            resolvedStart,
            minOf(exactEnd, resolvedStart + MAX_QUOTE_EXACT_CHARS),
        )
        val updatedRange = ReadingRange(
            start = currentStart.copy(
                schemaVersion = newScope.schemaVersion,
                scope = newScope,
                offset = resolvedStart,
                progression = progression(resolvedStart, newText.length),
                quote = replacementQuote,
            ),
            end = currentEnd.copy(
                schemaVersion = newScope.schemaVersion,
                scope = newScope,
                offset = exactEnd,
                progression = progression(exactEnd, newText.length),
                quote = null,
            ),
            quote = if (exactEnd - resolvedStart <= MAX_QUOTE_EXACT_CHARS) {
                newText.quoteAt(resolvedStart, exactEnd)
            } else {
                replacementQuote
            },
        )
        val updated = existing.copy(
            range = updatedRange,
            updatedAtEpochMillis = nowEpochMillis,
        )
        val effective = access.withActualTextCharacters(exactEnd - resolvedStart)
        return operationGate.execute(effective, ContentOperation.ANNOTATE) {
            store.put(updated, existing.updatedAtEpochMillis)
            onMutationCommitted()
            AnnotationReanchorResult(
                status = if (updated.range == existing.range) {
                    AnnotationReanchorStatus.UNCHANGED
                } else {
                    AnnotationReanchorStatus.REANCHORED
                },
                annotation = updated,
            )
        }
    }

    /**
     * Host-policy cleanup path. A revoked grant cannot authorize an ordinary annotation mutation,
     * so this method deliberately checks for denial and then bypasses the user-operation gate to
     * redact stored body/quote data while retaining only a convergence tombstone and locator ids.
     */
    public fun purgeRevokedDerivedData(
        scope: ReadingScope,
        access: ContentAccessRequest,
        nowEpochMillis: Long,
    ): Int {
        requireScopeMatches(scope, access)
        if (operationGate.decide(access, ContentOperation.ANNOTATE) ==
            dev.shinsou.kmp.rights.RightsDecision.ALLOW
        ) return 0

        var purged = 0
        store.list(scope, includeTombstones = true).forEach { existing ->
            val redacted = existing.redactedForRightsInvalidation(nowEpochMillis)
            if (redacted != existing) {
                store.put(redacted, existing.updatedAtEpochMillis)
                purged++
            }
        }
        if (purged > 0) onMutationCommitted()
        return purged
    }

    /**
     * Globally safe cleanup which never depends on an open reader scope.
     *
     * The resolver is host-owned and reconstructs the current acquisition/manifest access request
     * from the durable publication graph. Missing graph edges fail closed. Every page and row has a
     * cancellation boundary, and a raced annotation CAS is deferred to the next background pass.
     */
    public suspend fun purgeUnauthorizedDerivedDataInBackground(
        target: DerivedRightsCleanupTarget,
        nowEpochMillis: Long,
        resolveAccess: (ContentAnnotation) -> ContentAccessRequest?,
        pageSize: Int = BACKGROUND_ANNOTATION_RIGHTS_PAGE_SIZE,
    ): AnnotationDerivedDataCleanupResult {
        require(nowEpochMillis >= 0) { "Annotation cleanup timestamp must be non-negative" }
        require(pageSize in 1..MAX_BACKGROUND_ANNOTATION_RIGHTS_PAGE_SIZE) {
            "Annotation cleanup page size is invalid"
        }
        var afterAnnotationId: String? = null
        var examined = 0
        var redactedCount = 0
        var deferred = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            yield()
            currentCoroutineContext().ensureActive()
            val page = store.listRightsCleanupPage(target, afterAnnotationId, pageSize)
            if (page.isEmpty()) break
            afterAnnotationId = page.last().annotationId
            for (existing in page) {
                currentCoroutineContext().ensureActive()
                examined++
                val access = resolveAccess(existing)
                val belongsToTarget = when (target) {
                    DerivedRightsCleanupTarget.All -> true
                    is DerivedRightsCleanupTarget.Publication ->
                        existing.scope.publicationId == target.publicationId
                    is DerivedRightsCleanupTarget.Grant ->
                        access?.grantReference == target.reference ||
                            target.lastKnownScope?.contains(existing.scope) == true
                }
                if (!belongsToTarget) continue
                val effectiveAccess = access?.withActualTextCharacters(
                    existing.range.estimatedTextCharacters(),
                )
                if (effectiveAccess != null &&
                    operationGate.decide(effectiveAccess, ContentOperation.ANNOTATE) ==
                    RightsDecision.ALLOW
                ) continue

                val redacted = existing.redactedForRightsInvalidation(nowEpochMillis)
                if (redacted == existing) continue
                try {
                    store.putInBackground(redacted, existing.updatedAtEpochMillis)
                    redactedCount++
                } catch (_: AnnotationConflictException) {
                    // A foreground/sync winner owns the row. Re-read it during the next pass.
                    deferred++
                }
                yield()
            }
            if (page.size < pageSize) break
        }
        if (redactedCount > 0) onMutationCommitted()
        return AnnotationDerivedDataCleanupResult(examined, redactedCount, deferred)
    }

    private fun tombstoneForReanchorFailure(
        existing: ContentAnnotation,
        access: ContentAccessRequest,
        nowEpochMillis: Long,
    ): AnnotationReanchorResult {
        val tombstone = existing.copy(
            body = null,
            state = ContentAnnotationState.TOMBSTONE,
            updatedAtEpochMillis = nowEpochMillis,
            tombstoneReason = REANCHOR_FAILED_REASON,
        )
        return operationGate.execute(access, ContentOperation.ANNOTATE) {
            store.put(tombstone, existing.updatedAtEpochMillis)
            onMutationCommitted()
            AnnotationReanchorResult(AnnotationReanchorStatus.TOMBSTONED, tombstone)
        }
    }
}

internal fun DerivedRightsCleanupTarget.mayContain(scope: ReadingScope): Boolean = when (this) {
    DerivedRightsCleanupTarget.All -> true
    is DerivedRightsCleanupTarget.Publication -> scope.publicationId == publicationId
    is DerivedRightsCleanupTarget.Grant -> lastKnownScope?.contains(scope) ?: true
}

private fun dev.shinsou.kmp.rights.RightsScope.contains(scope: ReadingScope): Boolean =
    publicationId == scope.publicationId && acquisitionId == scope.acquisitionId &&
        (unitId == null || unitId == scope.unitId) &&
        (contentRevision == null || contentRevision == scope.contentRevision)

private fun ContentAnnotation.redactedForRightsInvalidation(nowEpochMillis: Long): ContentAnnotation = copy(
    range = range.withoutQuoteFallback(),
    body = null,
    colorArgb = null,
    state = ContentAnnotationState.TOMBSTONE,
    updatedAtEpochMillis = maxOf(nowEpochMillis, updatedAtEpochMillis),
    tombstoneReason = RIGHTS_REVOKED_REASON,
)

private fun ReadingRange.withoutQuoteFallback(): ReadingRange = copy(
    start = start.withoutQuoteFallback(),
    end = end.withoutQuoteFallback(),
    quote = null,
)

private fun ReadingLocator.withoutQuoteFallback(): ReadingLocator = when (this) {
    is ReadingLocator.Image -> this
    is ReadingLocator.Text -> copy(quote = null)
    is ReadingLocator.Epub -> copy(quote = null)
}

private fun ReadingRange.estimatedTextCharacters(): Int = when {
    start is ReadingLocator.Text && end is ReadingLocator.Text ->
        maxOf(0, end.offset - start.offset)
    start is ReadingLocator.Epub && end is ReadingLocator.Epub -> {
        val startOffset = start.offsetHint
        val endOffset = end.offsetHint
        if (startOffset != null && endOffset != null) maxOf(0, endOffset - startOffset) else 0
    }
    else -> 0
}

private fun ContentAccessRequest.withActualTextCharacters(length: Int): ContentAccessRequest = copy(
    context = RightsOperationContext(
        offlineBytes = context.offlineBytes,
        textCharacters = length.toLong(),
        watermarkApplied = context.watermarkApplied,
    ),
)

private fun requireRangeMatchesAccess(range: ReadingRange, access: ContentAccessRequest) {
    requireScopeMatches(range.start.scope, access)
}

private fun requireScopeMatches(scope: ReadingScope, access: ContentAccessRequest) {
    val rights = access.scope
    require(rights.publicationId == scope.publicationId &&
        rights.acquisitionId == scope.acquisitionId &&
        rights.unitId == scope.unitId &&
        rights.contentRevision == scope.contentRevision) {
        "Annotation and rights scope do not match"
    }
}

private fun String.quoteAt(start: Int, end: Int): TextQuote {
    require(start in 0 until end && end <= length)
    val prefixStart = safeBoundaryAtOrAfter(this, maxOf(0, start - QUOTE_CONTEXT_CHARS))
    val suffixEnd = safeBoundaryAtOrBefore(this, minOf(length, end + QUOTE_CONTEXT_CHARS))
    return TextQuote(
        exact = substring(start, end),
        prefix = substring(prefixStart, start),
        suffix = substring(end, suffixEnd),
    )
}

private fun progression(offset: Int, length: Int): Double = if (length == 0) 0.0 else offset.toDouble() / length

private fun safeBoundaryAtOrBefore(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index--
    return index
}

private fun safeBoundaryAtOrAfter(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index++
    return index
}

private fun isWellFormedText(value: String): Boolean {
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

private fun requireUuid(value: String, label: String) {
    require(UUID_PATTERN.matches(value) && value != NIL_UUID) { "$label must be a lowercase non-NIL UUID" }
}

private const val MAX_BODY_CHARS: Int = 16_384
private const val MAX_REASON_LENGTH: Int = 128
private const val MAX_DOCUMENT_CHARS: Int = 5_000_000
private const val MAX_QUOTE_EXACT_CHARS: Int = 4_096
private const val QUOTE_CONTEXT_CHARS: Int = 64
private const val USER_DELETED_REASON: String = "user_deleted"
private const val REANCHOR_FAILED_REASON: String = "quote_not_found_after_revision"
private const val RIGHTS_REVOKED_REASON: String = "rights_revoked"
private const val BACKGROUND_ANNOTATION_RIGHTS_PAGE_SIZE: Int = 64
private const val MAX_BACKGROUND_ANNOTATION_RIGHTS_PAGE_SIZE: Int = 256
private const val NIL_UUID: String = "00000000-0000-0000-0000-000000000000"
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
