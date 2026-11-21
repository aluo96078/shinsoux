package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.acquisition.ContentAcquisitionResult
import dev.shinsou.kmp.content.acquisition.BoundedEpubArchiveExtractor
import dev.shinsou.kmp.content.acquisition.ByteArrayEpubArchiveSource
import dev.shinsou.kmp.content.acquisition.EpubAcquisitionRequest
import dev.shinsou.kmp.content.acquisition.EpubAcquisitionService
import dev.shinsou.kmp.content.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.content.acquisition.ImageSequenceAcquisitionRequest
import dev.shinsou.kmp.content.acquisition.ImageSequenceAcquisitionService
import dev.shinsou.kmp.content.acquisition.LocalAcquisitionIdentityDeriver
import dev.shinsou.kmp.content.acquisition.LocalAcquisitionTarget
import dev.shinsou.kmp.content.acquisition.LocalImagePageSource
import dev.shinsou.kmp.content.acquisition.SUPPORTED_LOCAL_IMAGE_MEDIA_TYPES
import dev.shinsou.kmp.content.acquisition.TextAcquisitionRequest
import dev.shinsou.kmp.content.acquisition.TextAcquisitionService
import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.LegacyOutboxDraft
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentLocalBodyPurgeResult
import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ContentTransactionException
import dev.shinsou.kmp.domain.content.EpubResource
import dev.shinsou.kmp.domain.content.EpubSpineDocument
import dev.shinsou.kmp.domain.content.ImagePage
import dev.shinsou.kmp.domain.content.ImageTransform
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.domain.content.ResourceRef
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.content.access.PendingContentBodyStoreRequest
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.EXTENSION_CONTENT_NAMESPACE
import dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2
import dev.shinsou.kmp.domain.model.LocalPackageKind
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.RemoteEntityKind
import dev.shinsou.kmp.domain.model.Rfc9562UuidV5
import dev.shinsou.kmp.domain.model.SourceBinding
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.domain.model.extensionAcquisitionId
import dev.shinsou.kmp.domain.model.extensionPublicationKey
import dev.shinsou.kmp.domain.model.extensionUnitKey
import dev.shinsou.kmp.domain.model.framedIdentity
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.domain.model.ReadingLocator
import dev.shinsou.kmp.domain.model.ReadingScope
import dev.shinsou.kmp.domain.content.UnifiedReaderContent
import dev.shinsou.kmp.domain.content.UnifiedReaderNavigationFactory
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// R5-1: the constructors of this page and of ExtensionUnitSelectionV2 are public for the browse
// screen's tests (:ui fakes BrowseCallbacks). The authority stays with the consumer: a selection is
// accepted only with the consumer's own process-local owner token, which nothing outside can read.
/**
 * Host-owned page of exact v2 publication metadata.
 *
 * [units] are capabilities issued by the consumer that loaded this page. UI code cannot forge one
 * the consumer accepts (its owner token is the consumer's own), so they keep the
 * source/package/publication identity intact through the later body request.
 */
public class ExtensionPublicationPageV2 internal constructor(
    public val sourceKey: SourceKey,
    public val publication: RemotePublicationV2,
    units: List<ExtensionUnitSelectionV2>,
    public val page: Int,
    public val hasNextPage: Boolean,
    /** True when built from local copies (cache or stored chapters) because the source was not used. */
    public val offline: Boolean = false,
) {
    public val units: List<ExtensionUnitSelectionV2> = units.toList()
}

/** One host-issued exact unit selection. The ownership token is deliberately process-local. */
public class ExtensionUnitSelectionV2 internal constructor(
    public val sourceKey: SourceKey,
    public val remotePublicationId: String,
    public val publicationTitle: String,
    public val unit: RemoteUnitV2,
    internal val ownerToken: Any,
)

public data class ExtensionContentMaterializationV2(
    val sourceKey: SourceKey,
    val remotePublicationId: String,
    val remoteUnitId: String,
    val representationId: String,
    val publicationKey: PublicationKey,
    val acquisitionId: String,
    val unitKey: UnitKey,
    val manifestId: String,
    val contentRepresentationId: String,
    val commit: ContentCommitResult,
)

/** Durable local reading hints for one source-backed publication. */
public data class ExtensionLocalReadingStateV2(
    val completedRemoteUnitIds: Set<String> = emptySet(),
    val lastReadRemoteUnitId: String? = null,
    /** Last visual page of started but unfinished units, keyed by the source's opaque unit id. */
    val inProgressRemoteUnits: Map<String, ExtensionUnitPageProgressV2> = emptyMap(),
    /** When [lastReadRemoteUnitId] was read. */
    val lastReadAtMillis: Long? = null,
)

/**
 * Reading of a publication that other devices synced for units this device has not stored yet
 * (it is applied to them once they are), keyed by the source's opaque unit id.
 */
public class ExtensionSyncedReadingV2(
    public val lastReadRemoteUnitId: String? = null,
    public val lastReadAtMillis: Long? = null,
    public val completedRemoteUnitIds: Set<String> = emptySet(),
    /** Zero-based last page of units started but not finished. */
    public val pageIndexByRemoteUnit: Map<String, Int> = emptyMap(),
    private val locatorOf: (remoteUnitId: String, scope: ReadingScope) -> ReadingLocator? = { _, _ -> null },
) {
    /** The synced position inside [remoteUnitId], placed in this device's [scope]; null when none was synced. */
    public fun locator(remoteUnitId: String, scope: ReadingScope): ReadingLocator? = locatorOf(remoteUnitId, scope)
}

/** Where a unit opened here the first time starts: another device's position and page in it. */
public data class ExtensionSyncedStartV2(val locator: ReadingLocator?, val pageIndex: Int?)

/**
 * This device's reading with [synced] added for the units it has not stored: the later of the
 * two last reads is the one to resume, finished units add up, and started ones gain their page.
 */
internal fun ExtensionLocalReadingStateV2.withSynced(synced: ExtensionSyncedReadingV2): ExtensionLocalReadingStateV2 {
    val syncedAt = synced.lastReadAtMillis
    val localAt = lastReadAtMillis
    val resumeSynced = synced.lastReadRemoteUnitId != null &&
        (lastReadRemoteUnitId == null || (syncedAt != null && (localAt == null || syncedAt > localAt)))
    val completed = completedRemoteUnitIds + synced.completedRemoteUnitIds
    val syncedPages = synced.pageIndexByRemoteUnit
        .filterKeys { it !in completed && it !in inProgressRemoteUnits }
        .mapValues { (_, page) -> ExtensionUnitPageProgressV2(page.coerceAtLeast(0)) }
    return copy(
        completedRemoteUnitIds = completed,
        lastReadRemoteUnitId = if (resumeSynced) synced.lastReadRemoteUnitId else lastReadRemoteUnitId,
        lastReadAtMillis = if (resumeSynced) syncedAt else localAt,
        inProgressRemoteUnits = inProgressRemoteUnits + syncedPages,
    )
}

/** Zero-based last visual page; [pageCount] is absent when the rendition never reported one. */
public data class ExtensionUnitPageProgressV2(
    val pageIndex: Int,
    val pageCount: Int? = null,
) {
    init {
        require(pageIndex >= 0) { "Unit page index must be non-negative" }
        require(pageCount == null || pageCount > pageIndex) { "Unit page must be inside its page count" }
    }
}

