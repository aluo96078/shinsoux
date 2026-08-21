package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.acquisition.ContentAcquisitionResult
import dev.shinsou.kmp.acquisition.BoundedEpubArchiveExtractor
import dev.shinsou.kmp.acquisition.ByteArrayEpubArchiveSource
import dev.shinsou.kmp.acquisition.EpubAcquisitionRequest
import dev.shinsou.kmp.acquisition.EpubAcquisitionService
import dev.shinsou.kmp.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.acquisition.ImageSequenceAcquisitionRequest
import dev.shinsou.kmp.acquisition.ImageSequenceAcquisitionService
import dev.shinsou.kmp.acquisition.LocalAcquisitionIdentityDeriver
import dev.shinsou.kmp.acquisition.LocalAcquisitionTarget
import dev.shinsou.kmp.acquisition.LocalContentFormat
import dev.shinsou.kmp.acquisition.LocalImagePageSource
import dev.shinsou.kmp.acquisition.SUPPORTED_LOCAL_IMAGE_MEDIA_TYPES
import dev.shinsou.kmp.acquisition.TextAcquisitionRequest
import dev.shinsou.kmp.acquisition.TextAcquisitionService
import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStage
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobStoreException
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitResult
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ContentTransactionException
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ImagePage
import dev.shinsou.kmp.content.ImageTransform
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.PendingBlob
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.content.access.PendingContentBodyStoreRequest
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.LocalPackageKind
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.RemoteEntityKind
import dev.shinsou.kmp.domain.model.Rfc9562UuidV5
import dev.shinsou.kmp.domain.model.SourceBinding
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.PluginHttpRequest
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.UnifiedReaderContent
import dev.shinsou.kmp.reader.UnifiedReaderNavigationFactory
import dev.shinsou.kmp.sync.v2.ContentPublicationSyncDraftFactory
import dev.shinsou.kmp.sync.v2.SyncDraft
import kotlinx.coroutines.yield
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

/**
 * Host-owned page of exact v2 publication metadata.
 *
 * [units] are capabilities issued by the consumer that loaded this page. They cannot be forged by
 * UI code and therefore keep the source/package/publication identity intact through the later body
 * request.
 */
