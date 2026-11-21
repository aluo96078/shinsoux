package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey
import kotlinx.coroutines.sync.withLock
/** [SourceSettings] of [PluginBrowseAdapter]. */
internal class PluginSourceSettings(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
) : SourceSettings {
    private val manager = deps.manager
    private val keyValueStore = deps.keyValueStore

    override suspend fun setSourceEnabled(sourceId: Long, enabled: Boolean): Unit = shared.operationMutex.withLock {
        manager.setEventSourceEnabled(sourceId, enabled)
        keyValueStore.putString(core.sourceEnabledKey(sourceId), enabled.toString())
        core.rebuildSnapshot(errorMessage = null)
    }

    override suspend fun setSourceEnabledV2(sourceKey: SourceKey, enabled: Boolean): Unit =
        shared.operationMutex.withLock {
            checkNotNull(manager.extensionSourceV2(sourceKey)) {
                "Unknown extension v2 source: ${sourceKey.canonicalId}"
            }
            manager.setEventSourceEnabled(sourceKey, enabled)
            keyValueStore.putString(core.sourceEnabledV2Key(sourceKey), enabled.toString())
            core.rebuildSnapshot(errorMessage = null)
        }

    override suspend fun saveSourcePreferences(sourceId: Long, values: Map<String, String>): Unit =
        shared.operationMutex.withLock {
            val normalizedValues = values.mapValues { (key, value) ->
                if (key == ConfiguredPluginProxyResolver.SOURCE_PROXY_PREFERENCE) {
                    SourceNetworkOverride.fromStored(value, SourceNetworkOverride.GLOBAL).storedValue
                } else {
                    value
                }
            }
            val v2Scope = shared.requireV2SourceScope(sourceId)
            val storage = core.storageForSourceUi(sourceId)
            if (v2Scope != null) {
                normalizedValues.forEach { (key, value) ->
                    storage.setPreference(v2Scope.second, key, value)
                }
            } else {
                checkNotNull(manager.source(sourceId)) { "Unknown source: $sourceId" }
                normalizedValues.forEach { (key, value) ->
                    storage.setPreference(sourceId, key, value)
                }
            }
            core.rebuildSnapshot(errorMessage = null)
        }
}
