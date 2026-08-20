package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore
import dev.shinsou.kmp.sync.trust.DeviceDirectoryRevision
import dev.shinsou.kmp.sync.trust.PinnedDeviceDirectory
import dev.shinsou.kmp.sync.trust.requireMonotonicPin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException

/** Platform file boundary. Trust pins are public metadata, but corruption must fail closed. */
internal interface DeviceDirectoryPinBackend {
    suspend fun readUtf8(): String?
    suspend fun writeUtf8(value: String)
    suspend fun delete()
}

internal class PersistentDeviceDirectoryPinStore(
    private val backend: DeviceDirectoryPinBackend,
) : DeviceDirectoryPinStore {
    private val mutex = Mutex()

    override suspend fun load(workspaceId: String): PinnedDeviceDirectory? = mutex.withLock {
        readFile().directories.firstOrNull { it.workspaceId == workspaceId }
    }

    override suspend fun compareAndSet(
        workspaceId: String,
        expected: DeviceDirectoryRevision?,
        updated: PinnedDeviceDirectory,
    ): Boolean = mutex.withLock {
        require(updated.workspaceId == workspaceId) { "Directory pin workspace mismatch" }
        val file = readFile()
        val current = file.directories.firstOrNull { it.workspaceId == workspaceId }
        if (current?.revision != expected) return@withLock false
        requireMonotonicPin(current, updated)
        val next = file.directories
            .filterNot { it.workspaceId == workspaceId }
            .plus(updated)
            .sortedBy(PinnedDeviceDirectory::workspaceId)
        backend.writeUtf8(
            SyncMetadataJson.encodeToString(
                PinnedDeviceDirectoryFile.serializer(),
                PinnedDeviceDirectoryFile(directories = next),
            ),
        )
        true
    }

    override suspend fun clear(workspaceId: String): Unit = mutex.withLock {
        val file = readFile()
        val next = file.directories.filterNot { it.workspaceId == workspaceId }
        if (next.size == file.directories.size) return@withLock
        if (next.isEmpty()) {
            backend.delete()
        } else {
            backend.writeUtf8(
                SyncMetadataJson.encodeToString(
                    PinnedDeviceDirectoryFile.serializer(),
                    PinnedDeviceDirectoryFile(directories = next),
                ),
            )
        }
    }

    private suspend fun readFile(): PinnedDeviceDirectoryFile {
        val encoded = backend.readUtf8() ?: return PinnedDeviceDirectoryFile()
        return try {
            val file = SyncMetadataJson.decodeFromString(PinnedDeviceDirectoryFile.serializer(), encoded)
            if (file.formatVersion != PIN_FILE_VERSION) {
                throw SyncMetadataCorruptException("Unsupported device directory pin format")
            }
            if (file.directories.map(PinnedDeviceDirectory::workspaceId).distinct().size != file.directories.size ||
                file.directories.map(PinnedDeviceDirectory::workspaceId) !=
                file.directories.map(PinnedDeviceDirectory::workspaceId).sorted()
            ) {
                throw SyncMetadataCorruptException("Device directory pin index is inconsistent")
            }
            file
        } catch (error: SyncMetadataCorruptException) {
            throw error
        } catch (error: SerializationException) {
            throw SyncMetadataCorruptException("Device directory pin metadata is malformed", error)
        } catch (error: IllegalArgumentException) {
            throw SyncMetadataCorruptException("Device directory pin metadata failed validation", error)
        }
    }
}

@Serializable
private data class PinnedDeviceDirectoryFile(
    val formatVersion: Int = PIN_FILE_VERSION,
    val directories: List<PinnedDeviceDirectory> = emptyList(),
)

private const val PIN_FILE_VERSION = 1
