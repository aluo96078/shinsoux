package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.plugin.events.PluginHostPermission

/** Test fixtures deliberately review and approve the current artifact in one operation. */
internal suspend fun PluginManager.approveCurrentEventGrantReview(
    pluginId: String,
    permissions: Set<PluginHostPermission>,
) {
    val review = requireNotNull(pendingEventGrantReview(pluginId)) {
        "No exact plugin event grant review is pending"
    }
    approveEventGrantReview(pluginId, review, permissions)
}
