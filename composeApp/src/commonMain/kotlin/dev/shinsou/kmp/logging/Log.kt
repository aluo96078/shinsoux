package dev.shinsou.kmp.logging

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

    public inline fun error(tag: String, error: Throwable? = null, message: () -> String) {
        write(LogLevel.ERROR, tag, error, message)
    }

    public inline fun write(level: LogLevel, tag: String, error: Throwable?, message: () -> String) {
        if (!isLoggable(level)) return
        // A logger that fails must not turn a handled failure into an unhandled one.
        try {
            logger.log(level, tag, message(), error)
        } catch (_: Throwable) {
        }
    }
}

/** The system log of this platform: logcat, the unified log (NSLog), or standard error. */
internal expect fun platformLogger(): Logger
