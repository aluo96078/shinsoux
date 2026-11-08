package dev.shinsou.kmp.app

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.RightsEnforcedAnnotationService
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.content.access.RightsEnforcedContentOperations
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.EpubRenderRequestFactory
import dev.shinsou.kmp.reader.EpubSemanticDocumentFactory
import dev.shinsou.kmp.reader.ImageRenderPageFactory
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.DerivedRightsCleanupTarget
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantInvalidation
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.search.DerivedLocalFullTextIndex
import dev.shinsou.kmp.search.FoundationFullTextIndexReconciler
import dev.shinsou.kmp.search.FullTextIndexReconcileResult
import dev.shinsou.kmp.tts.PlatformTextToSpeechEngine
import dev.shinsou.kmp.tts.RightsEnforcedTextToSpeechService
import dev.shinsou.kmp.tts.UnavailablePlatformTextToSpeechEngine
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel

public data class RevokedContentCleanupResult(
    val searchDocumentsPurged: Int,
    val annotationsRedacted: Int,
    val speechStopped: Boolean,
)

public data class DerivedRightsCleanupResult(
    val targetsExamined: Int,
    val searchDocumentsExamined: Int,
    val searchDocumentsPurged: Int,
    val annotationsExamined: Int,
    val annotationsRedacted: Int,
    val concurrentAnnotationChangesDeferred: Int,
    val speechStopped: Boolean,
) {
    internal operator fun plus(other: DerivedRightsCleanupResult): DerivedRightsCleanupResult =
        DerivedRightsCleanupResult(
            targetsExamined = targetsExamined + other.targetsExamined,
            searchDocumentsExamined = searchDocumentsExamined + other.searchDocumentsExamined,
            searchDocumentsPurged = searchDocumentsPurged + other.searchDocumentsPurged,
            annotationsExamined = annotationsExamined + other.annotationsExamined,
            annotationsRedacted = annotationsRedacted + other.annotationsRedacted,
            concurrentAnnotationChangesDeferred = concurrentAnnotationChangesDeferred +
                other.concurrentAnnotationChangesDeferred,
            speechStopped = speechStopped || other.speechStopped,
        )

    internal companion object {
        val Empty: DerivedRightsCleanupResult = DerivedRightsCleanupResult(0, 0, 0, 0, 0, 0, false)
    }
}

