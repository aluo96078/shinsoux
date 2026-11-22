package dev.shinsou.kmp.plugin.runtime

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import dev.shinsou.kmp.plugin.model.PluginManifest
import dev.shinsou.kmp.plugin.model.SourceIndexEntry
import dev.shinsou.kmp.plugin.network.PluginHttpTransport
import dev.shinsou.kmp.plugin.network.PluginNetworkClient
import dev.shinsou.kmp.plugin.storage.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.storage.KeyValuePluginStorage

class JavaScriptCoreDirectRuntimePermissionTest {
    @Test
    fun directFactoryRequiresExplicitScriptExecutionPermission() = runTest {
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val source = SourceIndexEntry("Direct", "all", 905L, "https://source.example")
        val manifest = PluginManifest(
            id = "direct.jsc",
            name = "Direct JSC",
            version = "1.0.0",
            versionCode = 1,
            lang = "all",
            script = "direct.jsc.js",
            signature = "0".repeat(64),
            sources = listOf(source),
        )

        val failure = assertFailsWith<IllegalArgumentException> {
            JavaScriptCoreScriptPluginRuntimeFactory.unsafeForTests().createForSource(
                script = "var source = {};",
                manifest = manifest,
                source = source,
                environment = ScriptPluginEnvironment(
                    network = PluginNetworkClient(
                        PluginHttpTransport { error("No network request expected") },
                        storage,
                    ),
                    storage = storage,
                ),
            )
        }

        assertTrue(failure.message.orEmpty().contains("EXECUTE_SCRIPT"))
    }
}
