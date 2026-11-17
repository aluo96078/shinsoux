package dev.shinsou.kmp.logging

import platform.Foundation.NSLog

internal actual fun platformLogger(): Logger = Logger { level, tag, message, error ->
    // NSLog takes a format: the text goes in as an argument so a '%' in it is not read as one.
    val text = if (error == null) message else "$message: $error"
    NSLog("Shinsou X [%@] %@: %@", level.name, tag, text)
}
