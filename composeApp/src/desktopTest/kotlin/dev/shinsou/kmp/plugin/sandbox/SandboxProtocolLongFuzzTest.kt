package dev.shinsou.kmp.plugin.sandbox

import kotlin.test.Test

/**
 * Long-running SandboxProtocol fuzz (ADR-0002 §13, S1 acceptance: 24 h without a crash). Skipped
 * unless SHINSOU_SANDBOX_FUZZ_SECONDS is set; each round uses a fresh seed, printed on failure.
 *
 *   SHINSOU_SANDBOX_FUZZ_SECONDS=86400 ./gradlew :composeApp:desktopTest --tests '*SandboxProtocolLongFuzzTest*'
 */
class SandboxProtocolLongFuzzTest {
    @Test
    fun fuzzForConfiguredDuration() {
        val seconds = System.getenv("SHINSOU_SANDBOX_FUZZ_SECONDS")?.toLongOrNull() ?: return
        val end = System.nanoTime() + seconds * 1_000_000_000L
        var seed = System.getenv("SHINSOU_SANDBOX_FUZZ_SEED")?.toLongOrNull() ?: System.currentTimeMillis()
        var rounds = 0L
        while (System.nanoTime() < end) {
            try {
                SandboxProtocolFuzz.run(iterations = 100_000, seed = seed)
            } catch (e: Throwable) {
                throw AssertionError("fuzz failed with seed $seed", e)
            }
            seed++
            rounds++
        }
        println("SandboxProtocol fuzz: ${rounds * 100_000} inputs in $seconds s, no failure")
    }
}
