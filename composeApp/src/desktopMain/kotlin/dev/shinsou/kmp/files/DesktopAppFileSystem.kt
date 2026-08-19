package dev.shinsou.kmp.files

import dev.shinsou.kmp.desktop.DesktopAppDirectories
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DesktopAppFileSystem(
    private val root: Path = DesktopAppDirectories.contentRoot,
) : AppFileSystem {
    override suspend fun write(relativePath: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        path(relativePath).also { Files.createDirectories(it.parent) }.let { Files.write(it, bytes) }
    }

    override suspend fun writeAtomically(relativePath: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val destination = path(relativePath)
            Files.createDirectories(destination.parent)
            val temporary = Files.createTempFile(destination.parent, ".shinsou-${destination.fileName}-", ".tmp")
            try {
                Files.write(temporary, bytes)
                try {
                    Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                Files.deleteIfExists(temporary)
            }
        }

    override suspend fun read(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        path(relativePath).takeIf(Files::isRegularFile)?.let(Files::readAllBytes)
    }

    override suspend fun exists(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        Files.exists(path(relativePath))
    }

    override suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        Files.deleteIfExists(path(relativePath))
    }

    override suspend fun deleteTree(relativeDirectory: String): Boolean = withContext(Dispatchers.IO) {
        val directory = path(relativeDirectory)
        if (!Files.exists(directory)) return@withContext false
        Files.walk(directory).use { stream ->
            stream.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        true
    }

    override suspend fun list(relativeDirectory: String): List<String> = withContext(Dispatchers.IO) {
        val directory = path(relativeDirectory)
        if (!Files.isDirectory(directory)) return@withContext emptyList()
        Files.walk(directory).use { stream ->
            stream.filter(Files::isRegularFile).map { root.relativize(it).toString().replace('\\', '/') }.toList()
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
