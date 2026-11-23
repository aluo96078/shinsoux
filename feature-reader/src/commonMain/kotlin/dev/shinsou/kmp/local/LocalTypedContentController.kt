package dev.shinsou.kmp.local

import dev.shinsou.kmp.content.acquisition.ContentAcquisitionResult
import dev.shinsou.kmp.content.acquisition.DEFAULT_LOCAL_ACQUISITION_NAMESPACE
import dev.shinsou.kmp.content.acquisition.EpubAcquisitionRequest
import dev.shinsou.kmp.content.acquisition.EpubAcquisitionService
import dev.shinsou.kmp.content.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.content.acquisition.EpubArchiveSource
import dev.shinsou.kmp.content.acquisition.EpubPackageMetadata
import dev.shinsou.kmp.content.acquisition.ImageSequenceAcquisitionRequest
import dev.shinsou.kmp.content.acquisition.ImageSequenceAcquisitionService
import dev.shinsou.kmp.content.acquisition.LocalImagePageSource
import dev.shinsou.kmp.content.acquisition.LocalAcquisitionIdentityDeriver
import dev.shinsou.kmp.content.acquisition.LocalAcquisitionTarget
import dev.shinsou.kmp.content.acquisition.LocalContentFormat
import dev.shinsou.kmp.content.acquisition.TextAcquisitionMetadata
import dev.shinsou.kmp.content.acquisition.TextAcquisitionRequest
import dev.shinsou.kmp.content.acquisition.TextAcquisitionService
import dev.shinsou.kmp.content.acquisition.DecodedText
import dev.shinsou.kmp.content.acquisition.SourceTextEncoding
import dev.shinsou.kmp.content.acquisition.TextDecodingOutcome
import dev.shinsou.kmp.content.acquisition.TextEncodingHint
import dev.shinsou.kmp.content.acquisition.decodeTextSource
import dev.shinsou.kmp.content.acquisition.decodeTextSourceOfferingEveryReading
import dev.shinsou.kmp.content.transaction.ContentCommitBatch
import dev.shinsou.kmp.content.transaction.LegacyOutboxDraft
import dev.shinsou.kmp.content.transaction.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.blob.ContentManifestOwner
import dev.shinsou.kmp.content.transaction.ContentPublicationMutation
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.content.transaction.ContentRightsGrantMutation
import dev.shinsou.kmp.content.blob.ManifestAttachment
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.access.HostContentOperationGate
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.LocalPackageKind
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.Rfc9562UuidV5
import dev.shinsou.kmp.domain.model.TYPED_LOCAL_URL_PREFIX
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.domain.model.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.domain.content.UnifiedReaderContent
import dev.shinsou.kmp.domain.content.UnifiedReaderNavigationFactory
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.LocalTextEncoding
import dev.shinsou.kmp.contract.LocalTextEncodingCandidate
import dev.shinsou.kmp.contract.LocalTextEncodingQuestion
import dev.shinsou.kmp.contract.TypedReaderContentSession
import okio.Buffer
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import okio.HashingSink
import okio.blackholeSink

internal data class LocalTypedImport(
    val title: String,
    val chapterUrl: String,
    val itemCount: Int,
    val acquiredAtEpochMillis: Long?,
    /** Library chapters of the same TXT in another encoding, which this import replaces. */
    val supersededChapterUrls: List<String> = emptyList(),
)

/**
 * A typed import whose bodies are staged: [content] is what its library rows describe. Reading,
 * hashing and parsing are done; only the short metadata commit is left, so the caller can run
 * [commit] together with the library write, where a backup restore cannot fall between them.
 */
