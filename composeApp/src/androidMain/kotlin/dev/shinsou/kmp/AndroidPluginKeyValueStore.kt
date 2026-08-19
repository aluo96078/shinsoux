package dev.shinsou.kmp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.isSensitivePluginKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SharedPreferences-backed plugin state. Credentials and tokens are encrypted by a non-exportable
 * Android Keystore AES key; manifests, scripts, preferences and trust hashes remain plain JSON.
 */
internal class AndroidPluginKeyValueStore(context: Context) : PluginKeyValueStore {
    private val preferences = context.getSharedPreferences("shinsou-plugin-state", Context.MODE_PRIVATE)
    private val migrationLock = Any()
    @Volatile
    private var migrationChecked = false

    override suspend fun getString(key: String): String? {
        ensureSensitiveValuesMigrated()
        val stored = preferences.getString(key, null) ?: return null
        if (!isSensitivePluginKey(key)) return stored
        if (stored.startsWith(ENCRYPTED_PREFIX)) {
            return runCatching { decrypt(stored.removePrefix(ENCRYPTED_PREFIX)) }.getOrNull()
        }
        check(preferences.edit().putString(key, ENCRYPTED_PREFIX + encrypt(stored)).commit()) {
            "Unable to persist encrypted plugin value"
        }
        return stored
    }

    override suspend fun putString(key: String, value: String) {
        ensureSensitiveValuesMigrated()
        val stored = if (isSensitivePluginKey(key)) ENCRYPTED_PREFIX + encrypt(value) else value
        check(preferences.edit().putString(key, stored).commit()) { "Unable to persist plugin value" }
    }

    override suspend fun remove(key: String) {
        ensureSensitiveValuesMigrated()
        check(preferences.edit().remove(key).commit()) { "Unable to remove plugin value" }
    }

    /** Encrypts every legacy sensitive entry once, including values not touched in this session. */
    private fun ensureSensitiveValuesMigrated() {
        if (migrationChecked) return
        synchronized(migrationLock) {
            if (migrationChecked) return
            if (preferences.getBoolean(MIGRATION_MARKER, false)) {
                migrationChecked = true
                return
            }
            val editor = preferences.edit()
            preferences.all.forEach { (key, raw) ->
                val value = raw as? String ?: return@forEach
                if (isSensitivePluginKey(key) && !value.startsWith(ENCRYPTED_PREFIX)) {
                    editor.putString(key, ENCRYPTED_PREFIX + encrypt(value))
                }
            }
            editor.putBoolean(MIGRATION_MARKER, true)
            check(editor.commit()) { "Unable to complete plugin secret migration" }
            migrationChecked = true
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.encodeToByteArray())
        val payload = ByteArray(1 + cipher.iv.size + encrypted.size)
        payload[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(payload, 1)
        encrypted.copyInto(payload, 1 + cipher.iv.size)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): String {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize in 12..32 && payload.size > 1 + ivSize)
        val iv = payload.copyOfRange(1, 1 + ivSize)
        val encrypted = payload.copyOfRange(1 + ivSize, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return cipher.doFinal(encrypted).decodeToString()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val KEY_ALIAS = "dev.aluo.shinsoux.plugin-secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        const val MIGRATION_MARKER = "plugin.secrets.migration.v1"
    }
}
