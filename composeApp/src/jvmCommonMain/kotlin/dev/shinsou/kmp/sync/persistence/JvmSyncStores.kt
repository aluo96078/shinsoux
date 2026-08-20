package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStore
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
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/** Atomic JSON persistence for non-secret Cloudflare session metadata. */
internal class JvmFileSyncSessionStore(
    private val path: Path,
) : SyncSessionStore {
    private val mutex = Mutex()

    override suspend fun load(): SyncSession? = mutex.withLock {
        withContext(Dispatchers.IO) {
            val encoded = readUtf8IfPresent(path, MAX_METADATA_BYTES) ?: return@withContext null
            try {
                SyncMetadataJson.decodeFromString(SyncSession.serializer(), encoded)
            } catch (error: SerializationException) {
                throw SyncMetadataCorruptException("Sync session metadata is malformed", error)
            } catch (error: IllegalArgumentException) {
                throw SyncMetadataCorruptException("Sync session metadata failed validation", error)
            }
        }
    }

    override suspend fun save(session: SyncSession): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val encoded = SyncMetadataJson.encodeToString(SyncSession.serializer(), session)
            writeUtf8Atomically(path, encoded, MAX_METADATA_BYTES)
        }
    }

    override suspend fun clear(): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            try {
                ensureRegularOrMissing(path)
                Files.deleteIfExists(path)
            } catch (error: IOException) {
                throw SyncMetadataUnavailableException("Unable to clear sync session metadata", error)
            }
        }
    }
}

/** Device-local UUIDs survive restarts but are never silently regenerated after corruption. */
internal class JvmFileSyncInstallationStore(
    private val path: Path,
    private val randomUuid: () -> String = { UUID.randomUUID().toString() },
) : SyncInstallationStore {
    private val mutex = Mutex()

    override suspend fun loadOrCreate(): SyncInstallationIdentity = mutex.withLock {
        withContext(Dispatchers.IO) {
            readIdentity()?.let { return@withContext it }
            val created = newSyncInstallationIdentity(randomUuid)
            val encoded = SyncMetadataJson.encodeToString(SyncInstallationIdentity.serializer(), created)
            writeUtf8Atomically(path, encoded, MAX_METADATA_BYTES)
            readIdentity() ?: throw SyncMetadataUnavailableException(
                "Sync installation identity disappeared immediately after creation",
            )
        }
    }

    private fun readIdentity(): SyncInstallationIdentity? {
        val encoded = readUtf8IfPresent(path, MAX_METADATA_BYTES) ?: return null
        return try {
            SyncMetadataJson.decodeFromString(SyncInstallationIdentity.serializer(), encoded)
        } catch (error: SerializationException) {
            throw SyncMetadataCorruptException("Sync installation identity is malformed", error)
        } catch (error: IllegalArgumentException) {
            throw SyncMetadataCorruptException("Sync installation identity failed validation", error)
        }
    }
}

/**
 * Platform protection boundary used by the JVM file envelope. Android keeps its AES key
 * non-exportable in Keystore; Desktop obtains a random AES key through Keychain or DPAPI.
 */
internal interface JvmSyncSecretProtector {
    /** [protectedValuesExist] forbids silently creating a new master key over existing ciphertext. */
    fun encrypt(plaintext: ByteArray, protectedValuesExist: Boolean): ByteArray

    fun decrypt(payload: ByteArray): ByteArray
}

internal class JvmSyncProtectionUnavailableException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class JvmSyncProtectionCorruptException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

/**
 * Every value is protected, irrespective of its key name. The JSON file contains only versioned
 * ciphertext envelopes; platform storage failure is never replaced by a plaintext fallback.
 */
