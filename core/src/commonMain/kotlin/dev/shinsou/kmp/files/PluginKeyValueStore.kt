package dev.shinsou.kmp.files

/** Adapter point for DataStore, NSUserDefaults, Keychain, or an encrypted desktop store. */
public interface PluginKeyValueStore {
    public suspend fun getString(key: String): String?
    public suspend fun putString(key: String, value: String)
    public suspend fun remove(key: String)
}
