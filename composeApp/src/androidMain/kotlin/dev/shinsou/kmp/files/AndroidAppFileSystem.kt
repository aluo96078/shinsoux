package dev.shinsou.kmp.files

import android.content.Context
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidAppFileSystem(context: Context) : AppFileSystem {
    private val root = File(context.filesDir, "shinsou-content").apply { mkdirs() }

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

    override fun uri(relativePath: String): String = file(relativePath).toURI().toString()

    override fun absolutePath(relativePath: String): String = file(relativePath).absolutePath

    private fun file(relativePath: String): File = File(root, validatedRelativePath(relativePath)).canonicalFile.also {
        require(it.path.startsWith(root.canonicalPath + File.separator)) { "Path escapes application storage" }
    }
}