/** One host-owned M4 graph. It intentionally shares M1's hydrated rights authority and SQLite. */
public class ContentFeatureRuntime(
    private val foundation: ContentFoundationRuntime,
    platformTextToSpeechEngine: PlatformTextToSpeechEngine? = null,
    private val nowEpochMillis: () -> Long,
    onAnnotationMutationCommitted: () -> Unit = {},
    onRightsInvalidated: () -> Unit = {},
) {
    /** Read-only format metadata used by library chrome; bodies remain lazy and unopened. */
    public fun publicationContentKinds(
        publicationKeys: Set<PublicationKey>,
    ): Map<PublicationKey, Set<ContentKind>> {
        if (publicationKeys.isEmpty()) return emptyMap()
        return foundation.publications.all().asSequence()
            .filter { it.key in publicationKeys }
            .associate { publication ->
                publication.key to publication.units.flatMapTo(linkedSetOf()) { unit ->
                    unit.latestManifest?.representations.orEmpty().map { it.kind }
                }
            }
    }

    private val speechEngine = platformTextToSpeechEngine ?: UnavailablePlatformTextToSpeechEngine()

    public val operationGate: HostContentOperationGate = HostContentOperationGate(
        authority = foundation.rightsAuthority,
        nowEpochMillis = nowEpochMillis,
    )
    public val operations: RightsEnforcedContentOperations = RightsEnforcedContentOperations(operationGate)
    public val searchIndex: DerivedLocalFullTextIndex =
        foundation.createDerivedLocalFullTextIndex(operationGate)
    private val searchReconciler = FoundationFullTextIndexReconciler(
        foundation = foundation,
        operationGate = operationGate,
        index = searchIndex,
    )
    public val annotations: RightsEnforcedAnnotationService = RightsEnforcedAnnotationService(
        operationGate = operationGate,
        store = foundation.annotations,
        onMutationCommitted = onAnnotationMutationCommitted,
    )
    public val textToSpeech: RightsEnforcedTextToSpeechService = RightsEnforcedTextToSpeechService(
        operationGate = operationGate,
        platformEngine = speechEngine,
    )
    private val pendingRightsInvalidations = Channel<RightsGrantInvalidation>(Channel.UNLIMITED)
    private val rightsInvalidationSubscription = foundation.rightsAuthority.observeInvalidations { event ->
        // Revocation must stop already queued native speech synchronously. SQLite cleanup is
        // explicitly deferred to the app's cancellable background actor.
        runCatching { textToSpeech.stop() }
        if (pendingRightsInvalidations.trySend(event).isSuccess) onRightsInvalidated()
    }
    /** Reads exact EPUB resources lazily, only after the reader has passed the DISPLAY gate. */
    public val epubRenderRequests: EpubRenderRequestFactory = EpubRenderRequestFactory(foundation.blobStore)
    /** Rebuildable XHTML text/CFI map shared by EPUB progress, search, TTS and annotations. */
    public val epubSemanticDocuments: EpubSemanticDocumentFactory =
        EpubSemanticDocumentFactory(foundation.blobStore)
    /** Reads only visible image bodies; opening a long comic never hydrates every page at once. */
    public val imageRenderPages: ImageRenderPageFactory = ImageRenderPageFactory(foundation.blobStore)
    public val speechCapability get() = speechEngine.capability

    /**
     * Low-priority/cancellable hydration of latest durable plain-text manifests into global search.
     * Callers should launch this after startup or an import/restore commit, never on the UI thread.
     */
    public suspend fun reconcileSearchIndex(): FullTextIndexReconcileResult =
        searchReconciler.reconcile()

    /** What the last complete pass saw; only the background actor reads and writes it. */
    private var lastCompleteReconcile: SearchReconcileInputs? = null

    /**
     * [reconcileSearchIndex] for the background actor: skipped when the content graph, rights
     * policy and index rows are all as they were at the start of the last pass that left nothing
     * unavailable or unauthorized. That pass decodes the whole library and probes every indexed
     * paragraph, which cost tens of seconds of CPU each time the app left the screen.
     */
    public suspend fun reconcileSearchIndexIfChanged(): FullTextIndexReconcileResult? {
        val inputs = SearchReconcileInputs(
            contentGeneration = foundation.contentGeneration,
            rightsRevision = foundation.rightsAuthority.policyRevision,
            indexRemovals = searchIndex.removalRevision,
        )
        if (inputs == lastCompleteReconcile) return null
        lastCompleteReconcile = null
        val result = searchReconciler.reconcile()
        if (result.unavailableRepresentations == 0 && result.unauthorizedRepresentations == 0) {
            lastCompleteReconcile = inputs
        }
        return result
    }

    private data class SearchReconcileInputs(
        val contentGeneration: Long,
        val rightsRevision: Long,
        val indexRemovals: Long,
    )

    /** Called after foreground/resume and before presenting a typed content session. */
    public suspend fun cleanupRevokedDerivedData(
        scope: ReadingScope,
        access: ContentAccessRequest,
    ): RevokedContentCleanupResult {
        val searchPurged = searchIndex.purgeUnauthorizedForeground(scope)
        val annotationPurged = annotations.purgeRevokedDerivedData(
            scope = scope,
            access = access,
            nowEpochMillis = nowEpochMillis(),
        )
        val stopSpeech = operationGate.decide(access, ContentOperation.TTS) != RightsDecision.ALLOW
        if (stopSpeech) textToSpeech.stop()
        return RevokedContentCleanupResult(searchPurged, annotationPurged, stopSpeech)
    }

    /** Purges one invalidated grant without walking unrelated publications or acquisitions. */
    public suspend fun purgeInvalidatedGrantDerivedData(
        reference: RightsGrantRef,
        lastKnownGrant: RightsGrant? = foundation.rightsGrants.find(reference),
    ): DerivedRightsCleanupResult {
        textToSpeech.stop()
        return cleanupDerivedRightsTarget(
            target = DerivedRightsCleanupTarget.Grant(reference, lastKnownGrant?.scope),
            speechStopped = true,
        )
    }

    /** Publication-scoped host-policy cleanup, intended only for a low-priority background job. */
    public suspend fun purgePublicationDerivedData(
        publicationId: PublicationKey,
    ): DerivedRightsCleanupResult {
        textToSpeech.stop()
        return cleanupDerivedRightsTarget(
            target = DerivedRightsCleanupTarget.Publication(publicationId),
            speechStopped = true,
        )
    }

    /**
     * Drains authority invalidations captured before/after a durable graph replacement.
     * Cancellation or failure requeues every idempotent target so the next background edge can
     * finish cleanup even if this actor was stopped by foregrounding the app.
     */
    public suspend fun reconcilePendingRightsInvalidations(): DerivedRightsCleanupResult {
        val pending = mutableListOf<RightsGrantInvalidation>()
        while (true) {
            val event = pendingRightsInvalidations.tryReceive().getOrNull() ?: break
            pending += event
        }
        if (pending.isEmpty()) return DerivedRightsCleanupResult.Empty
        // One memoized read side for the whole drain: each publication an annotation needs is
        // decoded at most once, and none is decoded when no annotation refers to it.
        val publications = PublicationLookup()
        return try {
            pending.fold(DerivedRightsCleanupResult.Empty) { result, event ->
                result + cleanupDerivedRightsTarget(
                    target = DerivedRightsCleanupTarget.Grant(
                        event.reference,
                        event.lastKnownGrant?.scope,
                    ),
                    speechStopped = true,
                    publications = publications,
                )
            }
        } catch (failure: Throwable) {
            pending.forEach { event -> pendingRightsInvalidations.trySend(event) }
            if (failure is CancellationException) throw failure
            throw failure
        }
    }

    /** Finds durable grants whose exclusive valid-until boundary has passed and purges by scope. */
    public suspend fun sweepExpiredRightsDerivedData(): DerivedRightsCleanupResult {
        val now = nowEpochMillis()
        val expired = foundation.rightsGrants.all()
            .filter { grant -> grant.validUntilEpochMillis?.let { now >= it } == true }
            .sortedBy { grant -> grant.grantId.value }
        if (expired.isEmpty()) return DerivedRightsCleanupResult.Empty
        textToSpeech.stop()
        val publications = PublicationLookup()
        return expired.fold(DerivedRightsCleanupResult.Empty) { result, grant ->
            result + cleanupDerivedRightsTarget(
                target = DerivedRightsCleanupTarget.Grant(grant.grantId, grant.scope),
                speechStopped = true,
                publications = publications,
            )
        }
    }

    /**
     * Restart safety net for missing/revoked/provider-denied grants and orphaned annotation scopes.
     * This is a complete-library sweep, so production calls it only from the background actor.
     */
    public suspend fun sweepUnauthorizedDerivedData(): DerivedRightsCleanupResult {
        val result = cleanupDerivedRightsTarget(DerivedRightsCleanupTarget.All, speechStopped = false)
        if (result.searchDocumentsPurged == 0 && result.annotationsRedacted == 0) return result
        textToSpeech.stop()
        return result.copy(speechStopped = true)
    }

    private suspend fun cleanupDerivedRightsTarget(
        target: DerivedRightsCleanupTarget,
        speechStopped: Boolean,
        publications: PublicationLookup = PublicationLookup(),
    ): DerivedRightsCleanupResult {
        val search = searchIndex.purgeUnauthorizedInBackground(target)
        val annotationsResult = annotations.purgeUnauthorizedDerivedDataInBackground(
            target = target,
            nowEpochMillis = nowEpochMillis(),
            resolveAccess = { annotation -> currentAnnotationAccess(annotation, publications) },
        )
        return DerivedRightsCleanupResult(
            targetsExamined = 1,
            searchDocumentsExamined = search.documentsExamined,
            searchDocumentsPurged = search.documentsPurged,
            annotationsExamined = annotationsResult.annotationsExamined,
            annotationsRedacted = annotationsResult.annotationsRedacted,
            concurrentAnnotationChangesDeferred = annotationsResult.concurrentChangesDeferred,
            speechStopped = speechStopped,
        )
    }

    /**
     * Publications read on first use and kept for one cleanup. A full sweep used to decode the
     * whole library (every chapter and manifest) up front, though only annotations consult it and
     * most libraries have few or none: tens of seconds of CPU each time the app left the screen.
     */
    private inner class PublicationLookup {
        private val loaded = HashMap<PublicationKey, Publication?>()

        operator fun get(key: PublicationKey): Publication? =
            loaded.getOrPut(key) { foundation.publications.find(key) }
    }

    private fun currentAnnotationAccess(
        annotation: ContentAnnotation,
        publications: PublicationLookup,
    ): ContentAccessRequest? {
        val scope = annotation.scope
        val publication = publications[scope.publicationId] ?: return null
        val acquisition = publication.acquisitions.singleOrNull { it.id == scope.acquisitionId }
            ?: return null
        val unit = acquisition.units.singleOrNull { it.key == scope.unitId } ?: return null
        val manifest = unit.manifestRevisions.singleOrNull {
            it.contentRevision == scope.contentRevision
        } ?: return null
        return ContentAccessRequest(
            grantReference = acquisition.rightsGrantRef,
            scope = RightsScope(
                publicationId = scope.publicationId,
                acquisitionId = scope.acquisitionId,
                unitId = scope.unitId,
                manifestId = manifest.manifestId,
                contentRevision = scope.contentRevision,
            ),
        )
    }

    public fun close() {
        // Search rows are restart-safe derived state. Explicit clear/purge operations own deletion;
        // closing a UI/runtime graph must not make global search depend on reopening every reader.
        rightsInvalidationSubscription.cancel()
        pendingRightsInvalidations.close()
        textToSpeech.close()
    }
}