/** Clamps persisted chapter/history values, which may predate a re-paginated rendition. */
public fun extensionUnitPageProgress(lastPageRead: Int, lastPageCount: Int?): ExtensionUnitPageProgressV2 {
    val pageIndex = lastPageRead.coerceAtLeast(0)
    val pageCount = lastPageCount?.takeIf { it > pageIndex }
    return ExtensionUnitPageProgressV2(pageIndex, pageCount)
}

/** App compatibility keys used to join local history to the typed extension graph. */
public data class ExtensionLocalUnitProgressV2(
    val completedUnitKeys: Set<UnitKey> = emptySet(),
    val lastReadUnitKey: UnitKey? = null,
    val inProgressUnits: Map<UnitKey, ExtensionUnitPageProgressV2> = emptyMap(),
    /** When [lastReadUnitKey] was read. */
    val lastReadAtMillis: Long? = null,
)

/** Exact rights-gated reader input for one materialized extension representation. */
public data class ExtensionReadableContentV2(
    val content: UnifiedReaderContent,
    val canonicalText: String?,
    val access: ContentAccessRequest,
)

public sealed class ExtensionContentConsumerException(message: String) : IllegalStateException(message) {
    public class ForeignSelection : ExtensionContentConsumerException(
        "The extension unit selection was not issued by this host consumer",
    )

    public class RepresentationSelectionRequired(public val availableIds: List<String>) :
        ExtensionContentConsumerException("Select one exact extension content representation")

    public class UnsupportedRepresentation(public val representationId: String) :
        ExtensionContentConsumerException("The selected extension representation is not safely materializable")

    public class UnsupportedImageTransform(public val transformId: String) :
        ExtensionContentConsumerException("The extension image transform is not supported by the host")

    public class UnsupportedEpubEncryption(public val algorithms: Set<String>) :
        ExtensionContentConsumerException("The extension EPUB requires an unsupported protection scheme")

    public class MissingBodyFetcher : ExtensionContentConsumerException(
        "This extension representation requires a host-controlled resource fetcher",
    )
}

/** A bounded immutable response returned by the host network plane, never by extension code. */
public class ExtensionFetchedResourceV2(
    bytes: ByteArray,
    public val mediaType: String?,
) {
    private val body: ByteArray = bytes.copyOf()
    public val byteSize: Int get() = body.size
    public fun copyBytes(): ByteArray = body.copyOf()
    internal fun asEpubArchiveSource(): ByteArrayEpubArchiveSource = ByteArrayEpubArchiveSource(body)
}

/** Maps an exact portable source identity to a process-local network/cookie isolation scope. */
public fun interface ExtensionNetworkScopeResolverV2 {
    public fun resolve(sourceKey: SourceKey): Long?

    public companion object {
        public val LegacyOnly: ExtensionNetworkScopeResolverV2 = ExtensionNetworkScopeResolverV2 { sourceKey ->
            sourceKey.legacyLongId
        }
    }
}

/** Executes an already validated v2 request plan through a host-controlled network scope. */
public fun interface ExtensionResourceFetcherV2 {
    public suspend fun fetch(sourceKey: SourceKey, request: RemoteRequestPlanV2): ExtensionFetchedResourceV2
}

/**
 * Production consumer for the complete details -> units -> content path.
 *
 * Extension output remains inert until this class has revalidated an exact host-issued selection,
 * chosen one representation explicitly, acquired every byte through a host boundary, admitted a
 * conservative rights grant and atomically attached the resulting blobs/publication/outbox rows.
 */
