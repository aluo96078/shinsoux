package dev.shinsou.kmp.logging

// Darwin's network errors reach Kotlin wrapped in Ktor's exceptions (or kotlinx-io's IOException).
internal actual fun isNetworkFailure(error: Throwable): Boolean {
    val name = error::class.qualifiedName.orEmpty()
    return name.startsWith("io.ktor.") || name == "kotlinx.io.IOException" || name.endsWith(".IOException")
}

// Kotlin/Native fixes a throwable's stack trace when it is created.
internal actual fun keepStackTrace(from: Throwable, to: Throwable) = Unit
