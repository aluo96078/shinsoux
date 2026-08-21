package dev.shinsou.kmp.acquisition

import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubEncryption
import dev.shinsou.kmp.content.EpubEncryptionDescriptor
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubPackageCssDependency
import dev.shinsou.kmp.content.EpubPackageCssReference
import dev.shinsou.kmp.content.EpubPackageDocumentMetadata
import dev.shinsou.kmp.content.EpubPackageManifestItem
import dev.shinsou.kmp.content.EpubPackageNavigationDocument
import dev.shinsou.kmp.content.EpubPackageNavigationKind
import dev.shinsou.kmp.content.EpubPackageNavigationPoint
import dev.shinsou.kmp.content.EpubRendition
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ImageProgression
import dev.shinsou.kmp.content.MAX_CONTENT_METADATA_JSON_CHARS
import dev.shinsou.kmp.content.MAX_EPUB_AUXILIARY_GRAPH_ENTRIES
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

public class EpubAcquisitionRequest(
    public val target: LocalAcquisitionTarget,
    public val archiveSource: EpubArchiveSource,
) {
    /** Source-compatible small/in-memory input. No defensive whole-archive copy is made. */
    public constructor(target: LocalAcquisitionTarget, archiveBytes: ByteArray) :
        this(target, ByteArrayEpubArchiveSource(archiveBytes))

    public val archiveByteSize: Long get() = archiveSource.byteSize

    /** Compatibility view. Large-source callers should keep using [archiveSource]. */
    public val archiveBytes: ByteArray
        get() {
            (archiveSource as? ByteArrayEpubArchiveSource)?.let { return it.unsafeArray() }
            require(archiveSource.byteSize <= Int.MAX_VALUE) { "EPUB archive cannot fit in a ByteArray" }
            val output = ByteArray(archiveSource.byteSize.toInt())
            var offset = 0
            while (offset < output.size) {
                val count = minOf(EPUB_ARCHIVE_WRITE_CHUNK_BYTES, output.size - offset)
                val chunk = archiveSource.read(offset.toLong(), count)
                require(chunk.size == count) { "EPUB source returned a truncated read" }
                chunk.copyInto(output, offset)
                offset += count
            }
            return output
        }
}

/** Lossless OPF declaration retained alongside the content manifest's resolved resource graph. */
public data class EpubManifestDeclaration(
    val manifestIdRef: String,
    val declaredHref: String,
    val resolvedHref: String,
    val resourceId: String,
    val mediaType: String,
    val properties: Set<String>,
    val fallbackIdRef: String? = null,
    val mediaOverlayIdRef: String? = null,
)

public data class EpubSpineDeclaration(
    val manifestIdRef: String,
    val resourceId: String,
    val resolvedHref: String,
    val linear: Boolean,
    val properties: Set<String>,
)

public data class EpubCssReference(
    val declaredReference: String,
    val resolvedHref: String?,
    val external: Boolean,
)

public data class EpubCssDependencyGraph(
    val stylesheetHref: String,
    val references: List<EpubCssReference>,
)

public enum class EpubNavigationKind {
    EPUB3_NAV,
    NCX,
}

public data class EpubNavigationPoint(
    val label: String,
    val declaredHref: String,
    val resolvedHref: String?,
    val resourceId: String?,
    val fragment: String?,
)

public data class EpubNavigationMap(
    val kind: EpubNavigationKind,
    val documentResourceId: String,
    val documentHref: String,
    val points: List<EpubNavigationPoint>,
)

public data class EpubPackageMetadata(
    val packageDocumentHref: String,
    val uniqueIdentifier: String?,
    val title: String?,
    val language: String?,
    val manifest: List<EpubManifestDeclaration>,
    val spine: List<EpubSpineDeclaration>,
    val cssDependencies: List<EpubCssDependencyGraph>,
    val navigation: List<EpubNavigationMap>,
    val packageVersion: String? = null,
    val uniqueIdentifierIdRef: String? = null,
    val spineTocManifestIdRef: String? = null,
    val pageProgressionDirection: String? = null,
) {
    init {
        require(manifest.isNotEmpty()) { "EPUB metadata requires manifest declarations" }
        require(spine.isNotEmpty()) { "EPUB metadata requires spine declarations" }
        require(manifest.map(EpubManifestDeclaration::manifestIdRef).distinct().size == manifest.size) {
            "EPUB manifest declaration ids must be unique"
        }
    }
}

/**
 * Fully parsed, digest-verified and persistence-preflighted EPUB plan.
 *
 * Creating this value never opens a blob stage. The extension boundary can therefore compare and
 * remap its untrusted remote graph against [previewRepresentation] before granting offline storage
 * or making any body visible.
 */
internal class PreparedEpubAcquisition internal constructor(
    internal val serviceToken: Any,
    internal val target: LocalAcquisitionTarget,
    internal val archiveSource: EpubArchiveSource,
    internal val entries: List<EpubArchiveEntry>,
    internal val archiveReference: BlobRef,
    internal val referencesByPath: Map<String, BlobRef>,
    internal val aggregateDigest: String,
    internal val previewPublicationDraft: Publication,
    val previewRepresentation: ContentRepresentation.EpubSpine,
    val metadata: EpubPackageMetadata,
    val offlineByteCount: Long,
)

