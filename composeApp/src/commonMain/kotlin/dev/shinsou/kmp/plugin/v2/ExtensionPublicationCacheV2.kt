package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Details and the full unit list of a library title, as last seen from its source. */
@Serializable
public data class CachedExtensionPublicationV2(
    val sourceCanonicalId: String,
    val publication: RemotePublicationV2,
    val units: List<RemoteUnitV2>,
    val updatedAtEpochMillis: Long,
)

/**
 * Keeps library titles usable without the source: the detail screen shows the cached copy at
 * once and offline, and replaces it whenever a fresh load succeeds. Holds metadata only; chapter
 * bodies live in the content foundation.
 */
public class ExtensionPublicationCacheV2(private val fileSystem: AppFileSystem) {
    public suspend fun read(sourceKey: SourceKey, remotePublicationId: String): CachedExtensionPublicationV2? {
        val bytes = fileSystem.read(path(sourceKey, remotePublicationId)) ?: return null
        // A damaged or outdated file is just a cache miss; the next successful load rewrites it.
        return runCatching { json.decodeFromString<CachedExtensionPublicationV2>(bytes.decodeToString()) }
            .getOrNull()
            ?.takeIf {
                it.sourceCanonicalId == sourceKey.canonicalId && it.publication.remoteId == remotePublicationId
            }
            ?.let { it.copy(publication = it.publication.withoutScriptObjectText()) }
    }

    public suspend fun write(sourceKey: SourceKey, entry: CachedExtensionPublicationV2) {
        require(entry.sourceCanonicalId == sourceKey.canonicalId) { "Cached publication source changed" }
        fileSystem.writeAtomically(
            path(sourceKey, entry.publication.remoteId),
            json.encodeToString(CachedExtensionPublicationV2.serializer(), entry).encodeToByteArray(),
        )
    }

    public suspend fun delete(sourceKey: SourceKey, remotePublicationId: String) {
        read(sourceKey, remotePublicationId)?.publication?.thumbnailUrl?.let { fileSystem.delete(coverPath(sourceKey, it)) }
        fileSystem.delete(path(sourceKey, remotePublicationId))
    }

    /** Cover bytes of a library title, so its cover still shows when the source is unreachable. */
    public suspend fun readCover(sourceKey: SourceKey, url: String): ByteArray? =
        fileSystem.read(coverPath(sourceKey, url))?.takeIf { it.isNotEmpty() }

    public suspend fun hasCover(sourceKey: SourceKey, url: String): Boolean =
        fileSystem.exists(coverPath(sourceKey, url))

    public suspend fun writeCover(sourceKey: SourceKey, url: String, bytes: ByteArray) {
        if (bytes.isEmpty() || bytes.size > MAX_COVER_BYTES) return
        fileSystem.writeAtomically(coverPath(sourceKey, url), bytes)
    }

    private fun coverPath(sourceKey: SourceKey, url: String): String =
        "$DIRECTORY/covers/${Sha256.hex("${sourceKey.canonicalId}\u0000$url".encodeToByteArray())}"

    private fun path(sourceKey: SourceKey, remotePublicationId: String): String =
        "$DIRECTORY/${Sha256.hex("${sourceKey.canonicalId}\u0000$remotePublicationId".encodeToByteArray())}.json"

    private companion object {
        const val DIRECTORY = "extension-v2/publication-cache"
        const val MAX_COVER_BYTES = 4 * 1024 * 1024
        val json = Json { ignoreUnknownKeys = true }
    }
}
