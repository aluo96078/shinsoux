package dev.shinsou.kmp.plugin

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract
import kotlin.time.Clock

/**
 * A host of a source failed several times in a row; the request was not sent. The message (shown
 * to the user) names the host, which says what the user reads: it is never logged.
 */
public class PluginSourceBackoffException(host: String, retryInMillis: Long) :
    IllegalStateException("$host failed several times in a row; trying again in ${(retryInMillis + 999) / 1_000} s"),
    dev.shinsou.kmp.logging.QuotesExternalText {
    override val loggableSummary: String get() = "PluginSourceBackoffException"
}

/**
 * The host answered with something that is not an HTTP response a transport can read: a broken
 * status line, header or chunk, a length that does not match the body. It is the host failing,
 * unlike an answer that is well-formed and refused for its size or encoding.
 */
public class PluginMalformedResponseException(message: String) : IllegalArgumentException(message)

/** [require] for what makes an answer malformed: fails with [PluginMalformedResponseException]. */
@OptIn(ExperimentalContracts::class)
internal inline fun requireWellFormedResponse(value: Boolean, message: () -> String) {
    contract { returns() implies value }
    if (!value) throw PluginMalformedResponseException(message())
}

/**
 * Fails fast for a host of one source that keeps failing (R4-5, docs/architecture/0006): after
 * [threshold] transport failures in a row, requests to it are refused for a window that doubles
 * from [baseMillis] up to [maxMillis], instead of each browse waiting for the full timeout. A
 * success, or the window running out, lets the next request through. Keyed by source and host,
 * so a dead image CDN does not stop a source's API. Failures to reach the host count, and so
 * does an answer that is not well-formed ([PluginMalformedResponseException]); a well-formed
 * answer the transport refuses to take, or a request it refuses to send, does not.
 */
public class PluginSourceFailureBackoff(
    private val threshold: Int = 3,
    private val baseMillis: Long = 5_000L,
    private val maxMillis: Long = 60_000L,
    private val maxEntries: Int = PLUGIN_NETWORK_MAX_HOST_STATES,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private class Failures(var count: Int, var blockedUntil: Long)

    private val mutex = Mutex()
    private val failures = LinkedHashMap<Pair<Long, String>, Failures>()

    init {
        require(threshold > 0 && baseMillis > 0 && maxMillis >= baseMillis && maxEntries > 0)
    }

    /** Runs [block] unless the host is backing off; counts a thrown failure against it. */
    @Suppress("ThrowsCount") // each catch only decides whether the failure counts, then rethrows it
    public suspend fun <T> run(sourceId: Long, host: String, block: suspend () -> T): T {
        val key = sourceId to host.lowercase()
        refuseWhileBackingOff(key)
        val result = try {
            block()
        } catch (timeout: TimeoutCancellationException) {
            // A platform's own request timeout leaves this coroutine active; the caller's own
            // timeout running out (the caller going away) does not, and is not the host's fault.
            if (currentCoroutineContext().isActive) recordFailure(key)
            throw timeout
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (tooLarge: PluginResourceLimitException) {
            throw tooLarge // the host answered; the answer was refused
        } catch (malformed: PluginMalformedResponseException) {
            recordFailure(key) // the host keeps answering with what cannot be read
            throw malformed
        } catch (refused: IllegalArgumentException) {
            // What the transports refuse with `require`: an answer over the size limit, one in an
            // encoding or framing they do not take, a request they will not send. The host was
            // reached or never asked; stopping its other requests for that would be wrong.
            throw refused
        } catch (@Suppress("TooGenericExceptionCaught") error: Throwable) {
            recordFailure(key)
            throw error
        }
        mutex.withLock { failures.remove(key) }
        return result
    }

    /**
     * Refuses now when the host is backing off, for a caller about to wait its turn for the host:
     * a request that is going to be refused does not take a turn first.
     */
    public suspend fun check(sourceId: Long, host: String): Unit = refuseWhileBackingOff(sourceId to host.lowercase())

    private suspend fun refuseWhileBackingOff(key: Pair<Long, String>) = mutex.withLock {
        val wait = (failures[key]?.blockedUntil ?: 0L) - nowEpochMillis()
        if (wait > 0) throw PluginSourceBackoffException(key.second, wait)
    }

    private suspend fun recordFailure(key: Pair<Long, String>) = mutex.withLock {
        val now = nowEpochMillis()
        val entry = failures.remove(key) ?: Failures(0, 0L)
        if (failures.size >= maxEntries) {
            // The oldest that is not holding a host back, so one source failing on many hosts does
            // not lift another's window; the oldest of all when every one is.
            failures.remove(failures.entries.firstOrNull { it.value.blockedUntil <= now }?.key ?: failures.keys.first())
        }
        failures[key] = entry // most recently failed last, so the oldest is dropped first
        // Requests already in flight when the window opened fail with it; they are the same
        // outage, not further failures in a row, so they do not lengthen the next window.
        if (entry.blockedUntil > now) return@withLock
        entry.count++
        if (entry.count >= threshold) {
            val doublings = (entry.count - threshold).coerceAtMost(MAX_DOUBLINGS)
            entry.blockedUntil = now + minOf(maxMillis, baseMillis shl doublings)
        }
    }

    private companion object {
        const val MAX_DOUBLINGS = 16
    }
}
