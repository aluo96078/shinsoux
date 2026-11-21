package dev.shinsou.kmp.network

/*
 * Addresses that untrusted code (an executable source) or an untrusted URL (a web challenge)
 * must never reach: loopback, private, link-local, multicast, documentation and translated
 * ranges, in every spelling the URL parsers accept. Platform-free so every host path shares it.
 */

/** Rejects IPv4 alternate spellings and IPv6 (including IPv4-mapped IPv6) without platform APIs. */
public fun isBlockedNetworkAddress(raw: String): Boolean {
    val host = raw.trim().trim('[', ']').trimEnd('.').lowercase()
    val ipv4 = parseIpv4(host)
    return when {
        isReservedHostName(host) -> true
        ipv4 != null -> isBlockedIpv4(ipv4)
        ':' !in host -> false
        else -> parseIpv6(host)?.let(::isBlockedIpv6) ?: true
    }
}

/** Whether [raw] is a canonical IPv4 or IPv6 literal, as a resolver answer must be. */
public fun isCanonicalIpLiteral(raw: String): Boolean {
    val host = raw.trim().trim('[', ']').trimEnd('.')
    return isCanonicalIpv4(host) ||
        (':' in host && parseIpv6(host) != null && host.split(':').none { '.' in it && !isCanonicalIpv4(it) })
}

private fun isReservedHostName(host: String): Boolean =
    host == "localhost" || host.endsWith(".localhost") || host == "local" ||
        host.endsWith(".local") || host == "metadata.google.internal"

private fun isBlockedIpv4(value: Int): Boolean = BLOCKED_IPV4.any { (base, prefixBits) ->
    val shift = 32 - prefixBits
    value ushr shift == base ushr shift
}

private fun isBlockedIpv6(bytes: ByteArray): Boolean {
    val mapped = bytes.startsWith(IPV4_MAPPED_PREFIX, 96)
    // IPv4-compatible (::a.b.c.d) addresses are judged by their IPv4 part, like mapped ones.
    val compatible = bytes.startsWith(ByteArray(16), 96)
    if (mapped || compatible) {
        return isBlockedIpv4(bytes.copyOfRange(12, 16).fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xff) })
    }
    return BLOCKED_IPV6.any { (prefix, prefixBits) -> bytes.startsWith(prefix, prefixBits) }
}

/** Whether the first [prefixBits] bits of this address equal those of [prefix]. */
private fun ByteArray.startsWith(prefix: ByteArray, prefixBits: Int): Boolean = (0 until prefixBits).all { bit ->
    val mask = 0x80 ushr (bit % 8)
    (this[bit / 8].toInt() and mask) == (prefix[bit / 8].toInt() and mask)
}

private fun isCanonicalIpv4(value: String): Boolean {
    val parts = value.split('.')
    return parts.size == 4 && parts.all { part ->
        part.isNotEmpty() && (part == "0" || !part.startsWith('0')) &&
            part.all(Char::isDigit) && part.toIntOrNull()?.let { it in 0..255 } == true
    }
}

/** Parses every spelling `inet_aton` accepts: one to four parts, each decimal, octal or hex. */
private fun parseIpv4(value: String): Int? {
    val numbers = value.takeIf { it.isNotEmpty() && it.all(::isIpv4Character) }
        ?.split('.')
        ?.takeIf { it.size in 1..4 }
        ?.map(::parseIpv4Part)
        ?.takeIf { parts -> parts.none { it == null } }
        ?.filterNotNull()
        ?: return null
    val limits = IPV4_PART_LIMITS.getValue(numbers.size)
    val shifts = IPV4_PART_SHIFTS.getValue(numbers.size)
    return numbers.takeIf { it.zip(limits).none { (item, limit) -> item > limit } }
        ?.zip(shifts)
        ?.fold(0L) { acc, (item, shift) -> acc or (item shl shift) }
        ?.toInt()
}

private fun isIpv4Character(char: Char): Boolean =
    char.isDigit() || char in 'a'..'f' || char in 'A'..'F' || char == 'x' || char == 'X' || char == '.'

