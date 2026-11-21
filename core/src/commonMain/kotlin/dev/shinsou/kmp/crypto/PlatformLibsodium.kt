package dev.shinsou.kmp.crypto

/** Loads and initializes the platform's libsodium once; [SodiumPrimitives] serializes calls. */
internal expect suspend fun initializePlatformLibsodium()
