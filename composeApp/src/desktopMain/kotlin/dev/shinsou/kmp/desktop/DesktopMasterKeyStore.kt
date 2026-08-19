package dev.shinsou.kmp.desktop

import java.nio.file.Path

/** Storage boundary for the AES key used by desktop plugin state. */
internal interface DesktopMasterKeyStore {
    fun read(): ByteArray?

    fun write(value: ByteArray)
}

/**
 * Selects the operating-system credential store without loading a native library for another OS.
 *
 * Keeping native adapter construction inside the matching branch is important: loading
 * Security.framework on Windows (or Crypt32 on macOS) fails before the application window opens.
 */
internal object DesktopMasterKeyStoreFactory {
    fun create(
        directory: Path,
        platform: DesktopPlatform = DesktopPlatform.current,
    ): DesktopMasterKeyStore = when (platform) {
        DesktopPlatform.MAC_OS -> MacOsKeychainMasterKeyStore()
        DesktopPlatform.WINDOWS ->
            WindowsDpapiMasterKeyStore(directory.resolve(WINDOWS_PROTECTED_KEY_FILE))
        DesktopPlatform.LINUX,
        DesktopPlatform.OTHER,
        -> UnavailableDesktopMasterKeyStore(platform)
    }

    internal const val WINDOWS_PROTECTED_KEY_FILE = "plugin-secrets.dpapi"
}

/**
 * Allows unsupported desktop platforms to start and use non-sensitive plugin state. Accessing a
 * credential still fails closed instead of silently writing the AES key to disk in plaintext.
 */
private class UnavailableDesktopMasterKeyStore(
    private val platform: DesktopPlatform,
) : DesktopMasterKeyStore {
    override fun read(): ByteArray? = unavailable()

    override fun write(value: ByteArray): Unit = unavailable()

    private fun unavailable(): Nothing = error(
        "Secure desktop credential storage is unavailable on $platform.",
    )
}
