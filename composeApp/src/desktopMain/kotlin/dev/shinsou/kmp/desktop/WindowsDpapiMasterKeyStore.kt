package dev.shinsou.kmp.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Persists only a DPAPI-protected blob. DPAPI's default scope binds it to the current Windows user;
 * `CRYPTPROTECT_LOCAL_MACHINE` is deliberately never supplied.
 */
internal class WindowsDpapiMasterKeyStore(
    private val protectedKeyFile: Path,
    private val dpapi: WindowsDpapiApi = JnaWindowsDpapiApi(),
) : DesktopMasterKeyStore {
    override fun read(): ByteArray? {
        if (!Files.exists(protectedKeyFile, LinkOption.NOFOLLOW_LINKS)) return null
        check(Files.isRegularFile(protectedKeyFile, LinkOption.NOFOLLOW_LINKS)) {
            "Refusing to follow a non-regular Windows protected-key file."
        }
        check(Files.size(protectedKeyFile) in 1..MAX_PROTECTED_VALUE_BYTES.toLong()) {
            "Windows protected desktop master key has an invalid size."
        }
        val protectedValue = Files.newInputStream(protectedKeyFile).use { input ->
            input.readNBytes(MAX_PROTECTED_VALUE_BYTES + 1)
        }
        check(protectedValue.size in 1..MAX_PROTECTED_VALUE_BYTES) {
            "Windows protected desktop master key has an invalid size."
        }
        return try {
            dpapi.unprotect(protectedValue)
        } finally {
            protectedValue.fill(0)
        }
    }

    override fun write(value: ByteArray) {
        require(value.isNotEmpty()) { "Refusing to store an empty desktop master key." }
        require(value.size <= MAX_PLAINTEXT_VALUE_BYTES) { "Desktop master key is unexpectedly large." }

        val parent = protectedKeyFile.parent
            ?: error("Windows protected-key file must have a parent directory.")
        Files.createDirectories(parent)
        check(!Files.exists(protectedKeyFile, LinkOption.NOFOLLOW_LINKS) ||
            Files.isRegularFile(protectedKeyFile, LinkOption.NOFOLLOW_LINKS)) {
            "Refusing to replace a non-regular Windows protected-key file."
        }

        val plaintextCopy = value.copyOf()
        val protectedValue = try {
            dpapi.protect(plaintextCopy)
        } finally {
            plaintextCopy.fill(0)
        }
        var temporaryKeyFile: Path? = null
        try {
            check(protectedValue.size in 1..MAX_PROTECTED_VALUE_BYTES) {
                "Windows DPAPI returned an invalid protected value."
            }
            temporaryKeyFile = Files.createTempFile(parent, "${protectedKeyFile.fileName}.", ".tmp")
            Files.write(temporaryKeyFile, protectedValue)
            moveAtomically(temporaryKeyFile, protectedKeyFile)
        } finally {
            protectedValue.fill(0)
            temporaryKeyFile?.let(Files::deleteIfExists)
        }
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private companion object {
        const val MAX_PLAINTEXT_VALUE_BYTES = 4 * 1024
        const val MAX_PROTECTED_VALUE_BYTES = 64 * 1024
    }
}

internal interface WindowsDpapiApi {
    fun protect(value: ByteArray): ByteArray

    fun unprotect(value: ByteArray): ByteArray
}

/** Direct JNA binding to Windows Data Protection API (DPAPI). */
internal class JnaWindowsDpapiApi : WindowsDpapiApi {
    private val crypt32: Crypt32Library
    private val kernel32: Kernel32Library

    init {
        check(DesktopPlatform.fromOsName(System.getProperty("os.name")) == DesktopPlatform.WINDOWS) {
            "Windows DPAPI is unavailable on this operating system."
        }
        crypt32 = Native.load("Crypt32", Crypt32Library::class.java)
        kernel32 = Native.load("Kernel32", Kernel32Library::class.java)
    }

    override fun protect(value: ByteArray): ByteArray = transform(value, protecting = true)

    override fun unprotect(value: ByteArray): ByteArray = transform(value, protecting = false)

    private fun transform(value: ByteArray, protecting: Boolean): ByteArray {
        require(value.isNotEmpty()) { "DPAPI input must not be empty." }
        val inputMemory = Memory(value.size.toLong())
        inputMemory.write(0, value, 0, value.size)
        val input = DataBlob(value.size, inputMemory)
        val output = DataBlob()

        try {
            val succeeded = if (protecting) {
                crypt32.CryptProtectData(
                    input,
                    null,
                    null,
                    null,
                    null,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    output,
                )
            } else {
                crypt32.CryptUnprotectData(
                    input,
                    null,
                    null,
                    null,
                    null,
                    CRYPTPROTECT_UI_FORBIDDEN,
                    output,
                )
            }
            check(succeeded != 0) {
                "Windows DPAPI ${if (protecting) "protection" else "unprotection"} failed " +
                    "with error ${Native.getLastError()}."
            }
            output.read()
            val outputPointer = output.pbData
                ?: error("Windows DPAPI returned no output data.")
            check(output.cbData in 1..MAX_DPAPI_OUTPUT_BYTES) {
                "Windows DPAPI returned an invalid output size."
            }
            return outputPointer.getByteArray(0, output.cbData)
        } finally {
            inputMemory.setMemory(0, inputMemory.size(), 0)
            output.pbData?.let { outputPointer ->
                if (!protecting && output.cbData in 1..MAX_DPAPI_OUTPUT_BYTES) {
                    outputPointer.setMemory(0, output.cbData.toLong(), 0)
                }
                check(kernel32.LocalFree(outputPointer) == null) {
                    "Windows could not release DPAPI output memory."
                }
            }
        }
    }

    @Structure.FieldOrder("cbData", "pbData")
    internal open class DataBlob(
        @JvmField var cbData: Int = 0,
        @JvmField var pbData: Pointer? = null,
    ) : Structure() {
        init {
            write()
        }
    }

    private interface Crypt32Library : StdCallLibrary {
        fun CryptProtectData(
            dataIn: DataBlob,
            dataDescription: Pointer?,
            optionalEntropy: DataBlob?,
            reserved: Pointer?,
            prompt: Pointer?,
            flags: Int,
            dataOut: DataBlob,
        ): Int

        fun CryptUnprotectData(
            dataIn: DataBlob,
            dataDescription: PointerByReference?,
            optionalEntropy: DataBlob?,
            reserved: Pointer?,
            prompt: Pointer?,
            flags: Int,
            dataOut: DataBlob,
        ): Int
    }

    private interface Kernel32Library : StdCallLibrary {
        fun LocalFree(memory: Pointer): Pointer?
    }

    private companion object {
        // Omitting CRYPTPROTECT_LOCAL_MACHINE (0x4) is what gives this blob current-user scope.
        const val CRYPTPROTECT_UI_FORBIDDEN = 0x1
        const val MAX_DPAPI_OUTPUT_BYTES = 64 * 1024
    }
}
