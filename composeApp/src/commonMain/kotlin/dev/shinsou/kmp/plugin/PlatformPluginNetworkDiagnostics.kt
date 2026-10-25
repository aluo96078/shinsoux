package dev.shinsou.kmp.plugin

/**
 * Describes whether this platform can enforce the DNS/TLS binding required by plugin egress.
 *
 * Resolving a host before handing the URL to a normal HTTP client is not sufficient: the
 * client may resolve the host again and connect to a different address. Callers must use this
 * capability only for diagnostics; an unavailable pinned transport must continue to fail closed.
 */
public data class PlatformPluginNetworkCapability(
    public val hostResolverAvailable: Boolean,
    public val pinnedTransportAvailable: Boolean,
    public val detail: String,
) {
    init {
        require(detail.isNotBlank()) { "Platform network capability detail must not be blank" }
        require(!pinnedTransportAvailable || hostResolverAvailable) {
            "A pinned transport cannot be available without a platform host resolver"
        }
    }

    public val canServePinnedRequests: Boolean
        get() = hostResolverAvailable && pinnedTransportAvailable
}

/** Returns a non-secret explanation of the platform's pinned plugin-network capability. */
public expect fun inspectPlatformPluginNetwork(): PlatformPluginNetworkCapability
