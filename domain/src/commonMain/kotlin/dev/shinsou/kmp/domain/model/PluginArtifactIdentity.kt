package dev.shinsou.kmp.domain.model

import kotlinx.serialization.Serializable

/** Exact installed artifact identity. A package id alone is never an authorization key. */
@Serializable
public data class PluginArtifactIdentity(
    val packageId: String,
    val version: String,
    val versionCode: Int,
    val sha256: String,
) {
    init {
        require(isSafeAsciiIdentifier(packageId, 128)) { "Invalid plugin package id" }
        require(isSafeAsciiIdentifier(version, 64)) { "Invalid plugin version" }
        require(versionCode > 0) { "Plugin version code must be positive" }
        require(SHA256_PATTERN.matches(sha256)) { "Plugin digest must be lowercase SHA-256" }
    }
}

/** The exact source of one installed artifact: what a host prompt (such as a login) is bound to. */
public data class ExactPluginSourceTarget(
    val artifactIdentity: PluginArtifactIdentity,
    val sourceKey: SourceKey,
)

/** Whether [value] is a non-empty identifier of at most [maxBytes] safe ASCII characters. */
fun isSafeAsciiIdentifier(value: String, maxBytes: Int): Boolean =
    value.isNotEmpty() && value.encodeToByteArray().size <= maxBytes &&
        value.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in ".:_/-~" }

private val SHA256_PATTERN: Regex = Regex("[0-9a-f]{64}")
