package dev.shinsou.kmp.local

import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.blob.BlobPublishReceipt
import dev.shinsou.kmp.content.blob.BlobReadLease
import dev.shinsou.kmp.content.blob.claimOrPublish
import dev.shinsou.kmp.content.blob.ContentManifestOwner
import dev.shinsou.kmp.content.blob.ManifestAttachment
import dev.shinsou.kmp.content.transaction.ContentBlobSyncJobMutation
import dev.shinsou.kmp.content.transaction.ContentCommitBatch
import dev.shinsou.kmp.content.transaction.ContentPublicationMutation
import dev.shinsou.kmp.content.transaction.ContentRightsGrantMutation
import dev.shinsou.kmp.content.transaction.LegacyOutboxDraft
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionAvailability
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.rights.RightsGrant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The blobs of a restored title published again ([LocalPublicationBodies.prepareCopies]) for the
 * commit that gives it its body: held until that commit consumes them or they are given up, once.
 */
internal class PreparedBodyCopies(
    private val foundation: ContentFoundationRuntime,
    /** The restored manifest the copies were made for, whole: its id alone can be restored again with other content. */
    val manifest: ContentManifest,
    val receipts: List<BlobPublishReceipt>,
) {
    private var done = false

    /** Whether every receipt is still pending: none was consumed or given up meanwhile. */
    fun stillPending(): Boolean {
        val pending = foundation.blobStore.pendingReceipts()
        return !done && receipts.all { receipt -> pending.any { it === receipt } }
    }

    /** A commit consumed the receipts: there is nothing left to give up. */
    fun consumed() {
        done = true
    }

    /** Gives the receipts up, unless they were already, or consumed. */
    fun release() {
        if (done) return
        done = true
        foundation.blobStore.releasePendingReceipts(receipts)
    }
}

/** A committed publication's [unit] whose body the device does not hold. */
internal class BodylessUnit(val publication: Publication, val acquisition: Acquisition, val unit: PublicationUnit)

/**
 * Whether a committed local publication still has its body, and how one that lost it to a
 * metadata-only backup restore gets it back from an import of the same file.
 */
internal class LocalPublicationBodies(private val foundation: ContentFoundationRuntime) {
    /**
     * Whether [unit] of [acquisition] can be opened and will stay so. A restore that brings a
     * publication without its bodies keeps the manifest but marks the acquisition as not fully
     * available and attaches no blob: one this device still holds is then unreferenced and goes
     * with the next cleanup, so finding the blobs is not enough. A local body purge leaves no
     * manifest at all.
     */
    fun isReadable(acquisition: Acquisition, unit: PublicationUnit): Boolean {
        val manifest = unit.latestManifest
        return manifest != null && acquisition.availability == AcquisitionAvailability.AVAILABLE &&
            manifest.referencedBlobs.all(foundation.blobStore::contains)
    }

    /**
     * [isReadable] as far as the store's own records tell, without reading a body: the unit's
     * manifest is attached and its acquisition available. What a commit under the repository
     * lock checks again, after [isReadable] found the bodies outside it (opening a file-backed
     * body reads it whole to check it).
     */
    fun isAttached(publication: Publication, acquisition: Acquisition, unit: PublicationUnit): Boolean {
        val manifest = unit.latestManifest
        val owner = ContentManifestOwner(publication.key, acquisition.id, unit.key)
        return manifest != null && acquisition.availability == AcquisitionAvailability.AVAILABLE &&
            foundation.blobStore.attached(owner, manifest) != null
    }

    /**
     * Gives [bodyless] the body an import staged for the same file ([staged], its manifest). The
     * publication graph is immutable, so the restored manifest stays and the staged bytes are
     * published again under the blobs it names (a blob this device still holds is claimed
     * instead); then the manifest is attached and the acquisition marked available, in one
     * commit. The commit id is new each time: the store remembers the commit of the import that
     * first brought this body in, and a title can lose its body to a restore more than once. It
     * is random, not the time: two restores of one title can fall in the same millisecond (a
     * clock set back, a fixed clock), and the store refuses a second commit under a known id.
     * Nothing looks this commit up by its id, so it does not need to be derivable.
     * [grant] is the import's, committed only when the restored acquisition names no grant of its
     * own and it is not durable already; true when the title now refers to it, and false when it
     * goes unused (a restore since the import admitted it brought the title's own grant), for the
     * caller to withdraw. Throws when the staged file is not the restored title's; the receipts
     * of the blobs published for a commit that then does not happen are given up first.
     *
     * The copies are [prepared] before the caller took the repository lock, where this runs, when
     * the title was already without its body then: they are used while still the copies of this
     * manifest and still pending; otherwise they are given up and made here.
     */
    fun restore(
        bodyless: BodylessUnit,
        staged: ContentManifest,
        grant: RightsGrant,
        prepared: PreparedBodyCopies? = null,
        blobSyncJobs: (Publication) -> List<ContentBlobSyncJobMutation>,
    ): Boolean {
        val usable = prepared?.takeIf { it.manifest == bodyless.unit.latestManifest && it.stillPending() }
        if (usable == null) prepared?.release()
        val copies = usable ?: prepareCopies(bodyless, staged)
        try {
            return commitRestored(bodyless, checkNotNull(bodyless.unit.latestManifest), grant, copies.receipts, blobSyncJobs)
                .also { copies.consumed() }
        } finally {
            // Whatever ended it (cancellation too, which goes on): a receipt left pending would
            // refuse the next publish of its blob until the app restarts.
            copies.release()
        }
    }

