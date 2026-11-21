package dev.shinsou.kmp.logging

/** Standard error, which the desktop app's launchers keep (and the packaged app's console shows). */
internal actual fun platformLogger(): Logger = Logger { level, tag, message, error ->
    System.err.println("Shinsou X [$level] $tag: $message")
    error?.printStackTrace()
}
