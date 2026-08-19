package dev.shinsou.kmp.local

import dev.shinsou.kmp.files.AppFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Commit marker for an imported local chapter.
 *
 * Page files are written first and this manifest is atomically published last. A local chapter is
 * readable only when every controlled page name in the manifest exists and no other image page is
 * present in the chapter directory.
 */
@Serializable
internal data class LocalContentManifest(
    val version: Int,
    val pageCount: Int,
    val pages: List<String>,
) {
    companion object {
        const val CURRENT_VERSION: Int = 2
    }
}

internal object LocalContentManifests {
    const val FILE_NAME: String = "manifest.txt"

    private const val MAX_MANIFEST_BYTES = 1_048_576
    private val pageName = Regex("page-([0-9]{6})\\.(jpg|jpeg|png|webp|gif|avif|heic|bmp)")
    private val legacyManifest = Regex("version=1\\npageCount=([1-9][0-9]{0,4})\\n")
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
        explicitNulls = false
    }

    suspend fun publish(
        fileSystem: AppFileSystem,
        directory: String,
        pages: List<String>,
    ): LocalContentManifest {
        val manifest = LocalContentManifest(
            version = LocalContentManifest.CURRENT_VERSION,
            pageCount = pages.size,
            pages = pages.toList(),
        )
        require(isStructurallyValid(manifest)) { "Invalid local-content manifest" }
        require(filesMatchManifest(fileSystem, directory, manifest)) {
            "Local page files do not match the import manifest"
        }
        val encoded = json.encodeToString(manifest).encodeToByteArray()
        require(encoded.size <= MAX_MANIFEST_BYTES) { "Local-content manifest is too large" }
        fileSystem.writeAtomically("$directory/$FILE_NAME", encoded)
        return manifest
    }

    suspend fun readValid(
        fileSystem: AppFileSystem,
        directory: String,
    ): LocalContentManifest? {
        val bytes = fileSystem.read("$directory/$FILE_NAME")
            ?.takeIf { it.isNotEmpty() && it.size <= MAX_MANIFEST_BYTES }
            ?: return null
        val encoded = bytes.decodeToString()
        val current = runCatching { json.decodeFromString<LocalContentManifest>(encoded) }.getOrNull()
        if (current != null && isStructurallyValid(current) && filesMatchManifest(fileSystem, directory, current)) {
            return current
        }

        // Previous builds wrote exactly two lines. Upgrade only when that canonical marker and the
        // complete, contiguous controlled page set agree; malformed or partial legacy data is never
        // treated as readable content.
        val legacyCount = legacyManifest.matchEntire(encoded)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
            ?.takeIf { it in 1..MAX_ARCHIVE_ENTRIES }
            ?: return null
        val legacyPages = naturalSortedPaths(
            fileSystem.list(directory).filter { isSupportedLocalImage(it.substringAfterLast('/')) },
        ).map { it.substringAfterLast('/') }
        val migrated = LocalContentManifest(
            version = LocalContentManifest.CURRENT_VERSION,
            pageCount = legacyCount,
            pages = legacyPages,
        )
        if (!isStructurallyValid(migrated) || !filesMatchManifest(fileSystem, directory, migrated)) return null

        val migratedBytes = json.encodeToString(migrated).encodeToByteArray()
        fileSystem.writeAtomically("$directory/$FILE_NAME", migratedBytes)
        return migrated
    }

    private fun isStructurallyValid(manifest: LocalContentManifest): Boolean {
        if (manifest.version != LocalContentManifest.CURRENT_VERSION) return false
        if (manifest.pageCount !in 1..MAX_ARCHIVE_ENTRIES) return false
        if (manifest.pages.size != manifest.pageCount || manifest.pages.toSet().size != manifest.pageCount) return false
        return manifest.pages.withIndex().all { (index, fileName) ->
            val match = pageName.matchEntire(fileName) ?: return@all false
            match.groupValues[1].toIntOrNull() == index + 1
        }
    }

    private suspend fun filesMatchManifest(
        fileSystem: AppFileSystem,
        directory: String,
        manifest: LocalContentManifest,
    ): Boolean {
        val expected = manifest.pages.mapTo(linkedSetOf()) { "$directory/$it" }
        val actual = fileSystem.list(directory).filterTo(linkedSetOf()) {
            isSupportedLocalImage(it.substringAfterLast('/'))
        }
        return actual == expected && expected.all { fileSystem.exists(it) }
    }
}
