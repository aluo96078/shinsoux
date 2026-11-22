package dev.shinsou.kmp.content.sql

/**
 * Always false: the released IPA is archived with the Debug configuration (release.yml), so
 * `Platform.isDebugBinary` cannot tell a developer's build from what users install. Main-thread
 * content SQL is logged on iOS until the app gets a build flag that only development builds set.
 */
internal actual fun contentDebugBuild(): Boolean = false
