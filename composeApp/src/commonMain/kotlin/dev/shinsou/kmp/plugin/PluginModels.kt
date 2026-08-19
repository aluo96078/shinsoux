package dev.shinsou.kmp.plugin

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
    val minRuntimeVersion: String? = null,
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
)

@Serializable
public data class SourceIndexEntry(
    val name: String,
    val lang: String,
    @Serializable(with = StringOrNumberLongSerializer::class)
    val id: Long,
    val baseUrl: String? = null,
)

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