public class ExtensionContentConsumerV2(
    private val gateway: ExtensionBrowseContentGatewayV2,
    private val foundation: ContentFoundationRuntime,
    private val offlineStoreAuthorizer: ContentBodyOfflineStoreAuthorizer,
    private val resourceFetcher: ExtensionResourceFetcherV2? = null,
    private val nowEpochMillis: () -> Long,
    private val localUnitProgress: (PublicationKey) -> ExtensionLocalUnitProgressV2 = {
        ExtensionLocalUnitProgressV2()
    },
    /** Reading other devices synced for a publication's units not stored here; never on the UI thread. */
    private val syncedReading: (SourceKey, remotePublicationId: String) -> ExtensionSyncedReadingV2? = { _, _ -> null },
    private val identityDeriver: LocalAcquisitionIdentityDeriver = LocalAcquisitionIdentityDeriver(),
    private val epubArchiveExtractor: EpubArchiveExtractor = BoundedEpubArchiveExtractor(),
) {
    private val ownerToken: Any = Any()

    /**
     * Recovers an old UUID-only local-library row after at least one unit was materialized.
     * New favorites persist a reversible binding directly and do not need this graph fallback.
     */
    public fun extensionLibraryBinding(publicationKey: PublicationKey): ExtensionLibraryBindingV2? {
        val publication = foundation.publications.find(publicationKey) ?: return null
        val bindings = publication.acquisitions.mapNotNull { acquisition ->
            (acquisition.origin as? AcquisitionOrigin.ExtensionSource)?.sourceBinding
        }.distinct()
        val binding = bindings.singleOrNull()
            ?.takeIf { it.entityKind == RemoteEntityKind.PUBLICATION }
            ?: return null
        return runCatching {
            ExtensionLibraryBindingV2(
                publicationKey = publication.key,
                sourceKey = binding.sourceKey,
                remotePublicationId = binding.remoteId,
            )
        }.getOrNull()
    }

    /**
     * Maps compatibility history back through the typed graph to the source's opaque unit id.
     * No secret or source invocation is needed, so the local-library screen can select the exact
     * resume chapter before it starts downloading chapter content.
     */
    public fun localReadingState(publicationKey: PublicationKey): ExtensionLocalReadingStateV2 =
        localReadingState(publicationKey, remoteUnitIds = emptyMap())

    /**
     * Like [localReadingState], also resolving progress of [remoteUnitIds] (the units the caller
     * already listed for the exact source publication) whose content was never stored. Unit
     * identities are derived deterministically, so progress carried over by a library migration
     * shows before any of the target's chapters is opened.
     */
    public fun localReadingState(
        sourceKey: SourceKey,
        remotePublicationId: String,
        remoteUnitIds: Collection<String>,
    ): ExtensionLocalReadingStateV2 {
        val local = localReadingState(
            extensionPublicationKey(sourceKey, remotePublicationId),
            remoteUnitIds.associateBy { remoteId -> extensionUnitKey(sourceKey, remotePublicationId, remoteId) },
        )
        // Reading from other devices for units never stored here, which no local row carries yet.
        return syncedReading(sourceKey, remotePublicationId)?.let(local::withSynced) ?: local
    }

    /**
     * Where another device left the unit of [materialization], for a unit opened here the first
     * time, placed in this device's [scope]; its zero-based page too, when one was synced.
     */
    public fun syncedStart(
        materialization: ExtensionContentMaterializationV2,
        scope: ReadingScope,
    ): ExtensionSyncedStartV2? {
        val unitId = materialization.remoteUnitId
        val synced = syncedReading(materialization.sourceKey, materialization.remotePublicationId)
            ?.takeIf { unitId !in it.completedRemoteUnitIds }
            ?: return null
        val locator = synced.locator(unitId, scope)
        val page = synced.pageIndexByRemoteUnit[unitId]
        return ExtensionSyncedStartV2(locator, page).takeIf { locator != null || page != null }
    }

    private fun localReadingState(
        publicationKey: PublicationKey,
        remoteUnitIds: Map<UnitKey, String>,
    ): ExtensionLocalReadingStateV2 {
        val publication = foundation.publications.find(publicationKey)
        if (publication == null && remoteUnitIds.isEmpty()) return ExtensionLocalReadingStateV2()
        val remoteIdByUnitKey = remoteUnitIds + publication?.acquisitions
            .orEmpty()
            .asSequence()
            .flatMap { it.units.asSequence() }
            .mapNotNull { unit ->
                unit.sourceBinding
                    ?.takeIf { it.entityKind == RemoteEntityKind.UNIT }
                    ?.remoteId
                    ?.let { remoteId -> unit.key to remoteId }
            }
            .toMap()
        val localProgress = localUnitProgress(publicationKey)
        return ExtensionLocalReadingStateV2(
            completedRemoteUnitIds = localProgress.completedUnitKeys.mapNotNullTo(linkedSetOf()) {
                remoteIdByUnitKey[it]
            },
            lastReadRemoteUnitId = localProgress.lastReadUnitKey?.let(remoteIdByUnitKey::get),
            lastReadAtMillis = localProgress.lastReadAtMillis?.takeIf { localProgress.lastReadUnitKey?.let(remoteIdByUnitKey::containsKey) == true },
            inProgressRemoteUnits = localProgress.inProgressUnits
                .filterKeys { it !in localProgress.completedUnitKeys }
                .mapNotNull { (unitKey, progress) -> remoteIdByUnitKey[unitKey]?.let { it to progress } }
                .toMap(),
        )
    }

    /**
     * Source unit ids that have a stored content revision. Every materialization persists the
     * unit, so this survives leaving the detail screen and restarts. Bodies are not re-hashed here
     * (that would read every downloaded byte); opening a damaged one still fails closed.
     */
    public fun downloadedRemoteUnitIds(publicationKey: PublicationKey): Set<String> {
        val publication = foundation.publications.find(publicationKey) ?: return emptySet()
        return publication.acquisitions
            .asSequence()
            .flatMap { it.units.asSequence() }
            .filter { it.manifestRevisions.isNotEmpty() }
            .mapNotNullTo(linkedSetOf()) { unit ->
                unit.sourceBinding?.takeIf { it.entityKind == RemoteEntityKind.UNIT }?.remoteId
            }
    }

    /**
     * Drops every downloaded chapter body of a title that left the library, freeing its disk space.
     * Reading progress, the library binding, the unit graph and rights stay, nothing is sent
     * through sync, and a later download of the same unit works as the first one did. Returns the
     * number of units whose stored content was removed.
     */
    public fun purgeStoredContent(sourceKey: SourceKey, remotePublicationId: String): Int {
        val key = extensionPublicationKey(sourceKey, remotePublicationId)
        if (foundation.publications.find(key) == null) return 0
        var result: ContentLocalBodyPurgeResult? = null
        repeat(MAX_GRAPH_COMMIT_ATTEMPTS) { attempt ->
            if (result != null) return@repeat
            try {
                result = foundation.transactions.purgeLocalPublicationBodies(
                    publicationKey = key,
                    // `materialize` journals each graph change under a deterministic id; forget
                    // them so a re-download commits again instead of replaying the old entry.
                    forgetCommitIdsOf = { publication ->
                        publication.acquisitions
                            .flatMap { it.units }
                            .flatMap { it.manifestRevisions }
                            .mapTo(linkedSetOf()) { "extension-v2:${it.manifestId}" }
                    },
                )
            } catch (busy: IllegalStateException) {
                if (!busy.message.orEmpty().startsWith("Concurrent content transaction must retry") ||
                    attempt == MAX_GRAPH_COMMIT_ATTEMPTS - 1
                ) {
                    throw busy
                }
            }
        }
        val purged = result ?: return 0
        // Blobs are content-addressed and may be shared; the store re-checks every guard per blob.
        foundation.blobStore.deleteUnreferencedNow(purged.removedBlobs)
        return purged.purgedUnitCount
    }

    /**
     * The stored (downloaded or read) units of a publication as a unit page, for when the source
     * cannot be reached. Null when nothing of this publication is stored.
     */
    public fun storedPublicationPage(
        sourceKey: SourceKey,
        publication: RemotePublicationV2,
    ): ExtensionPublicationPageV2? {
        val stored = foundation.publications.find(extensionPublicationKey(sourceKey, publication.remoteId))
            ?: return null
        val units = stored.acquisitions
            .asSequence()
            .flatMap { it.units.asSequence() }
            .filter { it.manifestRevisions.isNotEmpty() }
            .mapNotNull { unit ->
                val remoteId = unit.sourceBinding?.takeIf { it.entityKind == RemoteEntityKind.UNIT }?.remoteId
                    ?: return@mapNotNull null
                unit to remoteId
            }
            .distinctBy { it.second }
            .sortedWith(compareBy(nullsLast()) { it.first.ordinal })
            .map { (unit, remoteId) ->
                ExtensionUnitSelectionV2(
                    sourceKey = sourceKey,
                    remotePublicationId = publication.remoteId,
                    publicationTitle = publication.title,
                    unit = RemoteUnitV2(remoteId, unit.title),
                    ownerToken = ownerToken,
                )
            }
            .toList()
        if (units.isEmpty()) return null
        return ExtensionPublicationPageV2(
            sourceKey = sourceKey,
            publication = publication,
            units = units,
            page = 0,
            hasNextPage = false,
            offline = true,
        )
    }

    /** The cached details and full unit list of a library title, as one offline unit page. */
    public fun cachedPublicationPage(
        sourceKey: SourceKey,
        cached: CachedExtensionPublicationV2,
    ): ExtensionPublicationPageV2 {
        require(cached.sourceCanonicalId == sourceKey.canonicalId) { "Cached publication source changed" }
        return ExtensionPublicationPageV2(
            sourceKey = sourceKey,
            publication = cached.publication,
            units = cached.units.distinctBy(RemoteUnitV2::remoteId).map { unit ->
                ExtensionUnitSelectionV2(
                    sourceKey = sourceKey,
                    remotePublicationId = cached.publication.remoteId,
                    publicationTitle = cached.publication.title,
                    unit = unit,
                    ownerToken = ownerToken,
                )
            },
            page = 0,
            hasNextPage = false,
            offline = true,
        )
    }

    /**
     * The offline unit page of a library title: its [cached] list, with the stored units it does
     * not list yet appended (a chapter downloaded or read after the list was saved), or the stored
     * units alone. Null when neither exists.
     */
    public fun offlinePublicationPage(
        sourceKey: SourceKey,
        cached: CachedExtensionPublicationV2?,
        publication: RemotePublicationV2,
    ): ExtensionPublicationPageV2? {
        val cachedPage = cached?.let { cachedPublicationPage(sourceKey, it) }
        val storedPage = storedPublicationPage(sourceKey, cachedPage?.publication ?: publication)
        if (cachedPage == null || storedPage == null) return cachedPage ?: storedPage
        val listed = cachedPage.units.mapTo(hashSetOf()) { it.unit.remoteId }
        val missing = storedPage.units.filterNot { it.unit.remoteId in listed }
        return ExtensionPublicationPageV2(
            sourceKey = sourceKey,
            publication = cachedPage.publication,
            units = cachedPage.units + missing,
            page = 0,
            hasNextPage = false,
            offline = true,
        )
    }

    /** Reissues a selection for a download queued by an earlier run of this app. */
    public fun restoredSelection(record: ExtensionDownloadRecordV2): ExtensionUnitSelectionV2 =
        ExtensionUnitSelectionV2(
            sourceKey = record.sourceKey,
            remotePublicationId = record.remotePublicationId,
            publicationTitle = record.publicationTitle,
            unit = record.unit,
            ownerToken = ownerToken,
        )

    /**
     * Opens an already stored unit without contacting the source, so downloaded chapters read
     * offline. Null when the unit has no stored revision (or [representationId] asks for one
     * that was not stored); the caller then materializes from the source.
     */
    public fun storedMaterialization(
        selection: ExtensionUnitSelectionV2,
        representationId: String? = null,
    ): ExtensionContentMaterializationV2? {
        if (selection.ownerToken !== ownerToken) throw ExtensionContentConsumerException.ForeignSelection()
        val publication = foundation.publications.find(publicationKey(selection)) ?: return null
        val acquisitionId = acquisitionId(selection)
        val acquisition = publication.acquisitions.singleOrNull { it.id == acquisitionId } ?: return null
        val unit = acquisition.units.firstOrNull { unit ->
            unit.sourceBinding?.takeIf { it.entityKind == RemoteEntityKind.UNIT }?.remoteId == selection.unit.remoteId
        } ?: return null
        val manifest = unit.manifestRevisions.maxByOrNull { it.contentRevision } ?: return null
        val representation = if (representationId == null) {
            // Each materialization stores the one representation that was chosen, so the first
            // stored one is the user's choice; never fall back to the source just to ask again.
            manifest.representations.firstOrNull() ?: return null
        } else {
            // Accept the source's representation id or the stored id this method returns.
            val hashed = extensionId("representation", selection, selection.unit.remoteId, representationId)
            manifest.representations.singleOrNull {
                it.representationId == hashed || it.representationId == representationId
            } ?: return null
        }
        return ExtensionContentMaterializationV2(
            sourceKey = selection.sourceKey,
            remotePublicationId = selection.remotePublicationId,
            remoteUnitId = selection.unit.remoteId,
            representationId = representationId ?: representation.representationId,
            publicationKey = publication.key,
            acquisitionId = acquisition.id,
            unitKey = unit.key,
            manifestId = manifest.manifestId,
            contentRepresentationId = representation.representationId,
            // Nothing was committed; this reopens the revision an earlier commit stored.
            commit = ContentCommitResult(
                commitId = manifest.manifestId,
                replayed = true,
                deferred = false,
                committedGeneration = null,
                attachedOwnerIds = emptyList(),
                outboxDraftIds = emptyList(),
            ),
        )
    }

    public suspend fun publicationPage(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int = 0,
    ): ExtensionPublicationPageV2 {
        val publication = gateway.details(sourceKey, remotePublicationId)
        return publicationUnitsPage(sourceKey, remotePublicationId, publication, page)
    }

    /**
     * Loads a unit page using metadata the host already has (normally from the catalogue row).
     * This keeps the detail surface responsive: chapters can be shown first while a full metadata
     * refresh (description/author/etc.) continues independently. The normal [publicationPage]
     * path remains strict and still fetches authoritative details when no hint exists.
     */
    public suspend fun publicationUnitsPage(
        sourceKey: SourceKey,
        remotePublicationId: String,
        publication: RemotePublicationV2,
        page: Int = 0,
    ): ExtensionPublicationPageV2 {
        require(publication.remoteId == remotePublicationId) {
            "Known extension publication metadata changed remote identity"
        }
        val units = gateway.units(sourceKey, remotePublicationId, page)
        return ExtensionPublicationPageV2(
            sourceKey = sourceKey,
            publication = publication,
            units = units.items.map { unit ->
                ExtensionUnitSelectionV2(
                    sourceKey = sourceKey,
                    remotePublicationId = publication.remoteId,
                    publicationTitle = publication.title,
                    unit = unit,
                    ownerToken = ownerToken,
                )
            },
            page = page,
            hasNextPage = units.hasNextPage,
        )
    }

    /** Opens only the exact immutable revision returned by [materialize]. */
    public fun open(materialization: ExtensionContentMaterializationV2): ExtensionReadableContentV2 {
        val publication = foundation.publications.find(materialization.publicationKey)
            ?: error("The materialized extension publication no longer exists")
        val acquisition = publication.acquisitions.singleOrNull { it.id == materialization.acquisitionId }
            ?: error("The materialized extension acquisition no longer exists")
        val unit = acquisition.units.singleOrNull { it.key == materialization.unitKey }
            ?: error("The materialized extension unit no longer exists")
        val manifest = unit.manifestRevisions.singleOrNull { it.manifestId == materialization.manifestId }
            ?: error("The materialized extension revision no longer exists")
        val representation = manifest.representations.singleOrNull {
            it.representationId == materialization.contentRepresentationId
        } ?: error("The materialized extension representation no longer exists")
        val scope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication.key,
            acquisitionId = acquisition.id,
            unitId = unit.key,
            contentRevision = manifest.contentRevision,
        )
        val access = ContentAccessRequest(
            grantReference = acquisition.rightsGrantRef,
            scope = RightsScope(
                publicationId = publication.key,
                acquisitionId = acquisition.id,
                unitId = unit.key,
                manifestId = manifest.manifestId,
                contentRevision = manifest.contentRevision,
            ),
        )
        val canonicalText = (representation as? ContentRepresentation.PlainText)?.let { text ->
            HostContentOperationGate(foundation.rightsAuthority, nowEpochMillis).execute(
                access,
                ContentOperation.DISPLAY,
            ) {
                val bytes = foundation.blobStore.read(text.resource.blob)
                    ?: error("The materialized extension text body is missing or corrupt")
                bytes.decodeToString(throwOnInvalidSequence = true).also { decoded ->
                    require(decoded.length == text.canonicalUtf16Length) {
                        "The materialized extension text length does not match its manifest"
                    }
                }
            }
        }
        return ExtensionReadableContentV2(
            content = UnifiedReaderContent(
                UnifiedReaderNavigationFactory.create(scope, representation, canonicalText),
            ),
            canonicalText = canonicalText,
            access = access,
        )
    }

    public suspend fun materialize(
        selection: ExtensionUnitSelectionV2,
        representationId: String? = null,
    ): ExtensionContentMaterializationV2 {
        if (selection.ownerToken !== ownerToken) throw ExtensionContentConsumerException.ForeignSelection()
        val result = gateway.content(
            selection.sourceKey,
            selection.remotePublicationId,
            selection.unit.remoteId,
        )
        val payload = selectRepresentation(result, representationId)
        val prepared = when (payload) {
            is UnitContentPayload.InlineTextPayload -> acquireText(selection, payload, payload.source.text)
            is UnitContentPayload.ChunkedTextPayload -> acquireText(
                selection,
                payload,
                collectChunkedText(selection.sourceKey, payload.source),
            )
            is UnitContentPayload.HostFetchTextPayload -> {
                val fetched = fetch(selection.sourceKey, payload.source.body)
                acquireText(
                    selection,
                    payload,
                    fetched.copyBytes().decodeToString(throwOnInvalidSequence = true),
                )
            }
            is UnitContentPayload.ImageSequence -> acquireImages(selection, payload)
            is UnitContentPayload.EpubSpine -> acquireEpub(selection, payload)
        }
        return commit(selection, payload.representationId, prepared)
    }

    private fun selectRepresentation(
        result: UnitContentResultV2,
        requestedId: String?,
    ): UnitContentPayload {
        if (requestedId == null && result.representations.size != 1) {
            throw ExtensionContentConsumerException.RepresentationSelectionRequired(
                result.representations.map(UnitContentPayload::representationId),
            )
        }
        return if (requestedId == null) {
            result.representations.single()
        } else {
            requireNotNull(result.representations.singleOrNull { it.representationId == requestedId }) {
                "Unknown extension representation '$requestedId'"
            }
        }
    }

    private suspend fun collectChunkedText(
        sourceKey: SourceKey,
        plan: TextPayloadSourceV2.ChunkedTextPayload,
    ): String {
        val source = requireNotNull(gateway.source(sourceKey)) {
            "Extension source disappeared while opening text"
        }
        val stream = source.openTextStream(plan)
        val text = StringBuilder()
        var cursor = plan.firstCursor
        try {
            while (true) {
                val chunk = stream.next(cursor)
                text.append(chunk.utf8Text)
                if (chunk.done) return text.toString()
                cursor = requireNotNull(chunk.nextCursor)
            }
        } finally {
            stream.cancel()
        }
    }

    private suspend fun acquireText(
        selection: ExtensionUnitSelectionV2,
        payload: UnitContentPayload,
        text: String,
    ): PreparedExtensionRepresentation {
        val target = target(selection, payload.representationId)
        val grant = conservativeGrant(selection)
        val request = PendingContentBodyStoreRequest(
            grant = grant,
            scope = grant.scope,
            byteCount = text.encodeToByteArray().size.toLong(),
        )
        val acquired = offlineStoreAuthorizer.execute(request) {
            TextAcquisitionService(
                blobStore = StableExtensionBlobStore(foundation.blobStore, target.stableImportId),
                identityDeriver = identityDeriver,
                authorizeOfflineStore = { byteCount ->
                    require(byteCount == request.byteCount) { "Canonical extension text changed byte size" }
                },
            ).acquire(TextAcquisitionRequest(target, text.encodeToByteArray()))
        }
        return acquired.toPreparedRepresentation()
    }

    private suspend fun acquireImages(
        selection: ExtensionUnitSelectionV2,
        payload: UnitContentPayload.ImageSequence,
    ): PreparedExtensionRepresentation {
        // Resolve every transform before network I/O. Known transforms remain typed manifest
        // metadata and are applied lazily by the reader; unknown transforms fail closed.
        val transforms = payload.pages.map { it.transform?.toContentTransform() }
        val pages = payload.pages.map { page ->
            val declaredMediaType = page.mediaType.lowercase()
            require(
                declaredMediaType == WILDCARD_IMAGE_MEDIA_TYPE ||
                    declaredMediaType in SUPPORTED_LOCAL_IMAGE_MEDIA_TYPES,
            ) {
                "Unsupported extension image media type: ${page.mediaType}"
            }
            val fetched = fetch(
                selection.sourceKey,
                RemoteBlobPlanV2(page.toResource()),
                verifyMediaType = false,
            )
            val storedMediaType = resolveFetchedImageMediaType(declaredMediaType, fetched.mediaType)
            LocalImagePageSource(page.resourceId, storedMediaType, fetched.copyBytes())
        }
        val target = target(selection, payload.representationId)
        val grant = conservativeGrant(selection)
        val totalBytes = pages.sumOf { it.byteSize.toLong() }
        val request = PendingContentBodyStoreRequest(grant, grant.scope, totalBytes)
        val acquired = offlineStoreAuthorizer.execute(request) {
            ImageSequenceAcquisitionService(
                blobStore = StableExtensionBlobStore(foundation.blobStore, target.stableImportId),
                identityDeriver = identityDeriver,
                authorizeOfflineStore = { byteCount ->
                    require(byteCount == totalBytes) { "Extension image aggregate changed byte size" }
                },
            ).acquire(
                ImageSequenceAcquisitionRequest(
                    target = target,
                    pages = pages,
                    packageKind = LocalPackageKind.IMAGES,
                    progression = payload.progression,
                    layout = payload.layout,
                ),
            )
        }
        val stored = acquired.representation as ContentRepresentation.ImageSequence
        val typedPages = stored.pages.mapIndexed { index, page ->
            val remote = payload.pages[index]
            page.copy(
                transform = transforms[index],
                spread = remote.spread,
                layout = remote.layout,
            )
        }
        return PreparedExtensionRepresentation(
            representation = stored.copy(
                pages = typedPages,
                progression = payload.progression,
                layout = payload.layout,
            ),
            receipts = acquired.publishedBlobs,
        )
    }

    private suspend fun acquireEpub(
        selection: ExtensionUnitSelectionV2,
        payload: UnitContentPayload.EpubSpine,
    ): PreparedExtensionRepresentation {
        val protectedAlgorithms = payload.packageGraph.encryptionDescriptors
            .mapTo(linkedSetOf()) { it.algorithm }
        if (protectedAlgorithms.isNotEmpty()) {
            throw ExtensionContentConsumerException.UnsupportedEpubEncryption(protectedAlgorithms)
        }
        require(payload.packageGraph.resources.map(RemoteEpubResourceV2::href).distinct().size ==
            payload.packageGraph.resources.size
        ) { "Extension EPUB resource hrefs must be unique" }

        val archive = fetch(selection.sourceKey, payload.packageGraph.archive)
        require(archive.byteSize.toLong() <= MAX_EXTENSION_EPUB_TOTAL_BYTES) {
            "Extension EPUB exceeds the host aggregate size limit"
        }
        val target = target(selection, payload.representationId)
        val grant = conservativeGrant(selection)
        val acquisitionService = EpubAcquisitionService(
            blobStore = StableExtensionBlobStore(foundation.blobStore, target.stableImportId),
            archiveExtractor = epubArchiveExtractor,
            identityDeriver = identityDeriver,
            authorizeOfflineStore = { byteCount ->
                require(byteCount <= MAX_EXTENSION_EPUB_TOTAL_BYTES) {
                    "Extension EPUB exceeds the host aggregate size limit"
                }
                offlineStoreAuthorizer.requireAllowed(
                    PendingContentBodyStoreRequest(grant, grant.scope, byteCount),
                )
            },
        )
        val prepared = acquisitionService.prepare(
            EpubAcquisitionRequest(target, archive.asEpubArchiveSource()),
        )
        val actual = prepared.previewRepresentation
        val metadata = prepared.metadata
        val actualResources = actual.packageGraph.resources.associateBy(EpubResource::href)
        val remoteResources = payload.packageGraph.resources.associateBy(RemoteEpubResourceV2::href)
        require(remoteResources.size == payload.packageGraph.resources.size &&
            actualResources.size == actual.packageGraph.resources.size &&
            actualResources.keys == remoteResources.keys
        ) {
            "Extension EPUB graph does not exactly match its fetched archive"
        }
        val remoteIdByHref = remoteResources.mapValues { (_, resource) -> resource.id }
        fun remoteResourceId(href: String): String = requireNotNull(remoteIdByHref[href]) {
            "Extension EPUB resource href is missing from the declared remote graph"
        }
        val remotePackageDocument = payload.packageGraph.resources.single {
            it.id == payload.packageGraph.packageDocumentId
        }
        require(remotePackageDocument.href == metadata.packageDocumentHref) {
            "Extension EPUB package document does not match its fetched archive"
        }
        val typedResources = payload.packageGraph.resources.map { remote ->
            val extracted = requireNotNull(actualResources[remote.href])
            require(extracted.mediaType == remote.mediaType) {
                "Extension EPUB resource media type does not match its fetched archive"
            }
            remote.body.expectedByteSize?.let { expected ->
                require(extracted.resource.blob.byteSize == expected) {
                    "Extension EPUB resource byte size does not match its fetched archive"
                }
            }
            remote.body.expectedPlaintextDigest?.let { expected ->
                require(extracted.resource.blob.plaintextDigest == expected) {
                    "Extension EPUB resource digest does not match its fetched archive"
                }
            }
            require(extracted.resource.blob.byteSize <= remote.body.resource.request.maxResponseBytes) {
                "Extension EPUB resource exceeds its declared response bound"
            }
            EpubResource(
                id = remote.id,
                href = remote.href,
                resource = ResourceRef(remote.id, extracted.resource.blob),
                mediaType = remote.mediaType,
                properties = extracted.properties,
            )
        }
        require(actual.documents.map(EpubSpineDocument::href) ==
            payload.documents.map(RemoteEpubSpineDocumentV2::href)
        ) {
            "Extension EPUB spine does not exactly match its fetched archive"
        }
        val representation = actual.copy(
            packageGraph = actual.packageGraph.copy(
                packageDocumentId = payload.packageGraph.packageDocumentId,
                resources = typedResources,
                manifest = actual.packageGraph.manifest.map { declaration ->
                    declaration.copy(resourceId = remoteResourceId(declaration.resolvedHref))
                },
                navigation = actual.packageGraph.navigation.map { document ->
                    document.copy(
                        documentResourceId = remoteResourceId(document.documentHref),
                        points = document.points.map { point ->
                            point.copy(
                                resourceId = point.resolvedHref?.let(::remoteResourceId),
                            )
                        },
                    )
                },
            ),
            documents = payload.documents.zip(actual.documents).map { (remote, extracted) ->
                require(extracted.linear == remote.linear) {
                    "Extension EPUB spine linearity does not match its fetched archive"
                }
                EpubSpineDocument(
                    id = remote.id,
                    href = remote.href,
                    resourceId = remote.resourceId,
                    linear = remote.linear,
                    pageProgression = extracted.pageProgression,
                    manifestIdRef = extracted.manifestIdRef,
                    properties = extracted.properties,
                    rendition = extracted.rendition,
                )
            },
        )
        val acquired = acquisitionService.publish(prepared, representation)
        return PreparedExtensionRepresentation(representation, acquired.publishedBlobs)
    }

    private suspend fun fetch(
        sourceKey: SourceKey,
        plan: RemoteBlobPlanV2,
        verifyMediaType: Boolean = true,
    ): ExtensionFetchedResourceV2 {
        val fetcher = resourceFetcher ?: throw ExtensionContentConsumerException.MissingBodyFetcher()
        val fetched = fetcher.fetch(sourceKey, plan.resource.request)
        require(fetched.byteSize.toLong() <= plan.resource.request.maxResponseBytes) {
            "Extension resource exceeded its declared response bound"
        }
        plan.expectedByteSize?.let { expected ->
            require(fetched.byteSize.toLong() == expected) { "Extension resource byte size changed" }
        }
        plan.expectedPlaintextDigest?.let { expected ->
            require(Sha256.hex(fetched.copyBytes()) == expected) { "Extension resource digest changed" }
        }
        fetched.mediaType?.takeIf { verifyMediaType }?.let { actual ->
            require(actual.equals(plan.resource.mediaType, ignoreCase = true)) {
                "Extension resource media type changed"
            }
        }
        return fetched
    }

    private fun resolveFetchedImageMediaType(declaredMediaType: String, fetchedMediaType: String?): String {
        if (declaredMediaType != WILDCARD_IMAGE_MEDIA_TYPE) {
            fetchedMediaType?.let { actual ->
                require(actual.equals(declaredMediaType, ignoreCase = true)) {
                    "Extension resource media type changed"
                }
            }
            return declaredMediaType
        }

        val actualMediaType = requireNotNull(fetchedMediaType)
            .substringBefore(';')
            .trim()
            .lowercase()
        require(actualMediaType in SUPPORTED_LOCAL_IMAGE_MEDIA_TYPES) {
            "Extension wildcard image response has unsupported media type: $actualMediaType"
        }
        return actualMediaType
    }

    private fun ImagePageV2.toResource(): RemoteResourceV2 = RemoteResourceV2(
        id = resourceId,
        request = request,
        mediaType = mediaType,
    )

    private fun target(
        selection: ExtensionUnitSelectionV2,
        representationId: String,
    ): LocalAcquisitionTarget {
        val stableIdentity = framedIdentity(
            selection.sourceKey.canonicalId,
            selection.remotePublicationId,
            selection.unit.remoteId,
            representationId,
        )
        return LocalAcquisitionTarget(
            publicationKey = publicationKey(selection),
            publicationTitle = selection.publicationTitle,
            stableImportId = stableIdentity,
            unitTitle = selection.unit.title,
            contentRevision = 0,
            acquiredAtEpochMillis = nowEpochMillis(),
        )
    }

    private fun conservativeGrant(selection: ExtensionUnitSelectionV2): RightsGrant {
        val publicationKey = publicationKey(selection)
        val acquisitionId = acquisitionId(selection)
        val reference = RightsGrantRef(extensionId("rights-grant", selection))
        foundation.rightsGrants.find(reference)?.let { durable ->
            require(durable.scope == RightsScope(publicationKey, acquisitionId) &&
                durable.provenance == RightsProvenance.HostPolicy(EXTENSION_HOST_POLICY_ID) &&
                durable.protectionScheme == ProtectionScheme.None &&
                durable.validUntilEpochMillis == null &&
                durable.allowedOperations == CONSERVATIVE_REMOTE_OPERATIONS
            ) { "Durable extension rights policy conflicts with the source publication identity" }
            return durable
        }
        return RightsGrant(
            schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
            grantId = reference,
            scope = RightsScope(publicationKey, acquisitionId),
            provenance = RightsProvenance.HostPolicy(EXTENSION_HOST_POLICY_ID),
            protectionScheme = ProtectionScheme.None,
            validFromEpochMillis = nowEpochMillis(),
            validUntilEpochMillis = null,
            allowedOperations = CONSERVATIVE_REMOTE_OPERATIONS,
        )
    }

    private fun mergeGraph(
        selection: ExtensionUnitSelectionV2,
        remoteRepresentationId: String,
        prepared: PreparedExtensionRepresentation,
        grant: RightsGrant,
    ): ExtensionGraphMerge {
        val publicationKey = publicationKey(selection)
        val acquisitionId = acquisitionId(selection)
        val unitKey = extensionUnitKey(selection.sourceKey, selection.remotePublicationId, selection.unit.remoteId)
        val representation = prepared.representation.withRepresentationId(
            extensionId("representation", selection, selection.unit.remoteId, remoteRepresentationId),
        )
        val publicationBinding = SourceBinding(
            sourceKey = selection.sourceKey,
            remoteId = selection.remotePublicationId,
            canonicalUrl = null,
            entityKind = RemoteEntityKind.PUBLICATION,
        )
        val unitBinding = SourceBinding(
            sourceKey = selection.sourceKey,
            remoteId = selection.unit.remoteId,
            canonicalUrl = selection.unit.url,
            entityKind = RemoteEntityKind.UNIT,
            parentPublication = publicationBinding.remoteEntityKey,
        )
        val expectedOrigin = AcquisitionOrigin.ExtensionSource(publicationBinding)
        val durablePublication = foundation.publications.find(publicationKey)
        val durableAcquisition = durablePublication?.acquisitions?.singleOrNull { it.id == acquisitionId }
        durablePublication?.acquisitions
            ?.filterNot { it.id == acquisitionId }
            ?.flatMap(Acquisition::units)
            ?.singleOrNull { it.key == unitKey }
            ?.let { error("Extension unit identity is already owned by another acquisition") }

        if (durableAcquisition != null) {
            require(durableAcquisition.origin == expectedOrigin) {
                "Extension acquisition identity is bound to a different source publication"
            }
            require(durableAcquisition.rightsGrantRef == null || durableAcquisition.rightsGrantRef == grant.grantId) {
                "Extension acquisition identity is bound to a different rights grant"
            }
        }

        val durableUnit = durableAcquisition?.units?.singleOrNull { it.key == unitKey }
        if (durableUnit != null) {
            require(durableUnit.sourceBinding == unitBinding) {
                "Extension unit identity is bound to a different remote unit"
            }
        }
        val latest = durableUnit?.latestManifest
        val durableRepresentation = latest?.representations
            ?.singleOrNull { it.representationId == representation.representationId }
        val graphChanged = durableUnit == null || latest == null || durableRepresentation != representation
        val manifest = if (graphChanged) {
            val revision = latest?.let { current ->
                require(current.contentRevision < Long.MAX_VALUE) { "Extension unit revision overflow" }
                current.contentRevision + 1
            } ?: 0L
            val representations = latest?.representations.orEmpty().toMutableList().apply {
                val index = indexOfFirst { it.representationId == representation.representationId }
                if (index >= 0) set(index, representation) else add(representation)
            }
            buildManifest(
                unitKey = unitKey,
                contentRevision = revision,
                representations = representations,
                resources = latest?.resources.orEmpty(),
            )
        } else {
            requireNotNull(latest)
        }

        val replacementUnit = if (graphChanged) {
            if (durableUnit == null) {
                PublicationUnit(
                    key = unitKey,
                    title = selection.unit.title,
                    manifestRevisions = listOf(manifest),
                    sourceBinding = unitBinding,
                )
            } else {
                durableUnit.copy(manifestRevisions = durableUnit.manifestRevisions + manifest)
            }
        } else {
            requireNotNull(durableUnit)
        }
        val mutableAcquisitionChanged = durableAcquisition != null &&
            (durableAcquisition.availability != AcquisitionAvailability.AVAILABLE ||
                durableAcquisition.rightsGrantRef != grant.grantId)
        val replacementAcquisition = when {
            durableAcquisition == null -> Acquisition(
                id = acquisitionId,
                origin = expectedOrigin,
                units = listOf(replacementUnit),
                contentRevision = 0,
                availability = AcquisitionAvailability.AVAILABLE,
                rightsGrantRef = grant.grantId,
                acquiredAtEpochMillis = nowEpochMillis(),
            )
            graphChanged -> {
                require(durableAcquisition.contentRevision < Long.MAX_VALUE) {
                    "Extension acquisition revision overflow"
                }
                durableAcquisition.copy(
                    units = durableAcquisition.units.replaceOrAppend(replacementUnit) { it.key },
                    contentRevision = durableAcquisition.contentRevision + 1,
                    availability = AcquisitionAvailability.AVAILABLE,
                    rightsGrantRef = grant.grantId,
                )
            }
            mutableAcquisitionChanged -> durableAcquisition.copy(
                availability = AcquisitionAvailability.AVAILABLE,
                rightsGrantRef = grant.grantId,
            )
            else -> durableAcquisition
        }
        val publicationChanged = durablePublication == null || graphChanged || mutableAcquisitionChanged
        val publication = when {
            durablePublication == null -> Publication(
                key = publicationKey,
                title = selection.publicationTitle,
                acquisitions = listOf(replacementAcquisition),
            )
            publicationChanged -> durablePublication.copy(
                acquisitions = durablePublication.acquisitions.replaceOrAppend(replacementAcquisition) { it.id },
            )
            else -> durablePublication
        }
        return ExtensionGraphMerge(
            publication = publication,
            acquisition = replacementAcquisition,
            unit = replacementUnit,
            manifest = manifest,
            graphChanged = graphChanged,
            publicationChanged = publicationChanged,
        )
    }

    private suspend fun commit(
        selection: ExtensionUnitSelectionV2,
        representationId: String,
        prepared: PreparedExtensionRepresentation,
    ): ExtensionContentMaterializationV2 {
        repeat(MAX_GRAPH_COMMIT_ATTEMPTS) { attempt ->
            // A concurrent first materialization may have installed the same acquisition-scoped
            // grant since body admission. Reload it with the durable graph on every retry.
            val grant = conservativeGrant(selection)
            val graph = mergeGraph(selection, representationId, prepared, grant)
            val operationNamespace = if (graph.graphChanged) {
                "extension-v2:${graph.manifest.manifestId}"
            } else {
                "extension-v2-receipt:${Sha256.hex(
                    framedIdentity(
                        graph.publication.key.value,
                        graph.manifest.manifestId,
                        *prepared.receipts.map(BlobPublishReceipt::commitToken).toTypedArray(),
                    ).encodeToByteArray(),
                )}"
            }
            try {
                val commit = foundation.transactions.commit(
                    ContentCommitBatch<LegacyOutboxDraft>(
                        commitId = operationNamespace,
                        receipts = prepared.receipts,
                        attachments = listOf(
                            ManifestAttachment(
                                ContentManifestOwner(
                                    graph.publication.key,
                                    graph.acquisition.id,
                                    graph.unit.key,
                                ),
                                graph.manifest,
                            ),
                        ),
                        metadata = if (graph.graphChanged) listOf(
                            ContentMetadataMutation(
                                "extension-v2.${graph.manifest.manifestId}.source",
                                selection.sourceKey.canonicalId,
                            ),
                            ContentMetadataMutation(
                                "extension-v2.${graph.manifest.manifestId}.remote-publication",
                                selection.remotePublicationId,
                            ),
                            ContentMetadataMutation(
                                "extension-v2.${graph.manifest.manifestId}.remote-unit",
                                selection.unit.remoteId,
                            ),
                            ContentMetadataMutation(
                                "extension-v2.${graph.manifest.manifestId}.representation",
                                representationId,
                            ),
                        ) else emptyList(),
                        publications = if (graph.publicationChanged) {
                            listOf(ContentPublicationMutation(graph.publication))
                        } else {
                            emptyList()
                        },
                        rightsGrants = listOf(ContentRightsGrantMutation(grant)),
                    ),
                )
                return ExtensionContentMaterializationV2(
                    sourceKey = selection.sourceKey,
                    remotePublicationId = selection.remotePublicationId,
                    remoteUnitId = selection.unit.remoteId,
                    representationId = representationId,
                    publicationKey = graph.publication.key,
                    acquisitionId = graph.acquisition.id,
                    unitKey = graph.unit.key,
                    manifestId = graph.manifest.manifestId,
                    contentRepresentationId = extensionId(
                        "representation",
                        selection,
                        selection.unit.remoteId,
                        representationId,
                    ),
                    commit = commit,
                )
            } catch (conflict: ContentTransactionException.CommitConflict) {
                val publicationConflict = "publication:${graph.publication.key.value}"
                if (conflict.conflictingId != publicationConflict || attempt == MAX_GRAPH_COMMIT_ATTEMPTS - 1) {
                    throw conflict
                }
                yield()
            } catch (busy: IllegalStateException) {
                if (!busy.message.orEmpty().startsWith("Concurrent content transaction must retry") ||
                    attempt == MAX_GRAPH_COMMIT_ATTEMPTS - 1
                ) {
                    throw busy
                }
                yield()
            }
        }
        error("Extension graph commit retry budget was exhausted")
    }

    private fun publicationKey(selection: ExtensionUnitSelectionV2): PublicationKey =
        extensionPublicationKey(selection.sourceKey, selection.remotePublicationId)

    private fun acquisitionId(selection: ExtensionUnitSelectionV2): String =
        extensionAcquisitionId(selection.sourceKey, selection.remotePublicationId)

    private fun extensionId(
        role: String,
        selection: ExtensionUnitSelectionV2,
        vararg additional: String,
    ): String = Rfc9562UuidV5.derive(
        EXTENSION_CONTENT_NAMESPACE,
        framedIdentity(
            role,
            selection.sourceKey.canonicalId,
            selection.remotePublicationId,
            *additional,
        ),
    )

    private fun buildManifest(
        unitKey: UnitKey,
        contentRevision: Long,
        representations: List<ContentRepresentation>,
        resources: List<ResourceRef>,
    ): ContentManifest {
        val declaredSize = (resources.map(ResourceRef::blob) + representations.flatMap { representation ->
            when (representation) {
                is ContentRepresentation.PlainText -> listOf(representation.resource.blob)
                is ContentRepresentation.ImageSequence -> representation.pages.map(ImagePage::resource).map(ResourceRef::blob)
                is ContentRepresentation.EpubSpine -> listOf(representation.packageGraph.archive) +
                    representation.packageGraph.resources.map { it.resource.blob }
            }
        }).distinctBy { it.blobId }.fold(0L) { total, blob ->
            require(total <= Long.MAX_VALUE - blob.byteSize) { "Extension manifest size overflow" }
            total + blob.byteSize
        }
        val fingerprintTemplate = ContentManifest(
            manifestId = FINGERPRINT_TEMPLATE_MANIFEST_ID,
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = contentRevision,
            representations = representations,
            resources = resources,
            declaredSizeBytes = declaredSize,
        )
        val fingerprint = Sha256.hex(
            EXTENSION_CONTENT_JSON.encodeToString(ContentManifest.serializer(), fingerprintTemplate)
                .encodeToByteArray(),
        )
        return fingerprintTemplate.copy(
            manifestId = Rfc9562UuidV5.derive(
                EXTENSION_CONTENT_NAMESPACE,
                framedIdentity(
                    "manifest",
                    unitKey.publicationKey.value,
                    unitKey.value,
                    contentRevision.toString(),
                    fingerprint,
                ),
            ),
        )
    }
}

