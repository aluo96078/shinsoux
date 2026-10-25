package dev.shinsou.kmp.download

import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.reader.ReaderImageTransform
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Commit marker for a completely downloaded chapter.
 *
 * Page files are written first and this manifest is atomically published last. The reader only
 * trusts files named by a valid manifest, so interrupted downloads can never masquerade as an
 * offline chapter and clearing the visible queue does not discard completion metadata.
 */
@Serializable
internal data class DownloadCompletionManifest(
    val version: Int = CURRENT_VERSION,
    val pageCount: Int,
    val pages: List<DownloadCompletionPage>,
) {
    companion object {
        const val CURRENT_VERSION: Int = 2
    }
}

@Serializable
internal data class DownloadCompletionPage(
    val index: Int,
    val fileName: String,
    val transformFileName: String? = null,
    /** Raw downloaded body integrity, required by v2 manifests. */
    val byteSize: Long? = null,
    val plaintextDigest: String? = null,
)

internal object DownloadCompletionManifests {
    private const val FILE_NAME = "completion-v1.json"
    private const val MAX_PAGES = 10_000
    private const val MAX_MANIFEST_BYTES = 1_048_576
    private val imageName = Regex("page-(0|[1-9][0-9]{0,6})\\.(jpg|jpeg|png|webp|gif|avif)")
    private val transformName = Regex("page-(0|[1-9][0-9]{0,6})\\.transform")
    private val sha256 = Regex("[0-9a-f]{64}")

    fun directory(mangaId: Long, chapterId: Long): String = "downloads/$mangaId/$chapterId"

    private fun manifestPath(mangaId: Long, chapterId: Long): String =
        "${directory(mangaId, chapterId)}/$FILE_NAME"

    suspend fun publish(
        fileSystem: AppFileSystem,
        mangaId: Long,
        chapterId: Long,
        pages: List<DownloadCompletionPage>,
    ): DownloadCompletionManifest {
        val manifest = DownloadCompletionManifest(pageCount = pages.size, pages = pages.sortedBy { it.index })
        require(isStructurallyValid(manifest)) { "Invalid completed-download manifest" }
        require(filesMatchManifest(fileSystem, mangaId, chapterId, manifest)) {
            "Downloaded page files do not match the completion manifest"
        }
        val encoded = AppSnapshotJson.format.encodeToString(manifest).encodeToByteArray()
        require(encoded.size <= MAX_MANIFEST_BYTES) { "Completed-download manifest is too large" }
        fileSystem.writeAtomically(manifestPath(mangaId, chapterId), encoded)
        return manifest
    }

    suspend fun readValid(
        fileSystem: AppFileSystem,
        mangaId: Long,
        chapterId: Long,
    ): DownloadCompletionManifest? {
        val bytes = fileSystem.read(manifestPath(mangaId, chapterId))
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_MANIFEST_BYTES }
            ?: return null
        val decoded = runCatching {
            AppSnapshotJson.format.decodeFromString<DownloadCompletionManifest>(bytes.decodeToString())
        }.getOrNull() ?: return null
        if (!isStructurallyValid(decoded)) return null
        if (!filesMatchManifest(fileSystem, mangaId, chapterId, decoded)) return null
        if (decoded.version == DownloadCompletionManifest.CURRENT_VERSION) return decoded

