package dev.shinsou.kmp

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import dev.shinsou.kmp.sync.persistence.JvmEncryptedSyncSecretStore
import dev.shinsou.kmp.sync.persistence.JvmFileSyncInstallationStore
import dev.shinsou.kmp.sync.persistence.JvmFileSyncSessionStore
import dev.shinsou.kmp.sync.persistence.createJvmFileDeviceDirectoryPinStore
import dev.shinsou.kmp.sync.persistence.JvmSyncProtectionCorruptException
import dev.shinsou.kmp.sync.persistence.JvmSyncProtectionUnavailableException
import dev.shinsou.kmp.sync.persistence.JvmSyncSecretProtector
import dev.shinsou.kmp.sync.persistence.SqlDriverSyncStatePersistence
import dev.shinsou.kmp.sync.persistence.SyncLocalSchema
import dev.shinsou.kmp.sync.persistence.SyncInstallationStore
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncPlatformInfrastructure
import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Android sync state uses SQLite while metadata/ciphertext indexes live in no-backup storage. */
internal class AndroidSyncInfrastructure(context: Context) : SyncPlatformInfrastructure {
    private val applicationContext = context.applicationContext
    private val metadataDirectory = applicationContext.noBackupFilesDir.resolve("SyncV2").apply { mkdirs() }

    override val installationStore: SyncInstallationStore =
        JvmFileSyncInstallationStore(metadataDirectory.resolve("installation.json").toPath())
    override val sessionStore: SyncSessionStore =
        JvmFileSyncSessionStore(metadataDirectory.resolve("session.json").toPath())
    override val secretStore: SyncSecretStore = JvmEncryptedSyncSecretStore(
        path = metadataDirectory.resolve("secrets.json").toPath(),
        protector = AndroidKeystoreSyncSecretProtector(),
    )
    override val deviceDirectoryPinStore: DeviceDirectoryPinStore =
        createJvmFileDeviceDirectoryPinStore(metadataDirectory.resolve("device-directory-pins.json").toPath())
    override val platform: String = "android"
    override val deviceDisplayName: String = Build.MODEL.trim().ifBlank { "Android device" }

    private val driverLock = Any()
    private var openPersistence: SqlDriverSyncStatePersistence? = null

    override fun statePersistence(): SqlDriverSyncStatePersistence = synchronized(driverLock) {
        openPersistence ?: SqlDriverSyncStatePersistence(
            AndroidSqliteDriver(
                schema = SyncLocalSchema,
                context = applicationContext,
                name = DATABASE_NAME,
            ),
        ).also { openPersistence = it }
    }

    override fun close() = synchronized(driverLock) {
        openPersistence?.close()
        openPersistence = null
    }

    private companion object {
        const val DATABASE_NAME = "shinsou-sync-v2.db"
    }
}

/** All values use one non-exportable Keystore AES key; there is no file/plaintext fallback. */
private class AndroidKeystoreSyncSecretProtector : JvmSyncSecretProtector {
    override fun encrypt(plaintext: ByteArray, protectedValuesExist: Boolean): ByteArray {
        val key = secretKey(createIfMissing = !protectedValuesExist)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val ciphertext = cipher.doFinal(plaintext)
            try {
                val iv = cipher.iv
                require(iv.size in 12..32) { "Android Keystore returned an invalid nonce" }
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
            throw JvmSyncProtectionUnavailableException("Android Keystore encryption is unavailable", error)
        }
    }

    override fun decrypt(payload: ByteArray): ByteArray {
        if (payload.size < MINIMUM_ENVELOPE_BYTES || payload[0] != ENVELOPE_VERSION) {
            throw JvmSyncProtectionCorruptException("Android secret envelope has an invalid version or size")
        }
        val ivSize = payload[1].toInt() and 0xff
        if (ivSize !in 12..32 || payload.size <= 2 + ivSize) {
            throw JvmSyncProtectionCorruptException("Android secret envelope has an invalid nonce")
        }
        val key = secretKey(createIfMissing = false)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, payload.copyOfRange(2, 2 + ivSize)),
            )
            cipher.doFinal(payload.copyOfRange(2 + ivSize, payload.size))
        } catch (error: AEADBadTagException) {
            throw JvmSyncProtectionCorruptException("Android secret envelope authentication failed", error)
        } catch (error: GeneralSecurityException) {
            throw JvmSyncProtectionUnavailableException("Android Keystore decryption is unavailable", error)
        }
    }

    private fun secretKey(createIfMissing: Boolean): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        } catch (error: GeneralSecurityException) {
            throw JvmSyncProtectionUnavailableException("Android Keystore is unavailable", error)
        }
        val existing = try {
            keyStore.getKey(KEY_ALIAS, null)
        } catch (error: GeneralSecurityException) {
            throw JvmSyncProtectionUnavailableException("Android sync key is inaccessible", error)
        }
        if (existing != null) {
            return existing as? SecretKey
                ?: throw JvmSyncProtectionCorruptException("Android sync key has an unexpected type")
        }
        if (!createIfMissing) {
            throw JvmSyncProtectionUnavailableException("Android sync key is missing")
        }

        return try {
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
                init(
                    KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                    )
                        .setKeySize(256)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setRandomizedEncryptionRequired(true)
                        .build(),
                )
                generateKey()
            }
        } catch (error: GeneralSecurityException) {
            throw JvmSyncProtectionUnavailableException("Unable to create the Android sync key", error)
        }
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "dev.aluo.shinsoux.sync-secrets.v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val MINIMUM_ENVELOPE_BYTES = 2 + 12 + 16
        val ENVELOPE_VERSION: Byte = 1
    }
}
