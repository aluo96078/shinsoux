package dev.shinsou.kmp.files

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.BufferedSink
import okio.buffer
import okio.sink

class DesktopAppFileSystem(
    root: Path = DesktopAppDirectories.contentRoot,
    /** Where the blocking file calls run. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppFileSystem {
    private val root = root.normalize().toAbsolutePath()

    override suspend fun write(relativePath: String, bytes: ByteArray): Unit = withContext(dispatcher) {
        path(relativePath).also { Files.createDirectories(it.parent) }.let { Files.write(it, bytes) }
    }

    override suspend fun writeAtomically(relativePath: String, bytes: ByteArray): Unit =
        withContext(dispatcher) {
            replaceAtomically(relativePath) { temporary -> Files.write(temporary, bytes) }
        }

    override suspend fun writeAtomically(relativePath: String, writer: (BufferedSink) -> Unit): Long =
        withContext(dispatcher) {
            replaceAtomically(relativePath) { temporary ->
                temporary.sink().buffer().use(writer)
                Files.size(temporary)
            }
        }

    /** Writes a temporary file beside [relativePath] with [write], then moves it into place. */
    private fun <T> replaceAtomically(relativePath: String, write: (Path) -> T): T {
        val destination = path(relativePath)
        Files.createDirectories(destination.parent)
        val temporary = Files.createTempFile(destination.parent, ".shinsou-${destination.fileName}-", ".tmp")
        try {
            val result = write(temporary)
            try {
                Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                // Atomic moves are optional on this file system; a plain replace is enough.
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
            return result
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override suspend fun read(relativePath: String): ByteArray? = withContext(dispatcher) {
        path(relativePath).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
    }

    override suspend fun exists(relativePath: String): Boolean = withContext(dispatcher) {
        Files.exists(path(relativePath))
    }

    override suspend fun delete(relativePath: String): Boolean = withContext(dispatcher) {
        Files.deleteIfExists(path(relativePath))
    }

    override suspend fun deleteTree(relativeDirectory: String): Boolean = withContext(dispatcher) {
        val directory = path(relativeDirectory)
        if (!Files.exists(directory)) return@withContext false
        Files.walk(directory).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        true
    }

    override suspend fun list(relativeDirectory: String): List<String> = withContext(dispatcher) {
        val directory = path(relativeDirectory)
        if (!Files.isDirectory(directory)) return@withContext emptyList()
        Files.walk(directory).use { stream ->
            stream.filter(Files::isRegularFile).map { root.relativize(it).toString().replace('\\', '/') }.toList()
        }
    }

    override suspend fun list(relativeDirectory: String, maximumEntries: Int): List<String> =
        withContext(dispatcher) {
            require(maximumEntries >= 0) { "Maximum directory entry count cannot be negative" }
            val directory = path(relativeDirectory)
            if (!Files.isDirectory(directory)) return@withContext emptyList()
            Files.walk(directory).use { stream ->
                stream.filter(Files::isRegularFile)
                    .limit(maximumEntries.toLong() + 1L)
                    .map { root.relativize(it).toString().replace('\\', '/') }
                    .toList()
                    .also { entries ->
                        require(entries.size <= maximumEntries) { "Directory contains too many files" }
                    }
            }
        }

    override fun uri(relativePath: String): String = path(relativePath).toUri().toString()

    override fun absolutePath(relativePath: String): String = path(relativePath).toString()

    private fun path(relativePath: String): Path {
        val resolved = root.resolve(validatedRelativePath(relativePath)).normalize().toAbsolutePath()
        val normalizedRoot = root.normalize().toAbsolutePath()
        require(resolved.startsWith(normalizedRoot)) { "Path escapes application storage" }
        return resolved
    }
}
