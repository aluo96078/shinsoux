package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun createJvmFileDeviceDirectoryPinStore(path: Path): DeviceDirectoryPinStore =
    PersistentDeviceDirectoryPinStore(JvmFileDeviceDirectoryPinBackend(path))

internal class JvmFileDeviceDirectoryPinBackend(
    private val path: Path,
) : DeviceDirectoryPinBackend {
    override suspend fun readUtf8(): String? = withContext(Dispatchers.IO) {
        try {
            ensureRegularOrMissing()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@withContext null
            val size = Files.size(path)
            if (size !in 0..MAX_PIN_FILE_BYTES) {
                throw SyncMetadataCorruptException("Device directory pin file exceeds its size limit")
            }
            String(Files.readAllBytes(path), StandardCharsets.UTF_8)
        } catch (error: SyncMetadataCorruptException) {
            throw error
        } catch (error: IOException) {
            throw SyncMetadataUnavailableException("Unable to read device directory pins", error)
        }
    }

    override suspend fun writeUtf8(value: String): Unit = withContext(Dispatchers.IO) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size.toLong() > MAX_PIN_FILE_BYTES) {
            throw SyncMetadataCorruptException("Device directory pin file exceeds its size limit")
        }
        val parent = path.parent
            ?: throw SyncMetadataUnavailableException("Device directory pin path has no parent")
        var temporary: Path? = null
        try {
            Files.createDirectories(parent)
            ensureRegularOrMissing()
            temporary = Files.createTempFile(parent, ".${path.fileName}-", ".tmp")
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
            temporary = null
        } catch (error: SyncMetadataCorruptException) {
            throw error
        } catch (error: IOException) {
            throw SyncMetadataUnavailableException("Unable to atomically persist device directory pins", error)
        } finally {
            bytes.fill(0)
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.IO) {
        try {
            ensureRegularOrMissing()
            Files.deleteIfExists(path)
        } catch (error: SyncMetadataCorruptException) {
            throw error
        } catch (error: IOException) {
            throw SyncMetadataUnavailableException("Unable to clear device directory pins", error)
        }
    }

    private fun ensureRegularOrMissing() {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw SyncMetadataCorruptException("Refusing to use a non-regular device directory pin file")
        }
    }
}

private const val MAX_PIN_FILE_BYTES = 4L * 1024L * 1024L
