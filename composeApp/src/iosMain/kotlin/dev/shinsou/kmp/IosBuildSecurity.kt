package dev.shinsou.kmp

/** Target-specific compile-time fact. It cannot be enabled by runtime plugin or repository data. */
internal expect val isIosSimulatorBuild: Boolean
