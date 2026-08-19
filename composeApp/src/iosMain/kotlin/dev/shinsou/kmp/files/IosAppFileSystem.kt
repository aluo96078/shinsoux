package dev.shinsou.kmp.files

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import kotlinx.cinterop.ByteVar

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAppFileSystem : AppFileSystem {
    private val manager = NSFileManager.defaultManager
    private val root: String = run {
        val support = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: error("Application Support directory is unavailable")
        "$support/Shinsou/Content"
    }

    override suspend fun write(relativePath: String, bytes: ByteArray): Unit = withContext(Dispatchers.Default) {
        val destination = path(relativePath)
        val parent = destination.substringBeforeLast('/')
        manager.createDirectoryAtPath(parent, withIntermediateDirectories = true, attributes = null, error = null)
        val written = bytes.toNSData().writeToFile(destination, atomically = true)
        check(written) { "Unable to atomically write application file: $relativePath" }
    }

    /** NSData's atomic write already performs a same-volume temporary-file replace. */
    override suspend fun writeAtomically(relativePath: String, bytes: ByteArray): Unit = write(relativePath, bytes)

    override suspend fun read(relativePath: String): ByteArray? = withContext(Dispatchers.Default) {
        NSData.dataWithContentsOfFile(path(relativePath))?.toByteArray()
    }

    override suspend fun exists(relativePath: String): Boolean = withContext(Dispatchers.Default) {
        manager.fileExistsAtPath(path(relativePath))
    }

    override suspend fun delete(relativePath: String): Boolean = withContext(Dispatchers.Default) {
        val target = path(relativePath)
        if (!manager.fileExistsAtPath(target)) false else manager.removeItemAtPath(target, error = null)
    }

    override suspend fun deleteTree(relativeDirectory: String): Boolean = delete(relativeDirectory)

    override suspend fun list(relativeDirectory: String): List<String> = withContext(Dispatchers.Default) {
        val directory = path(relativeDirectory)
        val enumerator = manager.enumeratorAtPath(directory) ?: return@withContext emptyList()
        buildList {
            while (true) {
                val next = enumerator.nextObject() as? String ?: break
                val absolute = "$directory/$next"
                if (manager.attributesOfItemAtPath(absolute, error = null)?.get(NSFileType) == NSFileTypeRegular) {
                    add("${validatedRelativePath(relativeDirectory)}/$next")
                }
            }
        }
    }

    override fun uri(relativePath: String): String = NSURL.fileURLWithPath(path(relativePath)).absoluteString ?: ""

    override fun absolutePath(relativePath: String): String = path(relativePath)

    private fun path(relativePath: String): String = "$root/${validatedRelativePath(relativePath)}"
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData = if (isEmpty()) {
    NSData()
} else {
    usePinned { pinned -> NSData.create(bytes = pinned.addressOf(0), length = size.toULong()) }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun NSData.toByteArray(): ByteArray = memScoped {
    val size = length.toInt()
    if (size <= 0) ByteArray(0)
    else bytes?.reinterpret<ByteVar>()?.readBytes(size) ?: ByteArray(0)
}