internal class PreparedTypedImport(
    val content: LocalTypedImport,
    private val commitContent: () -> Boolean,
    private val revokeProvisionalRights: () -> Unit = {},
    private val releaseStagedBlobs: () -> Unit = {},
) {
    private var committed = false

    /**
     * Commits the publication unless it is committed already. False, with nothing committed, when
     * the content store no longer matches what [content] was prepared against (a restore ran in
     * between): the import is prepared again.
     */
    fun commit(): Boolean = commitContent().also { committed = it }

    /**
     * Ends the import: gives up the receipts of the bodies it staged and did not commit (a staged
     * EPUB publishes blobs of fixed ids, which an unconsumed receipt would keep the next import of
     * the same file from publishing until the app restarts), and withdraws its provisional rights
     * when it ends without its commit.
     */
    fun abandon() {
        // Once: a receipt another operation holds too must not be given up for it.
        if (abandoned) return
        abandoned = true
        releaseStagedBlobs()
        if (!committed) revokeProvisionalRights()
    }

    private var abandoned = false
}

/**
 * The user must choose the encoding of a TXT ([question]). The question quotes the file name and
 * text, so it is shown to the user only; this exception's message names neither.
 */
internal class LocalTextEncodingChoiceRequired(val question: LocalTextEncodingQuestion) :
    Exception("The text encoding of a local document needs the user's choice")

/**
 * How a TXT's encoding is decided: [chosen] is the user's answer; without one, [askAlways] asks
 * for every file with more than one reading instead of only for an unclear one.
 */
internal class LocalTextEncodingRequest(val chosen: LocalTextEncoding? = null, val askAlways: Boolean = false)

/** A TXT to decode: its name, bytes and the identity it has when it is Unicode text. */
private class TextToResolve(val documentName: String, val bytes: ByteArray, val unicodeImportId: String)

