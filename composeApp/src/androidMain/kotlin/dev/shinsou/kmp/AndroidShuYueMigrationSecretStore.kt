package dev.shinsou.kmp

import android.content.Context
import dev.shinsou.kmp.migration.shuyue.KeyValueShuYueMigrationSecretStore
import dev.shinsou.kmp.migration.shuyue.ShuYueMigrationSecretStore
import dev.shinsou.kmp.plugin.PluginKeyValueStore

/** ShuYue batches use the plugin store's non-exportable Android Keystore AES-GCM key. */
internal class AndroidShuYueMigrationSecretStore private constructor(
    delegate: PluginKeyValueStore,
) : ShuYueMigrationSecretStore by KeyValueShuYueMigrationSecretStore(delegate) {
    constructor(context: Context) : this(AndroidPluginKeyValueStore(context.applicationContext))
}
