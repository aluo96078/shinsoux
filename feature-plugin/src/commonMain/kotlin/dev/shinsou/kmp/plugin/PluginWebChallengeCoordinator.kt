@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.network.isBlockedNetworkAddress
import dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi
import dev.shinsou.kmp.domain.model.PluginArtifactIdentity
import dev.shinsou.kmp.contract.SourceCookie
import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Object-identity keyed, process-local and deliberately non-serializable browser grant. */
internal data class WebChallengeGrant(
    val capability: SourceWebChallengeCapability,
    val sourceKey: SourceKey,
    val artifactIdentity: PluginArtifactIdentity,
    val runtimeEpoch: Long,
    val canonicalOrigin: String,
    val storageId: Long,
    val storage: PluginStorage,
    val localStorageKeys: Set<String>,
    val requiredLocalStorageKeys: Set<String>,
)

private const val MAX_WEB_CHALLENGE_GRANTS: Int = 8
private const val MAX_WEB_CHALLENGE_IMPORT_COOKIES: Int = 128
private const val MAX_WEB_CHALLENGE_IMPORT_COOKIE_BYTES: Int = 256 * 1_024

/** Issues, consumes and revokes the exact-source browser challenge capabilities. */
internal class PluginWebChallengeCoordinator(
    private val state: PluginManagerState,
    deps: PluginManagerDependencies,
    private val executionAdmission: PluginExecutionAdmission,
    private val legacyCompat: LegacyPluginCompat,
) {
    private val packageStore = deps.packageStore
    private val environment = deps.environment
    private val eventGrantAdmission = deps.eventGrantAdmission

    /**
     * Issues one exact-runtime browser capability. Generic packages are deliberately denied an
     * embedded browser: browser origins in repository/manifest data are not host review.
     */
    @OptIn(ExtensionImplementationApi::class)
    /**
     * ADR-0002 §6.2: the browser-challenge origins of a third-party (sandboxed) package's source:
     * only the browser-session origins the source declared — which the permission page showed — and
     * only while the user's grant includes BROWSER_CHALLENGE. Pages may load subresources from that
     * source's declared request origins too. Null when it may not open a challenge.
     */
    /**
     * The sign-in sites a source declared for its browser challenge: where the browser may go
     * besides the source itself, and what those pages may load. Empty unless the challenge
     * itself is granted for [sourceKey].
     */
    private suspend fun webChallengeLoginOrigins(stored: StoredPlugin, sourceKey: SourceKey): Pair<Set<String>, Set<String>> {
        if (genericWebChallengeOrigins(stored, sourceKey) == null) return emptySet<String>() to emptySet()
        val declared = stored.manifest.sources.orEmpty().singleOrNull { source ->
            sourceKeyFor(stored.manifest.id, source.id, source.canonicalSourceId) == sourceKey
        } ?: return emptySet<String>() to emptySet()
        val visit = canonicalWebChallengeOrigins(normalizePluginOrigins(declared.browserLoginOrigins))
        return visit to (visit + canonicalWebChallengeOrigins(normalizePluginOrigins(declared.browserLoginResourceOrigins)))
    }

    private suspend fun genericWebChallengeOrigins(stored: StoredPlugin, sourceKey: SourceKey): Pair<Set<String>, Set<String>>? {
        val declared = stored.manifest.sources.orEmpty().singleOrNull { source ->
            sourceKeyFor(stored.manifest.id, source.id, source.canonicalSourceId) == sourceKey
        } ?: return null
        if (PluginRuntimePermission.BROWSER_CHALLENGE !in effectiveRuntimePermissions(stored)) return null
        val grant = executionAdmission.grantFor(stored)
        if (eventGrantAdmission != null && (grant == null || PluginRuntimePermission.BROWSER_CHALLENGE !in grant.runtimePermissions)) return null
        val sessionOrigins = canonicalWebChallengeOrigins(declared.networkPolicy().browserSessionOrigins)
        if (sessionOrigins.isEmpty()) return null
        return sessionOrigins to (sessionOrigins + canonicalWebChallengeOrigins(declared.declaredRequestOrigins()))
    }

    internal suspend fun issueWebChallenge(requestedKey: SourceKey): PluginManager.WebChallengeAuthorization? =
        state.packageMutationMutex.withLock {
            // A stored key may lack the optional numeric projection; complete it from the manifest
            // so storage binding below gets the host-assigned id.
            val sourceKey = legacyCompat.installedLegacyIdLocked(requestedKey)
                ?.let { requestedKey.copy(legacyLongId = it) } ?: requestedKey
            require(sourceKey !in state.disabledNativeSources) { "Browser challenge source is disabled" }
            val stored = packageStore.get(sourceKey.packageId)
            val identity = state.nativeArtifactIdentities[sourceKey.packageId]
                ?: stored?.artifactIdentity()
                ?: return@withLock null
            if (stored == null) return@withLock null
            val generic = genericWebChallengeOrigins(stored, sourceKey)
                ?: throw IllegalArgumentException("This extension was not granted the browser challenge")
            require(PluginRuntimePermission.BROWSER_CHALLENGE in stored.manifest.runtimePermissions.orEmpty()) {
                "Package lacks browser challenge permission"
            }
            val source = legacyCompat.sourceWhilePackageLocked(sourceKey)
                ?: return@withLock null
            val origin = canonicalWebChallengeOrigin(source.webChallengeUrl() ?: source.descriptor.baseUrl)
                ?: return@withLock null
            require(origin in generic.first) {
                "Web challenge URL is outside the granted browser-session origins"
            }
            val login = webChallengeLoginOrigins(stored, sourceKey)
            val subresourceOrigins = generic.second + login.second
            require(origin in subresourceOrigins) {
                "Web challenge document origin must remain an allowed subresource origin"
            }
            val storageId = requireNotNull(sourceKey.legacyLongId)
            val storage = BoundPluginStorage(
                delegate = environment.storage,
                sourceKey = sourceKey,
                allowLegacyMigration = stored.metadata.legacyStorageMigrationAllowed,
            )
            val localKeys = normalizeWebChallengeStorageKeys(source.webChallengeLocalStorageKeys())
            val requiredKeys = normalizeWebChallengeStorageKeys(source.requiredWebChallengeLocalStorageKeys())
            require(requiredKeys.all(localKeys::contains)) {
                "Required browser storage keys must be included in the source allowlist"
            }
            val capability = SourceWebChallengeCapability()
            val epoch = state.nativeRuntimeGeneration
            // Only the newest dialog for an exact source can retain import authority.
            state.webChallengeGrants.removeAll { it.sourceKey == sourceKey }
            while (state.webChallengeGrants.size >= MAX_WEB_CHALLENGE_GRANTS) {
                state.webChallengeGrants.removeAt(0)
            }
            state.webChallengeGrants += WebChallengeGrant(
                capability = capability,
                sourceKey = sourceKey,
                artifactIdentity = identity,
                runtimeEpoch = epoch,
                canonicalOrigin = origin,
                storageId = storageId,
                storage = storage,
                localStorageKeys = localKeys,
                requiredLocalStorageKeys = requiredKeys,
            )
            PluginManager.WebChallengeAuthorization(
                capability, sourceKey, storageId, origin, source, localKeys, requiredKeys,
                allowedSubresourceOrigins = subresourceOrigins,
                // The source itself, and the sign-in sites it declared and the user was shown.
                allowedNavigationOrigins = setOf(origin) + login.first,
                storage = storage,
            )
        }

    /** Compatibility predicate; it does not issue import authority. */
    suspend fun authorizeWebChallenge(sourceKey: SourceKey): Boolean =
        state.packageMutationMutex.withLock {
            if (sourceKey in state.disabledNativeSources) return@withLock false
            // Artifact-bound native runtimes carry no browser-challenge declaration.
            if (state.nativeArtifactIdentities.containsKey(sourceKey.packageId)) return@withLock false
            val stored = packageStore.get(sourceKey.packageId) ?: return@withLock false
            if (state.isLocallyRevoked(stored.artifactIdentity())) return@withLock false
            val review = stored.eventGrantReview(includeEmpty = true) ?: return@withLock false
            val origins = genericWebChallengeOrigins(stored, sourceKey)?.first.orEmpty()
            origins.isNotEmpty() &&
                PluginRuntimePermission.BROWSER_CHALLENGE in stored.manifest.runtimePermissions.orEmpty() &&
                executionAdmission.executionAdmissionGranted(review) && legacyCompat.sourceWhilePackageLocked(sourceKey) != null
        }

    /** Dismissal consumes the exact token so stale platform callbacks cannot import later. */
    suspend fun cancelWebChallenge(capability: SourceWebChallengeCapability) {
        state.packageMutationMutex.withLock {
            state.webChallengeGrants.removeAll { it.capability === capability }
        }
    }

    /**
     * Consumes and revalidates an import under the package mutation lock, then completes every
     * storage write before replacement/unload/revocation can enter the same critical section.
     */
    suspend fun importWebChallengeSession(
        sourceKey: SourceKey,
        capability: SourceWebChallengeCapability,
        cookies: List<SourceCookie>,
        userAgent: String,
        localStorage: Map<String, String>,
    ): Unit = state.packageMutationMutex.withLock {
        val grantIndex = state.webChallengeGrants.indexOfFirst { it.capability === capability }
        require(grantIndex >= 0) { "Browser challenge capability is invalid or already consumed" }
        val grant = state.webChallengeGrants.removeAt(grantIndex)
        require(grant.sourceKey == sourceKey) { "Browser challenge source does not match its capability" }
        require(sourceKey !in state.disabledNativeSources) { "Browser challenge source is disabled" }

        val stored = packageStore.get(sourceKey.packageId)
        val liveIdentity = state.nativeArtifactIdentities[sourceKey.packageId] ?: stored?.artifactIdentity()
        require(liveIdentity == grant.artifactIdentity && state.nativeRuntimeGeneration == grant.runtimeEpoch) {
            "Browser challenge runtime is no longer live"
        }
        val generic = stored?.let { genericWebChallengeOrigins(it, sourceKey) }
        require(generic != null) {
            "Browser challenge storage scope is no longer granted"
        }
        val source = legacyCompat.sourceWhilePackageLocked(sourceKey)
            ?: throw IllegalArgumentException("Browser challenge source is no longer live")
        val liveOrigin = canonicalWebChallengeOrigin(source.webChallengeUrl() ?: source.descriptor.baseUrl)
        require(liveOrigin == grant.canonicalOrigin && liveOrigin in generic.first) {
            "Browser challenge origin changed before import"
        }
        val liveKeys = normalizeWebChallengeStorageKeys(source.webChallengeLocalStorageKeys())
        val liveRequired = normalizeWebChallengeStorageKeys(source.requiredWebChallengeLocalStorageKeys())
        require(liveKeys == grant.localStorageKeys && liveRequired == grant.requiredLocalStorageKeys &&
            liveRequired.all(liveKeys::contains)
        ) { "Browser challenge storage declaration changed before import" }
        val safeStorage = validateImportedWebChallengeStorage(localStorage, liveKeys)
        require(liveRequired.all { !safeStorage[it].isNullOrEmpty() }) {
            "Required browser session data is missing"
        }
        val safeUserAgent = requireNotNull(normalizePluginUserAgent(userAgent)) {
            "Invalid browser User-Agent"
        }
        require(cookies.size <= MAX_WEB_CHALLENGE_IMPORT_COOKIES) {
            "Too many browser cookies were supplied"
        }
        var cookieBytes = 0
        val safeCookies = cookies.map { cookie ->
            cookieBytes += cookie.name.encodeToByteArray().size + cookie.value.encodeToByteArray().size +
                cookie.domain.encodeToByteArray().size + cookie.path.encodeToByteArray().size
            require(cookieBytes <= MAX_WEB_CHALLENGE_IMPORT_COOKIE_BYTES) {
                "Browser cookie import is too large"
            }
            validateImportedWebChallengeCookie(cookie, grant.canonicalOrigin)
        }
        require(safeCookies.isNotEmpty() || safeStorage.isNotEmpty()) {
            "No browser session data was supplied"
        }
        commitWebChallengeSession(grant, safeCookies, safeStorage, safeUserAgent)
    }

    /**
     * PluginStorage has no cross-key transaction primitive. Snapshot every touched value and
     * restore both values and true absence on failure. If even rollback fails, unload the reviewed
     * runtime so residual bytes cannot be consumed in this process. The UA is always written last
     * and restored last as a commit marker convention.
     */
    @Suppress("SuspendFunSwallowedCancellation") // Runs under NonCancellable and rethrows the write failure after rolling back.
    private suspend fun commitWebChallengeSession(
        grant: WebChallengeGrant,
        cookies: List<PluginCookie>,
        localStorage: Map<String, String>,
        userAgent: String,
    ) = withContext(NonCancellable) {
        val previousCookies = grant.storage.getCookies(grant.storageId)
        val previousStorage = localStorage.keys.associateWith {
            grant.storage.getPreference(grant.storageId, it)
        }
        val previousUserAgent = grant.storage.getWebChallengeUserAgent(grant.storageId)
        try {
            cookies.forEach { grant.storage.setCookie(grant.storageId, it) }
            localStorage.forEach { (key, value) -> grant.storage.setPreference(grant.storageId, key, value) }
            grant.storage.setWebChallengeUserAgent(grant.storageId, userAgent)
        } catch (writeFailure: Throwable) {
            val rollbackFailure = runCatchingCancellable {
                grant.storage.clearCookies(grant.storageId)
                previousCookies.forEach { grant.storage.setCookie(grant.storageId, it) }
                previousStorage.forEach { (key, value) ->
                    if (value == null) grant.storage.removePreference(grant.storageId, key)
                    else grant.storage.setPreference(grant.storageId, key, value)
                }
                if (previousUserAgent == null) grant.storage.clearWebChallengeUserAgent(grant.storageId)
                else grant.storage.setWebChallengeUserAgent(grant.storageId, previousUserAgent)
            }.exceptionOrNull()
            if (rollbackFailure != null) {
                writeFailure.addSuppressed(rollbackFailure)
                runCatchingCancellable { grant.storage.clearCookies(grant.storageId) }
                    .exceptionOrNull()?.let(writeFailure::addSuppressed)
                grant.requiredLocalStorageKeys.forEach { key ->
                    runCatchingCancellable { grant.storage.removePreference(grant.storageId, key) }
                        .exceptionOrNull()?.let(writeFailure::addSuppressed)
                }
                runCatchingCancellable { grant.storage.clearWebChallengeUserAgent(grant.storageId) }
                    .exceptionOrNull()?.let(writeFailure::addSuppressed)
                state.nativeArtifactIdentities.remove(grant.sourceKey.packageId)
                state.nativeLifecycleRuntimes.remove(grant.sourceKey.packageId)
                runCatchingCancellable { state.extensionRegistryV2.uninstall(grant.sourceKey.packageId) }
                    .exceptionOrNull()?.let(writeFailure::addSuppressed)
                state.invalidateWebChallengeGrants()
            }
            throw writeFailure
        }
    }

    private fun canonicalWebChallengeOrigin(value: String?): String? = value?.let { raw ->
        runCatching {
            val parsed = io.ktor.http.Url(raw.trim())
            require(parsed.protocol.name.equals("https", ignoreCase = true) && parsed.host.isNotBlank())
            require(parsed.user.isNullOrEmpty() && parsed.password.isNullOrEmpty())
            require(!isBlockedNetworkAddress(parsed.host))
            pluginOrigin(parsed)
        }.getOrNull()
    }

    private fun canonicalWebChallengeOrigins(values: Iterable<String>): Set<String> =
        values.mapTo(linkedSetOf()) { value ->
            requireNotNull(canonicalWebChallengeOrigin(value)) { "Invalid Web challenge origin" }
        }

    private fun normalizeWebChallengeStorageKeys(values: Iterable<String>): Set<String> =
        values.asSequence()
            .map(String::trim)
            .onEach { key ->
                require(key.length in 1..64 && key.all { it.isLetterOrDigit() || it in "._-" }) {
                    "Invalid browser storage key declaration"
                }
            }
            .distinct()
            .take(9)
            .toSet()
            .also { require(it.size <= 8) { "Too many browser storage keys were declared" } }

    private fun validateImportedWebChallengeStorage(
        values: Map<String, String>,
        allowedKeys: Set<String>,
    ): Map<String, String> {
        require(values.keys.all(allowedKeys::contains)) {
            "Browser session contained an undeclared storage key"
        }
        var totalBytes = 0
        return values.entries.associate { (key, value) ->
            require(value.none(Char::isISOControl)) { "Invalid browser storage value" }
            val bytes = value.encodeToByteArray().size
            require(bytes <= 16 * 1_024 && totalBytes + bytes <= 32 * 1_024) {
                "Browser storage value is too large"
            }
            totalBytes += bytes
            key to value
        }
    }

    private fun validateImportedWebChallengeCookie(
        cookie: SourceCookie,
        canonicalOrigin: String,
    ): PluginCookie {
        val origin = io.ktor.http.Url(canonicalOrigin)
        val host = origin.host.lowercase().trimEnd('.')
        val domain = canonicalCookieDomain(cookie.domain).trimEnd('.')
        // Imported browser state is narrowed to the exact reviewed host. A site's login cookie is
        // often set for its parent domain (.linovelib.com for tw.linovelib.com); it is kept, but
        // stored host-only for the reviewed host, so it never reaches a sibling or the parent.
        // The parent must pass the same rules as a response cookie's Domain attribute (no IP
        // host, no public suffix); a cookie for a sibling or unrelated host is still refused.
        val parentDomain = domain != host && host.endsWith(".$domain") && !isCookieIpAddress(host) &&
            isValidCookieDomainSyntax(domain) && !isPluginCookiePublicSuffix(domain)
        require(domain == host || parentDomain) {
            "Browser cookie is outside the reviewed challenge origin"
        }
        require(cookie.path.startsWith('/') && cookie.path.none(Char::isISOControl)) {
            "Invalid browser cookie path"
        }
        return requireNotNull(
            normalizedPluginCookieOrNull(
                PluginCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = host,
                    path = cookie.path,
                    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                    secure = true,
                    httpOnly = cookie.httpOnly,
                    hostOnly = true,
                ),
            ),
        ) { "Invalid browser cookie" }
    }
}