public class ExtensionPublicationPageV2 internal constructor(
    public val sourceKey: SourceKey,
    public val publication: RemotePublicationV2,
    units: List<ExtensionUnitSelectionV2>,
    public val page: Int,
    public val hasNextPage: Boolean,
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

/** Executes an already validated v2 request plan through a host-controlled network scope. */
public fun interface ExtensionResourceFetcherV2 {
    public suspend fun fetch(sourceKey: SourceKey, request: RemoteRequestPlanV2): ExtensionFetchedResourceV2
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

/**
 * Production fetcher for legacy-backed v2 sources. Native packages must provide an explicit scope
 * resolver; an opaque SourceKey is never hashed or narrowed into a Long.
 */
public class PluginNetworkExtensionResourceFetcherV2(
    private val network: PluginNetworkClient,
    private val scopes: ExtensionNetworkScopeResolverV2 = ExtensionNetworkScopeResolverV2.LegacyOnly,
) : ExtensionResourceFetcherV2 {
    override suspend fun fetch(
        sourceKey: SourceKey,
        request: RemoteRequestPlanV2,
    ): ExtensionFetchedResourceV2 {
        request.validate()
        require(request.body == null) { "Host request-body references need a dedicated resolver" }
        require(request.method == HttpMethodV2.GET || request.method == HttpMethodV2.HEAD) {
            "Only body-free GET/HEAD extension resources can be fetched here"
        }
        val sourceScope = requireNotNull(scopes.resolve(sourceKey)) {
            "No host network scope is registered for ${sourceKey.canonicalId}"
        }
        val response = network.execute(
            sourceId = sourceScope,
            request = PluginHttpRequest(
                method = request.method.name,
                url = request.effectiveUri,
                headers = request.headerHints,
            ),
        )
        check(response.status in 200..299) { "HTTP ${response.status} while fetching extension content" }
        require(response.body.size.toLong() <= request.maxResponseBytes) {
            "Extension resource exceeded its declared response bound"
        }
        return ExtensionFetchedResourceV2(
            bytes = response.body,
            mediaType = response.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.substringBefore(';')
                ?.trim(),
        )
    }
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
    private val identityDeriver: LocalAcquisitionIdentityDeriver = LocalAcquisitionIdentityDeriver(),
    private val epubArchiveExtractor: EpubArchiveExtractor = BoundedEpubArchiveExtractor(),
) {
    private val ownerToken: Any = Any()

    public suspend fun publicationPage(
        sourceKey: SourceKey,
        remotePublicationId: String,
        page: Int = 0,
    ): ExtensionPublicationPageV2 {
        val publication = gateway.details(sourceKey, remotePublicationId)
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
            require(page.mediaType in SUPPORTED_LOCAL_IMAGE_MEDIA_TYPES) {
                "Unsupported extension image media type: ${page.mediaType}"
            }
            val fetched = fetch(selection.sourceKey, RemoteBlobPlanV2(page.toResource()))
            LocalImagePageSource(page.resourceId, page.mediaType, fetched.copyBytes())
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

    private suspend fun fetch(sourceKey: SourceKey, plan: RemoteBlobPlanV2): ExtensionFetchedResourceV2 {
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
        fetched.mediaType?.let { actual ->
            require(actual.equals(plan.resource.mediaType, ignoreCase = true)) {
                "Extension resource media type changed"
            }
        }
        return fetched
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
        val unitKey = UnitKey(publicationKey, extensionId("unit", selection, selection.unit.remoteId))
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
            val outbox = if (graph.publicationChanged) {
                ContentPublicationSyncDraftFactory.build(
                    publication = graph.publication,
                    rightsGrants = listOf(grant),
                    operationNamespace = operationNamespace,
                    createdAtMillis = nowEpochMillis(),
                ).drafts
            } else {
                emptyList()
            }
            try {
                val commit = foundation.transactions.commit(
                    ContentCommitBatch<SyncDraft>(
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
                        outbox = outbox,
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

    private fun publicationKey(selection: ExtensionUnitSelectionV2): PublicationKey = PublicationKey(
        extensionId("publication", selection),
    )

    private fun acquisitionId(selection: ExtensionUnitSelectionV2): String =
        extensionId("acquisition", selection)

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

/**
 * Gives one extension representation stable blob identities without changing the shared blob
 * store's allocation policy. Acquisition services publish resources in a deterministic order, so
 * the scoped ordinal keeps equal bytes at different logical paths distinct while making an exact
 * replay claim the already committed reference. That lets the transaction consume a fresh dedupe
 * receipt without manufacturing a new immutable manifest revision.
 */
private class StableExtensionBlobStore(
    private val delegate: ContentBlobStore,
    private val scope: String,
) : ContentBlobStore by delegate {
    private var nextOrdinal: Long = 0

    // Kotlin interface delegation forwards default methods to the delegate. Override both helpers
    // so their stage lifecycle is dispatched through this scoped store.
    override fun put(bytes: ByteArray, mediaType: String): BlobPublishReceipt {
        val stage = beginStage(bytes.size.toLong(), mediaType)
        return try {
            stage.append(bytes)
            publish(stage.seal())
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
    }

    override fun put(reference: BlobRef, bytes: ByteArray): BlobPublishReceipt {
        val stage = beginStage(reference.byteSize, reference.mediaType)
        return try {
            stage.append(bytes)
            publish(stage.seal(reference))
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
    }

    override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage {
        require(nextOrdinal < Long.MAX_VALUE) { "Extension representation has too many blob resources" }
        val ordinal = nextOrdinal++
        return StableExtensionBlobStage(
            delegate = delegate.beginStage(expectedSizeBytes, mediaType),
            mediaType = mediaType,
            stableBlobId = { digest ->
                Rfc9562UuidV5.derive(
                    EXTENSION_CONTENT_NAMESPACE,
                    framedIdentity("blob", scope, ordinal.toString(), mediaType, digest),
                )
            },
        )
    }

    override fun publish(candidate: PendingBlob): BlobPublishReceipt {
        val stable = candidate as? StableExtensionPendingBlob
            ?: throw ContentBlobStoreException.InvalidStage("Blob candidate belongs to another scoped store")
        return try {
            delegate.publish(stable.delegate)
        } catch (error: ContentBlobStoreException.InvalidStage) {
            // A concurrent exact materialization may already own the one live receipt. Reuse that
            // authenticated object; otherwise preserve the original fail-closed store error.
            delegate.claimExistingVerified(stable.reference)?.also { stable.stage.abort() } ?: throw error
        }
    }
}

internal class StableExtensionBlobStage(
    private val delegate: ContentBlobStage,
    private val mediaType: String,
    private val stableBlobId: (digest: String) -> String,
) : ContentBlobStage by delegate {
    private val digestSink = HashingSink.sha256(blackholeSink())
    private val digestBuffer = Buffer()

    override fun append(chunk: ByteArray) {
        delegate.append(chunk)
        if (chunk.isNotEmpty()) {
            digestBuffer.write(chunk)
            digestSink.write(digestBuffer, chunk.size.toLong())
        }
    }

    override fun seal(expected: BlobRef?): PendingBlob {
        val digestHex = digestSink.hash.hex()
        digestSink.close()
        val reference = expected ?: run {
            BlobRef(
                blobId = stableBlobId(digestHex),
                schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                digestAlgorithm = BlobRef.SHA_256,
                plaintextDigest = digestHex,
                byteSize = bytesWritten,
                mediaType = mediaType,
            )
        }
        return StableExtensionPendingBlob(delegate.seal(reference), delegate)
    }

    override fun abort() {
        runCatching { digestSink.close() }
        delegate.abort()
    }
}

private class StableExtensionPendingBlob(
    val delegate: PendingBlob,
    val stage: ContentBlobStage,
) : PendingBlob {
    override val reference: BlobRef get() = delegate.reference
}

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

private fun framedIdentity(vararg fields: String): String = buildString {
    append("shinsou-extension-content-v2")
    fields.forEach { field ->
        append('|')
        append(field.encodeToByteArray().size)
        append(':')
        append(field)
    }
}

private val EXTENSION_CONTENT_NAMESPACE = MigrationNamespaceId("bcdcd2d9-9908-5e51-a976-f3b8c19af82a")
private val EXTENSION_CONTENT_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
private const val FINGERPRINT_TEMPLATE_MANIFEST_ID: String = "00000000-0000-5000-8000-000000000001"
private const val EXTENSION_HOST_POLICY_ID: String = "extension-v2-host-policy"
private const val MAX_GRAPH_COMMIT_ATTEMPTS: Int = 4
private const val MAX_EXTENSION_EPUB_TOTAL_BYTES: Long = 512L * 1024L * 1024L
private val CONSERVATIVE_REMOTE_OPERATIONS: Set<ContentOperation> = setOf(
    ContentOperation.DISPLAY,
    ContentOperation.OFFLINE_STORE,
    ContentOperation.TTS,
    ContentOperation.SEARCH_INDEX,
    ContentOperation.ANNOTATE,
)
