package dev.shinsou.kmp.plugin.trust

import dev.shinsou.kmp.crypto.Sha256
import dev.shinsou.kmp.plugin.model.PluginManifest
import dev.shinsou.kmp.plugin.model.StoredPlugin
import dev.shinsou.kmp.plugin.storage.PluginTrustStore

internal sealed class PluginVerificationException(message: String) : IllegalArgumentException(message) {
    class HashMismatch(public val expected: String, public val actual: String) :
        PluginVerificationException("Hash mismatch: expected $expected, got $actual")

    class Untrusted(public val pluginId: String, public val versionCode: Int, public val hash: String) :
        PluginVerificationException("Plugin '$pluginId' v$versionCode is not trusted")

    class UnsafeIdentifier(public val value: String) :
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
