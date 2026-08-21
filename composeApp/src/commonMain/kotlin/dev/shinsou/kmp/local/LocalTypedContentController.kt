package dev.shinsou.kmp.local

import dev.shinsou.kmp.acquisition.ContentAcquisitionResult
import dev.shinsou.kmp.acquisition.DEFAULT_LOCAL_ACQUISITION_NAMESPACE
import dev.shinsou.kmp.acquisition.EpubAcquisitionRequest
import dev.shinsou.kmp.acquisition.EpubAcquisitionService
import dev.shinsou.kmp.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.acquisition.EpubArchiveSource
import dev.shinsou.kmp.acquisition.EpubPackageMetadata
import dev.shinsou.kmp.acquisition.ImageSequenceAcquisitionRequest
import dev.shinsou.kmp.acquisition.ImageSequenceAcquisitionService
import dev.shinsou.kmp.acquisition.LocalImagePageSource
import dev.shinsou.kmp.acquisition.LocalAcquisitionIdentityDeriver
import dev.shinsou.kmp.acquisition.LocalAcquisitionTarget
import dev.shinsou.kmp.acquisition.LocalContentFormat
import dev.shinsou.kmp.acquisition.TextAcquisitionMetadata
import dev.shinsou.kmp.acquisition.TextAcquisitionRequest
import dev.shinsou.kmp.acquisition.TextAcquisitionService
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.LocalPackageKind
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.Rfc9562UuidV5
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.reader.UnifiedReaderContent
import dev.shinsou.kmp.reader.UnifiedReaderNavigationFactory
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.sync.v2.ContentPublicationSyncDraftFactory
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.TypedReaderContentSession
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

internal data class LocalTypedImport(
    val title: String,
    val chapterUrl: String,
    val itemCount: Int,
    val acquiredAtEpochMillis: Long?,
)

/**
 * Production bridge from local TXT/EPUB documents to the shared publication/blob transaction.
 * The legacy source-0 Manga/Chapter row stores only a portable typed URL; authoritative bodies,
 * rights and manifests remain in ContentFoundationRuntime and survive process restart.
 */
