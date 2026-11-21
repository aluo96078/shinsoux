package dev.shinsou.kmp.translation

import dev.shinsou.kmp.files.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The key-value store the translation providers keep keys and quotas in (R5-1: these tests are in
 * :feature-translation, which does not see the plugin feature's InMemoryPluginKeyValueStore).
 */
internal class InMemoryKeyValues : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    private val mutex = Mutex()

    override suspend fun getString(key: String): String? = mutex.withLock { values[key] }
    override suspend fun putString(key: String, value: String): Unit = mutex.withLock { values[key] = value }
    override suspend fun remove(key: String): Unit = mutex.withLock { values.remove(key) }
}
