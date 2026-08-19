package dev.shinsou.kmp.desktop

import dev.shinsou.kmp.plugin.PluginJson
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.isSensitivePluginKey
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Atomic, owner-only desktop persistence with AES-GCM protection for credential/token values. */
internal class DesktopPluginKeyValueStore(
    private val directory: Path = Path.of(
        System.getProperty("user.home"),
        "Library",
        "Application Support",
        "Shinsou",
    ),
    private val masterKeyStore: DesktopMasterKeyStore = MacOsKeychainMasterKeyStore(),
    private val secureRandom: SecureRandom = SecureRandom(),
) : PluginKeyValueStore {
    private val lock = Any()
    private val stateFile = directory.resolve("plugin-state.json")
    private val keyFile = directory.resolve("plugin-secrets.key")
    private val temporaryKeyFile = directory.resolve("plugin-secrets.key.tmp")
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private var cachedMasterKey: ByteArray? = null

    override suspend fun getString(key: String): String? = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val values = readState()
            val value = values[key] ?: return@synchronized null
            if (!isSensitivePluginKey(key)) return@synchronized value
            if (value.startsWith(ENCRYPTED_PREFIX)) {
                val masterKey = masterKey(values)
                return@synchronized runCatching {
                    decrypt(value.removePrefix(ENCRYPTED_PREFIX), masterKey)
                }.getOrNull()
            }
            writeState(values + (key to (ENCRYPTED_PREFIX + encrypt(value, masterKey(values)))))
            value
        }
    }

    override suspend fun putString(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val values = readState().toMutableMap()
            values[key] = if (isSensitivePluginKey(key)) {
                ENCRYPTED_PREFIX + encrypt(value, masterKey(values))
            } else {
                value
            }
            writeState(values)
        }
    }

    override suspend fun remove(key: String): Unit = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val values = readState().toMutableMap()
            if (values.remove(key) != null) writeState(values)
        }
    }

    private fun readState(): Map<String, String> {
        if (!Files.exists(stateFile)) return emptyMap()
        return runCatching {
            PluginJson.decodeFromString(serializer, Files.readString(stateFile, StandardCharsets.UTF_8))
        }.getOrElse { error ->
            throw IllegalStateException("Desktop plugin state could not be decoded safely.", error)
        }
    }

    private fun writeState(values: Map<String, String>) {
        Files.createDirectories(directory)
        setOwnerOnly(directory, directory = true)
        val temporary = directory.resolve("plugin-state.json.tmp")
        Files.writeString(temporary, PluginJson.encodeToString(serializer, values), StandardCharsets.UTF_8)
        setOwnerOnly(temporary, directory = false)
        moveAtomically(temporary, stateFile)
        setOwnerOnly(stateFile, directory = false)
    }

    private fun encrypt(value: String, masterKey: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(masterKey, "AES"))
        val ciphertext = cipher.doFinal(value.encodeToByteArray())
        val payload = ByteArray(1 + cipher.iv.size + ciphertext.size)
        payload[0] = cipher.iv.size.toByte()
        cipher.iv.copyInto(payload, 1)
        ciphertext.copyInto(payload, 1 + cipher.iv.size)
        return Base64.getEncoder().encodeToString(payload)
    }

    private fun decrypt(value: String, masterKey: ByteArray): String {
        val payload = Base64.getDecoder().decode(value)
        require(payload.isNotEmpty())
        val ivSize = payload[0].toInt() and 0xff
        require(ivSize in 12..32 && payload.size > 1 + ivSize)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(masterKey, "AES"),
            GCMParameterSpec(128, payload.copyOfRange(1, 1 + ivSize)),
        )
        return cipher.doFinal(payload.copyOfRange(1 + ivSize, payload.size)).decodeToString()
    }

    private fun masterKey(state: Map<String, String>): ByteArray {
        cachedMasterKey?.let { return it }
        val legacyKey = readLegacyMasterKey()
        val keychainKey = masterKeyStore.read()?.also(::requireValidMasterKey)
        val resolved = if (legacyKey != null) {
            migrateLegacyMasterKey(legacyKey, keychainKey, state)
        } else if (keychainKey != null) {
            keychainKey
        } else {
            ByteArray(MASTER_KEY_BYTES).also(secureRandom::nextBytes).also(::persistAndVerifyMasterKey)
        }
        requireValidMasterKey(resolved)
        cachedMasterKey = resolved
        return resolved
    }

    private fun readLegacyMasterKey(): ByteArray? {
        val source = when {
            Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS) -> keyFile
            Files.exists(temporaryKeyFile, LinkOption.NOFOLLOW_LINKS) -> temporaryKeyFile
            else -> return null
        }
        check(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
            "Refusing to follow a non-regular legacy desktop key file."
        }
        return runCatching {
            Base64.getDecoder().decode(Files.readString(source, StandardCharsets.US_ASCII).trim())
        }.getOrElse { error ->
            throw IllegalStateException("Legacy desktop master key could not be decoded.", error)
        }.also(::requireValidMasterKey)
    }

    private fun migrateLegacyMasterKey(
        legacyKey: ByteArray,
        keychainKey: ByteArray?,
        state: Map<String, String>,
    ): ByteArray {
        val encryptedValues = state.values
            .filter { it.startsWith(ENCRYPTED_PREFIX) }
            .map { it.removePrefix(ENCRYPTED_PREFIX) }
        val legacyDecryptsState = decryptsAll(encryptedValues, legacyKey)
        val keychainDecryptsState = keychainKey?.let { decryptsAll(encryptedValues, it) } == true

        val resolved = when {
            legacyDecryptsState -> {
                if (keychainKey == null || !keychainKey.contentEquals(legacyKey)) {
                    persistAndVerifyMasterKey(legacyKey)
                }
                legacyKey
            }

            keychainKey != null && keychainDecryptsState -> keychainKey
            else -> error("Neither the legacy file nor macOS Keychain can decrypt desktop plugin secrets.")
        }

        check(decryptsAll(encryptedValues, resolved)) {
            "Desktop plugin secrets were not decryptable after Keychain migration."
        }
        deleteLegacyMasterKeyFiles()
        return resolved
    }

    private fun persistAndVerifyMasterKey(masterKey: ByteArray) {
        requireValidMasterKey(masterKey)
        masterKeyStore.write(masterKey)
        val persisted = masterKeyStore.read()
        check(persisted != null && persisted.contentEquals(masterKey)) {
            "macOS Keychain did not return the desktop master key after writing it."
        }
    }

    private fun decryptsAll(encryptedValues: List<String>, masterKey: ByteArray): Boolean =
        encryptedValues.all { value -> runCatching { decrypt(value, masterKey) }.isSuccess }

    private fun requireValidMasterKey(masterKey: ByteArray) {
        require(masterKey.size == MASTER_KEY_BYTES) { "Desktop master key must be 256 bits." }
    }

    private fun deleteLegacyMasterKeyFiles() {
        Files.deleteIfExists(keyFile)
        Files.deleteIfExists(temporaryKeyFile)
    }

    private fun moveAtomically(source: Path, target: Path) {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun setOwnerOnly(path: Path, directory: Boolean) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                if (directory) {
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                    )
                } else {
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                },
            )
        }
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ENCRYPTED_PREFIX = "enc:v1:"
        const val MASTER_KEY_BYTES = 32
    }
}