internal class LocalTypedContentController(
    private val foundation: ContentFoundationRuntime,
    private val epubArchiveExtractor: EpubArchiveExtractor?,
    private val nowEpochMillis: () -> Long,
    private val identityDeriver: LocalAcquisitionIdentityDeriver = LocalAcquisitionIdentityDeriver(),
) {
    private val operationGate: ContentOperationGate = HostContentOperationGate(
        authority = foundation.rightsAuthority,
        nowEpochMillis = nowEpochMillis,
    )

    fun supports(fileName: String): Boolean = fileExtension(fileName) in LOCAL_TYPED_CONTENT_EXTENSIONS

    fun import(
        document: ImportedDocument,
        syncContentBodies: Boolean = false,
        cancellationCheckpoint: () -> Unit = {},
    ): LocalTypedImport {
        cancellationCheckpoint()
        val extension = fileExtension(document.name)
        require(extension in LOCAL_TYPED_CONTENT_EXTENSIONS) { "Unsupported typed local document" }
        require(document.byteSize > 0) { "Local document is empty: ${document.name}" }
        val format = when (extension) {
            LOCAL_TEXT_EXTENSION -> LocalContentFormat.PLAIN_TEXT
            LOCAL_EPUB_EXTENSION -> LocalContentFormat.EPUB
            else -> error("Unsupported typed local document")
        }
        val inlineTextBytes = if (format == LocalContentFormat.PLAIN_TEXT) {
            document.copyBytes(cancellationCheckpoint)
        } else {
            null
        }
        val digest = inlineTextBytes?.let(Sha256::hex) ?: document.sha256Hex(cancellationCheckpoint)
        val stableImportId = "local-${format.name.lowercase()}:sha256:$digest"
        val publicationKey = PublicationKey(
            Rfc9562UuidV5.derive(
                LOCAL_PUBLICATION_NAMESPACE,
                "shinsou-local-publication-v1:$stableImportId",
            ),
        )
        foundation.publications.find(publicationKey)?.let { existing ->
            if (syncContentBodies) queueExistingBodySync(existing)
            return existing.toLocalTypedImport()
        }
        val fallbackTitle = document.name.substringBeforeLast('.', document.name).trim()
            .ifBlank { if (format == LocalContentFormat.EPUB) "Local EPUB" else "Local text" }
            .take(MAX_LOCAL_TITLE_CHARS)
        val acquiredAt = nowEpochMillis()
        val target = LocalAcquisitionTarget(
            publicationKey = publicationKey,
            publicationTitle = fallbackTitle,
            stableImportId = stableImportId,
            unitTitle = fallbackTitle,
            contentRevision = 0,
            acquiredAtEpochMillis = acquiredAt,
        )
        val grant = localOwnedGrant(target, format)
        val importAccess = ContentAccessRequest(grant.grantId, grant.scope)
        foundation.rightsAuthority.admit(grant)
        val acquired: ContentAcquisitionResult<*> = try {
            val authorizeOfflineStore: (Long) -> Unit = { byteCount ->
                operationGate.requireAllowed(
                    importAccess.copy(context = RightsOperationContext(offlineBytes = byteCount)),
                    ContentOperation.OFFLINE_STORE,
                )
            }
            val result = when (format) {
                LocalContentFormat.PLAIN_TEXT -> TextAcquisitionService(
                    blobStore = foundation.blobStore,
                    authorizeOfflineStore = authorizeOfflineStore,
                    cancellationCheckpoint = cancellationCheckpoint,
                ).acquire(TextAcquisitionRequest(target, requireNotNull(inlineTextBytes)))
                LocalContentFormat.EPUB -> {
                    val sourceCancellationCheckpoint = cancellationCheckpoint
                    EpubAcquisitionService(
                        blobStore = foundation.blobStore,
                        archiveExtractor = requireNotNull(epubArchiveExtractor) {
                            "EPUB import is unavailable on this platform"
                        },
                        authorizeOfflineStore = authorizeOfflineStore,
                    ).acquire(
                        EpubAcquisitionRequest(
                            target,
                            object : EpubArchiveSource {
                                override val byteSize: Long get() = document.byteSize
                                override fun cancellationCheckpoint(): Unit = sourceCancellationCheckpoint()
                                override fun read(offset: Long, byteCount: Int): ByteArray =
                                    document.readInCancellationChunks(
                                        offset,
                                        byteCount,
                                        sourceCancellationCheckpoint,
                                    )
                            },
                        ),
                    ).also { result ->
                        cancellationCheckpoint()
                        check(
                            result.manifest.epubSpines.single().packageGraph.archive.plaintextDigest == digest,
                        ) { "Local EPUB changed while it was being imported" }
                    }
                }
                LocalContentFormat.IMAGE_SEQUENCE -> error("Image sequences use importImageSequence")
            }
            cancellationCheckpoint()
            result
        } catch (failure: Throwable) {
            foundation.rightsAuthority.revoke(grant.grantId)
            throw failure
        }
        return commitAcquired(acquired, grant, fallbackTitle, acquiredAt, syncContentBodies)
    }

    private fun ImportedDocument.copyBytes(cancellationCheckpoint: () -> Unit): ByteArray {
        require(byteSize <= Int.MAX_VALUE.toLong()) { "Local document cannot fit in memory" }
        val output = ByteArray(byteSize.toInt())
        var offset = 0
        while (offset < output.size) {
            cancellationCheckpoint()
            val count = minOf(LOCAL_DOCUMENT_DIGEST_CHUNK_BYTES, output.size - offset)
            val chunk = source.read(offset.toLong(), count)
            require(chunk.size == count) { "Local document changed while it was being read" }
            chunk.copyInto(output, offset)
            offset += count
        }
        cancellationCheckpoint()
        return output
    }

    private fun ImportedDocument.sha256Hex(cancellationCheckpoint: () -> Unit): String {
        val hashing = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()
        return try {
            var offset = 0L
            while (offset < byteSize) {
                cancellationCheckpoint()
                val count = minOf(LOCAL_DOCUMENT_DIGEST_CHUNK_BYTES.toLong(), byteSize - offset).toInt()
                val chunk = source.read(offset, count)
                require(chunk.size == count) { "Local document changed while it was being hashed" }
                buffer.write(chunk)
                hashing.write(buffer, chunk.size.toLong())
                offset += chunk.size
            }
            cancellationCheckpoint()
            hashing.hash.hex()
        } finally {
            hashing.close()
        }
    }

    fun importImageSequence(
        title: String,
        pages: List<LocalImagePageSource>,
        packageKind: LocalPackageKind,
        syncContentBodies: Boolean = false,
        cancellationCheckpoint: () -> Unit = {},
    ): LocalTypedImport {
        cancellationCheckpoint()
        require(pages.isNotEmpty()) { "Local image sequence is empty" }
        val fallbackTitle = title.trim().ifBlank { "Local Manga" }.take(MAX_LOCAL_TITLE_CHARS)
        val identityDocument = buildString {
            append("shinsou-local-image-sequence-v1\u0000")
            appendIdentityField(packageKind.name)
            pages.forEach { page ->
                cancellationCheckpoint()
                appendIdentityField(page.logicalName)
                appendIdentityField(page.mediaType)
                appendIdentityField(page.sha256Hex(cancellationCheckpoint))
                appendIdentityField(page.byteSize.toString())
            }
        }
        val digest = Sha256.hex(identityDocument.encodeToByteArray())
        val stableImportId = "local-image-sequence:sha256:$digest"
        val publicationKey = PublicationKey(
            Rfc9562UuidV5.derive(
                LOCAL_PUBLICATION_NAMESPACE,
                "shinsou-local-publication-v1:$stableImportId",
            ),
        )
        foundation.publications.find(publicationKey)?.let { existing ->
            if (syncContentBodies) queueExistingBodySync(existing)
            return existing.toLocalTypedImport()
        }
        val acquiredAt = nowEpochMillis()
        val target = LocalAcquisitionTarget(
            publicationKey = publicationKey,
            publicationTitle = fallbackTitle,
            stableImportId = stableImportId,
            unitTitle = fallbackTitle,
            contentRevision = 0,
            acquiredAtEpochMillis = acquiredAt,
        )
        val grant = localOwnedGrant(target, LocalContentFormat.IMAGE_SEQUENCE)
        val importAccess = ContentAccessRequest(grant.grantId, grant.scope)
        foundation.rightsAuthority.admit(grant)
        val acquired = try {
            cancellationCheckpoint()
            ImageSequenceAcquisitionService(
                blobStore = foundation.blobStore,
                authorizeOfflineStore = { byteCount ->
                    operationGate.requireAllowed(
                        importAccess.copy(context = RightsOperationContext(offlineBytes = byteCount)),
                        ContentOperation.OFFLINE_STORE,
                    )
                },
                cancellationCheckpoint = cancellationCheckpoint,
            ).acquire(
                ImageSequenceAcquisitionRequest(
                    target = target,
                    pages = pages,
                    packageKind = packageKind,
                ),
            ).also { cancellationCheckpoint() }
        } catch (failure: Throwable) {
            foundation.rightsAuthority.revoke(grant.grantId)
            throw failure
        }
        return commitAcquired(acquired, grant, fallbackTitle, acquiredAt, syncContentBodies)
    }

    private fun ImportedDocument.readInCancellationChunks(
        offset: Long,
        byteCount: Int,
        cancellationCheckpoint: () -> Unit,
    ): ByteArray {
        require(
            offset >= 0 && byteCount >= 0 && offset <= byteSize &&
                byteCount.toLong() <= byteSize - offset,
        ) { "Local document read is out of bounds" }
        val output = ByteArray(byteCount)
        var copied = 0
        while (copied < byteCount) {
            cancellationCheckpoint()
            val count = minOf(LOCAL_DOCUMENT_DIGEST_CHUNK_BYTES, byteCount - copied)
            val chunk = source.read(offset + copied, count)
            require(chunk.size == count) { "Local document changed while it was being read" }
            chunk.copyInto(output, copied)
            copied += count
        }
        cancellationCheckpoint()
        return output
    }

    private fun commitAcquired(
        acquired: ContentAcquisitionResult<*>,
        grant: RightsGrant,
        fallbackTitle: String,
        acquiredAt: Long,
        syncContentBodies: Boolean,
    ): LocalTypedImport = try {
        val resolvedTitle = when (val metadata = acquired.metadata) {
            is EpubPackageMetadata -> metadata.title?.trim()?.takeIf(String::isNotEmpty)
            is TextAcquisitionMetadata -> null
            else -> null
        }?.take(MAX_LOCAL_TITLE_CHARS) ?: fallbackTitle
        val publication = acquired.publicationDraft.copy(
            title = resolvedTitle,
            acquisitions = listOf(acquired.acquisition.copy(rightsGrantRef = grant.grantId)),
        )
        val owner = ContentManifestOwner(
            publicationKey = publication.key,
            acquisitionId = publication.acquisitions.single().id,
            unitKey = publication.acquisitions.single().units.single().key,
        )
        val outbox = ContentPublicationSyncDraftFactory.build(
            publication = publication,
            rightsGrants = listOf(grant),
            operationNamespace = "local-import:${acquired.manifest.manifestId}",
            createdAtMillis = acquiredAt,
        )
        val bodySyncJobs = if (syncContentBodies) bodySyncJobs(publication) else emptyList()
        foundation.transactions.commit(
            ContentCommitBatch<SyncDraft>(
                commitId = "local-import:${acquired.manifest.manifestId}",
                receipts = acquired.publishedBlobs,
                attachments = listOf(ManifestAttachment(owner, acquired.manifest)),
                publications = listOf(ContentPublicationMutation(publication)),
                rightsGrants = listOf(ContentRightsGrantMutation(grant)),
                outbox = outbox.drafts,
                blobSyncJobs = bodySyncJobs,
            ),
        )
        publication.toLocalTypedImport()
    } catch (failure: Throwable) {
        // The body has already been staged, so every failure through draft construction and the
        // durable metadata transaction must invalidate the provisional authority.
        foundation.rightsAuthority.revoke(grant.grantId)
        throw failure
    }

    private fun queueExistingBodySync(publication: Publication) {
        val jobs = bodySyncJobs(publication)
        require(jobs.isNotEmpty()) { "The local publication has no rights-allowed body to synchronize" }
        val fingerprint = Sha256.hex(jobs.joinToString("|") { it.jobId }.encodeToByteArray())
        foundation.transactions.commit(
            ContentCommitBatch<SyncDraft>(
                commitId = "local-body-sync-opt-in:$fingerprint",
                blobSyncJobs = jobs,
            ),
        )
    }

    private fun bodySyncJobs(publication: Publication): List<ContentBlobSyncJobMutation> =
        publication.acquisitions.flatMap { acquisition ->
            val grantReference = requireNotNull(acquisition.rightsGrantRef) {
                "Local body sync requires an admitted rights grant"
            }
            acquisition.units.flatMap { unit ->
                unit.manifestRevisions.flatMap { manifest ->
                    val owner = ContentManifestOwner(publication.key, acquisition.id, unit.key)
                    manifest.referencedBlobs.map { blob ->
                        ContentBlobSyncJobMutation(
                            jobId = "local-body-sync:${Sha256.hex(
                                "${manifest.manifestId}|${blob.blobId}".encodeToByteArray(),
                            )}",
                            blob = blob,
                            owner = owner,
                            manifestId = manifest.manifestId,
                            contentRevision = manifest.contentRevision,
                            grantReference = grantReference,
                        )
                    }
                }
            }
        }.distinctBy(ContentBlobSyncJobMutation::jobId)

    fun load(chapterUrl: String): TypedReaderContentSession? {
        val reference = TypedLocalChapterReference.parse(chapterUrl) ?: return null
        val publication = foundation.publications.find(reference.publicationKey)
            ?: throw LocalContentUnavailableException("The typed local publication no longer exists.")
        val acquisition = publication.acquisitions.singleOrNull { it.id == reference.acquisitionId }
            ?: throw LocalContentUnavailableException("The typed local acquisition no longer exists.")
        val unit = acquisition.units.singleOrNull { it.key == reference.unitKey }
            ?: throw LocalContentUnavailableException("The typed local unit no longer exists.")
        val manifest = unit.latestManifest
            ?: throw LocalContentUnavailableException("The typed local content has no readable manifest.")
        val representation = manifest.representations.singleOrNull()
            ?: throw LocalContentUnavailableException("The typed local content representation is ambiguous.")
        val scope = dev.shinsou.kmp.reader.ReadingScope(
            schemaVersion = dev.shinsou.kmp.reader.ReadingScope.CURRENT_SCHEMA_VERSION,
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
        val canonicalText = when (representation) {
            is ContentRepresentation.PlainText -> {
                val bytes = operationGate.execute(access, ContentOperation.DISPLAY) {
                    foundation.blobStore.read(representation.resource.blob)
                        ?: throw LocalContentUnavailableException("The local text body is missing or corrupt.")
                }
                runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrElse {
                    throw LocalContentUnavailableException("The local text body is not canonical UTF-8.")
                }.also { text ->
                    if (text.length != representation.canonicalUtf16Length) {
                        throw LocalContentUnavailableException("The local text body length does not match its manifest.")
                    }
                }
            }
            is ContentRepresentation.EpubSpine -> null
            is ContentRepresentation.ImageSequence -> null
        }
        val navigation = UnifiedReaderNavigationFactory.create(scope, representation, canonicalText)
        return TypedReaderContentSession(
            content = UnifiedReaderContent(navigation),
            canonicalText = canonicalText,
            access = access,
        )
    }

    /**
     * Body-free compatibility rows which can be recreated after a crash between the shared
     * content transaction and AppSnapshot persistence. Only the singular local graph currently
     * emitted by this controller is projected; extension and legacy-migration Publications have
     * their own compatibility owners.
     */
    fun legacyProjectionCandidates(): List<LocalTypedImport> = foundation.publications.all()
        .asSequence()
        .filter { publication ->
            publication.acquisitions.singleOrNull()?.origin.let { origin ->
                origin == dev.shinsou.kmp.domain.model.AcquisitionOrigin.LocalText ||
                    origin == dev.shinsou.kmp.domain.model.AcquisitionOrigin.LocalEpub ||
                    origin is dev.shinsou.kmp.domain.model.AcquisitionOrigin.LocalPackage
            }
        }
        .mapNotNull { publication -> runCatching { publication.toLocalTypedImport() }.getOrNull() }
        .sortedBy(LocalTypedImport::chapterUrl)
        .toList()

    private fun localOwnedGrant(
        target: LocalAcquisitionTarget,
        format: LocalContentFormat,
    ): RightsGrant = RightsGrant(
        schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
        grantId = RightsGrantRef(identityDeriver.rightsGrantId(target, format)),
        scope = RightsScope(
            publicationId = target.publicationKey,
            acquisitionId = identityDeriver.acquisitionId(target, format),
        ),
        provenance = RightsProvenance.HostPolicy("local-user-owned-v1"),
        protectionScheme = ProtectionScheme.None,
        validFromEpochMillis = 0,
        validUntilEpochMillis = null,
        allowedOperations = ContentOperation.entries.toSet(),
    )

    private fun Publication.toLocalTypedImport(): LocalTypedImport {
        val acquisition = acquisitions.singleOrNull()
            ?: throw LocalContentUnavailableException("The typed local publication is not singular.")
        val unit = acquisition.units.singleOrNull()
            ?: throw LocalContentUnavailableException("The typed local publication has no singular unit.")
        val representation = unit.latestManifest?.representations?.singleOrNull()
            ?: throw LocalContentUnavailableException("The typed local publication has no readable representation.")
        return LocalTypedImport(
            title = title,
            chapterUrl = TypedLocalChapterReference(key, acquisition.id, unit.key).encode(),
            itemCount = when (representation) {
                is ContentRepresentation.PlainText -> representation.blocks.size
                is ContentRepresentation.EpubSpine -> representation.documents.size
                is ContentRepresentation.ImageSequence -> representation.pages.size
            },
            acquiredAtEpochMillis = acquisition.acquiredAtEpochMillis,
        )
    }

    private data class TypedLocalChapterReference(
        val publicationKey: PublicationKey,
        val acquisitionId: String,
        val unitKey: UnitKey,
    ) {
        fun encode(): String = encodeTypedLocalChapterUrl(publicationKey, acquisitionId, unitKey)

        companion object {
            fun parse(value: String): TypedLocalChapterReference? {
                if (!value.startsWith(URL_PREFIX)) return null
                val parts = value.removePrefix(URL_PREFIX).split('/')
                if (parts.size != 3) return null
                return runCatching {
                    val publication = PublicationKey(parts[0])
                    TypedLocalChapterReference(
                        publicationKey = publication,
                        acquisitionId = parts[1].also {
                            require(PublicationKey.isPortableUuid(it))
                        },
                        unitKey = UnitKey(publication, parts[2]),
                    )
                }.getOrNull()
            }
        }
    }

    private companion object {
        val LOCAL_PUBLICATION_NAMESPACE: MigrationNamespaceId = DEFAULT_LOCAL_ACQUISITION_NAMESPACE
        const val URL_PREFIX: String = TYPED_LOCAL_URL_PREFIX
        const val MAX_LOCAL_TITLE_CHARS: Int = 200
    }
}

