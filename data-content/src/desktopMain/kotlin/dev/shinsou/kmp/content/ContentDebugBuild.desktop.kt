package dev.shinsou.kmp.content

internal actual fun contentDebugBuild(): Boolean =
    System.getProperty("shinsou.debug")?.toBooleanStrictOrNull()
        ?: ContentSqlThreadGuard::class.java.desiredAssertionStatus()
