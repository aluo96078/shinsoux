package dev.shinsou.kmp.plugin

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginSystemEventDeclaration
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryEntry
import dev.shinsou.kmp.plugin.shuyue.ShuYueRepositorySource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.time.Clock

public sealed class RepositoryIndex {
    public data class Plugins(val entries: List<PluginIndexEntry>) : RepositoryIndex()
    public data class Legacy(val entries: List<LegacyExtensionIndexEntry>) : RepositoryIndex()
    public data class Combined(
        val plugins: List<PluginIndexEntry>,
        val shuyue: List<dev.shinsou.kmp.plugin.shuyue.ShuYueRepositoryEntry>,
    ) : RepositoryIndex()
}

/**
 * Exact process-local admission paired with the repository index returned by one fetch.
 *
 * The opaque object identity prevents a descriptor from one authenticated refresh being paired
 * with a mutable "current index" installed by a later refresh. Unsigned developer repositories
 * deliberately receive no admission.
 */
internal class RepositoryIndexSnapshot internal constructor(
    internal val index: RepositoryIndex,
    internal val admission: RepositoryIndexAdmission?,
)

public class RepositoryIndexAdmission internal constructor()

public sealed class ExtensionRepositoryException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    public class InvalidUrl(value: String) : ExtensionRepositoryException("Invalid repository URL or path: $value")
    public class Http(public val status: Int, url: String) :
        ExtensionRepositoryException("HTTP $status while fetching $url")
    public class InvalidDocument(url: String, cause: Throwable) :
        ExtensionRepositoryException("Invalid repository document at $url", cause)
    public class NetworkSecurity(url: String, cause: Throwable) :
        ExtensionRepositoryException("Repository network admission rejected $url", cause)
}

