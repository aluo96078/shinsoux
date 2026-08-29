package dev.shinsou.kmp.plugin

public sealed class PluginVerificationException(message: String) : IllegalArgumentException(message) {
    public class HashMismatch(public val expected: String, public val actual: String) :
        PluginVerificationException("Hash mismatch: expected $expected, got $actual")

    public class Untrusted(public val pluginId: String, public val versionCode: Int, public val hash: String) :
        PluginVerificationException("Plugin '$pluginId' v$versionCode is not trusted")

    public class UnsafeIdentifier(public val value: String) :
        PluginVerificationException("Unsafe plugin identifier or script name: '$value'")
}

public data class VerifiedPlugin(
    val sha256: String,
    val versionCode: Int,
)

/**
 * SHA-256 integrity verification plus the `{pluginId}:{versionCode}:{hash}` execution grant.
 *
 * A matching manifest digest proves only that the persisted bytes still match the package that
 * was installed. It is not, by itself, permission to execute those bytes after the user revokes
 * the package's trust token.
 */
public class PluginVerifier(
    private val trustStore: PluginTrustStore,
) {
    /**
     * Validates an executable package and requires an execution grant. [trustOnValidatedDigest]
     * is reserved for an explicit install/update action; startup loading must pass `false`.
     */
    public suspend fun verify(
        scriptBytes: ByteArray,
        manifest: PluginManifest,
        trustOnValidatedDigest: Boolean = true,
    ): VerifiedPlugin {
        val verified = verifyIntegrity(scriptBytes, manifest)
        val alreadyTrusted = trustStore.isTrusted(manifest.id, verified.versionCode, verified.sha256)
        if (!alreadyTrusted) {
            val hasValidatedDigest = manifest.signature.isNotBlank()
            if (!trustOnValidatedDigest || !hasValidatedDigest) {
                throw PluginVerificationException.Untrusted(
                    manifest.id,
                    verified.versionCode,
                    verified.sha256,
                )
            }
            trustStore.trust(manifest.id, verified.versionCode, verified.sha256)
        }
        return verified
    }

    /** Verifies persisted bytes before granting execution from the UI toggle. */
    public suspend fun trustInstalled(
        scriptBytes: ByteArray,
        manifest: PluginManifest,
        installedSha256: String,
    ): VerifiedPlugin {
        val verified = verifyIntegrity(scriptBytes, manifest)
        val recorded = installedSha256.trim().lowercase()
        if (recorded != verified.sha256) {
            throw PluginVerificationException.HashMismatch(recorded, verified.sha256)
        }
        trustStore.trust(manifest.id, verified.versionCode, verified.sha256)
        return verified
    }

    /** Metadata-only legacy packages never execute code, but their stub sources share the grant. */
    public suspend fun trustMetadataOnly(manifest: PluginManifest, installedSha256: String) {
        validateSafeFileComponent(manifest.id)
        validateSafeFileName(manifest.script)
        trustStore.trust(
            manifest.id,
            manifest.versionCode ?: versionInt(manifest.version),
            installedSha256.trim().lowercase(),
        )
    }

    public suspend fun isTrusted(manifest: PluginManifest, installedSha256: String): Boolean {
        validateSafeFileComponent(manifest.id)
        validateSafeFileName(manifest.script)
        return trustStore.isTrusted(
            manifest.id,
            manifest.versionCode ?: versionInt(manifest.version),
            installedSha256.trim().lowercase(),
        )
    }

    public suspend fun revokeAll(pluginId: String) {
        trustStore.revokeAll(pluginId)
    }

    /**
     * Validates the persisted grant used by the pre-signature index protocol without touching the
     * platform secure store.  Older installs recorded the package digest in their metadata and
     * marked it as trusted during installation; requiring that digest to still match lets those
     * packages start even when a locked macOS Keychain cannot answer synchronously.  Explicit
     * revocations clear this legacy flag before removing the trust token.
     */
    private fun verifyIntegrity(scriptBytes: ByteArray, manifest: PluginManifest): VerifiedPlugin {
        validateSafeFileComponent(manifest.id)
        validateSafeFileName(manifest.script)

        val actual = Sha256.hex(scriptBytes)
        val versionCode = manifest.versionCode ?: versionInt(manifest.version)
        val expected = manifest.signature.trim().lowercase()
        if (expected.isNotEmpty() && expected != actual) {
            throw PluginVerificationException.HashMismatch(expected, actual)
        }
        return VerifiedPlugin(actual, versionCode)
    }

    public companion object {
        public fun isLegacyTrustValid(stored: StoredPlugin): Boolean {
            val metadata = stored.metadata
            if (!metadata.legacyTrustOnInstall) return false
            if (stored.scriptBytes.isEmpty()) {
                return metadata.installedSha256.isBlank() && metadata.manifest.signature.isBlank()
            }
            val actual = Sha256.hex(stored.scriptBytes)
            val expected = metadata.installedSha256.trim().lowercase()
            val signature = metadata.manifest.signature.trim().lowercase()
            return expected.isNotEmpty() && actual == expected &&
                (signature.isEmpty() || signature == actual)
        }

        public fun versionInt(version: String): Int {
            val parts = version.split('.').mapNotNull(String::toIntOrNull)
            if (parts.isEmpty()) return 0
            return when (parts.size) {
                1 -> parts[0]
                2 -> parts[0] * 100 + parts[1]
                else -> parts[0] * 10_000 + parts[1] * 100 + parts[2]
            }
        }

        public fun validateSafeFileComponent(value: String) {
            val windowsDeviceName = value.substringBefore('.').uppercase()
            if (value.isBlank() || value == "." || value == ".." ||
                value.endsWith('.') || value.endsWith(' ') ||
                value.any { character ->
                    character.code < 32 || character in WINDOWS_FORBIDDEN_FILE_CHARACTERS
                } || windowsDeviceName in WINDOWS_RESERVED_DEVICE_NAMES
            ) {
                throw PluginVerificationException.UnsafeIdentifier(value)
            }
        }

        public fun validateSafeFileName(value: String) {
            validateSafeFileComponent(value)
            if (!value.endsWith(".js", ignoreCase = true)) {
                throw PluginVerificationException.UnsafeIdentifier(value)
            }
        }

        private val WINDOWS_FORBIDDEN_FILE_CHARACTERS = setOf('/', '\\', '<', '>', ':', '"', '|', '?', '*')
        private val WINDOWS_RESERVED_DEVICE_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL"))
            (1..9).forEach { suffix ->
                add("COM$suffix")
                add("LPT$suffix")
            }
        }
    }
}

