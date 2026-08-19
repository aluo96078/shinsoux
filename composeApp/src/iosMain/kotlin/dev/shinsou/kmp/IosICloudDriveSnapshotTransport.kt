package dev.shinsou.kmp

import dev.shinsou.kmp.sync.SnapshotSyncAvailability
import dev.shinsou.kmp.sync.SnapshotSyncCapability
import dev.shinsou.kmp.sync.SnapshotSyncTransport
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Foundation.dataWithContentsOfURL
import platform.Foundation.writeToURL

/** Real iCloud Documents transport for one coordinated Shinsou backup envelope. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosICloudDriveSnapshotTransport(
    private val fileManager: NSFileManager = NSFileManager.defaultManager,
) : SnapshotSyncTransport {
    override val initialCapability = SnapshotSyncCapability(
        availability = SnapshotSyncAvailability.CHECKING,
        serviceName = "iCloud Drive",
        detail = "Checking the iCloud ubiquity container…",
    )

    override suspend fun capability(): SnapshotSyncCapability = withContext(Dispatchers.Default) {
        when {
            fileManager.ubiquityIdentityToken == null -> unavailable(
                "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X.",
            )
            fileManager.URLForUbiquityContainerIdentifier(CONTAINER_IDENTIFIER) == null -> unavailable(
                "The Shinsou X iCloud Drive container is unavailable. Check the app's iCloud Documents entitlement.",
            )
            else -> SnapshotSyncCapability(
                availability = SnapshotSyncAvailability.AVAILABLE,
                serviceName = "iCloud Drive",
                detail = "A single Shinsou X snapshot will be stored in the app's iCloud Drive container.",
            )
        }
    }

    override suspend fun readSnapshot(): String? = withContext(Dispatchers.Default) {
        val url = snapshotUrl(createDirectory = false)
        val path = url.path ?: throw ICloudDriveSnapshotException("The iCloud snapshot URL has no file path.")
        if (!fileManager.fileExistsAtPath(path)) return@withContext null

        val coordinator = NSFileCoordinator(filePresenter = null)
        var payload: String? = null
        var accessorError: String? = null
        memScoped {
            val coordinationError = alloc<ObjCObjectVar<NSError?>>()
            coordinationError.value = null
            coordinator.coordinateReadingItemAtURL(
                url = url,
                options = 0u,
                error = coordinationError.ptr,
            ) { coordinatedUrl ->
                if (coordinatedUrl == null) {
                    accessorError = "iCloud Drive did not provide a coordinated read URL."
                } else {
                    val data = NSData.dataWithContentsOfURL(coordinatedUrl)
                    if (data == null) accessorError = "The iCloud Drive snapshot could not be read."
                    else payload = data.toSyncByteArray().decodeToString()
                }
            }
            coordinationError.value?.let { error ->
                throw ICloudDriveSnapshotException("Coordinated iCloud read failed: ${error.localizedDescription}")
            }
        }
        accessorError?.let { throw ICloudDriveSnapshotException(it) }
        payload ?: throw ICloudDriveSnapshotException("The iCloud Drive snapshot was empty or unreadable.")
    }

    override suspend fun writeSnapshot(encodedEnvelope: String): Unit = withContext(Dispatchers.Default) {
        val url = snapshotUrl(createDirectory = true)
        val data = NSString.create(string = encodedEnvelope).dataUsingEncoding(NSUTF8StringEncoding)
            ?: throw ICloudDriveSnapshotException("The snapshot could not be encoded as UTF-8.")
        val coordinator = NSFileCoordinator(filePresenter = null)
        var accessorError: String? = null
        memScoped {
            val coordinationError = alloc<ObjCObjectVar<NSError?>>()
            coordinationError.value = null
            coordinator.coordinateWritingItemAtURL(
                url = url,
                options = NSFileCoordinatorWritingForReplacing,
                error = coordinationError.ptr,
            ) { coordinatedUrl ->
                if (coordinatedUrl == null) {
                    accessorError = "iCloud Drive did not provide a coordinated write URL."
                } else if (!data.writeToURL(coordinatedUrl, atomically = true)) {
                    accessorError = "The coordinated iCloud Drive snapshot write failed."
                }
            }
            coordinationError.value?.let { error ->
                throw ICloudDriveSnapshotException("Coordinated iCloud write failed: ${error.localizedDescription}")
            }
        }
        accessorError?.let { throw ICloudDriveSnapshotException(it) }
    }

    private fun snapshotUrl(createDirectory: Boolean): NSURL {
        if (fileManager.ubiquityIdentityToken == null) {
            throw ICloudDriveSnapshotException(
                "iCloud Drive is unavailable. Sign in to iCloud and enable iCloud Drive for Shinsou X.",
            )
        }
        val container = fileManager.URLForUbiquityContainerIdentifier(CONTAINER_IDENTIFIER)
            ?: throw ICloudDriveSnapshotException(
                "The Shinsou X iCloud Drive container is unavailable. Check the iCloud Documents entitlement.",
            )
        val documents = container.URLByAppendingPathComponent("Documents", isDirectory = true)
            ?: throw ICloudDriveSnapshotException("The iCloud Documents directory could not be resolved.")
        val directory = documents.URLByAppendingPathComponent(APP_DIRECTORY, isDirectory = true)
            ?: throw ICloudDriveSnapshotException("The Shinsou X iCloud directory could not be resolved.")
        if (createDirectory) ensureDirectory(directory)
        return directory.URLByAppendingPathComponent(SNAPSHOT_FILE, isDirectory = false)
            ?: throw ICloudDriveSnapshotException("The iCloud snapshot file URL could not be resolved.")
    }

    private fun ensureDirectory(directory: NSURL) = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        error.value = null
        val created = fileManager.createDirectoryAtURL(
            url = directory,
            withIntermediateDirectories = true,
            attributes = null,
            error = error.ptr,
        )
        if (!created) {
            throw ICloudDriveSnapshotException(
                "The iCloud snapshot directory could not be created: " +
                    (error.value?.localizedDescription ?: "unknown file-system error"),
            )
        }
    }

    private fun unavailable(detail: String) = SnapshotSyncCapability(
        availability = SnapshotSyncAvailability.UNAVAILABLE,
        serviceName = "iCloud Drive",
        detail = detail,
    )

    private companion object {
        const val CONTAINER_IDENTIFIER = "iCloud.dev.aluo.shinsoux"
        const val APP_DIRECTORY = "Shinsou"
        const val SNAPSHOT_FILE = "shinsou-sync.shinsoubackup"
    }
}

internal class ICloudDriveSnapshotException(message: String) : IllegalStateException(message)

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toSyncByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    return bytes?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
}
