package dev.shinsou.kmp.plugin

/** Platform DNS used by both repository and untrusted plugin egress admission. */
public expect fun createPlatformPluginHostResolver(): PluginHostResolver

/**
 * Returns a transport which preserves the URL hostname for TLS while restricting connections to
 * the supplied [PluginHostResolution]. Platforms without such an API return null and fail closed.
 */
public expect fun createPlatformPinnedPluginHttpTransport(): PluginHttpTransport?