internal class JvmEncryptedSyncSecretStore(
    private val path: Path,
    private val protector: JvmSyncSecretProtector,
) : SyncSecretStore {
    private val mutex = Mutex()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    override suspend fun read(key: SyncSecretKey): SyncSecretReadResult = mutex.withLock {
        withContext(Dispatchers.IO) {
            val values = try {
                readValues()
            } catch (error: SyncMetadataCorruptException) {
                return@withContext SyncSecretReadResult.Corrupt(error.message ?: "Secret index is corrupt")
            } catch (error: SyncMetadataUnavailableException) {
                return@withContext SyncSecretReadResult.Unavailable(error.message ?: "Secret storage is unavailable")
            }
            val encoded = values[key.storageIdentifier()] ?: return@withContext SyncSecretReadResult.Missing
            val payload = try {
                Base64.getDecoder().decode(encoded)
            } catch (_: IllegalArgumentException) {
                return@withContext SyncSecretReadResult.Corrupt("Protected secret payload is malformed")
            }
            try {
                val plaintext = protector.decrypt(payload)
                try {
                    if (plaintext.isEmpty()) {
                        SyncSecretReadResult.Corrupt("Protected secret payload is empty")
                    } else {
                        SyncSecretReadResult.Available(SecretMaterial(plaintext.asList()))
                    }
                } finally {
                    plaintext.fill(0)
                }
            } catch (error: JvmSyncProtectionCorruptException) {
                SyncSecretReadResult.Corrupt(error.message ?: "Protected secret could not be authenticated")
            } catch (error: JvmSyncProtectionUnavailableException) {
                SyncSecretReadResult.Unavailable(error.message ?: "Protected secret storage is unavailable")
            } catch (_: Throwable) {
                SyncSecretReadResult.Unavailable("Protected secret storage failed")
            } finally {
                payload.fill(0)
            }
        }
    }

    override suspend fun write(key: SyncSecretKey, material: SecretMaterial): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val values = readValues().toMutableMap()
            var encrypted: ByteArray? = null
            material.useBytes { plaintext ->
                encrypted = try {
                    protector.encrypt(plaintext, protectedValuesExist = values.isNotEmpty())
                } catch (error: JvmSyncProtectionUnavailableException) {
                    throw SyncMetadataUnavailableException("Protected secret storage is unavailable", error)
                }
            }
            val payload = requireNotNull(encrypted)
            try {
                values[key.storageIdentifier()] = Base64.getEncoder().encodeToString(payload)
                writeValues(values)
            } finally {
                payload.fill(0)
            }
        }
    }

    override suspend fun delete(key: SyncSecretKey): Unit = mutex.withLock {
        withContext(Dispatchers.IO) {
            val values = readValues().toMutableMap()
            if (values.remove(key.storageIdentifier()) != null) writeValues(values)
        }
    }

    private fun readValues(): Map<String, String> {
        val encoded = readUtf8IfPresent(path, MAX_SECRET_INDEX_BYTES) ?: return emptyMap()
        return try {
            SyncMetadataJson.decodeFromString(serializer, encoded)
        } catch (error: SerializationException) {
            throw SyncMetadataCorruptException("Protected secret index is malformed", error)
        }
    }

    private fun writeValues(values: Map<String, String>) {
        val encoded = SyncMetadataJson.encodeToString(serializer, values.toSortedMap())
        writeUtf8Atomically(path, encoded, MAX_SECRET_INDEX_BYTES)
    }
}

private fun readUtf8IfPresent(path: Path, maximumBytes: Long): String? {
    try {
        ensureRegularOrMissing(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val size = Files.size(path)
        if (size !in 0..maximumBytes) {
            throw SyncMetadataCorruptException("Persisted sync metadata exceeds its size limit")
        }
        return String(Files.readAllBytes(path), StandardCharsets.UTF_8)
    } catch (error: SyncMetadataCorruptException) {
        throw error
    } catch (error: IOException) {
        throw SyncMetadataUnavailableException("Unable to read persisted sync metadata", error)
    }
}

private fun writeUtf8Atomically(path: Path, value: String, maximumBytes: Long) {
    val bytes = value.toByteArray(StandardCharsets.UTF_8)
    if (bytes.size.toLong() > maximumBytes) {
        throw SyncMetadataCorruptException("Persisted sync metadata exceeds its size limit")
    }
    val parent = path.parent ?: throw SyncMetadataUnavailableException("Sync metadata path has no parent")
    var temporary: Path? = null
    try {
        Files.createDirectories(parent)
        ensureRegularOrMissing(path)
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
        throw SyncMetadataUnavailableException("Unable to atomically persist sync metadata", error)
    } finally {
        bytes.fill(0)
        temporary?.let { runCatching { Files.deleteIfExists(it) } }
    }
}

private fun ensureRegularOrMissing(path: Path) {
    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
        !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    ) {
        throw SyncMetadataCorruptException("Refusing to use a non-regular sync metadata file")
    }
}

private const val MAX_METADATA_BYTES = 256L * 1024L
private const val MAX_SECRET_INDEX_BYTES = 16L * 1024L * 1024L
