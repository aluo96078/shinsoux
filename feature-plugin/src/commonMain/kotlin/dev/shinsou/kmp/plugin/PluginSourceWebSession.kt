package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.contract.SourceCookie as BrowseSourceCookie
import io.ktor.http.Url
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
/** [SourceWebSession] of [PluginBrowseAdapter]: source cookies and browser challenges. */
internal class PluginSourceWebSession(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
) : SourceWebSession {
    private val manager = deps.manager
    private val requestBuilder = deps.requestBuilder

    override suspend fun setSourceCookie(sourceId: Long, cookie: BrowseSourceCookie): Unit =
        shared.operationMutex.withLock {
            val storageId = shared.requireV2SourceScope(sourceId)?.second ?: run {
                checkNotNull(manager.source(sourceId)) { "Unknown source: $sourceId" }
                sourceId
            }
            require(cookie.name.isNotBlank()) { "Cookie name cannot be blank" }
            require(cookie.domain.isNotBlank()) { "Cookie domain cannot be blank" }
            core.storageForSourceUi(sourceId).setCookie(
                storageId,
                PluginCookie(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain,
                    path = cookie.path.ifBlank { "/" },
                    expiresAtEpochMillis = cookie.expiresAtEpochMillis,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                    hostOnly = cookie.hostOnly,
                ),
            )
            core.markSourceSignedIn(storageId, true)
            core.rebuildSnapshot(errorMessage = null)
        }

    override suspend fun importSourceWebChallengeSession(
        sourceId: Long,
        capability: SourceWebChallengeCapability,
        cookies: List<BrowseSourceCookie>,
        userAgent: String,
        localStorage: Map<String, String>,
    ): Unit = shared.operationMutex.withLock {
        val sourceKey = requireNotNull(shared.v2SourceKeyFor(sourceId) ?: manager.exactSourceKeyForLegacyId(sourceId)) {
            "Generic sources cannot import an embedded browser session"
        }
        manager.importWebChallengeSession(sourceKey, capability, cookies, userAgent, localStorage)
        // A browser session with cookies is a sign-in; one that only passed a check brings none worth the name.
        (shared.requireV2SourceScope(sourceId)?.second ?: sourceId).let { storageId ->
            if (cookies.isNotEmpty()) core.markSourceSignedIn(storageId, true)
        }
        core.rebuildSnapshot(errorMessage = null)
    }

    override suspend fun cancelSourceWebChallenge(
        capability: SourceWebChallengeCapability,
    ) {
        manager.cancelWebChallenge(capability)
    }

    override suspend fun deleteSourceCookie(sourceId: Long, name: String, domain: String): Unit =
        shared.operationMutex.withLock {
            val storageId = shared.requireV2SourceScope(sourceId)?.second ?: run {
                checkNotNull(manager.source(sourceId)) { "Unknown source: $sourceId" }
                sourceId
            }
            core.storageForSourceUi(sourceId).deleteCookie(storageId, name, domain)
            core.rebuildSnapshot(errorMessage = null)
        }

    override suspend fun clearSourceCookies(sourceId: Long): Unit = shared.operationMutex.withLock {
        val storageId = shared.requireV2SourceScope(sourceId)?.second ?: run {
            checkNotNull(manager.source(sourceId)) { "Unknown source: $sourceId" }
            sourceId
        }
        core.storageForSourceUi(sourceId).clearCookies(storageId)
        // A saved account can still sign in again; without one, nothing of the sign-in is left.
        if (runCatchingCancellable { core.storageForSourceUi(sourceId).getCredential(storageId) }.getOrNull() == null) {
            core.markSourceSignedIn(storageId, false)
        }
        core.rebuildSnapshot(errorMessage = null)
    }

    override suspend fun isSourceWebChallengeAvailable(sourceId: Long): Boolean {
        val sourceKey = shared.v2SourceKeyFor(sourceId)
            ?: manager.exactSourceKeyForLegacyId(sourceId)
            ?: return false
        return manager.authorizeWebChallenge(sourceKey)
    }

    override suspend fun sourceWebChallenge(
        sourceId: Long,
        username: String?,
        password: String?,
    ): SourceWebChallengeRequest? {
        val sourceKey = requireNotNull(shared.v2SourceKeyFor(sourceId) ?: manager.exactSourceKeyForLegacyId(sourceId)) {
            "Generic sources cannot receive an embedded browser challenge"
        }
        val authorization = requireNotNull(manager.issueWebChallenge(sourceKey)) {
            "Browser challenge is not authorized for this exact plugin artifact"
        }
        var requestBuilt = false
        return try {
            val source = authorization.source
            val storageId = authorization.storageId
            val sourceName = source.descriptor.displayName
            val baseUrl = source.descriptor.baseUrl.orEmpty()
            val challengeUrl = source.webChallengeUrl() ?: baseUrl
            // Read browser state only after the manager has proven the exact reviewed artifact.
            // The authorization never exposes a raw storage handle: this host adapter uses its
            // already-injected storage with the manager-issued fixed reviewed scope.
            val boundStorage = authorization.storage
            val sourceHeaders = boundStorage.getWebChallengeUserAgent(storageId)
                ?.let(::normalizePluginUserAgent)
                ?.let { mapOf("User-Agent" to it) }
                ?: source.webChallengeUserAgent()
                ?.let { mapOf("User-Agent" to it) }
                .orEmpty()
            val referer = baseUrl
            val supportsLogin = ExtensionCapability.LOGIN in source.descriptor.capabilities
            val localStorageKeys = authorization.localStorageKeys
            val requiredLocalStorageKeys = authorization.requiredLocalStorageKeys
            val sourceOrigin = runCatchingCancellable { Url(baseUrl.trim()) }.getOrNull() ?: return null
            val target = runCatchingCancellable { Url(challengeUrl.trim()) }.getOrNull() ?: return null
            if (!target.protocol.name.equals("https", ignoreCase = true) || target.host.isBlank()) return null
            require(target.sameOrigin(sourceOrigin)) {
                "Web challenge URL must use the source's exact origin"
            }
            require(target.sameOrigin(Url(authorization.canonicalOrigin))) {
                "Web challenge URL is outside the issued origin"
            }
            val built = requestBuilder.build(
                sourceId = storageId,
                request = PluginHttpRequest("GET", target.toString()),
                sourceHeaders = sourceHeaders,
                referer = referer,
            )
            val userAgent = built.transportRequest.headers.entries
                .firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }
                ?.value
                .orEmpty()
            // This method is called only from the explicit Web challenge action. Decrypt credentials
            // on demand here; catalogue discovery and ordinary snapshot refreshes never receive them.
            val hasSuppliedCredentialFields = username != null || password != null
            val suppliedCredential = username
                ?.takeIf(String::isNotBlank)
                ?.let { suppliedUsername ->
                    password
                        ?.takeIf(String::isNotEmpty)
                        ?.let { suppliedPassword -> PluginCredential(suppliedUsername, suppliedPassword) }
                }
            val credential = if (supportsLogin) {
                if (hasSuppliedCredentialFields) {
                    suppliedCredential
                } else {
                    boundStorage.getCredential(storageId)
                        ?.takeIf { it.username.isNotBlank() && it.password.isNotEmpty() }
                }
            } else {
                null
            }
            SourceWebChallengeRequest(
                capability = authorization.capability,
                sourceId = sourceId,
                sourceName = sourceName,
                url = target.toString(),
                userAgent = userAgent,
                cookies = boundStorage.getCookies(storageId).map(::toBrowseCookie),
                requiredCookieName = "cf_clearance".takeIf {
                    requiresCloudflareClearance(sourceKey)
                },
                localStorageKeys = localStorageKeys.sorted(),
                requiredLocalStorageKeys = requiredLocalStorageKeys,
                username = credential?.username,
                password = credential?.password,
                embeddedPolicy = dev.shinsou.kmp.contract.WebChallengeEmbeddedPolicy.ALLOW_REVIEWED_ORIGINS,
                allowedNavigationOrigins = authorization.allowedNavigationOrigins,
                allowedSubresourceOrigins = authorization.allowedSubresourceOrigins,
            ).also { requestBuilt = true }
        } finally {
            // Parsing, secure-storage reads, and request construction remain fallible. Do not
            // leave a capability live unless a complete request is actually returned to the UI.
            if (!requestBuilt) withContext(NonCancellable) {
                manager.cancelWebChallenge(authorization.capability)
            }
        }
    }

    private fun normalizeWebChallengeStorageKeyDeclaration(values: Iterable<String>): Set<String> =
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

    private fun requiresCloudflareClearance(sourceKey: SourceKey): Boolean =
        sourceKey.packageId == "zh.bilimanga" && sourceKey.sourceId == "zh.bilimanga.manga" ||
            sourceKey.packageId == "zh.bilimanga.manga" &&
            sourceKey.sourceId == BILIMANGA_MANGA_SOURCE_ID.toString()

    private fun Url.sameOrigin(other: Url): Boolean =
        protocol.name.equals(other.protocol.name, ignoreCase = true) &&
            host.equals(other.host, ignoreCase = true) &&
            port == other.port
}
