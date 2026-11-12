package dev.shinsou.kmp.crypto

import com.ionspin.kotlin.crypto.JnaLibsodiumInterface
import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.sun.jna.Native
import com.sun.jna.Platform
import dev.shinsou.kmp.desktop.DesktopAppDirectories
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual suspend fun initializePlatformLibsodium() {
    withContext(Dispatchers.IO) {
        DesktopLibsodium.load(DesktopAppDirectories.dataRoot.resolve("native"))
    }
}

/**
 * Loads the libsodium build bundled in the bindings jar without the bindings' own loader.
 *
 * That loader (com.goterl resource-loader 2.0.x) opens the jar through its URL-escaped path, so it
 * fails whenever the install path contains a space — `C:\Program Files\Shinsou X` and
 * `/Applications/Shinsou X.app` both do. On Windows it then falls back to `Paths.get(jar: URI)`,
 * which throws FileSystemNotFoundException, and every repository signature check fails.
 *
 * The library is copied once into the app's own data directory under a name carrying its SHA-256,
 * checked again before every load, and handed to the bindings through their public `sodiumJna`.
 */
internal object DesktopLibsodium {
    fun load(directory: Path) {
        val resource = bundledLibraryName()
        val bytes = JnaLibsodiumInterface::class.java.getResourceAsStream("/$resource")
            ?.use { it.readBytes() }
            ?: error("The bundled libsodium library $resource is missing.")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val target = directory.resolve("${digest.toHex().take(DIGEST_PREFIX_CHARS)}-$resource")
        val library = if (holds(target, digest)) target else install(directory, target, bytes, digest)
        val sodium = Native.load(library.toAbsolutePath().toString(), JnaLibsodiumInterface::class.java)
        // sodium_init: 0 initialized now, 1 already initialized, -1 failed.
        check(sodium.sodium_init() >= 0) { "libsodium failed to initialize." }
        LibsodiumInitializer.sodiumJna = sodium
        removeStaleCopies(directory, keep = library, resource = resource)
    }

    /** Copies left by earlier versions; Windows keeps one in use locked, so this is best effort. */
    private fun removeStaleCopies(directory: Path, keep: Path, resource: String) {
        runCatching {
            Files.list(directory).use { files ->
                files.filter { it != keep && it.fileName.toString().endsWith("-$resource") }.toList()
            }.forEach { runCatching { Files.deleteIfExists(it) } }
        }
    }

    /** The same choice the bindings make, so the resource names stay in step with their jar. */
    internal fun bundledLibraryName(): String = when {
        Platform.isMac() -> "libdynamic-macos.dylib"
        Platform.isLinux() && Platform.isARM() -> "libdynamic-linux-arm64-libsodium.so"
        Platform.isLinux() -> "libdynamic-linux-x86-64-libsodium.so"
        Platform.isWindows() -> "libdynamic-msvc-x86-64-libsodium.dll"
        else -> error("libsodium is not bundled for ${System.getProperty("os.name")}.")
    }

    private fun holds(file: Path, digest: ByteArray): Boolean =
        Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
            MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)), digest)

    private fun install(directory: Path, target: Path, bytes: ByteArray, digest: ByteArray): Path {
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "libsodium-", ".tmp")
        Files.write(temporary, bytes)
        try {
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (error: IOException) {
            // A damaged copy Windows keeps locked cannot be replaced; load the fresh one instead.
            if (holds(temporary, digest)) return temporary
            throw error
        }
        check(holds(target, digest)) { "The installed libsodium library does not match the bundled one." }
        return target
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val DIGEST_PREFIX_CHARS = 16
}