/** A TXT's decoded text and the import identity its encoding gives it. */
private class ResolvedText(
    val stableImportId: String,
    val decoded: DecodedText,
    val supersededChapterUrls: List<String>,
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
    identityDeriver: LocalAcquisitionIdentityDeriver = LocalAcquisitionIdentityDeriver(),
) {
    private val operationGate: ContentOperationGate = HostContentOperationGate(
        authority = foundation.rightsAuthority,
        nowEpochMillis = nowEpochMillis,
    )

    private val bodies = LocalPublicationBodies(foundation)
    private val rights = LocalImportRights(foundation, operationGate, identityDeriver)

    fun supports(fileName: String): Boolean = fileExtension(fileName) in LOCAL_TYPED_CONTENT_EXTENSIONS

    /**
     * Prepares the import of a TXT or EPUB: everything but the final commit
     * ([PreparedTypedImport.commit]). A TXT in a legacy Chinese encoding is identified by its
     * bytes and that encoding, so reading it the other way is another title; [textEncoding] holds
     * the user's choice, and without one an unclear or already imported reading (or, when the user
     * asked to choose, any file with several readings) throws [LocalTextEncodingChoiceRequired].
     * [isInLibrary] tells whether a chapter URL has a library row.
     */
    fun prepare(
        document: ImportedDocument,
        syncContentBodies: Boolean = false,
        cancellationCheckpoint: () -> Unit = {},
        textEncoding: LocalTextEncodingRequest = LocalTextEncodingRequest(),
        isInLibrary: (chapterUrl: String) -> Boolean = { true },
    ): PreparedTypedImport {
        cancellationCheckpoint()
        val extension = fileExtension(document.name)
        require(extension in LOCAL_TYPED_CONTENT_EXTENSIONS) { "Unsupported typed local document" }
        require(document.byteSize > 0) { "Local document is empty: ${document.name}" }
        val format = when (extension) {
            LOCAL_TEXT_EXTENSION -> LocalContentFormat.PLAIN_TEXT
            LOCAL_EPUB_EXTENSION -> LocalContentFormat.EPUB
            else -> error("Unsupported typed local document")
        }
        // Text is read once: the copy it is parsed from is hashed as it is read.
        val inlineText = document.takeIf { format == LocalContentFormat.PLAIN_TEXT }
            ?.copyBytesAndDigest(cancellationCheckpoint)
        val inlineTextBytes = inlineText?.first
        val digest = inlineText?.second ?: document.sha256Hex(cancellationCheckpoint)
        val unicodeImportId = "local-${format.name.lowercase()}:sha256:$digest"
        // Decoded even when already imported: re-importing is how a wrong reading is replaced.
        val resolvedText = inlineTextBytes
            ?.let { bytes ->
                resolveText(
                    TextToResolve(document.name, bytes, unicodeImportId),
                    textEncoding,
                    isInLibrary,
                    cancellationCheckpoint,
                )
            }
        val stableImportId = resolvedText?.stableImportId ?: unicodeImportId
        val superseded = resolvedText?.supersededChapterUrls.orEmpty()
        val publicationKey = publicationKeyFor(stableImportId)
        reusablePublication(publicationKey)?.let { existing ->
            return alreadyCommitted(existing, syncContentBodies, superseded)
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
        val grant = rights.stagingGrant(target, format)
        val importAccess = ContentAccessRequest(grant.grantId, rights.stagingScope(target, format))
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
                ).acquire(
                    TextAcquisitionRequest(
                        target = target,
                        sourceBytes = requireNotNull(inlineTextBytes),
                        decoded = resolvedText?.decoded,
                    ),
                )
                LocalContentFormat.EPUB ->
                    acquireEpub(target, document, digest, authorizeOfflineStore, cancellationCheckpoint)
                LocalContentFormat.IMAGE_SEQUENCE -> error("Image sequences use importImageSequence")
            }
            result.also { staged -> releasingOnFailure(staged, cancellationCheckpoint) }
        } catch (failure: Throwable) {
            rights.revokeProvisional(grant)
            throw failure
        }
        return preparedCommit(acquired, grant, fallbackTitle, syncContentBodies, superseded)
    }

    private fun acquireEpub(
        target: LocalAcquisitionTarget,
        document: ImportedDocument,
        digest: String,
        authorizeOfflineStore: (Long) -> Unit,
        cancellationCheckpoint: () -> Unit,
    ): ContentAcquisitionResult<*> = EpubAcquisitionService(
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
                override fun cancellationCheckpoint(): Unit = cancellationCheckpoint()
                override fun read(offset: Long, byteCount: Int): ByteArray =
                    document.readInCancellationChunks(offset, byteCount, cancellationCheckpoint)
            },
        ),
    ).also { result ->
        releasingOnFailure(result) {
            cancellationCheckpoint()
            check(
                result.manifest.epubSpines.single().packageGraph.archive.plaintextDigest == digest,
            ) { "Local EPUB changed while it was being imported" }
        }
    }

    /** Runs [block] on bodies just staged, and gives up their receipts when it throws. */
    private inline fun releasingOnFailure(staged: ContentAcquisitionResult<*>, block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            foundation.blobStore.releasePendingReceipts(staged.publishedBlobs)
            throw failure
        }
    }

    private fun publicationKeyFor(stableImportId: String): PublicationKey = PublicationKey(
        Rfc9562UuidV5.derive(
            LOCAL_PUBLICATION_NAMESPACE,
            "shinsou-local-publication-v1:$stableImportId",
        ),
    )

    /**
     * Decodes a TXT and gives it its identity: Unicode text keeps the plain digest identity, a
     * legacy reading adds its encoding. The user is asked when more than one reading is plausible,
     * or when the file is already in the library and its reading is not plainly Unicode, which is
     * how a wrong reading is replaced. A user who asked to choose is asked whenever the bytes have
     * more than one reading: detection cannot tell every legacy text from valid UTF-8.
     */
    private fun resolveText(
        text: TextToResolve,
        request: LocalTextEncodingRequest,
        isInLibrary: (chapterUrl: String) -> Boolean,
        cancellationCheckpoint: () -> Unit,
    ): ResolvedText {
        val choice = request.chosen
        val hint = when (choice) {
            null -> TextEncodingHint.AUTO
            LocalTextEncoding.GB18030 -> TextEncodingHint.GB18030
            LocalTextEncoding.BIG5 -> TextEncodingHint.BIG5
            LocalTextEncoding.UTF_8 -> TextEncodingHint.UTF_8
        }
        val unicodeImportId = text.unicodeImportId
        val outcome = if (choice == null && request.askAlways) {
            decodeTextSourceOfferingEveryReading(text.bytes, cancellationCheckpoint)
        } else {
            decodeTextSource(text.bytes, hint, cancellationCheckpoint)
        }
        val importedChapters = LocalTextEncoding.entries.mapNotNull { encoding ->
            foundation.publications.find(publicationKeyFor(importIdOf(unicodeImportId, encoding)))
                ?.localChapterUrl()
                ?.takeIf(isInLibrary)
                ?.let { encoding to it }
        }.toMap()
        val chosen = outcome.decoded.sourceEncoding.toLocalTextEncoding()
        val readings = outcome as? TextDecodingOutcome.Legacy
            ?: readingsBesideImported(text, choice, chosen, importedChapters.keys, cancellationCheckpoint)
        val unclear = readings
            ?.takeIf { choice == null && it.candidates.size > 1 }
            ?.takeIf { request.askAlways || it.isUnclear(chosen, importedChapters.keys) }
        if (unclear != null) {
            throw LocalTextEncodingChoiceRequired(
                LocalTextEncodingQuestion(
                    documentName = text.documentName,
                    candidates = unclear.candidates.map { candidate ->
                        LocalTextEncodingCandidate(candidate.encoding.toLocalTextEncoding(), candidate.preview)
                    },
                    // For valid UTF-8 that looks like legacy text this is the legacy reading.
                    recommended = unclear.recommended.toLocalTextEncoding(),
                    imported = importedChapters.keys.toList(),
                ),
            )
        }
        // Plain Unicode text read without a choice leaves other readings the user chose alone.
        val replaces = choice != null || outcome is TextDecodingOutcome.Legacy
        return ResolvedText(
            stableImportId = importIdOf(unicodeImportId, chosen),
            decoded = outcome.decoded,
            supersededChapterUrls = importedChapters.filterKeys { replaces && it != chosen }.values.toList(),
        )
    }

    /**
     * Every reading of a file read plainly as Unicode without a choice, when the library has it
     * in another reading: the one the user corrected it to (GBK text that is also valid UTF-8,
     * say). Asking then keeps that reading ([dev.shinsou.kmp.contract.KeepImportedTextEncoding])
     * or lets the user choose, instead of bringing the wrong reading back. Null otherwise.
     */
    private fun readingsBesideImported(
        text: TextToResolve,
        choice: LocalTextEncoding?,
        chosen: LocalTextEncoding,
        imported: Set<LocalTextEncoding>,
        cancellationCheckpoint: () -> Unit,
    ): TextDecodingOutcome.Legacy? = if (choice == null && imported.any { it != chosen }) {
        decodeTextSourceOfferingEveryReading(text.bytes, cancellationCheckpoint) as? TextDecodingOutcome.Legacy
    } else {
        null
    }

    /**
     * The user should choose: no reading is clearly likelier, the library has another reading, or
     * it has this one and the reading is not plainly Unicode (asking is how a wrong one is fixed).
     */
    private fun TextDecodingOutcome.Legacy.isUnclear(chosen: LocalTextEncoding, imported: Set<LocalTextEncoding>): Boolean =
        !confident || imported.any { it != chosen } || imported.isNotEmpty() && chosen != LocalTextEncoding.UTF_8

    private fun importIdOf(unicodeImportId: String, encoding: LocalTextEncoding): String = when (encoding) {
        LocalTextEncoding.UTF_8 -> unicodeImportId
        LocalTextEncoding.GB18030,
        LocalTextEncoding.BIG5,
        -> "$unicodeImportId:${encoding.name.lowercase()}"
    }

    /** A legacy encoding as itself; any Unicode encoding as [LocalTextEncoding.UTF_8], one identity. */
    private fun SourceTextEncoding.toLocalTextEncoding(): LocalTextEncoding = when (this) {
        SourceTextEncoding.GB18030 -> LocalTextEncoding.GB18030
        SourceTextEncoding.BIG5 -> LocalTextEncoding.BIG5
        SourceTextEncoding.UTF_8,
        SourceTextEncoding.UTF_16_LE,
        SourceTextEncoding.UTF_16_BE,
        -> LocalTextEncoding.UTF_8
    }

    /** [prepare] for the pages of an image title. */
    fun prepareImageSequence(
        title: String,
        pages: List<LocalImagePageSource>,
        packageKind: LocalPackageKind,
        syncContentBodies: Boolean = false,
        cancellationCheckpoint: () -> Unit = {},
    ): PreparedTypedImport {
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
        reusablePublication(publicationKey)?.let { existing ->
            return alreadyCommitted(existing, syncContentBodies)
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
        val grant = rights.stagingGrant(target, LocalContentFormat.IMAGE_SEQUENCE)
        val importAccess = ContentAccessRequest(grant.grantId, rights.stagingScope(target, LocalContentFormat.IMAGE_SEQUENCE))
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
            ).also { staged -> releasingOnFailure(staged, cancellationCheckpoint) }
        } catch (failure: Throwable) {
            rights.revokeProvisional(grant)
            throw failure
        }
        return preparedCommit(acquired, grant, fallbackTitle, syncContentBodies)
    }

    /** The import of a publication that is committed already: its commit only checks it still is. */
    private fun alreadyCommitted(
        existing: Publication,
        syncContentBodies: Boolean,
        superseded: List<String> = emptyList(),
    ): PreparedTypedImport {
        val content = existing.toLocalTypedImport().copy(supersededChapterUrls = superseded)
        // The manifest whose bodies were found readable here, off the lock the commit holds.
        val verified = existing.acquisitions.singleOrNull()?.units?.singleOrNull()?.latestManifest
        return PreparedTypedImport(content, commitContent = { keepCommitted(content, syncContentBodies, verified) })
    }

    /**
     * True while the publication of [content] is committed with its body; queues its bodies for
     * sync when asked. A metadata-only backup restores a publication without its body, and one
     * reused like that would be a library title that cannot be opened. Runs under the repository
     * lock: the bodies were found readable before it ([reusablePublication]), and only the store's
     * records of them are checked again here ([LocalPublicationBodies.isAttached]).
     */
    private fun keepCommitted(
        content: LocalTypedImport,
        syncContentBodies: Boolean,
        verified: ContentManifest? = null,
    ): Boolean {
        val reference = TypedLocalChapterReference.parse(content.chapterUrl)
        val current = reference?.let { foundation.publications.find(it.publicationKey) }
            ?.takeIf { publication -> publication.hasAttachedBody(reference, verified) }
        if (current != null && syncContentBodies) queueExistingBodySync(current)
        return current != null
    }

    /**
     * Whether the unit [reference] names has its body attached, and is still the manifest whose
     * bodies were found readable before the lock ([verified]), when there was one.
     */
    private fun Publication.hasAttachedBody(reference: TypedLocalChapterReference, verified: ContentManifest?): Boolean =
        unitOf(reference)?.let { (acquisition, unit) ->
            (verified == null || unit.latestManifest == verified) && bodies.isAttached(this, acquisition, unit)
        } == true

    /** The acquisition and unit [reference] names, when this publication has them. */
    private fun Publication.unitOf(reference: TypedLocalChapterReference): Pair<Acquisition, PublicationUnit>? =
        acquisitions.singleOrNull { it.id == reference.acquisitionId }?.let { acquisition ->
            acquisition.units.singleOrNull { it.key == reference.unitKey }?.let { acquisition to it }
        }

    /**
     * The committed publication of [key] an import can reuse instead of staging its file again:
     * null when there is none, and when it is a local title left without its body, which the
     * import then gives back ([commitStaged]).
     */
    private fun reusablePublication(key: PublicationKey): Publication? =
        foundation.publications.find(key)?.takeUnless { publication ->
            val acquisition = publication.acquisitions.singleOrNull()
            val unit = acquisition?.units?.singleOrNull()
            acquisition != null && unit != null && !bodies.isReadable(acquisition, unit)
        }

    /** The typed chapter URL of a singular local publication, with or without its body. */
    private fun Publication.localChapterUrl(): String? = acquisitions.singleOrNull()?.let { acquisition ->
        acquisition.units.singleOrNull()?.let { unit -> TypedLocalChapterReference(key, acquisition.id, unit.key).encode() }
    }

    /**
     * The commit of an import whose bodies are staged. A restore since they were staged can have
     * brought its publication in. With its body, that one is kept, and the import's provisional
     * grant withdrawn. Without it (a metadata-only
     * backup), the staged body is given to it ([restoreBody]). With no publication, the staged
     * one is committed. Under another acquisition or unit than the staged one, nothing is
     * committed and the import is prepared again.
     */
    private fun commitStaged(staged: StagedImport, content: LocalTypedImport, syncContentBodies: Boolean): Boolean {
        val restored = foundation.publications.find(staged.publication.key)
            ?: return commitNew(staged, syncContentBodies)
        val reference = TypedLocalChapterReference.parse(content.chapterUrl)
        val unit = reference?.let { restored.unitOf(it) }
        return when {
            unit == null -> false
            // Found unreadable before the lock, as this very manifest (an attached body whose bytes
            // went missing, say): it is given its body back, attached or not.
            staged.copies?.manifest?.let { it == unit.second.latestManifest } == true ->
                restoreBody(BodylessUnit(restored, unit.first, unit.second), staged, syncContentBodies)
            // The restored title keeps its own grant: the one this import admitted goes unused,
            // and would stay admitted, since the import counts as committed ([LocalImportRights.revokeProvisional]
            // keeps it when it is the durable one).
            bodies.isAttached(restored, unit.first, unit.second) ->
                keepCommitted(content, syncContentBodies).also { rights.revokeProvisional(staged.grant) }
            else -> restoreBody(BodylessUnit(restored, unit.first, unit.second), staged, syncContentBodies)
        }
    }

    /** Gives [bodyless] the staged body ([LocalPublicationBodies.restore]); it is committed after. */
    private fun restoreBody(bodyless: BodylessUnit, staged: StagedImport, syncContentBodies: Boolean): Boolean = try {
        rights.requireOfflineStoreUnderRestoredGrant(bodyless, staged.grant)
        val grantUsed = bodies.restore(
            bodyless,
            staged.acquired.manifest,
            staged.grant,
            blobSyncJobs = { publication -> if (syncContentBodies) bodySyncJobs(publication) else emptyList() },
            prepared = staged.copies,
        )
        // A restore since the import admitted its grant brought the title's own: the import's goes unused.
        if (!grantUsed) rights.revokeProvisional(staged.grant)
        true
    } catch (failure: Throwable) {
        // As commitNew: without its commit the import's provisional authority ends.
        rights.revokeProvisional(staged.grant)
        throw failure
    }

    /** The committed unit of [content] when it is without its body; null otherwise. */
    private fun bodylessUnitOf(content: LocalTypedImport): BodylessUnit? {
        val reference = TypedLocalChapterReference.parse(content.chapterUrl)
        val publication = reference?.let { foundation.publications.find(it.publicationKey) }
        return if (reference == null || publication == null) {
            null
        } else {
            publication.unitOf(reference)
                ?.takeUnless { (acquisition, unit) -> bodies.isReadable(acquisition, unit) }
                ?.let { (acquisition, unit) -> BodylessUnit(publication, acquisition, unit) }
        }
    }

    /** What an import staged: the acquisition, its provisional grant and the publication to commit. */
    private class StagedImport(
        val acquired: ContentAcquisitionResult<*>,
        val grant: RightsGrant,
        val publication: Publication,
        /** The restored title's blobs published again before the repository lock, when it was without its body. */
        val copies: PreparedBodyCopies? = null,
    )

    /** Drafts the publication of staged bodies; its commit is left to [PreparedTypedImport.commit]. */
    private fun preparedCommit(
        acquired: ContentAcquisitionResult<*>,
        grant: RightsGrant,
        fallbackTitle: String,
        syncContentBodies: Boolean,
        superseded: List<String> = emptyList(),
    ): PreparedTypedImport = try {
        val resolvedTitle = when (val metadata = acquired.metadata) {
            is EpubPackageMetadata -> metadata.title?.trim()?.takeIf(String::isNotEmpty)
            is TextAcquisitionMetadata -> null
            else -> null
        }?.take(MAX_LOCAL_TITLE_CHARS) ?: fallbackTitle
        val publication = acquired.publicationDraft.copy(
            title = resolvedTitle,
            acquisitions = listOf(acquired.acquisition.copy(rightsGrantRef = grant.grantId)),
        )
        val content = publication.toLocalTypedImport().copy(supersededChapterUrls = superseded)
        // A title restored without its body gets it back by a copy of the staged bytes: made here,
        // off the repository lock its commit holds. The last step: nothing after it can fail.
        val copies = bodylessUnitOf(content)?.let { bodyless -> bodies.prepareCopies(bodyless, acquired.manifest) }
        PreparedTypedImport(
            content = content,
            commitContent = { commitStaged(StagedImport(acquired, grant, publication, copies), content, syncContentBodies) },
            revokeProvisionalRights = { rights.revokeProvisional(grant) },
            // Consumed by a commit that attached them; the others are given up.
            releaseStagedBlobs = {
                foundation.blobStore.releasePendingReceipts(acquired.publishedBlobs)
                copies?.release()
            },
        )
    } catch (failure: Throwable) {
        // The body has already been staged, so every failure through draft construction and the
        // durable metadata transaction must invalidate the provisional authority.
        foundation.blobStore.releasePendingReceipts(acquired.publishedBlobs)
        rights.revokeProvisional(grant)
        throw failure
    }

    /**
     * Commits a new title. The commit id is new each time, as [LocalPublicationBodies.restore]'s:
     * the store keeps the commits it has seen, a backup restore that drops the title keeps the
     * record of this one, and the same file imported again after it would be refused under its id.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun commitNew(staged: StagedImport, syncContentBodies: Boolean): Boolean = try {
        val publication = staged.publication
        val acquisition = publication.acquisitions.single()
        val owner = ContentManifestOwner(
            publicationKey = publication.key,
            acquisitionId = acquisition.id,
            unitKey = acquisition.units.single().key,
        )
        val bodySyncJobs = if (syncContentBodies) bodySyncJobs(publication) else emptyList()
        foundation.transactions.commit(
            ContentCommitBatch<LegacyOutboxDraft>(
                commitId = "local-import:${staged.acquired.manifest.manifestId}:${Uuid.random()}",
                receipts = staged.acquired.publishedBlobs,
                attachments = listOf(ManifestAttachment(owner, staged.acquired.manifest)),
                publications = listOf(ContentPublicationMutation(publication)),
                // A durable grant the import staged under is not written again.
                rightsGrants = listOfNotNull(staged.grant.takeIf { foundation.rightsGrants.find(it.grantId) == null })
                    .map(::ContentRightsGrantMutation),
                blobSyncJobs = bodySyncJobs,
            ),
        )
        true
    } catch (failure: Throwable) {
        // See preparedCommit: a failed metadata transaction invalidates the provisional authority.
        rights.revokeProvisional(staged.grant)
        throw failure
    }

    private fun queueExistingBodySync(publication: Publication) {
        val jobs = bodySyncJobs(publication)
        require(jobs.isNotEmpty()) { "The local publication has no rights-allowed body to synchronize" }
        val fingerprint = Sha256.hex(jobs.joinToString("|") { it.jobId }.encodeToByteArray())
        foundation.transactions.commit(
            ContentCommitBatch<LegacyOutboxDraft>(
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

    /**
     * The extension source an acquisition came from, for chapters an extension download put in
     * the library (they open through this typed path, not the extension one). Null for imports.
     */
    fun extensionSourceKey(chapterUrl: String): dev.shinsou.kmp.domain.model.SourceKey? {
        val reference = TypedLocalChapterReference.parse(chapterUrl) ?: return null
        val acquisition = foundation.publications.findOutline(reference.publicationKey)
            ?.acquisitions?.singleOrNull { it.id == reference.acquisitionId } ?: return null
        return (acquisition.origin as? dev.shinsou.kmp.domain.model.AcquisitionOrigin.ExtensionSource)
            ?.sourceBinding?.remoteEntityKey?.sourceKey
    }

    fun load(
        chapterUrl: String,
        remoteAssetScope: dev.shinsou.kmp.contract.ReaderRemoteImageFetcher? = null,
    ): TypedReaderContentSession? {
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
        val scope = dev.shinsou.kmp.domain.model.ReadingScope(
            schemaVersion = dev.shinsou.kmp.domain.model.ReadingScope.CURRENT_SCHEMA_VERSION,
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
            // Only the scope issued for this acquisition's own source: inline images in an
            // extension chapter load through that source's declared content origins.
            remoteAssetScope = remoteAssetScope?.takeIf { scope ->
                (acquisition.origin as? dev.shinsou.kmp.domain.model.AcquisitionOrigin.ExtensionSource)
                    ?.sourceBinding?.remoteEntityKey?.sourceKey == scope.sourceKey
            },
        )
    }

    /**
     * Body-free compatibility rows which can be recreated after a crash between the shared
     * content transaction and AppSnapshot persistence. Only the singular local graph currently
     * emitted by this controller is projected; extension and legacy-migration Publications have
     * their own compatibility owners.
     */
    // Runs on every launch: pick local titles from outlines, then read only those in full (their
    // manifests give the item count). Reading every title's manifests took seconds each time.
    fun legacyProjectionCandidates(): List<LocalTypedImport> = foundation.publications.allOutlines()
        .asSequence()
        .filter { publication ->
            publication.acquisitions.singleOrNull()?.origin.let { origin ->
                origin == dev.shinsou.kmp.domain.model.AcquisitionOrigin.LocalText ||
                    origin == dev.shinsou.kmp.domain.model.AcquisitionOrigin.LocalEpub ||
                    origin is dev.shinsou.kmp.domain.model.AcquisitionOrigin.LocalPackage
            }
        }
        .mapNotNull { outline -> foundation.publications.find(outline.key) }
        .mapNotNull { publication ->
            runCatching { publication.toLocalTypedImport() }
                .onFailure { Log.warn("Local", it) { "A stored local publication could not be listed; skipping it" } }
                .getOrNull()
        }
        .sortedBy(LocalTypedImport::chapterUrl)
        .toList()

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
                // A parse probe: a reference that does not validate answers null.
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

