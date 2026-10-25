package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.events.PluginHostPermission
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

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
    /**
     * Host-service permissions declared by the executable artifact. `null` is reserved for
     * packages installed before runtime permissions were part of the Shinsou manifest contract.
     */
    val runtimePermissions: Set<PluginRuntimePermission>? = null,
    /**
     * Verified package-level V2 content-kind union. `null` identifies a pre-V2 manifest; an
     * explicitly empty set remains authoritative and must not regain legacy image compatibility.
     */
    val contentKinds: Set<String>? = null,
    /** Executable V2 contract persisted so runtime reconstruction can re-apply its allowlist. */
    val contract: String? = null,
    /** Executable V2 runtime/adapter persisted for fail-closed restart admission. */
    val runtime: String? = null,
    /** Presence marks a sidecar-backed V2 install even if a forged manifest omits its pair. */
    val sidecarUrl: String? = null,
)

/** Sensitive service ports available to a generic script runtime. */
@Serializable
public enum class PluginRuntimePermission {
    EXECUTE_SCRIPT,
    NETWORK,
    COOKIE_STORAGE,
    CREDENTIAL_ACCESS,
    LOGIN_PROMPT,
    FAVORITE_MUTATION,
    BROWSER_CHALLENGE,

    ;

    public companion object {
        /** Explicit opt-in bridge policy for tests and narrowly reviewed compatibility callers. */
        public val LEGACY_COMPATIBILITY: Set<PluginRuntimePermission> = setOf(
            EXECUTE_SCRIPT,
            NETWORK,
            COOKIE_STORAGE,
            CREDENTIAL_ACCESS,
            LOGIN_PROMPT,
            FAVORITE_MUTATION,
            BROWSER_CHALLENGE,
        )
    }
}

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
    /** V2 host runtime/adapter identifier (for example `legacy-shinsou-adapter-v2`). */
    val runtime: String? = null,
    /** Package-level content-kind union retained for exact index/sidecar admission parity. */
    val contentKinds: Set<String> = emptySet(),
    /** Package capabilities retained as signed review input, not inferred host grants. */
    val capabilities: Set<String> = emptySet(),
    /** Optional V2 sidecar containing the same exact-artifact admission declaration. */
    val sidecarUrl: String? = null,
    /** Requested event capabilities; installation does not turn these into grants. */
    val systemEvents: PluginSystemEventDeclaration? = null,
    /** Requested host permissions are review input only and never self-authorizing. */
    val requestedHostPermissions: Set<dev.shinsou.kmp.plugin.events.PluginHostPermission> = emptySet(),
    /** `null` identifies a legacy index entry which predates explicit runtime permissions. */
    val runtimePermissions: Set<PluginRuntimePermission>? = null,
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
    /** Source-specific content kinds retained for exact V2 sidecar admission parity. */
    val contentKinds: Set<String> = emptySet(),
    /** Presence bit keeps V2 wire omission distinct from an explicitly empty source override. */
    val contentKindsDeclared: Boolean? = null,
    /** Exact HTTPS origins available to active script HTTP calls. */
    val requestOrigins: Set<String> = emptySet(),
    /** Subset of [requestOrigins] which may receive cookies, authorization and source headers. */
    val credentialOrigins: Set<String> = emptySet(),
    /** Exact HTTPS origins available only to host-owned GET/HEAD image/content fetches. */
    val contentOrigins: Set<String> = emptySet(),
    /** Exact HTTPS API origins allowed to use this source's browser network transport. */
    val browserSessionOrigins: Set<String> = emptySet(),
    /** Persisted marker distinguishing authoritative V2 empty allowlists from legacy omission. */
    val originPolicyVersion: Int? = null,
    /** Lossless V2 legacy identifier copied into the sidecar SourceKey when present. */
    val legacyLongId: String? = null,
    /**
     * Exact V2 source identity when a source is backed by a legacy numeric engine scope.
     * This must survive package persistence: [id] remains the engine/storage compatibility
     * value, while every V2 selection continues to compare this opaque string identity.
     */
    val canonicalSourceId: String? = null,
) {
    init {
        val effectiveRequestOrigins = declaredRequestOrigins()
        val normalizedCredentials = normalizePluginOrigins(credentialOrigins)
        normalizePluginOrigins(contentOrigins)
        normalizePluginBrowserSessionOrigins(browserSessionOrigins)
        require(normalizedCredentials.all { it in effectiveRequestOrigins }) {
            "Credential origins must also be declared request origins"
        }
    }

    /**
     * Legacy manifests are narrowed to their HTTPS base origin. Once any origin declaration is
     * present, including an explicit empty set, the declaration is authoritative and an empty set
     * denies access. V2 readers set [originPolicyVersion] explicitly, while old serialized
     * manifests leave every origin field absent.
     */
    public fun declaredRequestOrigins(): Set<String> = if (usesLegacyOriginCompatibility()) {
        baseUrl?.let(::pluginHttpsOriginOrNull)?.let(::setOf).orEmpty()
    } else {
        normalizePluginOrigins(requestOrigins)
    }

    /** Legacy content is restricted to its source origin until a signed manifest declares more. */
    public fun declaredContentOrigins(): Set<String> = if (usesLegacyOriginCompatibility()) {
        baseUrl?.let(::pluginHttpsOriginOrNull)?.let(::setOf).orEmpty()
    } else {
        normalizePluginOrigins(contentOrigins)
    }

    public fun networkPolicy(): PluginNetworkPolicy = PluginNetworkPolicy(
        requestOrigins = declaredRequestOrigins(),
        credentialOrigins = if (usesLegacyOriginCompatibility()) {
            declaredRequestOrigins()
        } else {
            normalizePluginOrigins(credentialOrigins)
        },
        contentOrigins = declaredContentOrigins(),
        // Pre-contract installs had no browser-session field. Their compatibility policy is
        // deliberately narrow: only the source's exact HTTPS base origin is usable. New
        // manifests (originPolicyVersion != null) keep an explicitly empty declaration denied.
        browserSessionOrigins = if (usesLegacyOriginCompatibility()) {
            setOfNotNull(baseUrl?.let(::pluginHttpsOriginOrNull))
        } else {
            normalizePluginBrowserSessionOrigins(browserSessionOrigins)
        },
    )

    /**
     * Only a source with no origin-policy fields at all can be from the pre-policy manifest
     * format. A V2 source is never treated as legacy, even when all of its allowlists are empty.
     */
    private fun usesLegacyOriginCompatibility(): Boolean =
        originPolicyVersion == null &&
            requestOrigins.isEmpty() &&
            credentialOrigins.isEmpty() &&
            contentOrigins.isEmpty() &&
            browserSessionOrigins.isEmpty()
}

