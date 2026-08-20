package dev.shinsou.kmp.desktop

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.sync.persistence.JvmEncryptedSyncSecretStore
import dev.shinsou.kmp.sync.persistence.JvmFileSyncInstallationStore
import dev.shinsou.kmp.sync.persistence.JvmFileSyncSessionStore
import dev.shinsou.kmp.sync.persistence.createJvmFileDeviceDirectoryPinStore
import dev.shinsou.kmp.sync.persistence.JvmSyncProtectionCorruptException
import dev.shinsou.kmp.sync.persistence.JvmSyncProtectionUnavailableException
import dev.shinsou.kmp.sync.persistence.JvmSyncSecretProtector
import dev.shinsou.kmp.sync.persistence.SqlDriverSyncStatePersistence
import dev.shinsou.kmp.sync.persistence.SyncInstallationStore
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncPlatformInfrastructure
import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Desktop persistence bundle. No secret bytes are written outside the encrypted secret index. */
internal class DesktopSyncInfrastructure(
    private val directory: Path = DesktopAppDirectories.dataRoot.resolve("SyncV2"),
    private val desktopPlatform: DesktopPlatform = DesktopPlatform.current,
    secureRandom: SecureRandom = SecureRandom(),
) : SyncPlatformInfrastructure {
    override val installationStore: SyncInstallationStore =
        JvmFileSyncInstallationStore(directory.resolve("installation.json"))
    override val sessionStore: SyncSessionStore = JvmFileSyncSessionStore(directory.resolve("session.json"))
    override val secretStore: SyncSecretStore = JvmEncryptedSyncSecretStore(
        path = directory.resolve("secrets.json"),
        protector = DesktopSyncSecretProtector(
            masterKeyStore = desktopSyncMasterKeyStore(directory, desktopPlatform),
            secureRandom = secureRandom,
        ),
    )
    override val deviceDirectoryPinStore: DeviceDirectoryPinStore =
        createJvmFileDeviceDirectoryPinStore(directory.resolve("device-directory-pins.json"))
    override val platform: String = when (desktopPlatform) {
        DesktopPlatform.MAC_OS -> "macos"
        DesktopPlatform.WINDOWS -> "windows"
        DesktopPlatform.LINUX,
        DesktopPlatform.OTHER,
        -> "other"
    }
    override val deviceDisplayName: String = desktopDeviceDisplayName(desktopPlatform)

    private val driverLock = Any()
    private var openPersistence: SqlDriverSyncStatePersistence? = null

    init {
        Files.createDirectories(directory)
        setOwnerOnlyDirectory(directory)
    }

    override fun statePersistence(): SqlDriverSyncStatePersistence = synchronized(driverLock) {
        openPersistence ?: run {
            val database = directory.resolve("local-sync.db").toAbsolutePath().normalize()
            SqlDriverSyncStatePersistence(
                JdbcSqliteDriver("jdbc:sqlite:$database"),
            ).also { openPersistence = it }
        }
    }

    override fun close() = synchronized(driverLock) {
        openPersistence?.close()
        openPersistence = null
    }
}

private fun desktopDeviceDisplayName(platform: DesktopPlatform): String {
    val environmentName = when (platform) {
        DesktopPlatform.WINDOWS -> System.getenv("COMPUTERNAME")
        DesktopPlatform.MAC_OS,
        DesktopPlatform.LINUX,
        DesktopPlatform.OTHER,
        -> System.getenv("HOSTNAME")
    }
    return environmentName?.trim()?.takeIf(String::isNotEmpty) ?: when (platform) {
        DesktopPlatform.MAC_OS -> "Mac"
        DesktopPlatform.WINDOWS -> "Windows PC"
        DesktopPlatform.LINUX -> "Linux desktop"
        DesktopPlatform.OTHER -> "Desktop"
    }
}

