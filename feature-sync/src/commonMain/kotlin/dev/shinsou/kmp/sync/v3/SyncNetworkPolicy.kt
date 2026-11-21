package dev.shinsou.kmp.sync.v3

/** A validated sync server base URL (no trailing slash). */
public data class SyncServerAddress(
    val baseUrl: String,
    /** True when the server is reached over plain HTTP on a loopback or private-network address. */
    val insecureLan: Boolean,
) {
    val host: String get() = SyncNetworkPolicy.hostOf(baseUrl)
}

public class SyncServerAddressException(public val reason: Reason, message: String) : IllegalArgumentException(message) {
    public enum class Reason { EMPTY, INVALID, UNSUPPORTED_SCHEME, INSECURE_PUBLIC_HOST }
}

/**
 * Plain HTTP is accepted only for loopback and private-LAN hosts (a self-hosted server on the
 * user's own network); every other host must use HTTPS. Credentials in the URL are rejected.
 */
public object SyncNetworkPolicy {
    public fun parse(input: String): SyncServerAddress {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) throw SyncServerAddressException(SyncServerAddressException.Reason.EMPTY, "Server address is empty")
        val hasScheme = "://" in trimmed
        val scheme = if (hasScheme) trimmed.substringBefore("://").lowercase() else null
        if (scheme != null && scheme != "http" && scheme != "https") {
            throw SyncServerAddressException(SyncServerAddressException.Reason.UNSUPPORTED_SCHEME, "Unsupported scheme $scheme")
        }
        val rest = if (hasScheme) trimmed.substringAfter("://") else trimmed
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, authorityEnd)
        val path = rest.substring(authorityEnd).substringBefore('?').substringBefore('#').trimEnd('/')
        if (authority.isEmpty() || '@' in authority || authority.any { it.isWhitespace() }) {
            throw SyncServerAddressException(SyncServerAddressException.Reason.INVALID, "Invalid server address")
        }
        val host = hostOfAuthority(authority)
        if (host.isEmpty()) throw SyncServerAddressException(SyncServerAddressException.Reason.INVALID, "Invalid server address")
        val portText = portOfAuthority(authority)
        if (portText != null && (portText.toIntOrNull() ?: -1) !in 1..65535) {
            throw SyncServerAddressException(SyncServerAddressException.Reason.INVALID, "Invalid port")
        }
        val local = isLocalOrPrivateHost(host)
        val effectiveScheme = scheme ?: if (local) "http" else "https"
        if (effectiveScheme == "http" && !local) {
            throw SyncServerAddressException(
                SyncServerAddressException.Reason.INSECURE_PUBLIC_HOST,
                "Plain HTTP is only allowed on a local network",
            )
        }
        return SyncServerAddress("$effectiveScheme://$authority$path", insecureLan = effectiveScheme == "http")
    }

    internal fun hostOf(baseUrl: String): String = hostOfAuthority(baseUrl.substringAfter("://").substringBefore('/'))

    private fun hostOfAuthority(authority: String): String {
        if (authority.startsWith("[")) return authority.substringAfter('[').substringBefore(']').lowercase()
        return authority.substringBefore(':').lowercase()
    }

    private fun portOfAuthority(authority: String): String? {
        val tail = when {
            authority.startsWith("[") -> authority.substringAfter(']')
            ':' in authority -> ":" + authority.substringAfter(':')
            else -> ""
        }
        return if (tail.startsWith(":") && tail.length > 1) tail.substring(1) else null
    }

    public fun isLocalOrPrivateHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) {
            return true
        }
        ipv4(h)?.let { (a, b) ->
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in h) {
            return h == "::1" || h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80")
        }
        // A bare single-label name ("nas", "macbook") only resolves on the local network.
        return '.' !in h && h.isNotEmpty()
    }

    private fun ipv4(host: String): Pair<Int, Int>? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it !in 0..255 }) return null
        return numbers[0] to numbers[1]
    }
}