internal fun pluginHttpsOriginOrNull(value: String): String? = runCatching {
    val parsed = io.ktor.http.Url(value.trim())
    require(parsed.protocol.name.equals("https", ignoreCase = true) && parsed.host.isNotBlank())
    pluginOrigin(parsed)
}.getOrNull()

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
    /** Immutable identity of every repository field which can affect admission or execution. */
    val admissionFingerprint: String? = null,
)

/**
 * Stable identity for the complete repository row used to render and later install a plugin.
 * Collections which are sets are sorted first, so harmless wire ordering cannot change identity;
 * source list ordering is retained because it is observable by legacy runtimes.
 */
internal fun PluginIndexEntry.admissionFingerprint(): String = Sha256.hex(
    buildString {
        append("shinsou-repository-entry-v1\n")
        append(REPOSITORY_ADMISSION_JSON.encodeToString(PluginIndexEntry.serializer(), canonicalForAdmission()))
        append("\ncanonical-source-identities-v1\n")
        sources.orEmpty().forEach { source ->
            val identity = source.canonicalSourceId
            append(identity?.length ?: -1).append(':')
            if (identity != null) append(identity)
            append('\n')
        }
    }.encodeToByteArray(),
)

internal fun LegacyExtensionIndexEntry.admissionFingerprint(): String = Sha256.hex(
    REPOSITORY_ADMISSION_JSON.encodeToString(
        LegacyExtensionIndexEntry.serializer(),
        copy(sources = sources?.map(SourceIndexEntry::canonicalForAdmission)),
    ).encodeToByteArray(),
)

private fun PluginIndexEntry.canonicalForAdmission(): PluginIndexEntry = copy(
    sources = sources?.map(SourceIndexEntry::canonicalForAdmission),
    contentKinds = contentKinds.sortedStringSet(),
    capabilities = capabilities.sortedStringSet(),
    systemEvents = systemEvents?.copy(
        required = systemEvents.required.sortedStringSet(),
        optional = systemEvents.optional.sortedStringSet(),
    ),
    requestedHostPermissions = requestedHostPermissions.sortedBy { it.name }.toCollection(linkedSetOf()),
    runtimePermissions = runtimePermissions?.sortedBy { it.name }?.toCollection(linkedSetOf()),
)

private fun SourceIndexEntry.canonicalForAdmission(): SourceIndexEntry = copy(
    contentKinds = contentKinds.sortedStringSet(),
    requestOrigins = requestOrigins.sortedStringSet(),
    credentialOrigins = credentialOrigins.sortedStringSet(),
    contentOrigins = contentOrigins.sortedStringSet(),
    browserSessionOrigins = browserSessionOrigins.sortedStringSet(),
)

private fun Set<String>.sortedStringSet(): Set<String> = sorted().toCollection(linkedSetOf())

private val REPOSITORY_ADMISSION_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

@Serializable
public data class InstalledPluginMetadata(
    val manifest: PluginManifest,
    val repositoryBaseUrl: String? = null,
    val installedSha256: String,
    /** True for the current unsigned index.json protocol (TLS + trust-on-install). */
    val legacyTrustOnInstall: Boolean = false,
    /**
     * Old serialized installations may claim their numeric storage once during SourceKey
     * migration. New installations are written with this disabled, so recycling a Long cannot
     * inherit a previous package's state.
     */
    val legacyStorageMigrationAllowed: Boolean = true,
)

public data class StoredPlugin(
    val metadata: InstalledPluginMetadata,
    val scriptBytes: ByteArray,
) {
    val manifest: PluginManifest get() = metadata.manifest
    val script: String get() = scriptBytes.decodeToString()
}
