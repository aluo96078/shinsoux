package dev.shinsou.kmp.logging

import kotlinx.serialization.SerializationException
import kotlin.concurrent.Volatile

/** How serious a [Log] record is; records below [Log.minimumLevel] are not written. */
public enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/**
 * Where [Log] records go: each platform's system log by default ([platformLogger]).
 *
 * Never pass tokens, API keys, cookies, passwords or what the user reads or writes (titles of
 * their library, chapter text, search terms) to a logger: records may end up in bug reports.
 * Log what failed and why, with identifiers that mean nothing outside the app.
 */
public fun interface Logger {
    public fun log(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/**
 * The app's log (R2-4, docs/architecture/0006). Messages are built lazily, so a record below
 * [minimumLevel] costs nothing. Use it where a failure is caught and handled: a fallback taken
 * silently cannot be told apart from one that never happened.
 */
public object Log {
    @Volatile
    public var logger: Logger = platformLogger()

    /** Warnings and errors only unless a platform entry point asks for more. */
    @Volatile
    public var minimumLevel: LogLevel = LogLevel.WARN

    public fun isLoggable(level: LogLevel): Boolean = level >= minimumLevel

    public inline fun debug(tag: String, error: Throwable? = null, message: () -> String) {
        write(LogLevel.DEBUG, tag, error, message)
    }

    public inline fun info(tag: String, error: Throwable? = null, message: () -> String) {
        write(LogLevel.INFO, tag, error, message)
    }

    public inline fun warn(tag: String, error: Throwable? = null, message: () -> String) {
        write(LogLevel.WARN, tag, error, message)
    }

    /**
     * [warn] for a failure reading the user's own files (a backup, a document, a book): their
     * messages quote file names, titles and paths, so only the failure's kind is written.
     */
    public inline fun warnKindOnly(tag: String, error: Throwable, message: () -> String) {
        write(LogLevel.WARN, tag, null) { "${message()} (${error::class.simpleName ?: "Throwable"})" }
    }

    public inline fun error(tag: String, error: Throwable? = null, message: () -> String) {
        write(LogLevel.ERROR, tag, error, message)
    }

    public inline fun write(level: LogLevel, tag: String, error: Throwable?, message: () -> String) {
        if (!isLoggable(level)) return
        // A logger that fails must not turn a handled failure into an unhandled one.
        try {
            logger.log(level, tag, message(), error?.let(::redactedForLog))
        } catch (_: Throwable) {
            // Not logged: reporting a logger failure through the logger would recurse.
        }
    }
}

/** The system log of this platform: logcat, the unified log (NSLog), or standard error. */
internal expect fun platformLogger(): Logger

/**
 * A failure whose message quotes text the app does not define: a server's error text, or the
 * user's own content (a title, a document name).
 * [Log] never writes that message; it writes [loggableSummary], which must hold only what the app
 * itself defines (a status code, a stable error code).
 */
public interface QuotesExternalText {
    public val loggableSummary: String
}

/**
 * A logged failure whose own message was withheld: only the class names of its cause chain are
 * kept. See [redactedForLog].
 */
public class RedactedLogException internal constructor(classification: String) :
    Exception("$classification (message withheld: it may quote decoded input, a URL or server text)")

private const val MAX_LOGGED_FAILURES = 16

/**
 * Failure messages that may quote secrets are withheld; the record then carries only the class
 * names of the failures involved. Wrapping exceptions often repeat their cause's message, so the
 * whole failure is replaced, not just the part found:
 * - decoding failures quote the input they could not read (kotlinx.serialization adds a
 *   `JSON input:` excerpt), and saved state can hold cookies, passwords or tokens;
 * - network failures quote request URLs (a signed URL carries its token in the query), host
 *   names and header values: any message with a URL, any [isNetworkFailure];
 * - [QuotesExternalText] failures quote a server or the user's content.
 *
 * Causes and suppressed failures are both searched, at most [MAX_LOGGED_FAILURES] of them; a
 * failure with more is withheld too, since what was not searched could quote anything.
 */
@PublishedApi
internal fun redactedForLog(error: Throwable): Throwable {
    val involved = mutableListOf<Throwable>()
    val pending = ArrayDeque(listOf(error))
    var truncated = false
    while (pending.isNotEmpty() && !truncated) {
        val next = pending.removeFirst()
        if (involved.any { it === next }) continue
        truncated = involved.size == MAX_LOGGED_FAILURES
        if (!truncated) {
            involved += next
            next.cause?.let(pending::addLast)
            next.suppressedExceptions.forEach(pending::addLast)
        }
    }
    return if (truncated || involved.any { it.mayQuoteSecrets() }) {
        // Stack frames quote no input: the record keeps where the failure happened.
        redacted(involved, truncated).also { keepStackTrace(from = error, to = it) }
    } else {
        error
    }
}

private fun redacted(involved: List<Throwable>, truncated: Boolean): RedactedLogException {
    val names = involved.joinToString(" <- ") { failure ->
        (failure as? QuotesExternalText)?.loggableSummary ?: failure::class.simpleName ?: "Throwable"
    }
    return RedactedLogException(if (truncated) "$names <- …" else names)
}

private fun Throwable.mayQuoteSecrets(): Boolean =
    this is SerializationException ||
        this is QuotesExternalText ||
        message.quotesAUrl() ||
        isNetworkFailure(this)

private fun String?.quotesAUrl(): Boolean = this != null && "://" in this

/** I/O and HTTP client failures of this platform, whose messages name hosts, addresses and headers. */
internal expect fun isNetworkFailure(error: Throwable): Boolean

/** Gives [to] the stack trace of [from] where the platform allows it (the JVM); elsewhere nothing. */
internal expect fun keepStackTrace(from: Throwable, to: Throwable)