private fun parseIpv4Part(part: String): Long? {
    if (part.isEmpty()) return null
    val radix = if (part.startsWith("0x", true)) 16 else if (part.length > 1 && part.startsWith('0')) 8 else 10
    return part.removePrefix("0x").removePrefix("0X").toLongOrNull(radix)?.takeIf { it >= 0 }
}

private fun parseIpv6(value: String): ByteArray? = runCatching {
    val halves = value.split("::")
    require(halves.size <= 2)
    fun parseSide(side: String): List<Int> = if (side.isEmpty()) emptyList() else side.split(':').flatMap { token ->
        if ('.' in token) listOfNotNull(parseIpv4(token)?.let { it ushr 16 }, parseIpv4(token)?.let { it and 0xffff })
        else listOf(token.toInt(16).also { require(it in 0..0xffff) })
    }
    val left = parseSide(halves[0]); val right = if (halves.size == 2) parseSide(halves[1]) else emptyList()
    val words = if (halves.size == 2) left + List(8 - left.size - right.size) { 0 } + right else left
    require(words.size == 8)
    ByteArray(16) { index -> (words[index / 2] ushr (if (index % 2 == 0) 8 else 0)).toByte() }
}.getOrNull()

private fun ipv4(a: Int, b: Int, c: Int, d: Int): Int = (a shl 24) or (b shl 16) or (c shl 8) or d

private fun ipv6Prefix(vararg bytes: Int): ByteArray = ByteArray(16) { index -> bytes.getOrElse(index) { 0 }.toByte() }

/** Largest value of each part, by number of parts (the last part fills the remaining bytes). */
private val IPV4_PART_LIMITS: Map<Int, List<Long>> = mapOf(
    1 to listOf(0xffffffffL),
    2 to listOf(0xffL, 0xffffffL),
    3 to listOf(0xffL, 0xffL, 0xffffL),
    4 to List(4) { 0xffL },
)

private val IPV4_PART_SHIFTS: Map<Int, List<Int>> = mapOf(
    1 to listOf(0),
    2 to listOf(24, 0),
    3 to listOf(24, 16, 0),
    4 to listOf(24, 16, 8, 0),
)

/** Blocked IPv4 ranges as (base address, prefix length). */
private val BLOCKED_IPV4: List<Pair<Int, Int>> = listOf(
    ipv4(0, 0, 0, 0) to 8, // "this" network
    ipv4(10, 0, 0, 0) to 8, // private
    ipv4(100, 64, 0, 0) to 10, // carrier-grade NAT
    ipv4(127, 0, 0, 0) to 8, // loopback
    ipv4(169, 254, 0, 0) to 16, // link-local
    ipv4(172, 16, 0, 0) to 12, // private
    ipv4(192, 0, 0, 0) to 24, // IETF protocol assignments
    ipv4(192, 0, 2, 0) to 24, // TEST-NET-1
    ipv4(192, 168, 0, 0) to 16, // private
    ipv4(198, 18, 0, 0) to 15, // benchmarking
    ipv4(198, 51, 100, 0) to 24, // TEST-NET-2
    ipv4(203, 0, 113, 0) to 24, // TEST-NET-3
    ipv4(224, 0, 0, 0) to 3, // multicast, reserved and broadcast
)

private val IPV4_MAPPED_PREFIX: ByteArray = ipv6Prefix(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff)

/** Blocked IPv6 ranges as (prefix, prefix length). */
private val BLOCKED_IPV6: List<Pair<ByteArray, Int>> = listOf(
    ipv6Prefix(0x00, 0x64, 0xff, 0x9b) to 96, // NAT64
    ipv6Prefix(0x00, 0x64, 0xff, 0x9b, 0x00, 0x01) to 48, // local-use NAT64
    ipv6Prefix(0x20, 0x02) to 16, // 6to4
    ipv6Prefix(0x20, 0x01, 0x00) to 24, // Teredo and other IETF assignments
    ipv6Prefix(0x20, 0x01, 0x0d, 0xb8) to 32, // documentation
    ipv6Prefix(0xfc) to 7, // unique local
    ipv6Prefix(0xfe, 0x80) to 10, // link-local
    ipv6Prefix(0xfe, 0xc0) to 10, // site-local
    ipv6Prefix(0xff) to 8, // multicast
)
