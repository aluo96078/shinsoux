package dev.shinsou.kmp.sync.v2

import kotlinx.serialization.Serializable

/**
 * A Hybrid Logical Clock value. Natural ordering is also the deterministic LWW ordering used by
 * the sync reducer: physical time, logical counter, then the installation-specific device id.
 */
@Serializable
data class HlcTimestamp(
    val millis: Long,
    val counter: Int,
    val deviceId: String,
) : Comparable<HlcTimestamp> {
    init {
        require(millis >= 0) { "HLC millis cannot be negative" }
        require(counter >= 0) { "HLC counter cannot be negative" }
        require(deviceId.isNotBlank()) { "HLC device id cannot be blank" }
    }

    override fun compareTo(other: HlcTimestamp): Int = when {
        millis != other.millis -> millis.compareTo(other.millis)
        counter != other.counter -> counter.compareTo(other.counter)
        else -> deviceId.compareTo(other.deviceId)
    }
}

/**
 * Mutable clock state intended to be serialized by its owning [LocalSyncStore] transaction.
 * Callers must not share one instance between unsynchronised threads.
 */
class HybridLogicalClock(
    val deviceId: String,
    initial: HlcTimestamp = HlcTimestamp(0, 0, deviceId),
    private val wallClockMillis: () -> Long,
) {
    private var last: HlcTimestamp

    init {
        require(deviceId.isNotBlank()) { "Device id cannot be blank" }
        require(initial.deviceId == deviceId) { "Initial HLC belongs to another device" }
        last = initial
    }

    fun current(): HlcTimestamp = last

    fun tick(): HlcTimestamp = tick(wallClockMillis())

    fun tick(wallMillis: Long): HlcTimestamp {
        require(wallMillis >= 0) { "Wall clock cannot be negative" }
        last = if (wallMillis > last.millis) {
            HlcTimestamp(wallMillis, 0, deviceId)
        } else {
            nextAt(last.millis, last.counter)
        }
        return last
    }

    /** Incorporates a received timestamp before producing the next local timestamp. */
    fun observe(remote: HlcTimestamp): HlcTimestamp = observe(remote, wallClockMillis())

    fun observe(remote: HlcTimestamp, wallMillis: Long): HlcTimestamp {
        require(wallMillis >= 0) { "Wall clock cannot be negative" }
        val maxMillis = maxOf(wallMillis, last.millis, remote.millis)
        val nextCounter = when {
            maxMillis == last.millis && maxMillis == remote.millis -> maxOf(last.counter, remote.counter)
            maxMillis == last.millis -> last.counter
            maxMillis == remote.millis -> remote.counter
            else -> -1
        }
        last = if (nextCounter < 0) {
            HlcTimestamp(maxMillis, 0, deviceId)
        } else {
            nextAt(maxMillis, nextCounter)
        }
        return last
    }

    private fun nextAt(millis: Long, counter: Int): HlcTimestamp = if (counter == Int.MAX_VALUE) {
        check(millis < Long.MAX_VALUE) { "HLC exhausted" }
        HlcTimestamp(millis + 1, 0, deviceId)
    } else {
        HlcTimestamp(millis, counter + 1, deviceId)
    }
}