    /**
     * Publishes again, under the blobs [bodyless]'s restored manifest names, the bytes an import
     * staged for the same file ([staged]); a blob this device still holds is claimed instead.
     * Streams whole bodies, so it is done before the repository lock where it can be. Throws when
     * the staged file is not the restored title's, and gives up what it published when it fails.
     */
    fun prepareCopies(bodyless: BodylessUnit, staged: ContentManifest): PreparedBodyCopies {
        val manifest = bodyless.unit.latestManifest
        val stagedBlobs = staged.referencedBlobs.associateBy { it.contentIdentity() }
        // Matched before anything is published: a file that is not this title's leaves nothing behind.
        val sources = manifest?.referencedBlobs.orEmpty().distinctBy(BlobRef::blobId)
            .map { blob -> blob to stagedBlobs[blob.contentIdentity()] }
        check(manifest != null && sources.none { (_, source) -> source == null }) {
            "The library has this title from a backup without its content, and this file does not match it"
        }
        val receipts = ArrayList<BlobPublishReceipt>()
        var prepared = false
        try {
            sources.forEach { (blob, source) ->
                // Another operation (a backup restore) can publish the blob meanwhile: it is claimed then.
                receipts += foundation.blobStore.claimOrPublish(blob) { republish(checkNotNull(source), blob) }
            }
            return PreparedBodyCopies(foundation, manifest, receipts).also { prepared = true }
        } finally {
            if (!prepared) foundation.blobStore.releasePendingReceipts(receipts)
        }
    }

    /** The commit of [restore]: the manifest attached again and the acquisition available. */
    @OptIn(ExperimentalUuidApi::class)
    private fun commitRestored(
        bodyless: BodylessUnit,
        manifest: ContentManifest,
        grant: RightsGrant,
        receipts: List<BlobPublishReceipt>,
        blobSyncJobs: (Publication) -> List<ContentBlobSyncJobMutation>,
    ): Boolean {
        val ownReference = bodyless.acquisition.rightsGrantRef
        // The title refers to the import's grant unless the restore gave it another of its own.
        val usesGrant = ownReference == null || ownReference == grant.grantId
        val grantCommitted = usesGrant && foundation.rightsGrants.find(grant.grantId) == null
        val publication = bodyless.publication.copy(
            acquisitions = bodyless.publication.acquisitions.map { acquisition ->
                if (acquisition.id != bodyless.acquisition.id) {
                    acquisition
                } else {
                    acquisition.copy(
                        availability = AcquisitionAvailability.AVAILABLE,
                        rightsGrantRef = ownReference ?: grant.grantId,
                    )
                }
            },
        )
        val owner = ContentManifestOwner(publication.key, bodyless.acquisition.id, bodyless.unit.key)
        foundation.transactions.commit(
            ContentCommitBatch<LegacyOutboxDraft>(
                commitId = "local-import-body:${manifest.manifestId}:${Uuid.random()}",
                receipts = receipts,
                attachments = listOf(ManifestAttachment(owner, manifest)),
                publications = listOf(ContentPublicationMutation(publication)),
                // The restore brought the title's grant with it; one it did not bring is the import's.
                rightsGrants = listOfNotNull(grant.takeIf { grantCommitted }).map(::ContentRightsGrantMutation),
                blobSyncJobs = blobSyncJobs(publication),
            ),
        )
        return usesGrant
    }

    /** What makes two blobs the same bytes, whatever their ids. */
    private fun BlobRef.contentIdentity(): List<Any> = listOf(digestAlgorithm, plaintextDigest, byteSize, mediaType)

    /** Publishes the bytes of the staged [source] as [target], a blob of the same content. */
    private fun republish(source: BlobRef, target: BlobRef): BlobPublishReceipt {
        val stage = foundation.blobStore.beginStage(target.byteSize, target.mediaType)
        var reader: BlobReadLease? = null
        var published = false
        return try {
            reader = checkNotNull(foundation.blobStore.openRead(source)) { "The staged body is missing" }
            while (true) stage.append(reader.readChunk() ?: break)
            foundation.blobStore.publish(stage.seal(target)).also { published = true }
        } finally {
            if (!published) stage.abort()
            reader?.close()
        }
    }
}