/** Parses a complete OCF/OPF graph and publishes the archive plus every exact expanded resource. */
public class EpubAcquisitionService(
    private val blobStore: ContentBlobStore,
    private val archiveExtractor: EpubArchiveExtractor,
    private val identityDeriver: LocalAcquisitionIdentityDeriver = LocalAcquisitionIdentityDeriver(),
    private val policy: EpubAcquisitionPolicy = EpubAcquisitionPolicy(),
    private val authorizeOfflineStore: (byteCount: Long) -> Unit = {},
) {
    private val preparationToken: Any = Any()

    public fun acquire(request: EpubAcquisitionRequest): ContentAcquisitionResult<EpubPackageMetadata> =
        publish(prepare(request))

    /** Parses, hashes and preflights the complete graph without beginning or publishing a stage. */
    internal fun prepare(request: EpubAcquisitionRequest): PreparedEpubAcquisition {
        val archive = request.archiveSource
        if (archive.byteSize !in 1..policy.archiveLimits.maximumArchiveBytes) {
            throw EpubAcquisitionException.InvalidArchive("EPUB archive exceeds the configured size limit")
        }
        val extracted = try {
            archiveExtractor.extract(archive, policy.archiveLimits)
        } catch (error: EpubAcquisitionException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw EpubAcquisitionException.InvalidArchive(error.message ?: "EPUB archive extraction failed")
        }
        val entries = validateArchiveEntries(archive.byteSize, extracted, policy.archiveLimits)
        if (archive.byteSize > blobStore.maximumBlobSizeBytes ||
            entries.any { it.byteSize > blobStore.maximumBlobSizeBytes }
        ) {
            throw EpubAcquisitionException.InvalidArchive(
                "EPUB package contains a body larger than the configured blob-store limit",
            )
        }
        val parsed = try {
            parsePackage(request.target, entries)
        } catch (error: EpubAcquisitionException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw EpubAcquisitionException.InvalidPackage(
                error.message ?: "EPUB package metadata is invalid",
            )
        }

        val unsupportedAlgorithms = parsed.encryption.map(EpubEncryptionDescriptor::algorithm).toSet() -
            policy.supportedEncryptionAlgorithms
        if (unsupportedAlgorithms.isNotEmpty()) {
            throw EpubAcquisitionException.UnsupportedEncryption(unsupportedAlgorithms)
        }

        // Nothing is published until the archive, OCF, OPF, spine, paths and encryption policy
        // have all passed their common-code validation.
        val offlineBytes = entries.fold(archive.byteSize) { total, entry ->
            require(total <= Long.MAX_VALUE - entry.byteSize) { "EPUB offline size overflows" }
            total + entry.byteSize
        }
        val archiveReference = plannedBlobRef(
            target = request.target,
            logicalPath = EPUB_ARCHIVE_LOGICAL_PATH,
            byteSize = archive.byteSize,
            mediaType = EPUB_ARCHIVE_MEDIA_TYPE,
            plaintextDigest = digestArchive(archive),
        )
        val referencesByPath = LinkedHashMap<String, BlobRef>(entries.size)
        entries.forEach { entry ->
            val mediaType = parsed.mediaTypes.getValue(entry.path)
            referencesByPath[entry.path] = plannedBlobRef(
                target = request.target,
                logicalPath = entry.path,
                byteSize = entry.byteSize,
                mediaType = mediaType,
                plaintextDigest = Sha256.hex(entry.unsafeBytes()),
            )
        }
        val packageGraph = buildPackageGraph(
            archive = archiveReference,
            entries = entries,
            parsed = parsed,
            blobsByPath = referencesByPath,
        )
        val representation = buildRepresentation(request.target, parsed, packageGraph)
        val aggregateDigest = Sha256.hex(buildAggregateDigestInput(archiveReference, referencesByPath))
        val declaredSize = sequenceOf(archiveReference)
            .plus(referencesByPath.values.asSequence())
            .distinctBy { it.blobId }
            .fold(0L) { total, blob ->
                require(total <= Long.MAX_VALUE - blob.byteSize) { "EPUB declared size overflows" }
                total + blob.byteSize
            }
        require(declaredSize == offlineBytes) { "EPUB planned blob identities changed aggregate size" }
        val publicationDraft = preflightPersistedGraph(
            target = request.target,
            representation = representation,
            aggregateDigest = aggregateDigest,
            declaredSize = declaredSize,
        )
        return PreparedEpubAcquisition(
            serviceToken = preparationToken,
            target = request.target,
            archiveSource = archive,
            entries = entries.toList(),
            archiveReference = archiveReference,
            referencesByPath = referencesByPath.toMap(),
            aggregateDigest = aggregateDigest,
            previewPublicationDraft = publicationDraft,
            previewRepresentation = representation,
            metadata = buildMetadata(parsed),
            offlineByteCount = offlineBytes,
        )
    }

    /**
     * Publishes only a graph whose complete final shape has already passed persistence admission.
     * [representation] may remap host-visible EPUB ids, but its authoritative href-to-body mapping
     * must remain exactly the one verified from the archive during [prepare].
     */
    internal fun publish(
        prepared: PreparedEpubAcquisition,
        representation: ContentRepresentation.EpubSpine = prepared.previewRepresentation,
    ): ContentAcquisitionResult<EpubPackageMetadata> {
        require(prepared.serviceToken === preparationToken) {
            "Prepared EPUB acquisition belongs to another service instance"
        }
        validatePreparedBodyGraph(prepared, representation)
        val publicationDraft = if (representation === prepared.previewRepresentation) {
            prepared.previewPublicationDraft
        } else {
            preflightPersistedGraph(
                target = prepared.target,
                representation = representation,
                aggregateDigest = prepared.aggregateDigest,
                declaredSize = prepared.offlineByteCount,
            )
        }
        authorizeOfflineStore(prepared.offlineByteCount)

        val receipts = ArrayList<BlobPublishReceipt>(prepared.entries.size + 1)
        receipts += publishArchive(prepared.archiveSource, prepared.archiveReference)
        prepared.entries.forEach { entry ->
            receipts += blobStore.put(
                prepared.referencesByPath.getValue(entry.path),
                entry.unsafeBytes(),
            )
        }
        return ContentAcquisitionResult(
            publicationDraft = publicationDraft,
            metadata = prepared.metadata,
            publishedBlobs = receipts,
        )
    }

    /** Builds and serializes the exact metadata shape before any one-use blob receipt exists. */
    private fun preflightPersistedGraph(
        target: LocalAcquisitionTarget,
        representation: ContentRepresentation.EpubSpine,
        aggregateDigest: String,
        declaredSize: Long,
    ): Publication {
        requirePersistedGraphStringBudget(target, representation)
        try {
            val manifest = buildManifest(target, representation, aggregateDigest, declaredSize)
            val publication = buildPublication(target, manifest)
            val owner = ContentManifestOwner(
                publicationKey = target.publicationKey,
                acquisitionId = identityDeriver.acquisitionId(target, LocalContentFormat.EPUB),
                unitKey = UnitKey(
                    target.publicationKey,
                    identityDeriver.unitId(target, LocalContentFormat.EPUB),
                ),
            )
            val attachmentPayload = EPUB_PERSISTENCE_JSON.encodeToString(
                ManifestAttachment.serializer(),
                ManifestAttachment(owner, manifest),
            )
            if (attachmentPayload.length > MAX_CONTENT_METADATA_JSON_CHARS) {
                throw EpubAcquisitionException.InvalidPackage(
                    "EPUB manifest metadata exceeds the persistence limit",
                )
            }
            val publicationPayload = EPUB_PERSISTENCE_JSON.encodeToString(
                Publication.serializer(),
                publication,
            )
            if (publicationPayload.length > MAX_CONTENT_METADATA_JSON_CHARS) {
                throw EpubAcquisitionException.InvalidPackage(
                    "EPUB publication metadata exceeds the persistence limit",
                )
            }
            return publication
        } catch (error: EpubAcquisitionException) {
            throw error
        } catch (error: IllegalArgumentException) {
            throw EpubAcquisitionException.InvalidPackage(
                "EPUB persisted graph is invalid: ${error.message ?: "metadata validation failed"}",
            )
        }
    }

    /** Rejects an obviously oversized graph before asking the JSON encoder for a large allocation. */
    private fun requirePersistedGraphStringBudget(
        target: LocalAcquisitionTarget,
        representation: ContentRepresentation.EpubSpine,
    ) {
        var rawCharacterCount = 0L
        fun account(value: String?) {
            if (value == null) return
            rawCharacterCount += value.length.toLong()
            if (rawCharacterCount > MAX_CONTENT_METADATA_JSON_CHARS.toLong()) {
                throw EpubAcquisitionException.InvalidPackage(
                    "EPUB persisted metadata exceeds the persistence limit",
                )
            }
        }

        account(target.publicationTitle)
        account(target.unitTitle)
        account(representation.representationId)
        fun accountBlob(blob: BlobRef) {
            account(blob.blobId)
            account(blob.digestAlgorithm)
            account(blob.plaintextDigest)
            account(blob.mediaType)
        }
        val graph = representation.packageGraph
        accountBlob(graph.archive)
        account(graph.packageDocumentId)
        graph.resources.forEach { resource ->
            account(resource.id)
            account(resource.href)
            account(resource.resource.id)
            accountBlob(resource.resource.blob)
            account(resource.mediaType)
            resource.properties.forEach(::account)
        }
        graph.renditions.forEach { rendition ->
            account(rendition.layout)
            account(rendition.orientation)
            account(rendition.spread)
        }
        graph.encryption?.descriptors.orEmpty().forEach { descriptor ->
            account(descriptor.resourceId)
            account(descriptor.algorithm)
            account(descriptor.keyReference)
        }
        graph.packageMetadata?.let { metadata ->
            account(metadata.packageDocumentHref)
            account(metadata.packageVersion)
            account(metadata.uniqueIdentifierIdRef)
            account(metadata.uniqueIdentifier)
            account(metadata.title)
            account(metadata.language)
            account(metadata.pageProgressionDirection)
        }
        graph.manifest.forEach { item ->
            account(item.manifestIdRef)
            account(item.declaredHref)
            account(item.resolvedHref)
            account(item.resourceId)
            account(item.mediaType)
            item.properties.forEach(::account)
            account(item.fallbackIdRef)
            account(item.mediaOverlayIdRef)
        }
        account(graph.spineTocManifestIdRef)
        graph.navigation.forEach { navigation ->
            account(navigation.documentResourceId)
            account(navigation.documentHref)
            navigation.points.forEach { point ->
                account(point.label)
                account(point.declaredHref)
                account(point.resolvedHref)
                account(point.resourceId)
                account(point.fragment)
            }
        }
        graph.cssDependencies.forEach { dependency ->
            account(dependency.stylesheetHref)
            dependency.references.forEach { reference ->
                account(reference.declaredReference)
                account(reference.resolvedHref)
            }
        }
        representation.documents.forEach { document ->
            account(document.id)
            account(document.href)
            account(document.resourceId)
            account(document.manifestIdRef)
            document.properties.forEach(::account)
            document.rendition?.let { rendition ->
                account(rendition.layout)
                account(rendition.orientation)
                account(rendition.spread)
            }
        }
    }

    private fun validatePreparedBodyGraph(
        prepared: PreparedEpubAcquisition,
        representation: ContentRepresentation.EpubSpine,
    ) {
        if (representation.packageGraph.archive != prepared.archiveReference) {
            throw EpubAcquisitionException.InvalidPackage(
                "Final EPUB graph changed the prepared archive body reference",
            )
        }
        val expectedByHref = prepared.referencesByPath.mapKeys { (path, _) -> archiveHref(path) }
        val resources = representation.packageGraph.resources
        val actualByHref = resources.associateBy(EpubResource::href)
        if (actualByHref.size != resources.size || actualByHref.keys != expectedByHref.keys) {
            throw EpubAcquisitionException.InvalidPackage(
                "Final EPUB graph changed the prepared resource href set",
            )
        }
        actualByHref.forEach { (href, resource) ->
            if (resource.resource.blob != expectedByHref.getValue(href)) {
                throw EpubAcquisitionException.InvalidPackage(
                    "Final EPUB graph changed the prepared body for $href",
                )
            }
        }
    }

    private fun buildPackageGraph(
        archive: BlobRef,
        entries: List<EpubArchiveEntry>,
        parsed: ParsedEpub,
        blobsByPath: Map<String, BlobRef>,
    ): EpubPackage {
        val resources = entries.map { entry ->
            val resourceId = parsed.resourceIds.getValue(entry.path)
            EpubResource(
                id = resourceId,
                href = archiveHref(entry.path),
                resource = ResourceRef(resourceId, blobsByPath.getValue(entry.path)),
                properties = parsed.propertiesByPath[entry.path].orEmpty(),
            )
        }
        val encryption = parsed.encryption
            .takeIf(List<EpubEncryptionDescriptor>::isNotEmpty)
            ?.let(::EpubEncryption)
        return EpubPackage(
            archive = archive,
            packageDocumentId = parsed.resourceIds.getValue(parsed.packageDocumentPath),
            resources = resources,
            renditions = parsed.renditions,
            encryption = encryption,
            packageMetadata = EpubPackageDocumentMetadata(
                packageDocumentHref = archiveHref(parsed.packageDocumentPath),
                packageVersion = parsed.packageVersion,
                uniqueIdentifierIdRef = parsed.uniqueIdentifierIdRef,
                uniqueIdentifier = parsed.uniqueIdentifier,
                title = parsed.title,
                language = parsed.language,
                pageProgressionDirection = parsed.pageProgressionDirection,
            ),
            manifest = parsed.manifest.map { item ->
                EpubPackageManifestItem(
                    manifestIdRef = item.id,
                    declaredHref = item.declaredHref,
                    resolvedHref = archiveHref(item.resolvedPath),
                    resourceId = parsed.resourceIds.getValue(item.resolvedPath),
                    mediaType = item.mediaType,
                    properties = item.properties,
                    fallbackIdRef = item.fallback,
                    mediaOverlayIdRef = item.mediaOverlay,
                )
            },
            spineTocManifestIdRef = parsed.spineTocManifestIdRef,
            navigation = parsed.navigation.map { map ->
                EpubPackageNavigationDocument(
                    kind = when (map.kind) {
                        EpubNavigationKind.EPUB3_NAV -> EpubPackageNavigationKind.EPUB3_NAV
                        EpubNavigationKind.NCX -> EpubPackageNavigationKind.NCX
                    },
                    documentResourceId = map.documentResourceId,
                    documentHref = map.documentHref,
                    points = map.points.map { point ->
                        EpubPackageNavigationPoint(
                            label = point.label,
                            declaredHref = point.declaredHref,
                            resolvedHref = point.resolvedHref,
                            resourceId = point.resourceId,
                            fragment = point.fragment,
                        )
                    },
                )
            },
            cssDependencies = parsed.cssDependencies.map { dependency ->
                EpubPackageCssDependency(
                    stylesheetHref = dependency.stylesheetHref,
                    references = dependency.references.map { reference ->
                        EpubPackageCssReference(
                            declaredReference = reference.declaredReference,
                            resolvedHref = reference.resolvedHref,
                            external = reference.external,
                        )
                    },
                )
            },
        )
    }

    private fun buildRepresentation(
        target: LocalAcquisitionTarget,
        parsed: ParsedEpub,
        packageGraph: EpubPackage,
    ): ContentRepresentation.EpubSpine = ContentRepresentation.EpubSpine(
        representationId = identityDeriver.representationId(target, LocalContentFormat.EPUB),
        packageGraph = packageGraph,
        documents = parsed.spine.mapIndexed { index, item ->
            EpubSpineDocument(
                id = identityDeriver.spineId(target, item.manifestIdRef, index),
                href = archiveHref(item.resolvedPath),
                resourceId = parsed.resourceIds.getValue(item.resolvedPath),
                linear = item.linear,
                pageProgression = parsed.pageProgression,
                manifestIdRef = item.manifestIdRef,
                properties = item.properties,
                rendition = renditionFromProperties(item.properties),
            )
        },
    )

    private fun buildMetadata(parsed: ParsedEpub): EpubPackageMetadata = EpubPackageMetadata(
        packageDocumentHref = archiveHref(parsed.packageDocumentPath),
        uniqueIdentifier = parsed.uniqueIdentifier,
        title = parsed.title,
        language = parsed.language,
        manifest = parsed.manifest.map { item ->
            EpubManifestDeclaration(
                manifestIdRef = item.id,
                declaredHref = item.declaredHref,
                resolvedHref = archiveHref(item.resolvedPath),
                resourceId = parsed.resourceIds.getValue(item.resolvedPath),
                mediaType = item.mediaType,
                properties = item.properties,
                fallbackIdRef = item.fallback,
                mediaOverlayIdRef = item.mediaOverlay,
            )
        },
        spine = parsed.spine.map { item ->
            EpubSpineDeclaration(
                manifestIdRef = item.manifestIdRef,
                resourceId = parsed.resourceIds.getValue(item.resolvedPath),
                resolvedHref = archiveHref(item.resolvedPath),
                linear = item.linear,
                properties = item.properties,
            )
        },
        cssDependencies = parsed.cssDependencies,
        navigation = parsed.navigation,
        packageVersion = parsed.packageVersion,
        uniqueIdentifierIdRef = parsed.uniqueIdentifierIdRef,
        spineTocManifestIdRef = parsed.spineTocManifestIdRef,
        pageProgressionDirection = parsed.pageProgressionDirection,
    )

    private fun buildManifest(
        target: LocalAcquisitionTarget,
        representation: ContentRepresentation.EpubSpine,
        aggregateDigest: String,
        declaredSize: Long,
    ): ContentManifest = ContentManifest(
        manifestId = identityDeriver.manifestId(target, LocalContentFormat.EPUB, aggregateDigest),
        schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
        contentRevision = target.contentRevision,
        representations = listOf(representation),
        declaredSizeBytes = declaredSize,
    )

    private fun buildPublication(
        target: LocalAcquisitionTarget,
        manifest: ContentManifest,
    ): Publication {
        val unit = PublicationUnit(
            key = UnitKey(target.publicationKey, identityDeriver.unitId(target, LocalContentFormat.EPUB)),
            title = target.unitTitle,
            manifestRevisions = listOf(manifest),
        )
        val acquisition = Acquisition(
            id = identityDeriver.acquisitionId(target, LocalContentFormat.EPUB),
            origin = AcquisitionOrigin.LocalEpub,
            units = listOf(unit),
            contentRevision = target.contentRevision,
            acquiredAtEpochMillis = target.acquiredAtEpochMillis,
        )
        return Publication(
            key = target.publicationKey,
            title = target.publicationTitle,
            acquisitions = listOf(acquisition),
        )
    }

    private fun plannedBlobRef(
        target: LocalAcquisitionTarget,
        logicalPath: String,
        plaintextDigest: String,
        byteSize: Long,
        mediaType: String,
    ): BlobRef = BlobRef(
        blobId = identityDeriver.blobId(
            target = target,
            format = LocalContentFormat.EPUB,
            logicalPath = logicalPath,
            plaintextDigest = plaintextDigest,
            byteSize = byteSize,
            mediaType = mediaType,
        ),
        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
        digestAlgorithm = BlobRef.SHA_256,
        plaintextDigest = plaintextDigest,
        byteSize = byteSize,
        mediaType = mediaType,
    )

    private fun digestArchive(source: EpubArchiveSource): String {
        val hashing = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()
        return try {
            var offset = 0L
            while (offset < source.byteSize) {
                source.cancellationCheckpoint()
                val byteCount = minOf(
                    EPUB_ARCHIVE_WRITE_CHUNK_BYTES.toLong(),
                    source.byteSize - offset,
                ).toInt()
                val chunk = source.read(offset, byteCount)
                if (chunk.size != byteCount) {
                    throw EpubAcquisitionException.InvalidArchive("EPUB source returned a truncated read")
                }
                buffer.write(chunk)
                hashing.write(buffer, chunk.size.toLong())
                offset += byteCount
            }
            hashing.hash.hex()
        } finally {
            hashing.close()
        }
    }

    private fun publishArchive(source: EpubArchiveSource, expected: BlobRef): BlobPublishReceipt {
        val stage = blobStore.beginStage(source.byteSize, EPUB_ARCHIVE_MEDIA_TYPE)
        return try {
            var offset = 0L
            while (offset < source.byteSize) {
                val byteCount = minOf(EPUB_ARCHIVE_WRITE_CHUNK_BYTES.toLong(), source.byteSize - offset).toInt()
                val chunk = source.read(offset, byteCount)
                if (chunk.size != byteCount) {
                    throw EpubAcquisitionException.InvalidArchive("EPUB source returned a truncated read")
                }
                stage.append(chunk)
                offset += byteCount
            }
            blobStore.publish(stage.seal(expected))
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
    }

    private fun parsePackage(target: LocalAcquisitionTarget, entries: List<EpubArchiveEntry>): ParsedEpub {
        val byPath = entries.associateBy(EpubArchiveEntry::path)
        val mimetype = byPath[MIMETYPE_PATH]
            ?: throw EpubAcquisitionException.ResourceMissing(MIMETYPE_PATH)
        if (!mimetype.unsafeBytes().contentEquals(EPUB_ARCHIVE_MEDIA_TYPE.encodeToByteArray())) {
            throw EpubAcquisitionException.InvalidArchive("EPUB mimetype entry is not canonical")
        }
        val container = byPath[CONTAINER_PATH]
            ?: throw EpubAcquisitionException.ResourceMissing(CONTAINER_PATH)
        val containerXml = parseXml(CONTAINER_PATH, container)
        if (!containerXml.hasExpandedName(OCF_CONTAINER_NAMESPACE_URI, "container")) {
            throw EpubAcquisitionException.InvalidPackage("EPUB container document has the wrong root element")
        }
        val rootfiles = containerXml.descendants(OCF_CONTAINER_NAMESPACE_URI, "rootfile")
        val rootfile = rootfiles.firstOrNull {
            it.attribute("media-type") == OPF_MEDIA_TYPE
        } ?: rootfiles.firstOrNull()
            ?: throw EpubAcquisitionException.InvalidPackage("EPUB container has no package document")
        val packagePath = try {
            resolveArchivePath("root", requireAttribute(rootfile, "full-path"))
        } catch (error: IllegalArgumentException) {
            throw EpubAcquisitionException.InvalidPackage(error.message ?: "EPUB package path is unsafe")
        }
        val packageEntry = byPath[packagePath] ?: throw EpubAcquisitionException.ResourceMissing(packagePath)
        val opf = parseXml(packagePath, packageEntry)
        if (!opf.hasExpandedName(OPF_NAMESPACE_URI, "package")) {
            throw EpubAcquisitionException.InvalidPackage("EPUB package document has the wrong root element")
        }
        val packageVersion = opf.attribute("version")?.let { requireToken(it, "EPUB package version") }
        val uniqueIdentifierReference = opf.attribute("unique-identifier")?.let {
            requireToken(it, "EPUB unique identifier idref")
        }
        val manifestElement = opf.child(OPF_NAMESPACE_URI, "manifest")
            ?: throw EpubAcquisitionException.InvalidPackage("EPUB package has no manifest")
        val manifestItems = manifestElement.children(OPF_NAMESPACE_URI, "item").map { element ->
            val id = requireToken(requireAttribute(element, "id"), "EPUB manifest id")
            val declaredHref = requireAttribute(element, "href")
            if ('#' in declaredHref || '?' in declaredHref) {
                throw EpubAcquisitionException.InvalidPackage("EPUB manifest href must identify one archive resource")
            }
            val resolvedPath = try {
                resolveArchivePath(packagePath, declaredHref)
            } catch (error: IllegalArgumentException) {
                throw EpubAcquisitionException.InvalidPackage(error.message ?: "EPUB manifest href is unsafe")
            }
            if (resolvedPath !in byPath) throw EpubAcquisitionException.ResourceMissing(resolvedPath)
            val mediaType = requireMediaType(requireAttribute(element, "media-type"))
            OpfManifestItem(
                id = id,
                declaredHref = declaredHref,
                resolvedPath = resolvedPath,
                mediaType = mediaType,
                properties = parseTokens(element.attribute("properties")),
                fallback = element.attribute("fallback")?.let { requireToken(it, "EPUB fallback id") },
                mediaOverlay = element.attribute("media-overlay")?.let {
                    requireToken(it, "EPUB media-overlay id")
                },
            )
        }
        if (manifestItems.isEmpty() || manifestItems.map(OpfManifestItem::id).distinct().size != manifestItems.size) {
            throw EpubAcquisitionException.InvalidPackage("EPUB manifest ids must be non-empty and unique")
        }
        if (manifestItems.groupBy(OpfManifestItem::resolvedPath).values.any { aliases ->
                aliases.map(OpfManifestItem::mediaType).distinct().size > 1
            }
        ) {
            throw EpubAcquisitionException.InvalidPackage("EPUB manifest aliases disagree on media type")
        }
        val manifestById = manifestItems.associateBy(OpfManifestItem::id)
        manifestItems.forEach { item ->
            item.fallback?.let { fallback ->
                if (fallback !in manifestById) {
                    throw EpubAcquisitionException.InvalidPackage("EPUB fallback references an unknown manifest id")
                }
            }
            item.mediaOverlay?.let { overlay ->
                if (overlay !in manifestById) {
                    throw EpubAcquisitionException.InvalidPackage("EPUB media overlay references an unknown manifest id")
                }
            }
        }
        val spineElement = opf.child(OPF_NAMESPACE_URI, "spine")
            ?: throw EpubAcquisitionException.InvalidPackage("EPUB package has no spine")
        val spineTocManifestIdRef = spineElement.attribute("toc")?.let {
            requireToken(it, "EPUB spine TOC idref")
        }
        spineTocManifestIdRef?.let { toc ->
            val declaration = manifestById[toc]
                ?: throw EpubAcquisitionException.InvalidPackage("EPUB spine TOC references an unknown manifest id")
            if (!declaration.mediaType.equals(NCX_MEDIA_TYPE, ignoreCase = true)) {
                throw EpubAcquisitionException.InvalidPackage("EPUB spine TOC must reference an NCX resource")
            }
        }
        val spine = spineElement.children(OPF_NAMESPACE_URI, "itemref").map { itemref ->
            val idref = requireToken(requireAttribute(itemref, "idref"), "EPUB spine idref")
            val item = manifestById[idref]
                ?: throw EpubAcquisitionException.InvalidPackage("EPUB spine references an unknown manifest id: $idref")
            val linearValue = itemref.attribute("linear")
            if (linearValue != null && !linearValue.equals("yes", ignoreCase = true) &&
                !linearValue.equals("no", ignoreCase = true)
            ) {
                throw EpubAcquisitionException.InvalidPackage("EPUB spine linear value is invalid")
            }
            OpfSpineItem(
                manifestIdRef = idref,
                resolvedPath = item.resolvedPath,
                linear = !itemref.attribute("linear").equals("no", ignoreCase = true),
                properties = parseTokens(itemref.attribute("properties")),
            )
        }
        if (spine.isEmpty()) throw EpubAcquisitionException.InvalidPackage("EPUB spine is empty")

        val resourceIds = entries.associate { entry ->
            entry.path to identityDeriver.resourceId(target, LocalContentFormat.EPUB, entry.path)
        }
        if (resourceIds.values.distinct().size != resourceIds.size ||
            entries.any { archiveHref(it.path).length > MAX_RESOLVED_HREF_LENGTH }
        ) {
            throw EpubAcquisitionException.InvalidPackage("EPUB resolved resource identities are invalid")
        }
        val mediaTypes = entries.associate { entry ->
            entry.path to when (entry.path) {
                packagePath -> OPF_MEDIA_TYPE
                else -> manifestItems.firstOrNull { it.resolvedPath == entry.path }?.mediaType
                    ?: inferMediaType(entry.path)
            }
        }
        val propertiesByPath = manifestItems.groupBy(OpfManifestItem::resolvedPath).mapValues { (_, items) ->
            items.flatMap(OpfManifestItem::properties).toSet()
        }
        if (propertiesByPath.values.any { it.size > MAX_PROPERTY_TOKENS }) {
            throw EpubAcquisitionException.InvalidPackage("EPUB resource property union is too large")
        }
        val encryption = parseEncryption(byPath, resourceIds)
        val metadata = opf.child(OPF_NAMESPACE_URI, "metadata")
        val identifierElements = metadata?.descendants(DC_NAMESPACE_URI, "identifier").orEmpty()
        val uniqueIdentifier = if (uniqueIdentifierReference != null) {
            identifierElements.singleOrNull { it.attribute("id") == uniqueIdentifierReference }
                ?.textContent()?.trim()?.takeIf(String::isNotEmpty)
                ?: throw EpubAcquisitionException.InvalidPackage(
                    "EPUB unique identifier does not resolve to one DC identifier",
                )
        } else {
            identifierElements.firstOrNull()?.textContent()?.trim()?.takeIf(String::isNotEmpty)
        }
        val title = metadata?.descendants(DC_NAMESPACE_URI, "title")
            ?.firstOrNull()?.textContent()?.trim()?.takeIf(String::isNotEmpty)
        val language = metadata?.descendants(DC_NAMESPACE_URI, "language")
            ?.firstOrNull()?.textContent()?.trim()?.takeIf(String::isNotEmpty)
        listOfNotNull(uniqueIdentifier, title, language).forEach { value ->
            if (value.length > MAX_METADATA_VALUE_LENGTH || value.any(Char::isISOControl)) {
                throw EpubAcquisitionException.InvalidPackage("EPUB metadata is not bounded and printable")
            }
        }
        val renditionValues = metadata?.descendants(OPF_NAMESPACE_URI, "meta").orEmpty().associate { meta ->
            meta.attribute("property").orEmpty() to meta.textContent().trim()
        }
        val rendition = EpubRendition(
            layout = renditionValues["rendition:layout"]?.takeIf(String::isNotBlank),
            orientation = renditionValues["rendition:orientation"]?.takeIf(String::isNotBlank),
            spread = renditionValues["rendition:spread"]?.takeIf(String::isNotBlank),
        )
        val renditions = if (rendition.layout != null || rendition.orientation != null || rendition.spread != null) {
            listOf(rendition)
        } else {
            emptyList()
        }
        val declaredProgression = spineElement.attribute("page-progression-direction")?.lowercase()
        if (declaredProgression !in setOf(null, "default", "ltr", "rtl")) {
            throw EpubAcquisitionException.InvalidPackage("EPUB page progression direction is invalid")
        }
        val pageProgression = when (declaredProgression) {
            "rtl" -> ImageProgression.RIGHT_TO_LEFT
            else -> ImageProgression.LEFT_TO_RIGHT
        }
        val cssDependencies = manifestItems.asSequence()
            .filter { it.mediaType.equals(CSS_MEDIA_TYPE, ignoreCase = true) }
            .map { item -> cssGraph(item, byPath.getValue(item.resolvedPath).unsafeBytes(), byPath.keys) }
            .toList()
        val navigation = parseNavigation(manifestItems, byPath, resourceIds)
        return ParsedEpub(
            packageDocumentPath = packagePath,
            manifest = manifestItems,
            spine = spine,
            resourceIds = resourceIds,
            mediaTypes = mediaTypes,
            propertiesByPath = propertiesByPath,
            encryption = encryption,
            renditions = renditions,
            pageProgression = pageProgression,
            pageProgressionDirection = declaredProgression,
            packageVersion = packageVersion,
            uniqueIdentifierIdRef = uniqueIdentifierReference,
            spineTocManifestIdRef = spineTocManifestIdRef,
            uniqueIdentifier = uniqueIdentifier,
            title = title,
            language = language,
            cssDependencies = cssDependencies,
            navigation = navigation,
        )
    }

    private fun parseNavigation(
        manifest: List<OpfManifestItem>,
        entries: Map<String, EpubArchiveEntry>,
        resourceIds: Map<String, String>,
    ): List<EpubNavigationMap> = manifest.mapNotNull { item ->
        val kind = when {
            "nav" in item.properties -> EpubNavigationKind.EPUB3_NAV
            item.mediaType.equals(NCX_MEDIA_TYPE, ignoreCase = true) -> EpubNavigationKind.NCX
            else -> return@mapNotNull null
        }
        val xml = parseXml(item.resolvedPath, entries.getValue(item.resolvedPath))
        val rawPoints = when (kind) {
            EpubNavigationKind.EPUB3_NAV -> {
                if (!xml.hasExpandedName(XHTML_NAMESPACE_URI, "html") &&
                    !xml.hasExpandedName(XHTML_NAMESPACE_URI, "nav")
                ) {
                    throw EpubAcquisitionException.InvalidPackage(
                        "EPUB navigation document has the wrong root element",
                    )
                }
                val navElements = buildList {
                    if (xml.hasExpandedName(XHTML_NAMESPACE_URI, "nav")) add(xml)
                    addAll(xml.descendants(XHTML_NAMESPACE_URI, "nav"))
                }
                val toc = navElements.firstOrNull { nav ->
                    nav.attribute(EPUB_OPS_NAMESPACE_URI, "type")
                        ?.split(Regex("\\s+"))
                        ?.any { it == "toc" } == true
                } ?: throw EpubAcquisitionException.InvalidPackage(
                    "EPUB navigation document has no namespaced TOC",
                )
                toc.descendants(XHTML_NAMESPACE_URI, "a").mapNotNull { anchor ->
                    anchor.attribute("href")?.takeIf(String::isNotBlank)?.let { href ->
                        anchor.textContent().trim() to href
                    }
                }
            }
            EpubNavigationKind.NCX -> {
                if (!xml.hasExpandedName(NCX_NAMESPACE_URI, "ncx")) {
                    throw EpubAcquisitionException.InvalidPackage(
                        "EPUB NCX document has the wrong root element",
                    )
                }
                xml.descendants(NCX_NAMESPACE_URI, "navPoint").mapNotNull { point ->
                    val label = point.descendants(NCX_NAMESPACE_URI, "navLabel").firstOrNull()
                        ?.descendants(NCX_NAMESPACE_URI, "text")?.firstOrNull()
                        ?.textContent()?.trim().orEmpty()
                    val href = point.descendants(NCX_NAMESPACE_URI, "content")
                        .firstOrNull()?.attribute("src")
                    href?.takeIf(String::isNotBlank)?.let { label to it }
                }
            }
        }
        if (rawPoints.isEmpty()) {
            throw EpubAcquisitionException.InvalidPackage(
                "EPUB navigation document has no namespaced navigation points",
            )
        }
        val points = rawPoints.map { (label, declaredHref) ->
            navigationPoint(item.resolvedPath, label, declaredHref, resourceIds)
        }
        EpubNavigationMap(
            kind = kind,
            documentResourceId = resourceIds.getValue(item.resolvedPath),
            documentHref = archiveHref(item.resolvedPath),
            points = points,
        )
    }

    private fun navigationPoint(
        basePath: String,
        label: String,
        declaredHref: String,
        resourceIds: Map<String, String>,
    ): EpubNavigationPoint {
        if (label.length > MAX_METADATA_VALUE_LENGTH || label.any(Char::isISOControl) ||
            declaredHref.length > MAX_RESOLVED_HREF_LENGTH || declaredHref.any(Char::isISOControl)
        ) {
            throw EpubAcquisitionException.InvalidPackage("EPUB navigation point is not bounded and printable")
        }
        val pathPart = declaredHref.substringBefore('#').substringBefore('?')
        val isExternal = declaredHref.startsWith('/') || declaredHref.startsWith("//") ||
            EXTERNAL_SCHEME.containsMatchIn(declaredHref)
        val resolvedPath = when {
            isExternal -> null
            pathPart.isEmpty() -> basePath
            else -> resolveOptionalArchivePath(basePath, declaredHref)
        }?.takeIf { it in resourceIds }
        return EpubNavigationPoint(
            label = label,
            declaredHref = declaredHref,
            resolvedHref = resolvedPath?.let(::archiveHref),
            resourceId = resolvedPath?.let(resourceIds::get),
            fragment = declaredHref.substringAfter('#', "").substringBefore('?').takeIf(String::isNotEmpty),
        )
    }

    private fun parseEncryption(
        entries: Map<String, EpubArchiveEntry>,
        resourceIds: Map<String, String>,
    ): List<EpubEncryptionDescriptor> {
        val encryptionEntry = entries[ENCRYPTION_PATH] ?: return emptyList()
        val xml = parseXml(ENCRYPTION_PATH, encryptionEntry)
        if (!xml.hasExpandedName(OCF_CONTAINER_NAMESPACE_URI, "encryption")) {
            throw EpubAcquisitionException.InvalidPackage("EPUB encryption document has the wrong root element")
        }
        val descriptors = xml.descendants(XML_ENCRYPTION_NAMESPACE_URI, "EncryptedData").map { encryptedData ->
            val algorithm = encryptedData.descendants(XML_ENCRYPTION_NAMESPACE_URI, "EncryptionMethod")
                .singleOrNull()?.attribute("Algorithm")
                ?: throw EpubAcquisitionException.InvalidPackage("EPUB encryption method is missing")
            val uri = encryptedData.descendants(XML_ENCRYPTION_NAMESPACE_URI, "CipherReference")
                .singleOrNull()?.attribute("URI")
                ?: throw EpubAcquisitionException.InvalidPackage("EPUB encrypted resource URI is missing")
            val resolvedPath = resolveOptionalArchivePath("root", uri)
                ?: resolveOptionalArchivePath(ENCRYPTION_PATH, uri)
                ?: throw EpubAcquisitionException.InvalidPackage("EPUB encrypted resource URI is unsafe")
            val resourceId = resourceIds[resolvedPath] ?: throw EpubAcquisitionException.ResourceMissing(resolvedPath)
            EpubEncryptionDescriptor(
                resourceId = resourceId,
                algorithm = algorithm,
            )
        }
        if (descriptors.isEmpty()) {
            throw EpubAcquisitionException.InvalidPackage("EPUB encryption document has no encrypted resources")
        }
        if (descriptors.map(EpubEncryptionDescriptor::resourceId).distinct().size != descriptors.size) {
            throw EpubAcquisitionException.InvalidPackage("EPUB encryption resource declarations are duplicated")
        }
        return descriptors
    }

    private fun parseXml(path: String, entry: EpubArchiveEntry): XmlElement = try {
        BoundedXmlParser.parse(entry.unsafeBytes(), policy.archiveLimits.maximumXmlBytes)
    } catch (error: IllegalArgumentException) {
        throw EpubAcquisitionException.InvalidPackage("Invalid EPUB XML at $path: ${error.message}")
    }

    private fun cssGraph(
        item: OpfManifestItem,
        bytes: ByteArray,
        availablePaths: Set<String>,
    ): EpubCssDependencyGraph {
        val text = runCatching { StrictTextDecoder.decode(bytes).text }.getOrNull()
            ?: return EpubCssDependencyGraph(archiveHref(item.resolvedPath), emptyList())
        val declared = try {
            BoundedCssReferenceTokenizer(text).references()
        } catch (error: IllegalArgumentException) {
            throw EpubAcquisitionException.InvalidPackage(
                "Invalid EPUB CSS at ${item.resolvedPath}: ${error.message}",
            )
        }
        val references = declared.map { reference ->
            val external = reference.startsWith('/') || reference.startsWith("//") ||
                EXTERNAL_SCHEME.containsMatchIn(reference)
            val resolvedPath = if (!external && !reference.startsWith('#')) {
                resolveOptionalArchivePath(item.resolvedPath, reference)?.takeIf { it in availablePaths }
            } else {
                null
            }
            EpubCssReference(
                declaredReference = reference,
                resolvedHref = resolvedPath?.let(::archiveHref),
                external = external,
            )
        }
        return EpubCssDependencyGraph(archiveHref(item.resolvedPath), references)
    }
}

/** Small CSS lexical scanner used only for URL dependency discovery; publisher bytes stay exact. */
private class BoundedCssReferenceTokenizer(
    private val source: String,
) {
    private var cursor: Int = 0
    private val discovered = LinkedHashSet<String>()

    fun references(): List<String> {
        while (cursor < source.length) {
            when {
                startsComment() -> skipComment()
                source[cursor] == '\'' || source[cursor] == '"' -> skipString(source[cursor])
                source[cursor] == '@' -> consumeAtRule()
                source[cursor].isCssNameStart() || source[cursor] == '\\' -> consumeIdentifierOrFunction()
                else -> cursor++
            }
        }
        return discovered.toList()
    }

    private fun consumeAtRule() {
        cursor++
        val name = readIdentifier().lowercase()
        if (name != "import") return
        skipWhitespaceAndComments()
        when {
            cursor >= source.length -> Unit
            source[cursor] == '\'' || source[cursor] == '"' -> add(readString(source[cursor]))
            else -> {
                val checkpoint = cursor
                val function = readIdentifier().lowercase()
                skipCommentsOnly()
                if (function == "url" && cursor < source.length && source[cursor] == '(') {
                    add(readUrlFunction())
                } else {
                    cursor = checkpoint
                }
            }
        }
    }

    private fun consumeIdentifierOrFunction() {
        val name = readIdentifier().lowercase()
        skipCommentsOnly()
        if (name == "url" && cursor < source.length && source[cursor] == '(') {
            add(readUrlFunction())
        }
    }

    private fun readUrlFunction(): String {
        check(source[cursor] == '(')
        cursor++
        skipWhitespaceAndComments()
        if (cursor >= source.length) throw IllegalArgumentException("unterminated url()")
        val value = if (source[cursor] == '\'' || source[cursor] == '"') {
            readString(source[cursor]).also {
                skipWhitespaceAndComments()
                require(cursor < source.length && source[cursor] == ')') { "unterminated url()" }
                cursor++
            }
        } else {
            buildString {
                var trailingWhitespace = false
                while (cursor < source.length && source[cursor] != ')') {
                    when {
                        startsComment() -> {
                            skipComment()
                        }
                        source[cursor].isWhitespace() -> {
                            cursor++
                            trailingWhitespace = true
                        }
                        source[cursor] == '\\' -> {
                            require(!trailingWhitespace || isEmpty()) {
                                "unquoted url() contains internal whitespace"
                            }
                            append(readEscape())
                        }
                        source[cursor] == '\'' || source[cursor] == '"' || source[cursor] == '(' ->
                            throw IllegalArgumentException("unquoted url() contains an unsafe delimiter")
                        else -> {
                            require(!trailingWhitespace || isEmpty()) {
                                "unquoted url() contains internal whitespace"
                            }
                            append(source[cursor++])
                        }
                    }
                    require(length <= MAX_CSS_REFERENCE_CHARS) { "CSS reference is too large" }
                }
                require(cursor < source.length && source[cursor] == ')') { "unterminated url()" }
                cursor++
            }
        }
        return value.trim()
    }

    private fun readIdentifier(): String {
        val decoded = StringBuilder()
        var exceedsKeywordLength = false
        while (cursor < source.length) {
            val value = source[cursor]
            val fragment = when {
                value.isCssNameCharacter() -> {
                    cursor++
                    value.toString()
                }
                value == '\\' -> readEscape()
                else -> break
            }
            if (!exceedsKeywordLength) {
                if (decoded.length + fragment.length <= MAX_CSS_KEYWORD_CHARS) {
                    decoded.append(fragment)
                } else {
                    exceedsKeywordLength = true
                }
            }
        }
        return if (exceedsKeywordLength) "" else decoded.toString()
    }

    private fun readString(quote: Char): String {
        check(source[cursor] == quote)
        cursor++
        return buildString {
            while (cursor < source.length && source[cursor] != quote) {
                when (source[cursor]) {
                    '\\' -> append(readEscape())
                    '\n', '\r', '\u000c' -> throw IllegalArgumentException("CSS string contains an unescaped newline")
                    else -> append(source[cursor++])
                }
                require(length <= MAX_CSS_REFERENCE_CHARS) { "CSS reference is too large" }
            }
            require(cursor < source.length && source[cursor] == quote) { "unterminated CSS string" }
            cursor++
        }
    }

    private fun skipString(quote: Char) {
        check(source[cursor] == quote)
        cursor++
        while (cursor < source.length && source[cursor] != quote) {
            when (source[cursor]) {
                '\\' -> readEscape()
                '\n', '\r', '\u000c' -> throw IllegalArgumentException("CSS string contains an unescaped newline")
                else -> cursor++
            }
        }
        require(cursor < source.length && source[cursor] == quote) { "unterminated CSS string" }
        cursor++
    }

    private fun readEscape(): String {
        check(source[cursor] == '\\')
        cursor++
        require(cursor < source.length) { "unterminated CSS escape" }
        if (source[cursor] == '\n') {
            cursor++
            return ""
        }
        if (source[cursor] == '\r') {
            cursor++
            if (cursor < source.length && source[cursor] == '\n') cursor++
            return ""
        }
        if (source[cursor] == '\u000c') {
            cursor++
            return ""
        }
        if (source[cursor].isCssHexDigit()) {
            var codePoint = 0
            var digits = 0
            while (cursor < source.length && digits < 6 && source[cursor].isCssHexDigit()) {
                codePoint = codePoint * 16 + source[cursor].digitToInt(16)
                cursor++
                digits++
            }
            if (cursor < source.length && source[cursor].isWhitespace()) cursor++
            require(codePoint != 0 && codePoint <= 0x10ffff && codePoint !in 0xd800..0xdfff) {
                "CSS escape resolves to an invalid character"
            }
            return codePoint.toCssString()
        }
        return source[cursor++].toString()
    }

    private fun skipWhitespaceAndComments() {
        while (cursor < source.length) {
            when {
                source[cursor].isWhitespace() -> cursor++
                startsComment() -> skipComment()
                else -> return
            }
        }
    }

    private fun skipCommentsOnly() {
        while (startsComment()) skipComment()
    }

    private fun startsComment(): Boolean =
        cursor + 1 < source.length && source[cursor] == '/' && source[cursor + 1] == '*'

    private fun skipComment() {
        val end = source.indexOf("*/", cursor + 2)
        require(end >= 0) { "unterminated CSS comment" }
        cursor = end + 2
    }

    private fun add(value: String) {
        if (value.isBlank()) return
        require(value.length <= MAX_CSS_REFERENCE_CHARS && value.none(Char::isISOControl)) {
            "CSS reference is not bounded and printable"
        }
        discovered += value
        require(discovered.size <= MAX_EPUB_AUXILIARY_GRAPH_ENTRIES) {
            "EPUB CSS dependency graph has too many references"
        }
    }
}

private fun Char.isCssNameStart(): Boolean = isLetter() || this == '_' || this == '-' || code >= 0x80
private fun Char.isCssNameCharacter(): Boolean = isCssNameStart() || isDigit()
private fun Char.isCssHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun Int.toCssString(): String = if (this <= 0xffff) {
    toChar().toString()
} else {
    val adjusted = this - 0x10000
    charArrayOf(
        ((adjusted ushr 10) + 0xd800).toChar(),
        ((adjusted and 0x3ff) + 0xdc00).toChar(),
    ).concatToString()
}

private data class OpfManifestItem(
    val id: String,
    val declaredHref: String,
    val resolvedPath: String,
    val mediaType: String,
    val properties: Set<String>,
    val fallback: String?,
    val mediaOverlay: String?,
)

private data class OpfSpineItem(
    val manifestIdRef: String,
    val resolvedPath: String,
    val linear: Boolean,
    val properties: Set<String>,
)

private data class ParsedEpub(
    val packageDocumentPath: String,
    val manifest: List<OpfManifestItem>,
    val spine: List<OpfSpineItem>,
    val resourceIds: Map<String, String>,
    val mediaTypes: Map<String, String>,
    val propertiesByPath: Map<String, Set<String>>,
    val encryption: List<EpubEncryptionDescriptor>,
    val renditions: List<EpubRendition>,
    val pageProgression: ImageProgression,
    val pageProgressionDirection: String?,
    val packageVersion: String?,
    val uniqueIdentifierIdRef: String?,
    val spineTocManifestIdRef: String?,
    val uniqueIdentifier: String?,
    val title: String?,
    val language: String?,
    val cssDependencies: List<EpubCssDependencyGraph>,
    val navigation: List<EpubNavigationMap>,
)

private fun renditionFromProperties(properties: Set<String>): EpubRendition? {
    fun value(prefix: String, label: String): String? {
        val values = properties.mapNotNull { property ->
            property.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.takeIf(String::isNotBlank)
        }.distinct()
        if (values.size > 1) {
            throw EpubAcquisitionException.InvalidPackage("EPUB spine item declares conflicting $label")
        }
        return values.singleOrNull()
    }
    val layout = value("rendition:layout-", "rendition layouts")
    val orientation = value("rendition:orientation-", "rendition orientations")
    val spread = value("rendition:spread-", "rendition spreads")
    return if (layout == null && orientation == null && spread == null) {
        null
    } else {
        EpubRendition(layout = layout, orientation = orientation, spread = spread)
    }
}

private fun buildAggregateDigestInput(
    archive: BlobRef,
    resources: Map<String, BlobRef>,
): ByteArray = buildString {
    append(archive.plaintextDigest)
    resources.forEach { (path, reference) ->
        append(path.encodeToByteArray().size)
        append(':')
        append(path)
        append(reference.plaintextDigest)
    }
}.encodeToByteArray()

private fun requireAttribute(element: XmlElement, name: String): String =
    element.attribute(name)?.takeIf(String::isNotBlank)
        ?: throw EpubAcquisitionException.InvalidPackage("EPUB ${element.localName} is missing $name")

private fun requireToken(value: String, label: String): String {
    if (value.isBlank() || value.length > MAX_TOKEN_LENGTH || value.any(Char::isWhitespace) ||
        value.any(Char::isISOControl)
    ) {
        throw EpubAcquisitionException.InvalidPackage("$label is invalid")
    }
    return value
}

private fun parseTokens(value: String?): Set<String> {
    if (value.isNullOrBlank()) return emptySet()
    val tokens = value.split(Regex("\\s+")).filter(String::isNotBlank)
    if (tokens.size > MAX_PROPERTY_TOKENS) {
        throw EpubAcquisitionException.InvalidPackage("EPUB property list is too large")
    }
    return tokens.mapTo(linkedSetOf()) { requireToken(it, "EPUB property") }
}

private fun requireMediaType(value: String): String {
    if (value.length > MAX_MEDIA_TYPE_LENGTH || !MEDIA_TYPE.matches(value)) {
        throw EpubAcquisitionException.InvalidPackage("EPUB manifest media type is invalid")
    }
    return value
}

private fun inferMediaType(path: String): String = if (path == MIMETYPE_PATH) {
    "text/plain"
} else when (path.substringAfterLast('.', "").lowercase()) {
    "xhtml", "xht" -> "application/xhtml+xml"
    "html", "htm" -> "text/html"
    "css" -> CSS_MEDIA_TYPE
    "xml" -> "application/xml"
    "ncx" -> "application/x-dtbncx+xml"
    "svg" -> "image/svg+xml"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "avif" -> "image/avif"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "mp3" -> "audio/mpeg"
    "m4a", "mp4" -> "audio/mp4"
    "smil" -> "application/smil+xml"
    "js" -> "text/javascript"
    "txt" -> "text/plain"
    else -> "application/octet-stream"
}

private val MEDIA_TYPE = Regex("[A-Za-z0-9!#${'$'}%&'*+.^_`|~-]+/[A-Za-z0-9!#${'$'}%&'*+.^_`|~-]+")
private val EXTERNAL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")
private const val MAX_TOKEN_LENGTH: Int = 512
private const val MAX_PROPERTY_TOKENS: Int = 128
private const val MAX_METADATA_VALUE_LENGTH: Int = 4096
private const val MAX_RESOLVED_HREF_LENGTH: Int = 4096
private const val MAX_MEDIA_TYPE_LENGTH: Int = 256
private const val MAX_CSS_KEYWORD_CHARS: Int = 16
private const val MAX_CSS_REFERENCE_CHARS: Int = 4_096
private const val EPUB_ARCHIVE_MEDIA_TYPE: String = "application/epub+zip"
private const val OPF_MEDIA_TYPE: String = "application/oebps-package+xml"
private const val CSS_MEDIA_TYPE: String = "text/css"
private const val NCX_MEDIA_TYPE: String = "application/x-dtbncx+xml"
private const val OCF_CONTAINER_NAMESPACE_URI: String =
    "urn:oasis:names:tc:opendocument:xmlns:container"
private const val OPF_NAMESPACE_URI: String = "http://www.idpf.org/2007/opf"
private const val XHTML_NAMESPACE_URI: String = "http://www.w3.org/1999/xhtml"
private const val NCX_NAMESPACE_URI: String = "http://www.daisy.org/z3986/2005/ncx/"
private const val XML_ENCRYPTION_NAMESPACE_URI: String = "http://www.w3.org/2001/04/xmlenc#"
private const val DC_NAMESPACE_URI: String = "http://purl.org/dc/elements/1.1/"
private const val EPUB_OPS_NAMESPACE_URI: String = "http://www.idpf.org/2007/ops"
private const val MIMETYPE_PATH: String = "mimetype"
private const val CONTAINER_PATH: String = "META-INF/container.xml"
private const val ENCRYPTION_PATH: String = "META-INF/encryption.xml"
private const val EPUB_ARCHIVE_LOGICAL_PATH: String = "shinsou-internal://epub-archive"
private const val EPUB_ARCHIVE_WRITE_CHUNK_BYTES: Int = 64 * 1024
private val EPUB_PERSISTENCE_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}
