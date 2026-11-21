package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.files.AppFileSystem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtensionPublicationCacheV2Test {
    private val source = SourceKey(2, "fixture.extension", "opaque-source")
    private val other = SourceKey(2, "fixture.extension", "other-source")

    private fun entry(title: String, vararg units: String) = CachedExtensionPublicationV2(
        sourceCanonicalId = source.canonicalId,
        publication = RemotePublicationV2("publication", title, description = "About $title"),
        units = units.map { RemoteUnitV2(it, "Chapter $it") },
        updatedAtEpochMillis = 1,
    )

    @Test
    fun roundTripsAndIsReplacedByTheLatestLoad() = runBlocking {
        val files = MemoryFileSystem()
        val cache = ExtensionPublicationCacheV2(files)
        cache.write(source, entry("Old", "1"))
        cache.write(source, entry("New", "1", "2"))

        assertEquals(entry("New", "1", "2"), cache.read(source, "publication"))
        assertNull(cache.read(other, "publication"))
        assertNull(cache.read(source, "missing"))

        cache.delete(source, "publication")
        assertNull(cache.read(source, "publication"))
    }

    @Test
    fun damagedFilesAreACacheMiss() = runBlocking {
        val files = MemoryFileSystem()
        val cache = ExtensionPublicationCacheV2(files)
        cache.write(source, entry("Title", "1"))
        files.corruptAll()

        assertNull(cache.read(source, "publication"))
    }

    private class MemoryFileSystem : AppFileSystem {
        private val values = linkedMapOf<String, ByteArray>()

        fun corruptAll() = values.keys.forEach { values[it] = "{not json".encodeToByteArray() }

        override suspend fun write(relativePath: String, bytes: ByteArray) {
            values[relativePath] = bytes
        }

        override suspend fun read(relativePath: String): ByteArray? = values[relativePath]
        override suspend fun exists(relativePath: String): Boolean = relativePath in values
        override suspend fun delete(relativePath: String): Boolean = values.remove(relativePath) != null
        override suspend fun deleteTree(relativeDirectory: String): Boolean = false
        override suspend fun list(relativeDirectory: String): List<String> = emptyList()
        override fun uri(relativePath: String): String = "memory://$relativePath"
    }
}