public object Sha256 {
    private val constants = intArrayOf(
        0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
        0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
        0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
        0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
        0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
        0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
        0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
    )

    public fun hex(input: ByteArray): String = digest(input).joinToString("") { byte ->
        byte.toUByte().toString(16).padStart(2, '0')
    }

    public fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8L
        val paddedLength = ((input.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.lastIndex - i] = (bitLength ushr (8 * i)).toByte()
        }

        val hash = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19,
        )
        val words = IntArray(64)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                val at = offset + i * 4
                words[i] = ((padded[at].toInt() and 0xff) shl 24) or
                    ((padded[at + 1].toInt() and 0xff) shl 16) or
                    ((padded[at + 2].toInt() and 0xff) shl 8) or
                    (padded[at + 3].toInt() and 0xff)
            }
            for (i in 16 until 64) {
                val s0 = rotateRight(words[i - 15], 7) xor rotateRight(words[i - 15], 18) xor
                    (words[i - 15] ushr 3)
                val s1 = rotateRight(words[i - 2], 17) xor rotateRight(words[i - 2], 19) xor
                    (words[i - 2] ushr 10)
                words[i] = words[i - 16] + s0 + words[i - 7] + s1
            }

            var a = hash[0]
            var b = hash[1]
            var c = hash[2]
            var d = hash[3]
            var e = hash[4]
            var f = hash[5]
            var g = hash[6]
            var h = hash[7]
            for (i in 0 until 64) {
                val sum1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
                val choice = (e and f) xor (e.inv() and g)
                val temp1 = h + sum1 + choice + constants[i] + words[i]
                val sum0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = sum0 + majority
                h = g
                g = f
                f = e
                e = d + temp1
                d = c
                c = b
                b = a
                a = temp1 + temp2
            }
            hash[0] += a
            hash[1] += b
            hash[2] += c
            hash[3] += d
            hash[4] += e
            hash[5] += f
            hash[6] += g
            hash[7] += h
            offset += 64
        }

        return ByteArray(32).also { output ->
            hash.forEachIndexed { index, value ->
                output[index * 4] = (value ushr 24).toByte()
                output[index * 4 + 1] = (value ushr 16).toByte()
                output[index * 4 + 2] = (value ushr 8).toByte()
                output[index * 4 + 3] = value.toByte()
            }
        }
    }

    private fun rotateRight(value: Int, bits: Int): Int =
        (value ushr bits) or (value shl (32 - bits))
}
