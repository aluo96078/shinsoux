package dev.shinsou.kmp.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Plugin developer mode as the user set it on this device (ADR-0002 §8.1, S7). */
public data class PluginDeveloperModeState(
    val enabled: Boolean = false,
    /** LAN addresses the user entered for a development server, besides this device itself. */
    val lanHosts: List<String> = emptyList(),
)

/**
 * ADR-0002 §8.1: lets a plugin author load an *unsigned* package from a development server on
 * this device (127.0.0.1, localhost, ::1) or on a LAN address the user typed in, and run it in the
 * sandbox marked as in development. Device-local and off by default; it never applies to any other
 * address, and the package's own requests stay HTTPS-only with the usual network rules. Such
 * packages install under their own `packageId~dev` key, so they never replace a real install.
 */
public class PluginDeveloperModeStore(private val store: PluginKeyValueStore) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(PluginDeveloperModeState())

    /** Read synchronously by repository trust decisions; current once [load] has run. */
    public val state: StateFlow<PluginDeveloperModeState> = mutableState.asStateFlow()

    public suspend fun load(): PluginDeveloperModeState = mutex.withLock {
        val enabled = store.getString(ENABLED_KEY) == "true"
        val hosts = store.getString(HOSTS_KEY).orEmpty().split('\n').filter(String::isNotBlank).filter(::isAllowedLanHost)
        PluginDeveloperModeState(enabled, hosts).also { mutableState.value = it }
    }

    public suspend fun setEnabled(enabled: Boolean): Unit = mutex.withLock {
        store.putString(ENABLED_KEY, enabled.toString())
        mutableState.value = mutableState.value.copy(enabled = enabled)
    }

    /** Replaces the LAN addresses; anything that is not a private-network address is refused. */
    public suspend fun setLanHosts(hosts: List<String>): Unit = mutex.withLock {
        val normalized = hosts.map { it.trim().lowercase() }.filter(String::isNotEmpty).distinct()
        require(normalized.size <= MAX_HOSTS) { "Too many development addresses" }
        normalized.forEach { require(isAllowedLanHost(it)) { "'$it' is not a private network address" } }
        store.putString(HOSTS_KEY, normalized.joinToString("\n"))
        mutableState.value = mutableState.value.copy(lanHosts = normalized)
    }

    /** True when developer mode is on and [normalizedBaseUrl] is on this device or an entered LAN address. */
    public fun admitsRepository(normalizedBaseUrl: String): Boolean {
        val current = state.value
        if (!current.enabled) return false
        val host = repositoryHost(normalizedBaseUrl) ?: return false
        return host in LOOPBACK_HOSTS || host in current.lanHosts
    }

    private companion object {
        const val ENABLED_KEY = "plugin.developer-mode.enabled"
        const val HOSTS_KEY = "plugin.developer-mode.lan-hosts"
        const val MAX_HOSTS = 8
        val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
    }
}

private fun repositoryHost(url: String): String? {
    val scheme = url.substringBefore("://", "")
    if (scheme != "http" && scheme != "https") return null
    val authority = url.substringAfter("://").substringBefore('/')
    if ('@' in authority) return null
    val host = if (authority.startsWith('[')) authority.substringAfter('[').substringBefore(']')
    else authority.substringBefore(':')
    return host.lowercase().takeIf(String::isNotEmpty)
}

/**
 * A private-network IPv4 literal (10/8, 172.16/12, 192.168/16). Names (even mDNS `.local`) are
 * refused: the unsigned fetch is not DNS-pinned, and a literal cannot resolve to a public host.
 */
internal fun isAllowedLanHost(host: String): Boolean {
    val octets = host.split('.').map(String::toIntOrNull)
    if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) return false
    val a = octets[0]!!
    val b = octets[1]!!
    return a == 10 || a == 172 && b in 16..31 || a == 192 && b == 168
}
