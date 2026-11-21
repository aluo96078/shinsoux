package dev.shinsou.kmp.plugin.sandbox

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.KeyValuePluginStorage
import dev.shinsou.kmp.plugin.PluginHttpTransport
import dev.shinsou.kmp.plugin.PluginManifest
import dev.shinsou.kmp.plugin.PluginNetworkClient
import dev.shinsou.kmp.plugin.PluginRuntimePermission
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.SourceIndexEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * R5-3 (docs/architecture/0006): one round trip through the isolated-process plugin sandbox, run by
 * the release workflow on the shrunk APK before it is published, since R8 can strip what only the
 * AIDL binding, the app-zygote preload or JNI reaches:
 * `adb shell am broadcast -n <package>/dev.shinsou.kmp.plugin.sandbox.SandboxSelfCheckReceiver`.
 *
 * The manifest guards it with the DUMP permission, which only the shell and the system hold, so no
 * installed app can start it. It touches no user data: a built-in script, an in-memory store and
 * no network. The broadcast's result data is [SELF_CHECK_PASSED], or what failed (its kind only).
 */
public class SandboxSelfCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            val outcome = withTimeoutOrNull(SELF_CHECK_TIMEOUT_MS) { selfCheckOutcome(app) } ?: "failed: timed out"
            pending.resultCode = if (outcome == SELF_CHECK_PASSED) RESULT_PASSED else RESULT_FAILED
            pending.resultData = outcome
            pending.finish()
        }
    }

    private companion object {
        const val RESULT_PASSED = 1
        const val RESULT_FAILED = 2

        /** Under the 60 s a background broadcast may take; a cold app zygote on an emulator needs a few. */
        const val SELF_CHECK_TIMEOUT_MS = 45_000L
    }
}

internal const val SELF_CHECK_PASSED = "passed"

@Suppress("TooGenericExceptionCaught") // Any failure is the check's answer, reported by its kind.
private suspend fun selfCheckOutcome(context: Context): String = try {
    runSandboxSelfCheck(context)
    SELF_CHECK_PASSED
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    "failed: ${error::class.simpleName}"
}

/**
 * Starts a sandbox process and checks that it is isolated (the native probe over AIDL), then runs a
 * built-in script in another one through SandboxProtocol and checks what it returns.
 */
internal suspend fun runSandboxSelfCheck(context: Context) {
    val provider = AndroidSandboxTransportProvider(context)
    val probed = provider.open(SELF_CHECK_PACKAGE) as AndroidSandboxTransport
    try {
        val escaped = probed.probe(null) or probed.probe(context.filesDir.path)
        check(escaped == 0) { "The sandbox could do something forbidden (bits $escaped)" }
    } finally {
        probed.close()
    }
    val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
    val environment = ScriptPluginEnvironment(
        network = PluginNetworkClient(transport = PluginHttpTransport { error("The self-check has no network") }, storage = storage),
        storage = storage,
        runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT),
    )
    val runtime = SandboxRuntimeFactory(transports = provider)
        .createForSource(SELF_CHECK_SCRIPT, selfCheckManifest, selfCheckSource, environment)
    try {
        val page = runtime.getPopularManga(1)
        check(page.mangas == listOf(SManga(url = "/self-check", title = "自我檢查"))) { "The script's result came back changed" }
    } finally {
        runtime.close()
    }
}

private const val SELF_CHECK_PACKAGE = "dev.shinsou.sandbox.selfcheck"

private val selfCheckSource = SourceIndexEntry(
    name = "Self-check",
    lang = "all",
    id = 1,
    baseUrl = "https://self-check.invalid",
)

private val selfCheckManifest = PluginManifest(
    id = SELF_CHECK_PACKAGE,
    name = "Self-check",
    version = "1.0.0",
    versionCode = 1,
    lang = "all",
    script = "index.js",
    signature = "0".repeat(64),
    sources = listOf(selfCheckSource),
)

private val SELF_CHECK_SCRIPT = """
    var source = {
      name: 'Self-check', baseUrl: 'https://self-check.invalid',
      getPopularManga: function (page) {
        return { mangas: [{ url: '/self-check', title: '自我檢查' }], hasNextPage: page > 1 };
      }
    };
""".trimIndent()