private fun ImportedDocument.copyBytesAndDigest(cancellationCheckpoint: () -> Unit): Pair<ByteArray, String> {
    require(byteSize <= Int.MAX_VALUE.toLong()) { "Local document cannot fit in memory" }
    val output = ByteArray(byteSize.toInt())
    val hashing = HashingSink.sha256(blackholeSink())
    val buffer = Buffer()
    return try {
        var offset = 0
        while (offset < output.size) {
            cancellationCheckpoint()
            val count = minOf(LOCAL_DOCUMENT_DIGEST_CHUNK_BYTES, output.size - offset)
            val chunk = source.read(offset.toLong(), count)
            require(chunk.size == count) { "Local document changed while it was being read" }
            chunk.copyInto(output, offset)
            buffer.write(chunk)
            hashing.write(buffer, chunk.size.toLong())
            offset += count
        }
        cancellationCheckpoint()
        output to hashing.hash.hex()
    } finally {
        hashing.close()
    }
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


private const val LOCAL_DOCUMENT_DIGEST_CHUNK_BYTES: Int = 64 * 1024

private fun StringBuilder.appendIdentityField(value: String) {
    append(value.encodeToByteArray().size)
    append(':')
    append(value)
}

internal const val LOCAL_TEXT_EXTENSION: String = "txt"
internal const val LOCAL_EPUB_EXTENSION: String = "epub"
internal val LOCAL_TYPED_CONTENT_EXTENSIONS: Set<String> = setOf(LOCAL_TEXT_EXTENSION, LOCAL_EPUB_EXTENSION)
