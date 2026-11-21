package dev.shinsou.kmp.logging

internal actual fun isNetworkFailure(error: Throwable): Boolean =
    error is java.io.IOException || error.javaClass.name.startsWith("io.ktor.")

internal actual fun keepStackTrace(from: Throwable, to: Throwable) {
    to.stackTrace = from.stackTrace
}
