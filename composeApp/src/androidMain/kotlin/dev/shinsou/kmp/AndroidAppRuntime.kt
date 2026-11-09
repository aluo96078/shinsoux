package dev.shinsou.kmp

import android.content.Context
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.network.installConfiguredImageLoader
import dev.shinsou.kmp.plugin.AndroidBrowserUserAgentProvider
import dev.shinsou.kmp.plugin.AndroidPluginBrowserSessionTransport
import dev.shinsou.kmp.plugin.sandbox.AndroidSandboxTransportProvider
import dev.shinsou.kmp.plugin.SandboxOnlyScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.sandbox.SandboxRuntimeFactory
import dev.shinsou.kmp.plugin.sandbox.androidSandboxLifecycle
import dev.shinsou.kmp.tts.AndroidTextToSpeechEngine
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The app core for the whole process, not one activity. It used to be built in
 * MainActivity.onCreate and closed in onDestroy, so backing out of the app or swiping it away
 * shut the sources down mid-download. Now downloads keep running (under the download service)
 * until they finish, and a new activity reattaches to the same core.
 */
internal class AndroidAppRuntime private constructor(context: Context) {
    private val applicationContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val progressPublished = AtomicBoolean(false)

    val sharedState = AndroidSharedState.get(applicationContext)
    val repository = sharedState.repository

    /** Idle stops and LRU of the plugin sandboxes; lives as long as the process. */
    private val sandboxScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val composition: ShinsouComposition = run {
        // Coil creates its own Ktor client by default. On the Android emulator that client
        // inherits the system's stale 10.0.2.2 proxy, while the app's source client explicitly
        // bypasses it. Reuse the same configured client for images so covers follow the exact
        // networking policy used by source requests.
        val httpClient = createPlatformHttpClient()
        installConfiguredImageLoader(httpClient) {
            add(dev.shinsou.kmp.network.AndroidAvifDecoder.Factory())
        }
        ShinsouComposition(
            repository = repository,
            httpClient = httpClient,
            pluginKeyValueStore = AndroidPluginKeyValueStore(applicationContext),
            fileSystem = sharedState.fileSystem,
            // ADR-0002 §10.1: every package runs in an isolated process
            // of its own (S2).
            runtimeFactory = SandboxOnlyScriptPluginRuntimeFactory(
                SandboxRuntimeFactory(
                    transports = AndroidSandboxTransportProvider(applicationContext),
                    lifecycle = androidSandboxLifecycle(applicationContext, sandboxScope),
                ),
            ),
            autoBackupService = sharedState.autoBackups,
            contentInfrastructure = AndroidContentInfrastructure(applicationContext),
            platformTextToSpeechEngine = AndroidTextToSpeechEngine(applicationContext),
            shuYueMigrationSecretStore = AndroidShuYueMigrationSecretStore(applicationContext),
            pluginBrowserSessionTransport = AndroidPluginBrowserSessionTransport(applicationContext),
            platformBrowserUserAgentProvider = AndroidBrowserUserAgentProvider(applicationContext),
        )
    }

    fun strings(): ShinsouStrings {
        val preference = repository.snapshot.value.settings.general.languagePreference
        val tag = preference
            ?.takeUnless { it.equals("system", ignoreCase = true) }
            ?: Locale.getDefault().toLanguageTag()
        return shinsouStringsFor(tag)
    }

    /** Starts the core once per process; later activities only reattach. */
    suspend fun start() {
        if (progressPublished.compareAndSet(false, true)) {
            AndroidBackgroundDownloads.systemStop = {
                val stoppedRun = AndroidBackgroundDownloads.currentRun()
                scope.launch {
                    composition.backgroundDownloads.suspendForSystem(
                        stillApplies = { AndroidBackgroundDownloads.currentRun() == stoppedRun },
                    )
                }
            }
            scope.launch {
                composition.backgroundDownloads.status
                    .collect { progress -> AndroidBackgroundDownloads.publish(applicationContext, progress, strings()) }
            }
        }
        if (!started.compareAndSet(false, true)) return
        try {
            composition.start()
        } catch (error: Throwable) {
            // Let the next activity try again instead of leaving the core unstarted for good.
            started.set(false)
            throw error
        }
    }

    companion object {
        @Volatile
        private var instance: AndroidAppRuntime? = null

        fun get(context: Context): AndroidAppRuntime = instance ?: synchronized(this) {
            instance ?: AndroidAppRuntime(context).also { instance = it }
        }
    }
}
