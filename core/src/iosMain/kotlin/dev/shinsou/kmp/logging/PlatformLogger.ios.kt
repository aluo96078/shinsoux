package dev.shinsou.kmp.logging

import platform.Foundation.NSLog

internal actual fun platformLogger(): Logger = Logger { level, tag, message, error ->
    // NSLog takes a format. A Kotlin String passed as a variadic argument is not bridged to
    // NSString and crashes on '%@', so the whole line is the format with every '%' escaped.
    val text = if (error == null) message else "$message: $error"
    NSLog("Shinsou X [${level.name}] $tag: $text".replace("%", "%%"))
}
