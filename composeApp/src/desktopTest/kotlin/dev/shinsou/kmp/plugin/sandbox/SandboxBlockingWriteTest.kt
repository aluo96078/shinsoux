package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.PluginHttpResponse
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginManifest
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.sandbox.FakeSandbox.HookOutcome
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A real blocking write (like a full pipe to a sandbox that stopped reading) must not hold
 * cancellation or teardown: writes run on the session's writer, never in the invocation.
 */
class SandboxBlockingWriteTest {
    /** Passes writes to [inner] until [stall], then blocks the writing thread uncancellably. */
    private class StallingTransport(private val inner: FakeSandbox) : SandboxTransport {
        @Volatile var stall = false
        private val release = CountDownLatch(1)
        override suspend fun send(bytes: ByteArray) {
            if (stall) withContext(Dispatchers.IO) { release.await() } // not cancellable
            if (!stall) inner.send(bytes)
        }
        override suspend fun receive(): ByteArray? = inner.receive()
        override fun close() {
            release.countDown()
            inner.close()
        }
    }

    @Test
    fun cancellationIsNotHeldByABlockedWrite() = runBlocking {
        val source = SourceIndexEntry(name = "S", lang = "zh", id = 1, baseUrl = "https://s.test")
        val manifest = PluginManifest(id = "dev.example.stall", name = "S", version = "1.0.0", versionCode = 1, lang = "zh", script = "i.js", signature = "c".repeat(64), sources = listOf(source))
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val env = ScriptPluginEnvironment(
            network = PluginNetworkClient(PluginHttpTransport { PluginHttpResponse(200, ByteArray(0)) }, storage),
            storage = storage,
            runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
        )
        val fakeScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        lateinit var transport: StallingTransport
        val hooks: Map<String, suspend FakeSandbox.Hook.(kotlinx.serialization.json.JsonArray) -> HookOutcome> = mapOf(
            "__shinsouSelectSource" to { HookOutcome.Ok("null") },
            "__shinsouMetadata" to { HookOutcome.Ok("{}") },
            "getPopularManga" to { transport.stall = true; HookOutcome.Hang },
        )
        val runtime = SandboxRuntimeFactory(transports = { StallingTransport(FakeSandbox(fakeScope, hooks = hooks)).also { transport = it } })
            .createForSource("x", manifest, source, env)
        val call = async { runCatching { runtime.getPopularManga(1) } }
        delay(200)
        val t0 = System.nanoTime()
        call.cancel()
        call.join() // the Cancel write blocks forever; the session must still give up and kill
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms < 3_000, "cancellation took $ms ms")
        runtime.close()
        fakeScope.cancel()
    }
}
