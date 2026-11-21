package dev.shinsou.kmp

import android.content.Context
import dev.shinsou.kmp.backup.shuyue.KeyValueShuYueMigrationSecretStore
import dev.shinsou.kmp.backup.shuyue.ShuYueMigrationSecretStore
import dev.shinsou.kmp.files.KeyValueStore

/** ShuYue batches use the plugin store's non-exportable Android Keystore AES-GCM key. */
internal class AndroidShuYueMigrationSecretStore private constructor(
    delegate: KeyValueStore,
) : ShuYueMigrationSecretStore by KeyValueShuYueMigrationSecretStore(delegate) {
    constructor(context: Context) : this(AndroidPluginKeyValueStore(context.applicationContext))
}
