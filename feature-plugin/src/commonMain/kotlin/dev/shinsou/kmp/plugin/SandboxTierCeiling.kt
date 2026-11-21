package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginHostPermission

/**
 * ADR-0002 §6.1–§6.2: what a sandboxed package can ever be given, whatever it declares and whatever
 * the user approves. Effective permission = declaration ∩ host support ∩ this ceiling ∩ the user's
 * grant. The ceiling is a host constant; neither a package nor the user can raise it.
 *
 * Several ceiling items are enforced structurally elsewhere and are listed here for the record:
 * HTTPS-only exact origins and public-address DNS pinning ([PluginNetworkPolicy]), no developer
 * unpinned browser transport for repository code ([dev.shinsou.kmp.plugin.sandbox.SandboxBridgeDispatcher]),
 * transport-owned framing headers, and no in-process provenance (only the host mints it).
 */
internal object SandboxTierCeiling {
    /** Host permissions a sandboxed package may never hold (§6.2 "永不授予第三方"). */
    val NEVER_GRANTED_HOST_PERMISSIONS: Set<PluginHostPermission> = setOf(PluginHostPermission.REPORT_USER_MESSAGE)

    /** Granted with the installation itself: running the script and reaching its declared origins. */
    val ORDINARY_RUNTIME_PERMISSIONS: Set<PluginRuntimePermission> = setOf(
        PluginRuntimePermission.EXECUTE_SCRIPT,
        PluginRuntimePermission.NETWORK,
    )

    /** Each one off by default and approved item by item (§6.2, §6.5). */
    val SENSITIVE_RUNTIME_PERMISSIONS: Set<PluginRuntimePermission> =
        PluginRuntimePermission.entries.toSet() - ORDINARY_RUNTIME_PERMISSIONS

    /**
     * Refuses a declaration that asks for anything above the ceiling, before any byte is installed.
     * Origins, suffixes and permissions are otherwise validated where they are parsed.
     */
    fun requireWithinCeiling(entry: PluginIndexEntry) {
        val refused = entry.requestedHostPermissions intersect NEVER_GRANTED_HOST_PERMISSIONS
        require(refused.isEmpty()) {
            "Plugin '${entry.id}' requests host permissions no plugin can hold: ${refused.joinToString()}"
        }
        entry.sources.orEmpty().forEach { source ->
            require(source.contentHostSuffixes.all(::isGrantableContentHostSuffix)) {
                "Plugin '${entry.id}' declares a content host suffix that is a public suffix"
            }
        }
    }
}