private data class PreparedExtensionRepresentation(
    val representation: ContentRepresentation,
    val receipts: List<BlobPublishReceipt>,
)

private data class ExtensionGraphMerge(
    val publication: Publication,
    val acquisition: Acquisition,
    val unit: PublicationUnit,
    val manifest: ContentManifest,
    val graphChanged: Boolean,
    val publicationChanged: Boolean,
)

private fun ContentAcquisitionResult<*>.toPreparedRepresentation(): PreparedExtensionRepresentation =
    PreparedExtensionRepresentation(representation, publishedBlobs)

private fun ContentRepresentation.withRepresentationId(id: String): ContentRepresentation = when (this) {
    is ContentRepresentation.PlainText -> copy(representationId = id)
    is ContentRepresentation.ImageSequence -> copy(representationId = id)
    is ContentRepresentation.EpubSpine -> copy(representationId = id)
}

private fun ImageTransformPlanV2.toContentTransform(): ImageTransform = when (transformId) {
    "identity" -> ImageTransform(
        schemaVersion = ImageTransform.CURRENT_SCHEMA_VERSION,
        transformId = "identity",
        parameters = parameters,
    )
    "reverse-vertical-segments" -> ImageTransform(
        schemaVersion = ImageTransform.CURRENT_SCHEMA_VERSION,
        transformId = "reverse_vertical_segments",
        parameters = parameters,
    )
    else -> throw ExtensionContentConsumerException.UnsupportedImageTransform(transformId)
}

private inline fun <T, K> List<T>.replaceOrAppend(value: T, key: (T) -> K): List<T> {
    val replacementKey = key(value)
    val index = indexOfFirst { key(it) == replacementKey }
    if (index < 0) return this + value
    return toMutableList().apply { set(index, value) }
}

private val EXTENSION_CONTENT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}

private const val FINGERPRINT_TEMPLATE_MANIFEST_ID: String = "00000000-0000-5000-8000-000000000001"

private const val EXTENSION_HOST_POLICY_ID: String = "extension-v2-host-policy"

private const val WILDCARD_IMAGE_MEDIA_TYPE: String = "image/*"

private const val MAX_GRAPH_COMMIT_ATTEMPTS: Int = 4

private const val MAX_EXTENSION_EPUB_TOTAL_BYTES: Long = 512L * 1024L * 1024L

private val CONSERVATIVE_REMOTE_OPERATIONS: Set<ContentOperation> = setOf(
    ContentOperation.DISPLAY,
    ContentOperation.OFFLINE_STORE,
    ContentOperation.TTS,
    ContentOperation.SEARCH_INDEX,
    ContentOperation.ANNOTATE,
)
