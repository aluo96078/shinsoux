package dev.shinsou.kmp.acquisition

import dev.shinsou.kmp.content.BlobPublishReceipt
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.Rfc9562UuidV5
import dev.shinsou.kmp.reader.ReadingScope

/** Stable caller-owned identity for a local import. File-system paths are deliberately excluded. */
public data class LocalAcquisitionTarget(
    val publicationKey: PublicationKey,
    val publicationTitle: String,
    val stableImportId: String,
    val unitTitle: String = publicationTitle,
    val contentRevision: Long = 0,
    val acquiredAtEpochMillis: Long? = null,
) {
    init {
        publicationKey.validate()
        require(stableImportId.isNotBlank() && stableImportId.length <= MAX_STABLE_IMPORT_ID_LENGTH) {
            "Stable import id must be non-blank and bounded"
        }
        require(stableImportId.none(Char::isISOControl)) { "Stable import id contains control characters" }
        require(publicationTitle.length <= MAX_TITLE_LENGTH && publicationTitle.none(Char::isISOControl)) {
            "Publication title must be bounded and printable"
        }
        require(unitTitle.length <= MAX_TITLE_LENGTH && unitTitle.none(Char::isISOControl)) {
            "Unit title must be bounded and printable"
        }
        require(contentRevision >= 0) { "Content revision must be non-negative" }
        require(acquiredAtEpochMillis == null || acquiredAtEpochMillis >= 0) {
            "Acquisition timestamp must be non-negative"
        }
    }
}

public enum class LocalContentFormat {
    PLAIN_TEXT,
    EPUB,
    IMAGE_SEQUENCE,
}

/**
 * Result of the non-persistent acquisition phase.
 *
 * [publishedBlobs] are one-use transaction capabilities. The service intentionally does not
 * attach them or write SQL; the composition root commits this draft with the shared content
 * transaction boundary.
 */
public class ContentAcquisitionResult<out Metadata>(
    public val publicationDraft: Publication,
    public val metadata: Metadata,
    publishedBlobs: List<BlobPublishReceipt>,
) {
    public val publishedBlobs: List<BlobPublishReceipt> = publishedBlobs.toList()
    public val acquisition: Acquisition get() = publicationDraft.acquisitions.single()
    public val unit: PublicationUnit get() = acquisition.units.single()
    public val manifest: ContentManifest get() = unit.manifestRevisions.single()
    public val representation: ContentRepresentation get() = manifest.representations.single()
    public val readingScope: ReadingScope
        get() = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publicationDraft.key,
            acquisitionId = acquisition.id,
            unitId = unit.key,
            contentRevision = manifest.contentRevision,
        )

    init {
        publicationDraft.validate()
        require(publicationDraft.acquisitions.size == 1) { "Acquisition draft must contain exactly one acquisition" }
        require(acquisition.units.size == 1) { "Acquisition draft must contain exactly one unit" }
        require(unit.manifestRevisions.size == 1) { "Acquisition draft must contain exactly one manifest" }
        require(manifest.representations.size == 1) { "Acquisition draft must contain exactly one representation" }
        require(publishedBlobs.isNotEmpty()) { "Acquisition must publish at least one blob" }
        require(publishedBlobs.map { it.commitToken }.distinct().size == publishedBlobs.size) {
            "Published blob receipts must be unique"
        }
        val expected = manifest.referencedBlobs.mapTo(linkedSetOf()) { it.blobId }
        val actual = publishedBlobs.mapTo(linkedSetOf()) { it.reference.blobId }
        require(actual == expected) { "Published blob receipts must exactly cover the manifest" }
    }
}

/** Cross-platform deterministic identities for local acquisition artifacts and locators. */
public class LocalAcquisitionIdentityDeriver(
    private val namespace: MigrationNamespaceId = DEFAULT_LOCAL_ACQUISITION_NAMESPACE,
) {
    public fun acquisitionId(target: LocalAcquisitionTarget, format: LocalContentFormat): String =
        derive(target, format, "acquisition")

    public fun unitId(target: LocalAcquisitionTarget, format: LocalContentFormat): String =
        derive(target, format, "unit")

    public fun representationId(target: LocalAcquisitionTarget, format: LocalContentFormat): String =
        derive(target, format, "representation")

    /** Deterministic host grant paired atomically with the acquisition and its manifest. */
    public fun rightsGrantId(target: LocalAcquisitionTarget, format: LocalContentFormat): String =
        derive(target, format, "rights-grant")

    public fun resourceId(
        target: LocalAcquisitionTarget,
        format: LocalContentFormat,
        logicalPath: String,
    ): String = "resource-${derive(target, format, "resource", logicalPath)}"

    /**
     * Plans an immutable blob identity before a body is staged. Including every authoritative
     * blob-reference scalar prevents a changed body or media declaration from reusing an existing
     * immutable id while still making an exact acquisition replay deterministic.
     */
    internal fun blobId(
        target: LocalAcquisitionTarget,
        format: LocalContentFormat,
        logicalPath: String,
        plaintextDigest: String,
        byteSize: Long,
        mediaType: String,
    ): String = derive(
        target,
        format,
        "blob",
        logicalPath,
        plaintextDigest,
        byteSize.toString(),
        mediaType,
    )

    public fun blockId(target: LocalAcquisitionTarget, contentDigest: String, occurrence: Int): String =
        "block-${derive(target, LocalContentFormat.PLAIN_TEXT, "block", contentDigest, occurrence.toString())}"

    public fun chapterId(target: LocalAcquisitionTarget, title: String, occurrence: Int): String =
        "chapter-${derive(target, LocalContentFormat.PLAIN_TEXT, "chapter", title, occurrence.toString())}"

    public fun spineId(target: LocalAcquisitionTarget, manifestIdRef: String, occurrence: Int): String =
        "spine-${derive(target, LocalContentFormat.EPUB, "spine", manifestIdRef, occurrence.toString())}"

    public fun manifestId(
        target: LocalAcquisitionTarget,
        format: LocalContentFormat,
        contentDigest: String,
    ): String = derive(
        target,
        format,
        "manifest",
        target.contentRevision.toString(),
        contentDigest,
    )

    private fun derive(
        target: LocalAcquisitionTarget,
        format: LocalContentFormat,
        role: String,
        vararg parts: String,
    ): String {
        val fields = buildList {
            add(IDENTITY_SCHEMA)
            add(format.name)
            add(role)
            add(target.publicationKey.value)
            add(target.stableImportId)
            addAll(parts)
        }
        val canonicalName = buildString {
            fields.forEach { field ->
                append(field.encodeToByteArray().size)
                append(':')
                append(field)
            }
        }
        return Rfc9562UuidV5.derive(namespace, canonicalName)
    }

    private companion object {
        const val IDENTITY_SCHEMA: String = "shinsou-local-acquisition-v1"
    }
}

public val DEFAULT_LOCAL_ACQUISITION_NAMESPACE: MigrationNamespaceId =
    MigrationNamespaceId("78549f68-401f-5aac-9c08-7f11faf4af50")

private const val MAX_STABLE_IMPORT_ID_LENGTH: Int = 4096
private const val MAX_TITLE_LENGTH: Int = 4096