public class ExtensionRepositoryClient(
    private val client: HttpClient,
    private val json: Json = PluginJson,
    private val cacheToken: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val repositoryTrustPolicy: RepositoryTrustPolicy = RepositoryTrustPolicies.REQUIRE_CONFIGURED_PIN,
    private val repositorySecurityState: RepositorySecurityStateStore =
        RepositorySecurityStateStore.RequireDurableState,
    /** Explicit compatibility for local/LAN development repositories; production is HTTPS-only. */
    private val allowInsecureDeveloperHttp: Boolean = false,
    /** Production repository DNS must be supplied by the platform composition. */
    private val repositoryHostResolver: PluginHostResolver = PluginHostResolver.Unavailable,
    /** Production transport must bind sockets to the already-vetted DNS answer set. */
    private val repositoryTransport: PluginHttpTransport? = null,
    /** Explicit process-start admission for one app-reviewed loopback repository. */
    private val reviewedLocalRepositoryPolicy: ReviewedLocalRepositoryPolicy =
        ReviewedLocalRepositoryPolicy.DISABLED,
    /** Dedicated credential-free loopback transport; never reused for ordinary repositories. */
    private val reviewedLocalRepositoryTransport: PluginHttpTransport? = null,
) {
    /** Repository admission owns redirect handling; Ktor must never follow an unvalidated hop. */
    private val repositoryHttpClient: HttpClient = client.config { followRedirects = false }
    private val signedDocuments = RepositorySignedDocumentVerifier(repositoryTrustPolicy, repositorySecurityState)
    private val authenticatedIndexCommitMutex = Mutex()
    private val artifactAdmissionMutex = Mutex()
    private val authenticatedIndexEntries = mutableMapOf<String, AuthenticatedIndexSnapshot>()

    public suspend fun fetchRepository(baseUrl: String): ExtensionRepository {
        val normalized = normalizeBaseUrl(baseUrl)
        val url = resolve(normalized, "repo.json", cacheBust = false)
        val verified = fetchVerifiedJson(
            normalized,
            url,
            RepositorySignedDocumentType.REPOSITORY,
            "repo.json",
            MAX_REPOSITORY_BYTES,
        )
        val document = decodeElement<RepositoryDocument>(url, verified.payload)
        if (reviewedLocalRepositoryPolicy.admits(normalized) && verified.admission == null) {
            require(document.meta.signingKeyFingerprint.isNullOrEmpty()) {
                "Unsigned reviewed local repository cannot advertise signing authority"
            }
        }
        val repository = ExtensionRepository(
            baseUrl = normalized,
            name = document.meta.name,
            shortName = document.meta.shortName,
            website = document.meta.website ?: normalized,
            signingKeyFingerprint = document.meta.signingKeyFingerprint.orEmpty(),
        )
        signedDocuments.commit(verified.admission)
        return repository
    }

    public suspend fun fetchPluginIndex(baseUrl: String): List<PluginIndexEntry> {
        val normalized = normalizeBaseUrl(baseUrl)
        val url = resolve(normalized, "index.json", cacheBust = true)
        val verified = fetchVerifiedJson(
            normalized,
            url,
            RepositorySignedDocumentType.INDEX,
            "index.json",
            MAX_INDEX_BYTES,
        )
        val root = verified.payload
        requireReviewedLocalV2Index(normalized, root)
        validateIndexJsonShape(root)
        if (root is JsonObject && isUnifiedEnvelope(root)) {
            val document = decodeElement<UnifiedRepositoryDocument>(url, root)
            val entries = document.shinsou + document.legacy
            validatePluginEntries(entries, requireAuthenticatedArtifacts = verified.admission != null)
            validateUnifiedIds(document)
            commitAuthenticatedIndex(normalized, entries, verified.admission)
            return entries
        }
        if (root is JsonObject && root["format"]?.jsonPrimitive?.contentOrNull == "shinsou-extension-v2") {
            val document = decodeV2Index(url, root, normalized).also(::validateCombinedIndex)
            val entries = document.plugins
            validatePluginEntries(entries, requireAuthenticatedArtifacts = verified.admission != null)
            commitAuthenticatedIndex(normalized, entries, verified.admission)
            return entries
        }
        val entries = decodeElement(url, root, ListSerializer(PluginIndexEntry.serializer())).also {
            validatePluginEntries(it, requireAuthenticatedArtifacts = verified.admission != null)
        }
        commitAuthenticatedIndex(normalized, entries, verified.admission)
        return entries
    }

    public suspend fun fetchLegacyIndex(baseUrl: String): List<LegacyExtensionIndexEntry> {
        val normalized = normalizeBaseUrl(baseUrl)
        if (reviewedLocalRepositoryPolicy.admits(normalized)) {
            throw ExtensionRepositoryException.InvalidUrl(
                "Legacy index is outside the reviewed local repository contract",
            )
        }
        val url = resolve(normalized, "index.min.json", cacheBust = true)
        val verified = fetchVerifiedJson(
            normalized,
            url,
            RepositorySignedDocumentType.LEGACY_INDEX,
            "index.min.json",
            MAX_INDEX_BYTES,
        )
        val entries = decodeElement(
            url,
            verified.payload.also(::validateIndexJsonShape),
            ListSerializer(LegacyExtensionIndexEntry.serializer()),
        ).also(::validateLegacyEntries)
        signedDocuments.commit(verified.admission)
        return entries
    }

    public suspend fun fetchIndex(baseUrl: String): RepositoryIndex = fetchIndexSnapshot(baseUrl).index

    internal suspend fun fetchIndexSnapshot(baseUrl: String): RepositoryIndexSnapshot =
        run {
            val normalized = normalizeBaseUrl(baseUrl)
            val url = resolve(normalized, "index.json", cacheBust = true)
            try {
                val verified = fetchVerifiedJson(
                    normalized,
                    url,
                    RepositorySignedDocumentType.INDEX,
                    "index.json",
                    MAX_INDEX_BYTES,
                )
                val root = verified.payload
                requireReviewedLocalV2Index(normalized, root)
                validateIndexJsonShape(root)
                if (root is JsonObject && isUnifiedEnvelope(root)) {
                    val document = decodeElement<UnifiedRepositoryDocument>(url, root)
                    validatePluginEntries(
                        document.shinsou + document.legacy,
                        requireAuthenticatedArtifacts = verified.admission != null,
                    )
                    validateUnifiedIds(document)
                    val index = RepositoryIndex.Combined(
                        plugins = document.shinsou + document.legacy,
                        shuyue = document.shuyue,
                    )
                    val admission = commitAuthenticatedIndex(normalized, index.plugins, verified.admission)
                    return@run RepositoryIndexSnapshot(index, admission)
                }
                if (root is JsonObject && root["format"]?.jsonPrimitive?.contentOrNull == "shinsou-extension-v2") {
                    val index = decodeV2Index(url, root, normalized).also(::validateCombinedIndex)
                    validatePluginEntries(index.plugins, requireAuthenticatedArtifacts = verified.admission != null)
                    val admission = commitAuthenticatedIndex(normalized, index.plugins, verified.admission)
                    return@run RepositoryIndexSnapshot(index, admission)
                }
                val index = RepositoryIndex.Plugins(
                    decodeElement(url, root, ListSerializer(PluginIndexEntry.serializer())).also {
                        validatePluginEntries(it, requireAuthenticatedArtifacts = verified.admission != null)
                    },
                )
                RepositoryIndexSnapshot(
                    index,
                    commitAuthenticatedIndex(normalized, index.entries, verified.admission),
                )
            } catch (pluginFailure: Throwable) {
                if (pluginFailure.hasRepositorySecurityFailure()) throw pluginFailure
                if (reviewedLocalRepositoryPolicy.admits(normalized)) throw pluginFailure
                try {
                    val legacyUrl = resolve(normalized, "index.min.json", cacheBust = true)
                    val legacyVerified = fetchVerifiedJson(
                        normalized,
                        legacyUrl,
                        RepositorySignedDocumentType.LEGACY_INDEX,
                        "index.min.json",
                        MAX_INDEX_BYTES,
                    )
                    val index = RepositoryIndex.Legacy(decodeElement(
                        legacyUrl,
                        legacyVerified.payload.also(::validateIndexJsonShape),
                        ListSerializer(LegacyExtensionIndexEntry.serializer()),
                    ).also(::validateLegacyEntries))
                    // Legacy packages are metadata-only stubs; they never receive artifact
                    // execution admission, but this refresh still revokes a prior executable
                    // snapshot for the same repository URL.
                    clearAuthenticatedIndex(normalized)
                    signedDocuments.commit(legacyVerified.admission)
                    RepositoryIndexSnapshot(index, null)
                } catch (legacyFailure: Throwable) {
                    legacyFailure.addSuppressed(pluginFailure)
                    throw legacyFailure
                }
            }
        }

    /** Decodes the host-consumable V2 repository without projecting 64-bit ids through Double. */
    private fun decodeV2Index(
        url: String,
        root: JsonObject,
        normalizedBaseUrl: String? = null,
    ): RepositoryIndex.Combined = try {
        require(root["contractVersion"]?.jsonPrimitive?.intOrNull == 2) { "Unsupported V2 contract" }
        val packages = root["packages"] as? JsonArray ?: error("V2 packages must be an array")
        require(packages.size <= MAX_INDEX_PACKAGES) { "Repository contains too many packages" }
        var totalSources = 0
        val packageIds = mutableSetOf<String>()
        val sourceIdsByPackage = mutableMapOf<String, MutableSet<String>>()
        val shinsou = mutableListOf<PluginIndexEntry>()
        val shuyue = mutableListOf<ShuYueRepositoryEntry>()
        packages.forEach { element ->
            val pkg = element.jsonObject
            val contract = pkg["contract"]?.jsonPrimitive?.contentOrNull
            require(contract == "shinsou" || contract == "shuyue") {
                "Unknown or missing executable V2 contract"
            }
            val runtime = pkg.requiredString("runtime")
            require(
                (contract == "shinsou" && runtime == V2_SHINSOU_RUNTIME) ||
                    (contract == "shuyue" && runtime == V2_SHUYUE_RUNTIME),
            ) { "Unsupported V2 runtime '$runtime' for contract '$contract'" }
            val packageId = pkg.requiredString("id")
            require(packageIds.add(packageId)) { "Duplicate V2 package id '$packageId'" }
            val sourceIds = sourceIdsByPackage.getOrPut(packageId) { mutableSetOf() }
            val packageSources = pkg["sources"] as? JsonArray ?: error("Missing sources")
            require(packageSources.size <= MAX_INDEX_SOURCES_PER_PACKAGE) {
                "Repository package '$packageId' contains too many sources"
            }
            totalSources += packageSources.size
            require(totalSources <= MAX_INDEX_TOTAL_SOURCES) { "Repository contains too many sources" }
            packageSources.forEach { source ->
                val sourceId = source.jsonObject.requiredString("sourceId")
                require(sourceIds.add(sourceId)) { "Duplicate V2 source id '$sourceId'" }
            }
            if (contract == "shuyue") {
                val capabilityIds = pkg.requiredStringSet("capabilities")
                val eventObject = pkg["systemEvents"]?.jsonObject ?: error("Missing systemEvents")
                require(eventObject.requiredString("protocol") == "dev.shinsou.system")
                val eventDeclaration = PluginSystemEventDeclaration(
                    minVersion = eventObject["minVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing minVersion"),
                    maxVersion = eventObject["maxVersion"]?.jsonPrimitive?.intOrNull ?: error("Missing maxVersion"),
                    required = eventObject.stringSet("required"),
                    optional = eventObject.stringSet("optional"),
                )
                val requested = pkg.stringSet("requestedHostPermissions").mapTo(linkedSetOf()) {
                    PluginHostPermission.valueOf(it)
                }
                shuyue += ShuYueRepositoryEntry(
                    id = packageId,
                    name = pkg.requiredString("name"),
                    version = pkg.requiredString("version"),
                    versionCode = pkg["versionCode"]?.jsonPrimitive?.intOrNull ?: error("Missing versionCode"),
                    lang = pkg.requiredString("lang"),
                    nsfw = if (pkg.requiredBoolean("nsfw")) 1 else 0,
                    scriptUrl = pkg.requiredString("scriptUrl"),
                    sources = packageSources.map { sourceElement ->
                        val source = sourceElement.jsonObject
                        ShuYueRepositorySource(
                            id = source.requiredString("sourceId"),
                            name = source.requiredString("name"),
                            lang = source.requiredString("lang"),
                            baseUrl = source.requiredString("baseUrl"),
                            supportsLogin = "LOGIN" in capabilityIds,
                            supportsLatest = "LATEST" in capabilityIds,
                            supportsFavorites = "FAVORITE" in capabilityIds,
                            contentType = pkg["contentType"]?.jsonPrimitive?.contentOrNull,
                        )
                    },
                    contentType = pkg["contentType"]?.jsonPrimitive?.contentOrNull,
                    capabilities = capabilityIds,
                    contract = contract,
                    runtime = runtime,
                    sha256 = pkg.requiredString("sha256"),
                    byteSize = pkg["byteSize"]?.jsonPrimitive?.intOrNull ?: error("Missing byteSize"),
                    sidecarUrl = pkg.requiredString("sidecarUrl"),
                    systemEvents = eventDeclaration,
                    requestedHostPermissions = requested,
                    installable = pkg["installable"]?.jsonPrimitive?.booleanOrNull ?: true,
                    referenceOnly = pkg["referenceOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                    legacyCompatibilityOnly = pkg["legacyCompatibilityOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
                return@forEach
            }
            // Reference-only/non-installable Shinsou packages may intentionally use opaque
            // source IDs (for example, a login capability sample). They are not executable
            // legacy-adapter entries and must not make the installable half of a mixed V2 index
            // fail while coercing source IDs to Long. Existing installed packages are still
            // projected from packageStore by PluginManager.refresh().
            if (pkg["installable"]?.jsonPrimitive?.booleanOrNull == false ||
                pkg["referenceOnly"]?.jsonPrimitive?.booleanOrNull == true ||
                pkg["legacyCompatibilityOnly"]?.jsonPrimitive?.booleanOrNull == true
            ) {
                requireV2PackageAdmissionFields(pkg)
                return@forEach
            }
            val eventObject = pkg["systemEvents"]?.jsonObject ?: error("Missing systemEvents")
            require(eventObject.requiredString("protocol") == "dev.shinsou.system")
            val events = PluginSystemEventDeclaration(
                minVersion = eventObject.requiredInt("minVersion"),
                maxVersion = eventObject.requiredInt("maxVersion"),
                required = eventObject.requiredStringSet("required"),
                optional = eventObject.requiredStringSet("optional"),
            )
            val requested = pkg.requiredStringSet("requestedHostPermissions").mapTo(linkedSetOf()) {
                PluginHostPermission.valueOf(it)
            }
            val runtimePermissions = pkg.requiredStringSet("runtimePermissions").let { permissions ->
                permissions.mapTo(linkedSetOf()) {
                    runCatching { PluginRuntimePermission.valueOf(it) }
                        .getOrElse { error("Unknown runtime permission '$it'") }
                }
            }
            val packageContentKinds = pkg.requiredStringSet("contentKinds")
            shinsou += PluginIndexEntry(
                id = packageId,
                name = pkg.requiredString("name"),
                version = pkg.requiredString("version"),
                versionCode = pkg["versionCode"]?.jsonPrimitive?.intOrNull ?: error("Missing versionCode"),
                lang = pkg.requiredString("lang"),
                nsfw = if (pkg.requiredBoolean("nsfw")) 1 else 0,
                scriptUrl = pkg.requiredString("scriptUrl"),
                description = pkg["description"]?.jsonPrimitive?.contentOrNull,
                sources = packageSources.map { sourceElement ->
                    val source = sourceElement.jsonObject
                    val rawId = source.requiredString("sourceId")
                    SourceIndexEntry(
                        name = source.requiredString("name"),
                        lang = source.requiredString("lang"),
                        id = rawId.toLongOrNull() ?: error("Invalid lossless source id '$rawId'"),
                        baseUrl = source.requiredString("baseUrl"),
                        // Preserve the raw index metadata. An omitted source refinement is
                        // meaningful to exact installed-manifest matching; sidecar validation
                        // applies package inheritance only at its semantic comparison boundary.
                        contentType = source["contentType"]?.jsonPrimitive?.contentOrNull,
                        // The published V2 schema permits these source-level refinements to be
                        // omitted.  Missing origin declarations stay explicitly empty (and the
                        // V2 policy marker below keeps them fail-closed); they must never fall
                        // through to the legacy base-origin compatibility policy.
                        contentKinds = source.optionalStringSet("contentKinds"),
                        contentKindsDeclared = "contentKinds" in source,
                        requestOrigins = source.optionalStringSet("requestOrigins"),
                        credentialOrigins = source.optionalStringSet("credentialOrigins"),
                        contentOrigins = source.optionalStringSet("contentOrigins"),
                        browserSessionOrigins = source.optionalStringSet("browserSessionOrigins"),
                        originPolicyVersion = 2,
                        legacyLongId = source.requiredNullableString("legacyLongId"),
                        canonicalSourceId = rawId,
                    )
                },
                sha256 = pkg.requiredString("sha256"),
                byteSize = pkg["byteSize"]?.jsonPrimitive?.intOrNull ?: error("Missing byteSize"),
                contentType = pkg["contentType"]?.jsonPrimitive?.contentOrNull,
                contract = contract,
                runtime = pkg.requiredString("runtime"),
                contentKinds = packageContentKinds,
                capabilities = pkg.requiredStringSet("capabilities"),
                sidecarUrl = pkg.requiredString("sidecarUrl"),
                systemEvents = events,
                requestedHostPermissions = requested,
                runtimePermissions = runtimePermissions,
                installable = pkg["installable"]?.jsonPrimitive?.booleanOrNull ?: true,
                referenceOnly = pkg["referenceOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
                legacyCompatibilityOnly = pkg["legacyCompatibilityOnly"]?.jsonPrimitive?.booleanOrNull ?: false,
            )
        }
        require(shinsou.map { it.id }.distinct().size == shinsou.size)
        val decoded = RepositoryIndex.Combined(shinsou, shuyue)
        if (normalizedBaseUrl != null && reviewedLocalRepositoryPolicy.admits(normalizedBaseUrl)) {
            // A local working tree can contain ShuYue, examples, or unreviewed drafts. Project only
            // exact current app-reviewed Shinsou rows; directory contents cannot mint authority.
            RepositoryIndex.Combined(
                plugins = decoded.plugins.filter { entry ->
                    OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
                        entry, reviewedLocalRepositoryPolicy, normalizedBaseUrl,
                    ) != null
                },
                shuyue = emptyList(),
            )
        } else decoded
    } catch (error: Throwable) {
        throw ExtensionRepositoryException.InvalidDocument(url, error)
    }

    public suspend fun downloadPluginScript(baseUrl: String, scriptUrl: String): ByteArray {
        validateRelativePath(scriptUrl)
        val normalized = normalizeBaseUrl(baseUrl)
        val reviewedLocalDigests = if (reviewedLocalRepositoryPolicy.admits(normalized)) {
            OfficialShinsouReviewedCatalog.reviewedLocalScriptDigests(scriptUrl).also { digests ->
                require(digests.isNotEmpty()) {
                    "Local repository script path is not a current app-reviewed artifact"
                }
            }
        } else emptySet()
        val decision = trustDecisionFor(normalized)
        repositorySecurityState.requireDurableFor(decision)
        val url = resolve(normalized, scriptUrl, cacheBust = true)
        val bytes = requestBytes(normalized, url, MAX_SCRIPT_BYTES, decision)
        if (reviewedLocalRepositoryPolicy.admits(normalized)) {
            val digest = Sha256.hex(bytes)
            require(digest in reviewedLocalDigests) {
                "Local repository script is not an exact current app-reviewed artifact"
            }
        }
        return bytes
    }

    /**
     * Reads and cross-checks a V2 sidecar before artifact download. Repository declarations are
     * review input only; this method never turns requested permissions into an execution grant.
     */
    public suspend fun verifyPluginV2Sidecar(baseUrl: String, entry: PluginIndexEntry) {
        val sidecarPath = entry.sidecarUrl ?: return
        validateRelativePath(sidecarPath)
        val normalized = normalizeBaseUrl(baseUrl)
        if (reviewedLocalRepositoryPolicy.admits(normalized)) {
            require(
                OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
                    entry, reviewedLocalRepositoryPolicy, normalized,
                ) != null,
            ) { "Local repository sidecar request is not bound to an exact reviewed index row" }
        }
        val url = resolve(normalized, sidecarPath, cacheBust = true)
        val verified = fetchVerifiedJson(
            normalized,
            url,
            RepositorySignedDocumentType.SIDECAR,
            sidecarPath,
            MAX_SIDECAR_BYTES,
        )
        val sidecar = verified.payload as? JsonObject
            ?: throw ExtensionRepositoryException.InvalidDocument(url, IllegalArgumentException("V2 sidecar must be an object"))
        try {
            require(sidecar.requiredString("format") == "shinsou-extension-sidecar-v2")
            require(sidecar.requiredInt("contractVersion") == 2)
            require(sidecar.requiredString("packageId") == entry.id)
            require(sidecar.requiredString("version") == entry.version)
            require(sidecar.requiredInt("versionCode") == entry.versionCode)
            require(sidecar.requiredString("contract") == entry.contract)
            requireSidecarRuntimeParity(
                normalized, sidecar, entry, reviewedLocalRepositoryPolicy,
            )
            require(sidecar.requiredString("name") == entry.name)
            require(sidecar.requiredString("lang") == entry.lang)
            require(sidecar.requiredBoolean("nsfw") == (entry.nsfw == 1))
            require(sidecar.requiredBoolean("installable") == entry.installable)
            require(sidecar.optionalBoolean("referenceOnly", false) == entry.referenceOnly)
            require(sidecar.optionalBoolean("legacyCompatibilityOnly", false) == entry.legacyCompatibilityOnly)
            val artifact = sidecar["artifact"]?.jsonObject ?: error("Missing artifact")
            require(artifact.requiredString("scriptUrl") == entry.scriptUrl)
            require(artifact.requiredString("sha256") == entry.sha256)
            require(artifact.requiredInt("byteSize") == entry.byteSize)
            val content = sidecar["content"]?.jsonObject ?: error("Missing content")
            require(content.requiredInt("contractVersion") == 2)
            require(content.requiredString("type") == entry.contentType)
            require(content.requiredString("contract") == "extension-content-v2")
            require(content.requiredStringSet("kinds") == entry.contentKinds)
            require(sidecar.requiredStringSet("capabilities") == entry.capabilities)
            val sidecarSources = sidecar["sources"] as? JsonArray ?: error("Missing sources")
            require(sidecarSources.size == entry.sources.orEmpty().size)
            entry.sources.orEmpty().forEach { expected ->
                val expectedSourceId = expected.canonicalSourceId ?: expected.id.toString()
                val match = sidecarSources.map { it.jsonObject }.single { source ->
                    source.requiredString("sourceId") == expectedSourceId
                }
                val sourceKey = match["sourceKey"]?.jsonObject ?: error("Missing sourceKey")
                require(sourceKey.requiredInt("contractVersion") == 2)
                require(sourceKey.requiredString("packageId") == entry.id)
                require(sourceKey.requiredString("sourceId") == expectedSourceId)
                requireNullableStringParity(sourceKey, "legacyLongId", expected.legacyLongId)
                require(match.requiredString("name") == expected.name)
                require(match.requiredString("lang") == expected.lang)
                require(match.requiredString("baseUrl") == expected.baseUrl)
                val expectedContentType = expected.contentType
                    ?: entry.contentType
                // Source contentType is an optional refinement on both sides. When the index
                // omits it, the package type is the semantic inherited value; maintained
                // sidecars may omit the redundant source field as well. Explicit values still
                // have to match and are never widened.
                requireOptionalNullableStringParity(
                    match,
                    "contentType",
                    expectedContentType,
                    allowMissing = expected.contentType == null && entry.contentType in setOf("manga", "novel"),
                )
                require("contentKinds" in match) {
                    "Sidecar source contentKinds must preserve its explicit V2 binding"
                }
                val sidecarContentKinds = match.optionalStringSet("contentKinds")
                val expectedSidecarContentKinds = if (expected.contentKindsDeclared == false) {
                    // The maintained index omits this source refinement and the sidecar repeats
                    // the package-level contract. Preserve omission as empty on SourceIndexEntry,
                    // but do not confuse an explicitly empty source declaration with inheritance.
                    entry.contentKinds
                } else {
                    expected.contentKinds
                }
                require(sidecarContentKinds == expectedSidecarContentKinds)
                require(match.optionalStringSet("requestOrigins") == expected.requestOrigins)
                require(match.optionalStringSet("credentialOrigins") == expected.credentialOrigins)
                require(match.optionalStringSet("contentOrigins") == expected.contentOrigins)
                require(match.optionalStringSet("browserSessionOrigins") == expected.browserSessionOrigins)
                require(match.requiredStringSet("capabilities") == entry.capabilities)
                val sourceEvents = match["systemEvents"]?.jsonObject ?: error("Missing source systemEvents")
                require(parseEventDeclaration(sourceEvents) == entry.systemEvents)
                require(
                    match.requiredStringSet("requestedHostPermissions") ==
                        entry.requestedHostPermissions.mapTo(linkedSetOf()) { it.name },
                )
                require(
                    match.requiredStringSet("runtimePermissions") ==
                        entry.runtimePermissions.orEmpty().mapTo(linkedSetOf()) { it.name },
                )
            }
            val events = sidecar["systemEvents"]?.jsonObject ?: error("Missing systemEvents")
            val declaration = parseEventDeclaration(events)
            require(declaration == entry.systemEvents)
            val requested = sidecar.requiredStringSet("requestedHostPermissions").mapTo(linkedSetOf()) {
                PluginHostPermission.valueOf(it)
            }
            require(requested == entry.requestedHostPermissions)
            val runtimePermissions = sidecar.requiredStringSet("runtimePermissions").mapTo(linkedSetOf()) {
                runCatching { PluginRuntimePermission.valueOf(it) }
                    .getOrElse { error("Unknown runtime permission '$it'") }
            }
            require(runtimePermissions == entry.runtimePermissions)
            signedDocuments.commit(verified.admission)
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }
    }

    public fun resolveAssetUrl(baseUrl: String, relativePath: String): String {
        validateRelativePath(relativePath)
        return resolve(normalizeBaseUrl(baseUrl), relativePath, cacheBust = false)
    }

    /** Call immediately before artifact download/verification to reject known older versions. */
    public suspend fun requireArtifactNotDowngraded(baseUrl: String, entry: PluginIndexEntry) {
        val normalized = normalizeBaseUrl(baseUrl)
        val admission = artifactAdmissionMutex.withLock {
            authenticatedIndexEntries[normalized]?.admission
        }
        requireArtifactNotDowngraded(baseUrl, entry, admission)
    }

    public suspend fun requireArtifactNotDowngraded(
        baseUrl: String,
        entry: PluginIndexEntry,
        admission: RepositoryIndexAdmission?,
    ) {
        val normalized = normalizeBaseUrl(baseUrl)
        requireReviewedLocalEntry(normalized, entry)
        val trustDecision = trustDecisionFor(normalized)
        repositorySecurityState.requireDurableFor(trustDecision)
        if (trustDecision is RepositoryTrustDecision.RequirePinnedSignature) {
            artifactAdmissionMutex.withLock {
                requireCurrentAuthenticatedArtifact(normalized, entry, admission, trustDecision)
            }
        }
        val digest = entry.sha256?.trim()?.lowercase()
            ?: throw RepositoryTrustException.Malformed("Signed executable entry has no artifact SHA-256")
        if (trustDecision is RepositoryTrustDecision.RequirePinnedSignature ||
            repositorySecurityState.isConfigured()
        ) {
            repositorySecurityState.requireArtifactNotDowngraded(
                normalized,
                entry.id,
                entry.versionCode,
                digest,
            )
        }
    }

    private suspend fun rememberAuthenticatedIndex(
        normalizedBaseUrl: String,
        entries: List<PluginIndexEntry>,
        admission: RepositoryDocumentAdmission?,
    ): RepositoryIndexAdmission? {
        if (admission == null) return null
        val capability = RepositoryIndexAdmission()
        // Replace rather than union so a removed package cannot be installed from stale UI state.
        artifactAdmissionMutex.withLock {
            authenticatedIndexEntries[normalizedBaseUrl] = AuthenticatedIndexSnapshot(
                admission.keyFingerprint,
                entries.associate { it.id to it.admissionFingerprint() },
                capability,
            )
        }
        return capability
    }

    /**
     * The replay watermark and the process-local install capability are one admission commit.
     * Cancellation between them must not leave an older UI row authorized after a newer signed
     * document has already advanced durable state.
     */
    private suspend fun commitAuthenticatedIndex(
        normalizedBaseUrl: String,
        entries: List<PluginIndexEntry>,
        admission: RepositoryDocumentAdmission?,
    ): RepositoryIndexAdmission? = withContext(NonCancellable) {
        authenticatedIndexCommitMutex.withLock {
            if (admission == null) {
                clearAuthenticatedIndex(normalizedBaseUrl)
                signedDocuments.commit(null)
                return@withLock null
            }
            // Remove the previous process-local capability first. If either durable commit or
            // replacement fails, stale descriptors remain unusable until a fresh fetch succeeds.
            // The outer commit mutex also prevents concurrent sequence N/N+1 fetches from
            // publishing their in-memory snapshots in the opposite order.
            artifactAdmissionMutex.withLock {
                authenticatedIndexEntries.remove(normalizedBaseUrl)
            }
            signedDocuments.commit(admission)
            return@withLock rememberAuthenticatedIndex(normalizedBaseUrl, entries, admission)
        }
    }

    private suspend fun clearAuthenticatedIndex(normalizedBaseUrl: String) {
        artifactAdmissionMutex.withLock { authenticatedIndexEntries.remove(normalizedBaseUrl) }
    }

    private data class AuthenticatedIndexSnapshot(
        val keyFingerprint: String,
        val entryFingerprints: Map<String, String>,
        val admission: RepositoryIndexAdmission,
    )

    /**
     * Call only at the end of the verified artifact/package transaction. For pinned repositories,
     * [repositoryAdmission] must be the opaque capability returned with the exact selected index.
     * The capability and row fingerprint are rechecked under the index lock immediately before
     * advancing durable downgrade state, so an index refresh during download invalidates commit.
     */
    public suspend fun recordCommittedArtifact(
        baseUrl: String,
        entry: PluginIndexEntry,
        actualSha256: String,
        repositoryAdmission: RepositoryIndexAdmission? = null,
    ) {
        val declared = entry.sha256?.trim()?.lowercase()
            ?: throw RepositoryTrustException.Malformed("Signed executable entry has no artifact SHA-256")
        val actual = actualSha256.trim().lowercase()
        if (actual != declared) throw PluginVerificationException.HashMismatch(declared, actual)
        val normalized = normalizeBaseUrl(baseUrl)
        requireReviewedLocalEntry(normalized, entry)
        val trustDecision = trustDecisionFor(normalized)
        repositorySecurityState.requireDurableFor(trustDecision)
        if (trustDecision is RepositoryTrustDecision.RequirePinnedSignature) {
            artifactAdmissionMutex.withLock {
                requireCurrentAuthenticatedArtifact(
                    normalized,
                    entry,
                    repositoryAdmission,
                    trustDecision,
                )
                repositorySecurityState.admitArtifact(
                    normalized,
                    entry.id,
                    entry.versionCode,
                    actual,
                )
            }
        } else if (repositorySecurityState.isConfigured()) {
            repositorySecurityState.admitArtifact(
                normalized,
                entry.id,
                entry.versionCode,
                actual,
            )
        }
    }

    private fun requireCurrentAuthenticatedArtifact(
        normalizedBaseUrl: String,
        entry: PluginIndexEntry,
        admission: RepositoryIndexAdmission?,
        trustDecision: RepositoryTrustDecision.RequirePinnedSignature,
    ) {
        val authenticated = authenticatedIndexEntries[normalizedBaseUrl]
        if (authenticated?.keyFingerprint != trustDecision.root.fingerprint ||
            authenticated.admission !== admission ||
            authenticated.entryFingerprints[entry.id] != entry.admissionFingerprint()
        ) {
            throw RepositoryTrustException.UnauthenticatedArtifact(entry.id)
        }
    }

    private suspend fun fetchVerifiedJson(
        normalizedBaseUrl: String,
        url: String,
        type: RepositorySignedDocumentType,
        documentId: String,
        maximumBytes: Long,
    ): VerifiedRepositoryPayload {
        // Resolve trust before touching the network. This prevents an unpinned URL from being used
        // as an SSRF/read oracle even when the downloaded bytes would later fail verification.
        val decision = trustDecisionFor(normalizedBaseUrl)
        repositorySecurityState.requireDurableFor(decision)
        val root = parseRoot(url, requestBytes(normalizedBaseUrl, url, maximumBytes, decision))
        return try {
            signedDocuments.verifiedPayload(normalizedBaseUrl, type, documentId, root, decision)
        } catch (error: ExtensionRepositoryException) {
            throw error
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }
    }

    private suspend fun requestBytes(
        normalizedBaseUrl: String,
        url: String,
        maxBytes: Long,
        trustDecision: RepositoryTrustDecision,
    ): ByteArray {
        require(maxBytes in 1..Int.MAX_VALUE.toLong())
        var currentUrl = url
        val seen = mutableSetOf(url)
        repeat(MAX_REDIRECTS + 1) { hop ->
            val current = parseRepositoryUrl(currentUrl)
            requireAllowedRepositoryScheme(current, currentUrl)
            if (!currentUrl.startsWith(normalizedBaseUrl + "/")) {
                throw ExtensionRepositoryException.InvalidUrl("Repository request escaped its admitted base URL: $currentUrl")
            }
            val response = requestRepositoryHop(currentUrl, current, trustDecision, maxBytes)
            val location = response.location.trim()
            if (reviewedLocalRepositoryPolicy.admits(normalizedBaseUrl) &&
                (response.status in 300..399 || location.isNotEmpty())
            ) {
                throw ExtensionRepositoryException.InvalidUrl(
                    "Local repository redirects are forbidden: $currentUrl",
                )
            }
            if (response.status in REDIRECT_STATUS_CODES && location.isNotEmpty()) {
                if (hop >= MAX_REDIRECTS) throw ExtensionRepositoryException.InvalidUrl(currentUrl)
                val next = resolveRedirect(currentUrl, location)
                val nextParsed = parseRepositoryUrl(next)
                requireAllowedRepositoryScheme(nextParsed, next)
                if (nextParsed.origin != current.origin) {
                    throw ExtensionRepositoryException.InvalidUrl("Repository redirect changes origin: $next")
                }
                if (current.scheme == "https" && nextParsed.scheme != "https") {
                    throw ExtensionRepositoryException.InvalidUrl("Repository redirect downgrades HTTPS: $next")
                }
                if (!seen.add(next)) {
                    throw ExtensionRepositoryException.InvalidUrl("Repository redirect loop: $next")
                }
                currentUrl = next
                return@repeat
            }
            if (response.status !in 200..299) {
                throw ExtensionRepositoryException.Http(response.status, currentUrl)
            }
            return response.body
        }
        throw ExtensionRepositoryException.InvalidUrl(currentUrl)
    }

    private suspend fun requestRepositoryHop(
        url: String,
        parsed: ParsedRepositoryUrl,
        trustDecision: RepositoryTrustDecision,
        maxBytes: Long,
    ): RepositoryHopResponse = if (reviewedLocalRepositoryPolicy.admits(parsed.origin)) {
        val transport = reviewedLocalRepositoryTransport ?: throw IllegalStateException(
            "Reviewed local repository transport is unavailable",
        )
        if (!reviewedLocalRepositoryPolicy.admitsRequestUrl(url)) {
            throw ExtensionRepositoryException.InvalidUrl(url)
        }
        val response = try {
            transport.execute(PluginHttpRequest("GET", url, maxResponseBytes = maxBytes.toInt()))
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: ExtensionRepositoryException) {
            throw error
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.NetworkSecurity(url, error)
        }
        val responseHeaders = try {
            boundedPluginResponseHeaders(response.headers.entries)
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.NetworkSecurity(url, error)
        }
        try {
            responseHeaders.pluginContentEncoding()
            val declared = responseHeaders.pluginDeclaredContentLength()
            if (declared != null && declared > maxBytes) throw repositoryResponseTooLarge(url)
        } catch (error: ExtensionRepositoryException) {
            throw error
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.NetworkSecurity(url, error)
        }
        val bounded = requireBoundedRepositoryResponse(
            RepositoryHopResponse(
                response.status,
                responseHeaders.pluginHeaderValues(HttpHeaders.Location).singleOrNull().orEmpty(),
                response.body,
            ),
            maxBytes,
        )
        if (bounded.status in 300..399 || bounded.location.isNotEmpty()) {
            throw ExtensionRepositoryException.InvalidUrl("Local repository redirects are forbidden: $url")
        }
        bounded
    } else when (trustDecision) {
        is RepositoryTrustDecision.RequirePinnedSignature -> {
            val transport = repositoryTransport ?: throw IllegalStateException(
                "Pinned repository transport is unavailable; refusing an unbound DNS request",
            )
            val response = try {
                val resolution = PluginNetworkPolicy(
                    requestOrigins = setOf(parsed.origin),
                    resolver = repositoryHostResolver,
                    maxResponseBytes = maxBytes.toInt(),
                ).authorize(Url(url), repositoryHostResolver)
                transport.executeResolved(
                    PluginHttpRequest("GET", url, maxResponseBytes = maxBytes.toInt()),
                    resolution,
                )
            } catch (error: ExtensionRepositoryException) {
                throw error
            } catch (error: Throwable) {
                throw ExtensionRepositoryException.NetworkSecurity(url, error)
            }
            val responseHeaders = try {
                boundedPluginResponseHeaders(response.headers.entries)
            } catch (error: Throwable) {
                throw ExtensionRepositoryException.NetworkSecurity(url, error)
            }
            requireBoundedRepositoryResponse(
                RepositoryHopResponse(
                    response.status,
                    responseHeaders.pluginHeaderValues(HttpHeaders.Location).singleOrNull().orEmpty(),
                    response.body,
                ),
                maxBytes,
            )
        }
        RepositoryTrustDecision.UnsignedDeveloperCompatibility -> {
            val response: HttpResponse = repositoryHttpClient.get(url) { }
            val channel = response.bodyAsChannel()
            try {
                val responseHeaders = boundedPluginResponseHeaders(response.headers.entries())
                responseHeaders.pluginContentEncoding()
                val location = responseHeaders.pluginHeaderValues(HttpHeaders.Location).singleOrNull().orEmpty()
                val declared = responseHeaders.pluginDeclaredContentLength()
                if (declared != null && declared > maxBytes) throw repositoryResponseTooLarge(url)
                val body = readBoundedPluginResponseBody(channel, maxBytes.toInt())
                requireBoundedRepositoryResponse(
                    RepositoryHopResponse(response.status.value, location, body),
                    maxBytes,
                )
            } catch (error: Throwable) {
                channel.cancel(null)
                throw error
            }
        }
    }

    private fun requireBoundedRepositoryResponse(response: RepositoryHopResponse, maxBytes: Long): RepositoryHopResponse {
        if (response.body.size.toLong() > maxBytes) throw repositoryResponseTooLarge("repository response")
        if (pluginUtf8ByteCountAtMost(response.location, PLUGIN_NETWORK_MAX_REDIRECT_LOCATION_BYTES) == null) {
            throw ExtensionRepositoryException.InvalidUrl("Repository redirect location exceeds byte limit")
        }
        return response
    }

    private fun repositoryResponseTooLarge(url: String): ExtensionRepositoryException.InvalidDocument =
        ExtensionRepositoryException.InvalidDocument(url, IllegalArgumentException("Response exceeds byte limit"))

    private data class RepositoryHopResponse(val status: Int, val location: String, val body: ByteArray)

    private inline fun <reified T> decode(url: String, bytes: ByteArray): T =
        try {
            json.decodeFromString(bytes.decodeToString())
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }

    private fun <T> decode(url: String, bytes: ByteArray, serializer: kotlinx.serialization.KSerializer<T>): T =
        try {
            json.decodeFromString(serializer, bytes.decodeToString())
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }

    private inline fun <reified T> decodeElement(url: String, element: JsonElement): T =
        try {
            json.decodeFromJsonElement(element)
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }

    private fun <T> decodeElement(
        url: String,
        element: JsonElement,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): T = try {
        json.decodeFromJsonElement(serializer, element)
    } catch (error: Throwable) {
        throw ExtensionRepositoryException.InvalidDocument(url, error)
    }

    private fun parseRoot(url: String, bytes: ByteArray): kotlinx.serialization.json.JsonElement =
        try {
            json.parseToJsonElement(bytes.decodeToString())
        } catch (error: Throwable) {
            throw ExtensionRepositoryException.InvalidDocument(url, error)
        }

    private fun isUnifiedEnvelope(root: JsonObject): Boolean =
        root["format"]?.toString()?.contains("unified", ignoreCase = true) == true ||
            root["shinsou"] is JsonArray || root["legacy"] is JsonArray || root["shuyue"] is JsonArray

    private fun resolve(baseUrl: String, path: String, cacheBust: Boolean): String {
        val resolved = "$baseUrl/${path.trimStart('/')}"
        if (!cacheBust) return resolved
        return resolved + (if ('?' in resolved) "&" else "?") + "_t=${cacheToken()}"
    }

    private fun normalizeBaseUrl(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        val parsed = parseRepositoryUrl(trimmed)
        requireAllowedRepositoryScheme(parsed, value)
        if ('?' in trimmed || '#' in trimmed) throw ExtensionRepositoryException.InvalidUrl(value)
        if (parsed.path != "/") {
            validateRelativePath(parsed.path.removePrefix("/"))
            return parsed.origin + parsed.path.trimEnd('/')
        }
        return parsed.origin
    }

    private fun validateRelativePath(path: String) {
        val route = path.substringBefore('?').substringBefore('#')
        if ('\\' in route) throw ExtensionRepositoryException.InvalidUrl(path)
        val segments = route.split('/')
        if (route.isBlank() || route.startsWith('/') || "://" in route || segments.any { it == ".." || it == "." }) {
            throw ExtensionRepositoryException.InvalidUrl(path)
        }
        if (route.any { it.isWhitespace() || it.isISOControl() }) throw ExtensionRepositoryException.InvalidUrl(path)
        validatePercentEncodedPath(route)
    }

    private fun validatePercentEncodedPath(route: String) {
        var i = 0
        while (i < route.length) {
            if (route[i] == '%') {
                require(i + 2 < route.length && route[i + 1].digitToIntOrNull(16) != null && route[i + 2].digitToIntOrNull(16) != null)
                val byte = route.substring(i + 1, i + 3).toInt(16)
                require(byte !in setOf('/'.code, '\\'.code, '.'.code, '%'.code) && byte >= 0x20)
                i += 3
            } else i++
        }
    }

    private data class ParsedRepositoryUrl(val scheme: String, val host: String, val origin: String, val path: String)

    private fun parseRepositoryUrl(value: String): ParsedRepositoryUrl {
        val match = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://([^/?#]+)(/[^?#]*)?(?:\\?[^#]*)?$").matchEntire(value)
            ?: throw ExtensionRepositoryException.InvalidUrl(value)
        val scheme = match.groupValues[1].lowercase()
        val authority = match.groupValues[2]
        if (scheme !in setOf("http", "https") || '@' in authority || '%' in authority) throw ExtensionRepositoryException.InvalidUrl(value)
        val host: String
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            if (close <= 1 || authority.substring(close + 1).let { it.isNotEmpty() && !it.startsWith(':') }) throw ExtensionRepositoryException.InvalidUrl(value)
            host = authority.substring(1, close).lowercase()
        } else {
            if (authority.count { it == ':' } > 1) throw ExtensionRepositoryException.InvalidUrl(value)
            host = authority.substringBefore(':').lowercase()
            val port = authority.substringAfter(':', "")
            if (port.isNotEmpty() && (port.any { !it.isDigit() } || port.toIntOrNull() !in 1..65535)) throw ExtensionRepositoryException.InvalidUrl(value)
            if (host.isBlank() || host.any { !(it.isLetterOrDigit() || it == '.' || it == '-') }) throw ExtensionRepositoryException.InvalidUrl(value)
        }
        val path = match.groupValues[3].ifEmpty { "/" }
        if (path != "/") {
            val route = path.removePrefix("/")
            if (route.isBlank()) throw ExtensionRepositoryException.InvalidUrl(value)
            validateRelativePath(route)
        }
        return ParsedRepositoryUrl(scheme, host, "$scheme://$authority", path)
    }

    private fun resolveRedirect(base: String, location: String): String =
        if (location.startsWith("/")) parseRepositoryUrl(base).origin + location
        else if (location.startsWith("http://") || location.startsWith("https://")) location
        else parseRepositoryUrl(base).origin + "/" + location

    private fun isPrivateHost(host: String): Boolean {
        if (host == "localhost" || host == "::1" || host == "0:0:0:0:0:0:0:1" || host.endsWith(".local")) return true
        if (':' in host) return isPrivateIpv6Literal(host)
        val octets = host.split('.').map(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
        val a = octets[0]!!; val b = octets[1]!!
        return a == 0 || a == 10 || a == 127 || a == 169 && b == 254 || a == 172 && b in 16..31 || a == 192 && b == 168
    }

    private fun isPrivateIpv6Literal(host: String): Boolean {
        val normalized = host.lowercase()
        return normalized.startsWith("fc") || normalized.startsWith("fd") ||
            normalized.matches(Regex("^fe[89ab][0-9a-f]?:.*$"))
    }

    private fun requireAllowedRepositoryScheme(parsed: ParsedRepositoryUrl, original: String) {
        if (parsed.scheme == "https") return
        if (reviewedLocalRepositoryPolicy.admits(parsed.origin) &&
            (parsed.path == "/" || reviewedLocalRepositoryPolicy.admitsRequestUrl(original))
        ) return
        if (!allowInsecureDeveloperHttp || !isPrivateHost(parsed.host)) {
            throw ExtensionRepositoryException.InvalidUrl(original)
        }
    }

    private fun requireReviewedLocalEntry(normalizedBaseUrl: String, entry: PluginIndexEntry) {
        if (!reviewedLocalRepositoryPolicy.admits(normalizedBaseUrl)) return
        require(
            OfficialShinsouReviewedCatalog.matchReviewedRepositoryEntry(
                entry, reviewedLocalRepositoryPolicy, normalizedBaseUrl,
            ) != null,
        ) { "Local repository entry is not an exact current app-reviewed artifact" }
    }

    private fun requireReviewedLocalV2Index(normalizedBaseUrl: String, root: JsonElement) {
        if (!reviewedLocalRepositoryPolicy.admits(normalizedBaseUrl)) return
        val format = (root as? JsonObject)?.get("format")?.jsonPrimitive?.contentOrNull
        if (format != "shinsou-extension-v2" || root !is JsonObject ||
            listOf("shinsou", "legacy", "shuyue").any { it in root }
        ) {
            throw RepositoryTrustException.Malformed(
                "Reviewed local repository must expose the fixed Shinsou V2 index contract",
            )
        }
    }

    /**
     * Configured author pins retain precedence. Only the exact explicitly enabled loopback origin
     * may replace an ordinary untrusted-repository result with compiled-catalogue compatibility;
     * malformed policy/configuration failures are never downgraded.
     */
    private suspend fun trustDecisionFor(normalizedBaseUrl: String): RepositoryTrustDecision = try {
        repositoryTrustPolicy.decisionFor(normalizedBaseUrl)
    } catch (untrusted: RepositoryTrustException.UntrustedRepository) {
        if (reviewedLocalRepositoryPolicy.admits(normalizedBaseUrl)) {
            RepositoryTrustDecision.UnsignedDeveloperCompatibility
        } else {
            throw untrusted
        }
    }

    private fun JsonElement.stringSet(): Set<String> = (this as? JsonArray).orEmpty().mapTo(linkedSetOf()) { it.jsonPrimitive.content }

    private fun validatePluginEntries(
        entries: List<PluginIndexEntry>,
        requireAuthenticatedArtifacts: Boolean = false,
    ) {
        require(entries.size <= MAX_INDEX_PACKAGES) { "Repository contains too many packages" }
        var totalSources = 0
        val ids = mutableSetOf<String>()
        entries.forEach { entry ->
            requireIndexString(entry.id, MAX_INDEX_ID_BYTES, "package id")
            requireIndexString(entry.name, MAX_INDEX_NAME_BYTES, "package name")
            requireIndexString(entry.version, MAX_INDEX_VERSION_BYTES, "package version")
            requireIndexString(entry.lang, MAX_INDEX_LANG_BYTES, "package language")
            requireIndexString(entry.scriptUrl, MAX_INDEX_URL_BYTES, "script URL")
            entry.iconUrl?.let { requireIndexString(it, MAX_INDEX_URL_BYTES, "icon URL") }
            entry.description?.let { requireIndexString(it, MAX_INDEX_DESCRIPTION_BYTES, "description") }
            entry.sidecarUrl?.let { requireIndexString(it, MAX_INDEX_URL_BYTES, "sidecar URL") }
            require(entry.contentKinds.size <= MAX_INDEX_SET_VALUES &&
                entry.capabilities.size <= MAX_INDEX_SET_VALUES &&
                entry.requestedHostPermissions.size <= MAX_INDEX_SET_VALUES &&
                entry.runtimePermissions.orEmpty().size <= MAX_INDEX_SET_VALUES
            ) { "Repository package declaration contains too many values" }
            (entry.contentKinds + entry.capabilities).forEach {
                requireIndexString(it, MAX_INDEX_ID_BYTES, "package declaration")
            }
            require(ids.add(entry.id)) { "Duplicate plugin package id '${entry.id}'" }
            val sources = entry.sources.orEmpty()
            require(sources.size <= MAX_INDEX_SOURCES_PER_PACKAGE) {
                "Repository package '${entry.id}' contains too many sources"
            }
            totalSources += sources.size
            require(totalSources <= MAX_INDEX_TOTAL_SOURCES) { "Repository contains too many sources" }
            val sourceIds = mutableSetOf<Long>()
            sources.forEach { source ->
                validateIndexSource(source)
                require(sourceIds.add(source.id)) { "Duplicate source id '${source.id}' in '${entry.id}'" }
            }
            entry.runtimePermissions?.forEach { /* enum deserialization already rejects unknown values */ }
            if (requireAuthenticatedArtifacts && entry.installable &&
                !entry.referenceOnly && !entry.legacyCompatibilityOnly
            ) {
                require(entry.sha256?.matches(SHA256_HEX) == true) {
                    "Signed executable '${entry.id}' must declare a canonical SHA-256"
                }
                require(entry.byteSize in 1..MAX_SCRIPT_BYTES.toInt()) {
                    "Signed executable '${entry.id}' must declare a bounded byteSize"
                }
            }
        }
    }

    /** Bounded structural preflight before repository DTO decoding and fingerprint construction. */
    private fun validateIndexJsonShape(element: JsonElement, depth: Int = 1) {
        require(depth <= MAX_INDEX_JSON_DEPTH) { "Repository JSON nesting is too deep" }
        when (element) {
            is JsonObject -> {
                require(element.size <= MAX_INDEX_OBJECT_MEMBERS) {
                    "Repository JSON object contains too many members"
                }
                element.forEach { (key, value) ->
                    requireIndexString(key, MAX_INDEX_ID_BYTES, "JSON member name")
                    validateIndexJsonShape(value, depth + 1)
                }
            }
            is JsonArray -> {
                require(element.size <= MAX_INDEX_ARRAY_ELEMENTS) {
                    "Repository JSON array contains too many elements"
                }
                element.forEach { validateIndexJsonShape(it, depth + 1) }
            }
            is kotlinx.serialization.json.JsonPrimitive -> if (element.isString) {
                requireIndexString(element.content, MAX_INDEX_STRING_BYTES, "JSON string")
            }
        }
    }

    private fun validateLegacyEntries(entries: List<LegacyExtensionIndexEntry>) {
        require(entries.size <= MAX_INDEX_PACKAGES) { "Repository contains too many packages" }
        var totalSources = 0
        val ids = mutableSetOf<String>()
        entries.forEach { entry ->
            requireIndexString(entry.pkg, MAX_INDEX_ID_BYTES, "package id")
            requireIndexString(entry.name, MAX_INDEX_NAME_BYTES, "package name")
            requireIndexString(entry.version, MAX_INDEX_VERSION_BYTES, "package version")
            requireIndexString(entry.lang, MAX_INDEX_LANG_BYTES, "package language")
            requireIndexString(entry.apk, MAX_INDEX_URL_BYTES, "APK path")
            require(ids.add(entry.pkg)) { "Duplicate plugin package id '${entry.pkg}'" }
            val sources = entry.sources.orEmpty()
            require(sources.size <= MAX_INDEX_SOURCES_PER_PACKAGE) {
                "Repository package '${entry.pkg}' contains too many sources"
            }
            totalSources += sources.size
            require(totalSources <= MAX_INDEX_TOTAL_SOURCES) { "Repository contains too many sources" }
            val sourceIds = mutableSetOf<Long>()
            sources.forEach { source ->
                validateIndexSource(source)
                require(sourceIds.add(source.id)) { "Duplicate source id '${source.id}' in '${entry.pkg}'" }
            }
        }
    }

    private fun validateUnifiedIds(document: UnifiedRepositoryDocument) {
        require(document.shinsou.size + document.legacy.size + document.shuyue.size <= MAX_INDEX_PACKAGES) {
            "Repository contains too many packages"
        }
        val ids = mutableSetOf<String>()
        (document.shinsou.map { it.id } + document.legacy.map { it.id } + document.shuyue.map { it.id })
            .forEach { require(ids.add(it)) { "Duplicate plugin package id '$it'" } }
        document.shuyue.forEach { packageEntry ->
            requireIndexString(packageEntry.id, MAX_INDEX_ID_BYTES, "package id")
            requireIndexString(packageEntry.name, MAX_INDEX_NAME_BYTES, "package name")
            requireIndexString(packageEntry.version, MAX_INDEX_VERSION_BYTES, "package version")
            requireIndexString(packageEntry.lang, MAX_INDEX_LANG_BYTES, "package language")
            requireIndexString(packageEntry.scriptUrl, MAX_INDEX_URL_BYTES, "script URL")
            packageEntry.description?.let { requireIndexString(it, MAX_INDEX_DESCRIPTION_BYTES, "description") }
            require(packageEntry.sources.size <= MAX_INDEX_SOURCES_PER_PACKAGE) {
                "Repository package '${packageEntry.id}' contains too many sources"
            }
            val sourceIds = mutableSetOf<String>()
            packageEntry.sources.forEach { source ->
                requireIndexString(source.id, MAX_INDEX_ID_BYTES, "source id")
                requireIndexString(source.name, MAX_INDEX_NAME_BYTES, "source name")
                requireIndexString(source.lang, MAX_INDEX_LANG_BYTES, "source language")
                requireIndexString(source.baseUrl, MAX_INDEX_URL_BYTES, "source URL")
                require(sourceIds.add(source.id)) { "Duplicate source id '${source.id}' in '${packageEntry.id}'" }
            }
        }
        require(
            document.shinsou.sumOf { it.sources.orEmpty().size } +
                document.legacy.sumOf { it.sources.orEmpty().size } +
                document.shuyue.sumOf { it.sources.size } <= MAX_INDEX_TOTAL_SOURCES,
        ) { "Repository contains too many sources" }
    }

    private fun validateCombinedIndex(index: RepositoryIndex.Combined) {
        require(index.plugins.size + index.shuyue.size <= MAX_INDEX_PACKAGES) {
            "Repository contains too many packages"
        }
        validatePluginEntries(index.plugins)
        val synthetic = UnifiedRepositoryDocument(shuyue = index.shuyue)
        validateUnifiedIds(synthetic)
        require(index.plugins.sumOf { it.sources.orEmpty().size } +
            index.shuyue.sumOf { it.sources.size } <= MAX_INDEX_TOTAL_SOURCES
        ) { "Repository contains too many sources" }
    }

    private fun validateIndexSource(source: SourceIndexEntry) {
        requireIndexString(source.name, MAX_INDEX_NAME_BYTES, "source name")
        requireIndexString(source.lang, MAX_INDEX_LANG_BYTES, "source language")
        source.baseUrl?.let { requireIndexString(it, MAX_INDEX_URL_BYTES, "source URL") }
        val declarations = listOf(
            source.contentKinds,
            source.requestOrigins,
            source.credentialOrigins,
            source.contentOrigins,
            source.browserSessionOrigins,
        )
        require(declarations.all { it.size <= MAX_INDEX_SET_VALUES }) {
            "Repository source declaration contains too many values"
        }
        declarations.flatten().forEach {
            requireIndexString(it, MAX_INDEX_URL_BYTES, "source declaration")
        }
    }

    private fun requireIndexString(value: String, maxBytes: Int, label: String) {
        require(value.isNotBlank() && value.encodeToByteArray().size <= maxBytes &&
            value.none { it.isISOControl() }
        ) { "Repository $label is invalid or too large" }
    }

    private fun requireV2PackageAdmissionFields(pkg: JsonObject) {
        pkg.requiredBoolean("nsfw")
        pkg.requiredString("runtime")
        pkg.requiredString("contentType")
        pkg.requiredStringSet("contentKinds")
        pkg.requiredStringSet("capabilities")
        pkg.requiredStringSet("runtimePermissions")
        pkg.requiredStringSet("requestedHostPermissions")
        val events = pkg["systemEvents"]?.jsonObject ?: error("Missing systemEvents")
        events.requiredString("protocol")
        events.requiredInt("minVersion")
        events.requiredInt("maxVersion")
        events.requiredStringSet("required")
        events.requiredStringSet("optional")
        val sources = pkg["sources"] as? JsonArray ?: error("Missing sources")
        sources.forEach { element ->
            val source = element.jsonObject
            source.requiredString("sourceId")
            source.requiredString("name")
            source.requiredString("lang")
            source.requiredString("baseUrl")
            source.requiredNullableString("legacyLongId")
            source.optionalStringSet("contentKinds")
            source.optionalStringSet("requestOrigins")
            source.optionalStringSet("credentialOrigins")
            source.optionalStringSet("contentOrigins")
            source.optionalStringSet("browserSessionOrigins")
        }
    }

    private fun parseEventDeclaration(events: JsonObject): PluginSystemEventDeclaration {
        require(events.requiredString("protocol") == "dev.shinsou.system")
        return PluginSystemEventDeclaration(
            minVersion = events.requiredInt("minVersion"),
            maxVersion = events.requiredInt("maxVersion"),
            required = events.requiredStringSet("required"),
            optional = events.requiredStringSet("optional"),
        )
    }

    private companion object {
        const val MAX_REPOSITORY_BYTES = 256L * 1024
        const val MAX_INDEX_BYTES = 2L * 1024 * 1024
        const val MAX_SIDECAR_BYTES = 512L * 1024
        const val MAX_SCRIPT_BYTES = 8L * 1024 * 1024
        const val MAX_REDIRECTS = 5
        const val MAX_INDEX_PACKAGES = 256
        const val MAX_INDEX_SOURCES_PER_PACKAGE = 256
        const val MAX_INDEX_TOTAL_SOURCES = 2_048
        const val MAX_INDEX_SET_VALUES = 256
        const val MAX_INDEX_ID_BYTES = 256
        const val MAX_INDEX_NAME_BYTES = 1_024
        const val MAX_INDEX_VERSION_BYTES = 128
        const val MAX_INDEX_LANG_BYTES = 64
        const val MAX_INDEX_URL_BYTES = 4_096
        const val MAX_INDEX_DESCRIPTION_BYTES = 16 * 1_024
        const val MAX_INDEX_STRING_BYTES = 16 * 1_024
        const val MAX_INDEX_JSON_DEPTH = 64
        const val MAX_INDEX_OBJECT_MEMBERS = 512
        const val MAX_INDEX_ARRAY_ELEMENTS = 2_048
        val REDIRECT_STATUS_CODES = setOf(301, 302, 303, 307, 308)
        val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    }
}

private const val V2_SHINSOU_RUNTIME = "legacy-shinsou-adapter-v2"
private const val V2_SHUYUE_RUNTIME = "reviewed-shuyue-adapter-v2"

private fun JsonObject.requiredString(key: String): String =
    this[key]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
        ?: error("Missing or invalid '$key'")

/** An omitted sidecar runtime inherits the already-required index runtime. */
private fun requireSidecarRuntimeParity(
    normalizedBaseUrl: String,
    sidecar: JsonObject,
    entry: PluginIndexEntry,
    reviewedLocalRepositoryPolicy: ReviewedLocalRepositoryPolicy = ReviewedLocalRepositoryPolicy.DISABLED,
) {
    val expected = entry.runtime
    val element = sidecar["runtime"]
    if (element == null) {
        // The original official Shinsou V2 sidecars predate the package-level runtime field.
        // Keep this narrowly scoped compatibility exception; every other repository must bind
        // the sidecar runtime explicitly to the index runtime.
        require(
            (normalizedBaseUrl == OFFICIAL_SHINSOU_REPOSITORY_BASE_URL &&
                entry.contract == "shinsou" &&
                expected == V2_SHINSOU_RUNTIME) ||
                (reviewedLocalRepositoryPolicy.admits(normalizedBaseUrl) &&
                    entry.contract == "shinsou" &&
                    expected == V2_SHINSOU_RUNTIME),
        ) { "Missing 'runtime'" }
        return
    }
    val actual = (element as? JsonPrimitive)?.takeIf { it.isString }?.content
    require(!actual.isNullOrBlank() && actual == expected) { "'runtime' does not match index" }
}

private fun JsonObject.requiredInt(key: String): Int =
    this[key]?.jsonPrimitive?.intOrNull ?: error("Missing or invalid '$key'")

private fun JsonObject.requiredBoolean(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull ?: error("Missing or invalid '$key'")

private fun JsonObject.optionalBoolean(key: String, default: Boolean): Boolean =
    this[key]?.let { it.jsonPrimitive.booleanOrNull ?: error("Invalid '$key'") } ?: default

private fun JsonObject.requiredStringSet(key: String): Set<String> =
    (this[key] as? JsonArray)?.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
        ?: error("Missing or invalid '$key'")

/** Missing V2 source refinements mean an explicit empty declaration, not legacy inheritance. */
private fun JsonObject.optionalStringSet(key: String): Set<String> {
    val element = this[key] ?: return emptySet()
    val values = element as? JsonArray ?: error("Invalid '$key'")
    return values.mapTo(linkedSetOf()) { it.jsonPrimitive.content }
}

private fun requireOptionalNullableStringParity(
    objectValue: JsonObject,
    key: String,
    expected: String?,
    allowMissing: Boolean = false,
) {
    val element = objectValue[key] ?: return require(allowMissing || expected == null) { "Missing '$key'" }
    val actual = if (element is kotlinx.serialization.json.JsonNull) null else element.jsonPrimitive.contentOrNull
    require(actual == expected) { "'$key' does not match index" }
}

private fun JsonObject.requiredNullableString(key: String): String? {
    val element = this[key] ?: error("Missing '$key'")
    if (element is kotlinx.serialization.json.JsonNull) return null
    return element.jsonPrimitive.contentOrNull
        ?: error("Invalid '$key'")
}

private fun requireNullableStringParity(objectValue: JsonObject, key: String, expected: String?) {
    require(objectValue.requiredNullableString(key) == expected) { "'$key' does not match index" }
}

private fun JsonObject.stringSet(key: String): Set<String> =
    ((this[key] as? JsonArray) ?: JsonArray(emptyList())).mapTo(linkedSetOf()) {
        it.jsonPrimitive.content
    }

private fun Throwable.hasRepositorySecurityFailure(): Boolean {
    var current: Throwable? = this
    while (current != null) {
        if (current is RepositoryTrustException || current is ExtensionRepositoryException.NetworkSecurity) return true
        current = current.cause
    }
    return false
}