internal fun isTypedLocalCompatibilityUrl(value: String): Boolean =
    value.startsWith(TYPED_LOCAL_URL_PREFIX)

/**
 * Stable compatibility identity for one authoritative typed unit. The scheme name remains
 * historical: the referenced acquisition may be local or extension-backed, and [load] resolves
 * it exclusively through the typed Publication graph.
 */
internal fun encodeTypedLocalChapterUrl(
    publicationKey: PublicationKey,
    acquisitionId: String,
    unitKey: UnitKey,
): String {
    publicationKey.validate()
    require(PublicationKey.isPortableUuid(acquisitionId)) {
        "Typed acquisition id must be a portable UUID"
    }
    unitKey.validate()
    require(unitKey.publicationKey == publicationKey) {
        "Typed unit must belong to its URL publication"
    }
    return "$TYPED_LOCAL_URL_PREFIX${publicationKey.value}/$acquisitionId/${unitKey.value}"
}

/** Publication-scoped identity used by multi-unit legacy compatibility projections. */
internal fun encodeTypedLocalPublicationUrl(publicationKey: PublicationKey): String {
    publicationKey.validate()
    return "$TYPED_LOCAL_URL_PREFIX${publicationKey.value}"
}

private const val TYPED_LOCAL_URL_PREFIX: String = "local://typed/v1/"
private const val LOCAL_DOCUMENT_DIGEST_CHUNK_BYTES: Int = 64 * 1024

private fun StringBuilder.appendIdentityField(value: String) {
    append(value.encodeToByteArray().size)
    append(':')
    append(value)
}

internal const val LOCAL_TEXT_EXTENSION: String = "txt"
internal const val LOCAL_EPUB_EXTENSION: String = "epub"
internal val LOCAL_TYPED_CONTENT_EXTENSIONS: Set<String> = setOf(LOCAL_TEXT_EXTENSION, LOCAL_EPUB_EXTENSION)
