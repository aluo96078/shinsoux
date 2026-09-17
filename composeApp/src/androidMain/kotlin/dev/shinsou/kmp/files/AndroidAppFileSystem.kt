package dev.shinsou.kmp.files

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidAppFileSystem internal constructor(rootDirectory: File) : AppFileSystem {
    constructor(context: Context) : this(File(context.filesDir, "shinsou-content"))

    // file() returns canonical paths. Use that same base for relative directory entries;
    // Android can expose filesDir through a different alias after process reconstruction.
    private val root = rootDirectory.canonicalFile.apply { mkdirs() }

    override suspend fun write(relativePath: String, bytes: ByteArray): Unit = withContext(Dispatchers.IO) {
        file(relativePath).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
    }

    override suspend fun writeAtomically(relativePath: String, bytes: ByteArray): Unit =
        withContext(Dispatchers.IO) {
            val destination = file(relativePath)
            val parent = destination.parentFile ?: error("Destination has no parent directory")
            parent.mkdirs()
            val temporary = File.createTempFile(".shinsou-${destination.name}-", ".tmp", parent)
            try {
                temporary.writeBytes(bytes)
                try {
                    Files.move(
                        temporary.toPath(),
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
        }

    override suspend fun read(relativePath: String): ByteArray? = withContext(Dispatchers.IO) {
        file(relativePath).takeIf(File::isFile)?.readBytes()
    }

    override suspend fun exists(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        file(relativePath).exists()
    }

    override suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.IO) {
        file(relativePath).delete()
    }

    override suspend fun deleteTree(relativeDirectory: String): Boolean = withContext(Dispatchers.IO) {
        file(relativeDirectory).deleteRecursively()
    }

    override suspend fun list(relativeDirectory: String): List<String> = withContext(Dispatchers.IO) {
        val directory = file(relativeDirectory)
        if (!directory.isDirectory) emptyList()
        else directory.walkTopDown().filter(File::isFile).map { it.relativeTo(root).invariantSeparatorsPath }.toList()
    }

    override suspend fun list(relativeDirectory: String, maximumEntries: Int): List<String> =
        withContext(Dispatchers.IO) {
            require(maximumEntries in 0 until Int.MAX_VALUE) { "Invalid maximum directory entry count" }
            val directory = file(relativeDirectory)
            if (!directory.isDirectory) return@withContext emptyList()
            directory.walkTopDown()
                .filter(File::isFile)
                .take(maximumEntries + 1)
                .map { it.relativeTo(root).invariantSeparatorsPath }
                .toList()
                .also { entries ->
                    require(entries.size <= maximumEntries) { "Directory contains too many files" }
                }
        }

    override fun uri(relativePath: String): String = file(relativePath).toURI().toString()

    override fun absolutePath(relativePath: String): String = file(relativePath).absolutePath

    private fun file(relativePath: String): File = File(root, validatedRelativePath(relativePath)).canonicalFile.also {
        require(it.path.startsWith(root.canonicalPath + File.separator)) { "Path escapes application storage" }
    }
}
