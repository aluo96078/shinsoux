package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.sync.v2.HlcTimestamp

/**
 * Canonical wire form of an HLC timestamp (shinsou_sync_service `docs/spec/sync-semantics.md` §1):
 * `<millis>:<counter>:<deviceId>`, no leading zeros, ASCII device id. Equal timestamps have equal
 * strings, and [HlcTimestamp]'s natural order matches the server's.
 */
public object SyncClock {
    public const val MAX_MILLIS: Long = (1L shl 53) - 1
    public const val MAX_COUNTER: Long = Int.MAX_VALUE.toLong()

    private val PATTERN = Regex("^(0|[1-9][0-9]{0,15}):(0|[1-9][0-9]{0,9}):([A-Za-z0-9_-]{1,64})$")

    public fun parse(value: String): HlcTimestamp {
        val match = requireNotNull(PATTERN.matchEntire(value)) { "Invalid HLC timestamp" }
        val millis = match.groupValues[1].toLong()
        val counter = match.groupValues[2].toLong()
        require(millis <= MAX_MILLIS) { "HLC millis out of range" }
        require(counter <= MAX_COUNTER) { "HLC counter out of range" }
        return HlcTimestamp(millis, counter.toInt(), match.groupValues[3])
    }

    public fun format(value: HlcTimestamp): String {
        require(value.millis <= MAX_MILLIS) { "HLC millis out of range" }
        require(PATTERN.matches("0:0:${value.deviceId}")) { "HLC device id is not portable" }
        return "${value.millis}:${value.counter}:${value.deviceId}"
    }
}