        // V1 recorded only names. Verify its exact file set first, then atomically replace it with
        // a digest-bearing v2 marker so every subsequent reopen detects truncation/corruption.
        val directory = directory(mangaId, chapterId)
        val upgradedPages = decoded.pages.map { page ->
            val body = fileSystem.read("$directory/${page.fileName}")
                ?.takeIf(ByteArray::isNotEmpty)
                ?: return null
            page.copy(
                byteSize = body.size.toLong(),
                plaintextDigest = Sha256.hex(body),
            )
        }
        return runCatching {
            publish(fileSystem, mangaId, chapterId, upgradedPages)
        }.getOrNull()
    }

    /** One-time upgrade for queues completed by builds that predate the atomic manifest. */
    suspend fun readValidOrMigrateLegacy(
        fileSystem: AppFileSystem,
        mangaId: Long,
        chapterId: Long,
        expectedPageCount: Int,
    ): DownloadCompletionManifest? {
        readValid(fileSystem, mangaId, chapterId)?.let { return it }
        if (expectedPageCount !in 1..MAX_PAGES || fileSystem.exists(manifestPath(mangaId, chapterId))) {
            return null
        }
        val directory = directory(mangaId, chapterId)
        val listed = fileSystem.list(directory)
        val images = listed.filter { imageName.matches(it.substringAfterLast('/')) }
        if (images.size != expectedPageCount) return null
        val transforms = listed.filter { transformName.matches(it.substringAfterLast('/')) }
            .associateBy { it.substringAfterLast('/').substringBeforeLast('.') }
        val pages = images.mapNotNull { path ->
            val fileName = path.substringAfterLast('/')
            val match = imageName.matchEntire(fileName) ?: return@mapNotNull null
            val index = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val stem = fileName.substringBeforeLast('.')
            val body = fileSystem.read(path)?.takeIf(ByteArray::isNotEmpty) ?: return@mapNotNull null
            DownloadCompletionPage(
                index = index,
                fileName = fileName,
                transformFileName = transforms[stem]?.substringAfterLast('/'),
                byteSize = body.size.toLong(),
                plaintextDigest = Sha256.hex(body),
            )
        }
        if (pages.size != expectedPageCount || transforms.size != pages.count { it.transformFileName != null }) {
            return null
        }
        return runCatching { publish(fileSystem, mangaId, chapterId, pages) }.getOrNull()
    }

    private fun isStructurallyValid(manifest: DownloadCompletionManifest): Boolean {
        if (manifest.version !in 1..DownloadCompletionManifest.CURRENT_VERSION) return false
        if (manifest.pageCount !in 1..MAX_PAGES || manifest.pages.size != manifest.pageCount) return false
        if (manifest.pages.map { it.index }.toSet().size != manifest.pageCount) return false
        if (manifest.pages.map { it.fileName }.toSet().size != manifest.pageCount) return false
        return manifest.pages.all { page ->
            if (page.index !in 0..9_999_999) return@all false
            val match = imageName.matchEntire(page.fileName) ?: return@all false
            if (match.groupValues[1].toIntOrNull() != page.index) return@all false
            val validTransform = page.transformFileName?.let { transform ->
                val transformMatch = transformName.matchEntire(transform) ?: return@all false
                transformMatch.groupValues[1].toIntOrNull() == page.index
            } ?: true
            validTransform && when (manifest.version) {
                1 -> page.byteSize == null && page.plaintextDigest == null
                DownloadCompletionManifest.CURRENT_VERSION ->
                    page.byteSize != null && page.byteSize in 1..Int.MAX_VALUE.toLong() &&
                        page.plaintextDigest != null && sha256.matches(page.plaintextDigest)
                else -> false
            }
        }
    }

    private suspend fun filesMatchManifest(
        fileSystem: AppFileSystem,
        mangaId: Long,
        chapterId: Long,
        manifest: DownloadCompletionManifest,
    ): Boolean {
        val directory = directory(mangaId, chapterId)
        val expectedImages = manifest.pages.mapTo(linkedSetOf()) { "$directory/${it.fileName}" }
        val expectedTransforms = manifest.pages.mapNotNullTo(linkedSetOf()) {
            it.transformFileName?.let { name -> "$directory/$name" }
        }
        val listed = fileSystem.list(directory)
        val actualImages = listed.filterTo(linkedSetOf()) {
            imageName.matches(it.substringAfterLast('/'))
        }
        val actualTransforms = listed.filterTo(linkedSetOf()) {
            transformName.matches(it.substringAfterLast('/'))
        }
        if (actualImages != expectedImages || actualTransforms != expectedTransforms) return false
        // Validate sequentially: retaining every chapter page at once would turn a reopen check
        // into an unbounded aggregate allocation for long/high-resolution chapters.
        for (page in manifest.pages) {
            val body = fileSystem.read("$directory/${page.fileName}") ?: return false
            if (body.isEmpty()) return false
            if (
                manifest.version == DownloadCompletionManifest.CURRENT_VERSION &&
                (body.size.toLong() != page.byteSize || Sha256.hex(body) != page.plaintextDigest)
            ) return false
        }
        return expectedTransforms.all { path ->
            val sidecar = fileSystem.read(path) ?: return@all false
            ReaderImageTransform.decodeSidecar(sidecar) != null
        }
    }
}