private class DesktopSyncSecretProtector(
    private val masterKeyStore: DesktopMasterKeyStore,
    private val secureRandom: SecureRandom,
) : JvmSyncSecretProtector {
    override fun encrypt(plaintext: ByteArray, protectedValuesExist: Boolean): ByteArray {
        val masterKey = masterKey(createIfMissing = !protectedValuesExist)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))
            val ciphertext = cipher.doFinal(plaintext)
            try {
                val iv = cipher.iv
                require(iv.size in 12..32) { "Desktop cipher returned an invalid nonce" }
                return ByteArray(2 + iv.size + ciphertext.size).also { payload ->
                    payload[0] = ENVELOPE_VERSION
                    payload[1] = iv.size.toByte()
                    iv.copyInto(payload, 2)
                    ciphertext.copyInto(payload, 2 + iv.size)
                }
            } finally {
                ciphertext.fill(0)
            }
        } catch (error: GeneralSecurityException) {
            throw JvmSyncProtectionUnavailableException("Desktop secret encryption is unavailable", error)
        } finally {
            masterKey.fill(0)
        }
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size < MINIMUM_ENVELOPE_BYTES || payload[0] != ENVELOPE_VERSION) {
            throw JvmSyncProtectionCorruptException("Desktop secret envelope has an invalid version or size")
        }
        val ivSize = payload[1].toInt() and 0xff
        if (ivSize !in 12..32 || payload.size <= 2 + ivSize) {
            throw JvmSyncProtectionCorruptException("Desktop secret envelope has an invalid nonce")
        }
        val masterKey = masterKey(createIfMissing = false)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(masterKey, "AES"),
                GCMParameterSpec(128, payload.copyOfRange(2, 2 + ivSize)),
            )
            cipher.doFinal(payload.copyOfRange(2 + ivSize, payload.size))
        } catch (error: AEADBadTagException) {
            throw JvmSyncProtectionCorruptException("Desktop secret envelope authentication failed", error)
        } catch (error: GeneralSecurityException) {
            throw JvmSyncProtectionUnavailableException("Desktop secret decryption is unavailable", error)
        } finally {
            masterKey.fill(0)
        }
    }

    private fun masterKey(createIfMissing: Boolean): ByteArray {
        val existing = try {
            masterKeyStore.read()
        } catch (error: Throwable) {
            throw JvmSyncProtectionUnavailableException("Desktop protected credential store is unavailable", error)
        }
        if (existing != null) {
            if (existing.size != MASTER_KEY_BYTES) {
                existing.fill(0)
                throw JvmSyncProtectionCorruptException("Desktop protected master key has an invalid size")
            }
            return existing
        }
        if (!createIfMissing) {
            throw JvmSyncProtectionUnavailableException("Desktop protected master key is missing")
        }

        val generated = ByteArray(MASTER_KEY_BYTES).also(secureRandom::nextBytes)
        try {
            masterKeyStore.write(generated)
            val verified = masterKeyStore.read()
                ?: throw JvmSyncProtectionUnavailableException("Desktop protected master key was not readable after writing")
            if (!verified.contentEquals(generated)) {
                verified.fill(0)
                throw JvmSyncProtectionCorruptException("Desktop protected master key failed read-back verification")
            }
            verified.fill(0)
            return generated.copyOf()
        } catch (error: JvmSyncProtectionCorruptException) {
            throw error
        } catch (error: JvmSyncProtectionUnavailableException) {
            throw error
        } catch (error: Throwable) {
            throw JvmSyncProtectionUnavailableException("Unable to persist the desktop sync master key", error)
        } finally {
            generated.fill(0)
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MASTER_KEY_BYTES = 32
        const val MINIMUM_ENVELOPE_BYTES = 2 + 12 + 16
        val ENVELOPE_VERSION: Byte = 1
    }
}

private fun desktopSyncMasterKeyStore(
    directory: Path,
    platform: DesktopPlatform,
): DesktopMasterKeyStore = when (platform) {
    DesktopPlatform.MAC_OS -> MacOsKeychainMasterKeyStore(
        service = "dev.aluo.shinsoux.desktop.sync-secrets",
        account = "master-key-v1",
    )

    DesktopPlatform.WINDOWS -> WindowsDpapiMasterKeyStore(directory.resolve("master-key.dpapi"))
    DesktopPlatform.LINUX,
    DesktopPlatform.OTHER,
    -> UnavailableSyncDesktopMasterKeyStore(platform)
}

private class UnavailableSyncDesktopMasterKeyStore(
    private val platform: DesktopPlatform,
) : DesktopMasterKeyStore {
    override fun read(): ByteArray? = unavailable()

    override fun write(value: ByteArray): Unit = unavailable()

    private fun unavailable(): Nothing = error("Sync secret storage is unavailable on $platform")
}

private fun setOwnerOnlyDirectory(path: Path) {
    runCatching {
        Files.setPosixFilePermissions(
            path,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    }
}
