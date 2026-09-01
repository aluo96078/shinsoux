package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Metadata returned by a Shinsou repository's `repo.json`. */
@Serializable
public data class RepositoryDocument(
    val meta: RepositoryMeta,
)

/**
 * Optional envelope understood by both repository readers. It lets one URL expose old Shinsou
 * packages and ShuYue packages without making either reader infer a protocol from `index.json`
 * or a hostname. A plain JSON array remains fully supported for both historical formats.
 */
@Serializable
public data class UnifiedRepositoryDocument(
    val format: String = "shinsou-unified-v1",
    val shinsou: List<PluginIndexEntry> = emptyList(),
    val legacy: List<PluginIndexEntry> = emptyList(),
    val shuyue: List<dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryEntry> = emptyList(),
)

@Serializable
public data class RepositoryMeta(
    val name: String,
    val shortName: String? = null,
    val website: String? = null,
    val signingKeyFingerprint: String? = null,
)

@Serializable
public data class ExtensionRepository(
    val baseUrl: String,
    val name: String,
    val shortName: String? = null,
    val website: String = baseUrl,
    val signingKeyFingerprint: String = "",
)

/** Companion manifest stored next to an installed JavaScript file. */
@Serializable
public data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int? = null,
    val lang: String,
    val nsfw: Boolean = false,
    val script: String,
    val signature: String,
    val minRuntimeVersion: String? = null,
    val sources: List<SourceIndexEntry>? = null,
    /** Optional request declaration; grants are still host-reviewed per exact artifact digest. */
    val systemEvents: PluginSystemEventDeclaration? = null,
    /** Requested permissions retained for review; never interpreted as grants. */
    val requestedHostPermissions: Set<PluginHostPermission> = emptySet(),
)

/** Shinsou JavaScript entry from `index.json`. */
@Serializable
public data class PluginIndexEntry(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val lang: String,
    val nsfw: Int = 0,
    val scriptUrl: String,
    val iconUrl: String? = null,
    val description: String? = null,
    val sources: List<SourceIndexEntry>? = null,
    /** Optional protocol-v2 digest. Existing repositories omit it. */
    val sha256: String? = null,
    val byteSize: Int? = null,
    val minRuntimeVersion: String? = null,
    /** Optional unified-contract type hint. Missing/unknown values resolve to [PluginContentType.BOTH]. */
    val type: String? = null,
    /** Alias accepted by newer repositories; [type] remains the canonical wire key. */
    val contentType: String? = null,
    /** `shinsou`/`shuyue` marker used only by a unified repository index. */
    val contract: String? = null,
    /** Optional V2 sidecar containing the same exact-artifact admission declaration. */
    val sidecarUrl: String? = null,
    /** Requested event capabilities; installation does not turn these into grants. */
    val systemEvents: PluginSystemEventDeclaration? = null,
    /** Requested host permissions are review input only and never self-authorizing. */
    val requestedHostPermissions: Set<dev.shinsou.kmp.plugin.events.PluginHostPermission> = emptySet(),
    val installable: Boolean = true,
    val referenceOnly: Boolean = false,
    val legacyCompatibilityOnly: Boolean = false,
)

/** Mihon/Tachiyomi metadata entry from `index.min.json`. APKs are metadata-only. */
@Serializable
public data class LegacyExtensionIndexEntry(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String,
    val code: Int,
    val version: String,
    val nsfw: Int = 0,
    val sources: List<SourceIndexEntry>? = null,
    val type: String? = null,
    val contentType: String? = null,
    val contract: String? = null,
)

@Serializable
public data class SourceIndexEntry(
    val name: String,
    val lang: String,
    @Serializable(with = StringOrNumberLongSerializer::class)
    val id: Long,
    val baseUrl: String? = null,
    val type: String? = null,
    val contentType: String? = null,
    /** Exact HTTPS API origins allowed to use this source's browser network transport. */
    val browserSessionOrigins: Set<String> = emptySet(),
    /**
     * Optional v2 canonical identity used when a reviewed source is backed by a legacy engine.
     * It is process-local metadata: the legacy engine still uses [id] as its host execution
     * scope, while source selection must compare the script's opaque string id.
     */
    @Transient
    val canonicalSourceId: String? = null,
) {
    init {
        normalizePluginBrowserSessionOrigins(browserSessionOrigins)
    }
}

/** Accepts both native Shinsou numeric IDs and Mihon's quoted 64-bit IDs. */
public object StringOrNumberLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("StringOrNumberLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val primitive = decoder.decodeJsonElement() as? JsonPrimitive
            ?: throw SerializationException("Source id must be a number or string")
        return primitive.longOrNull
            ?: primitive.content.toLongOrNull()
            ?: throw SerializationException("Source id '${primitive.content}' is not an Int64")
    }

    override fun serialize(encoder: Encoder, value: Long) {
        encoder.encodeLong(value)
    }
}

public enum class ExtensionState {
    AVAILABLE,
    INSTALLED,
    UPDATE_AVAILABLE,
    INSTALLING,
}

public data class ExtensionDescriptor(
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val lang: String,
    val nsfw: Boolean,
    val sources: List<SourceIndexEntry>,
    val repositoryBaseUrl: String?,
    val scriptUrl: String?,
    val iconUrl: String?,
    val description: String?,
    val state: ExtensionState,
    val installedVersion: String? = null,
    val contentType: PluginContentType = PluginContentType.BOTH,
)

@Serializable
public data class InstalledPluginMetadata(
    val manifest: PluginManifest,
    val repositoryBaseUrl: String? = null,
    val installedSha256: String,
    /** True for the current unsigned index.json protocol (TLS + trust-on-install). */
    val legacyTrustOnInstall: Boolean = false,
)

public data class StoredPlugin(
    val metadata: InstalledPluginMetadata,
    val scriptBytes: ByteArray,
) {
    val manifest: PluginManifest get() = metadata.manifest
    val script: String get() = scriptBytes.decodeToString()
}
