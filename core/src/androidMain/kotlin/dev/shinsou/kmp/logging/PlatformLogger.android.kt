package dev.shinsou.kmp.logging

internal actual fun platformLogger(): Logger = Logger { level, tag, message, error ->
    when (level) {
        LogLevel.DEBUG -> android.util.Log.d(tag, message, error)
        LogLevel.INFO -> android.util.Log.i(tag, message, error)
        LogLevel.WARN -> android.util.Log.w(tag, message, error)
        LogLevel.ERROR -> android.util.Log.e(tag, message, error)
    }
}
