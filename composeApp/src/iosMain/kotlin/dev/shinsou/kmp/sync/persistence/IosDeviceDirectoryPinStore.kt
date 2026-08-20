package dev.shinsou.kmp.sync.persistence

import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

internal fun createIosFileDeviceDirectoryPinStore(path: String): DeviceDirectoryPinStore =
    PersistentDeviceDirectoryPinStore(IosFileDeviceDirectoryPinBackend(path))

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosFileDeviceDirectoryPinBackend(
    private val path: String,
) : DeviceDirectoryPinBackend {
    override suspend fun readUtf8(): String? = withContext(Dispatchers.Default) {
        ensureRegularOrMissing()
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return@withContext null
        val attributes = manager.attributesOfItemAtPath(path, error = null)
            ?: throw SyncMetadataUnavailableException("Unable to inspect device directory pins")
        val size = (attributes[NSFileSize] as? NSNumber)?.unsignedLongLongValue
            ?: throw SyncMetadataCorruptException("Device directory pin file has no valid size")
        if (size > MAX_PIN_FILE_BYTES.toULong()) {
            throw SyncMetadataCorruptException("Device directory pin file exceeds its size limit")
        }
        val data = NSData.dataWithContentsOfFile(path)
            ?: throw SyncMetadataUnavailableException("Unable to read device directory pins")
        @Suppress("CAST_NEVER_SUCCEEDS")
        NSString.create(data, NSUTF8StringEncoding) as? String
            ?: throw SyncMetadataCorruptException("Device directory pin file is not valid UTF-8")
    }

    override suspend fun writeUtf8(value: String): Unit = withContext(Dispatchers.Default) {
        ensureRegularOrMissing()
        val parent = path.substringBeforeLast('/', missingDelimiterValue = "")
        if (parent.isBlank()) throw SyncMetadataUnavailableException("Device directory pin path has no parent")
        val manager = NSFileManager.defaultManager
        if (!manager.createDirectoryAtPath(parent, true, null, null)) {
            throw SyncMetadataUnavailableException("Unable to create the device directory pin directory")
        }
        val data = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)
            ?: throw SyncMetadataUnavailableException("Unable to encode device directory pins")
        if (data.length > MAX_PIN_FILE_BYTES.toULong()) {
            throw SyncMetadataCorruptException("Device directory pin file exceeds its size limit")
        }
        if (!data.writeToFile(path, atomically = true)) {
            throw SyncMetadataUnavailableException("Unable to atomically persist device directory pins")
        }
    }

    override suspend fun delete(): Unit = withContext(Dispatchers.Default) {
        ensureRegularOrMissing()
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(path) && !manager.removeItemAtPath(path, error = null)) {
            throw SyncMetadataUnavailableException("Unable to clear device directory pins")
        }
    }

    private fun ensureRegularOrMissing() {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return
        val attributes = manager.attributesOfItemAtPath(path, error = null)
            ?: throw SyncMetadataUnavailableException("Unable to inspect device directory pins")
        if (attributes[NSFileType] != NSFileTypeRegular) {
            throw SyncMetadataCorruptException("Refusing to use a non-regular device directory pin file")
        }
    }
}

private const val MAX_PIN_FILE_BYTES = 4L * 1024L * 1024L
