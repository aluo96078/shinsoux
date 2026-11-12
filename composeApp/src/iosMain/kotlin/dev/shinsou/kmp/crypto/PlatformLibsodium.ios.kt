package dev.shinsou.kmp.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer

internal actual suspend fun initializePlatformLibsodium() {
    LibsodiumInitializer.initialize()
}
