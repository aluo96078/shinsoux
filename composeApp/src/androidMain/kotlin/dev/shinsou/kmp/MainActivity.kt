package dev.shinsou.kmp

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.KeyEvent
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import dev.shinsou.kmp.app.App
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.network.installConfiguredImageLoader
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.plugin.RhinoScriptPluginRuntimeFactory
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.sync.UnavailableSnapshotSyncTransport
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.ImportedDocumentLimits
import dev.shinsou.kmp.ui.ImportedDocumentReadException
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.readBoundedImportedBytes
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {
    private val platformScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documentLauncher = ActivityDocumentLauncher()
    private lateinit var composition: ShinsouComposition
    private lateinit var appServices: AndroidAppServices

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val sharedState = AndroidSharedState.get(applicationContext)
        val repository = sharedState.repository
        // Coil creates its own Ktor client by default. On the Android emulator that client
        // inherits the system's stale 10.0.2.2 proxy, while the app's source client explicitly
        // bypasses it. Reuse the same configured client for images so covers follow the exact
        // networking policy used by source requests.
        val httpClient = createPlatformHttpClient()
        installConfiguredImageLoader(httpClient)
        val syncInfrastructure = AndroidSyncInfrastructure(applicationContext)
        composition = ShinsouComposition(
            repository = repository,
            httpClient = httpClient,
            pluginKeyValueStore = AndroidPluginKeyValueStore(applicationContext),
            fileSystem = sharedState.fileSystem,
            runtimeFactory = RhinoScriptPluginRuntimeFactory(),
            autoBackupService = sharedState.autoBackups,
            syncInfrastructure = syncInfrastructure,
        )
        val syncRuntime = requireNotNull(composition.syncRuntime)
        appServices = AndroidAppServices(
            activity = this,
            documentLauncher = documentLauncher,
            browse = composition.browse,
            content = composition.content,
            tracking = composition.tracking,
            cloudflareSync = composition.cloudflareSync,
            syncAwareSnapshotRestore = composition.syncAwareSnapshotRestore,
            stringsProvider = {
                val preference = repository.snapshot.value.settings.general.languagePreference
                val tag = preference
                    ?.takeUnless { it.equals("system", ignoreCase = true) }
                    ?: Locale.getDefault().toLanguageTag()
                shinsouStringsFor(tag)
            },
            snapshotSync = SnapshotSyncController(
                repository = repository,
                transport = UnavailableSnapshotSyncTransport(
                    "iCloud Drive snapshot sync is available only on iOS.",
                ),
                deviceId = "android",
                deviceIdProvider = composition::installationDeviceId,
                writerAllowed = composition::isLegacySnapshotWriterAllowed,
            ),
        )

        setContent {
            val snapshot by repository.snapshot.collectAsState()
            val readerProgressReporter by syncRuntime.readerProgressReporter.collectAsState()
            val syncBoundaryReady by composition.syncBoundaryReady.collectAsState()
            val darkTheme = when (snapshot.settings.appearance.theme) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            val view = LocalView.current
            SideEffect {
                // The activity is edge-to-edge, so icon contrast must follow
                // the persisted Compose theme even with transparent bars.
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            App(
                repository = repository,
                appServices = appServices,
                autoBackupService = composition.autoBackups,
                readerProgressReporter = readerProgressReporter,
                interactionReady = syncBoundaryReady,
            )
        }
        platformScope.launch { composition.start() }
        platformScope.launch {
            repository.snapshot
                .map { snapshot ->
                    snapshot.backupState.let { state ->
                        AndroidBackupScheduleKey(
                            enabled = state.automaticEnabled,
                            intervalHours = state.intervalHours,
                            lastBackupAt = state.lastBackupAt,
                        )
                    }
                }
                .distinctUntilChanged()
                .collect {
                    AndroidAutoBackupScheduler.apply(
                        applicationContext,
                        repository.currentSnapshot.backupState,
                    )
                }
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val readerEvent = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> ReaderVolumeKeyEvent.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> ReaderVolumeKeyEvent.VOLUME_DOWN
            else -> null
        }
        if (
            readerEvent != null &&
            ::appServices.isInitialized &&
            appServices.shouldInterceptReaderVolumeKeys()
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                appServices.emitReaderVolumeKey(readerEvent)
            }
            // Consume both DOWN and UP so Android does not change volume or display its HUD.
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        if (::appServices.isInitialized) appServices.emitForeground()
        if (::composition.isInitialized) platformScope.launch { composition.onForeground() }
    }

    override fun onStop() {
        if (::appServices.isInitialized) appServices.emitBackground()
        if (::composition.isInitialized) platformScope.launch { composition.onBackground() }
        super.onStop()
    }

    override fun onDestroy() {
        if (::composition.isInitialized) runCatching { runBlocking { composition.close() } }
        super.onDestroy()
        platformScope.cancel()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.dataString?.let(DeepLinkParser::parse)?.let(appServices::emitDeepLink)
    }

    private inner class ActivityDocumentLauncher : AndroidDocumentLauncher {
        private var exportContinuation: CancellableContinuation<Boolean>? = null
        private var exportBytes: ByteArray? = null
        private var importContinuation: CancellableContinuation<ImportedDocument?>? = null
        private var importLimits: ImportedDocumentLimits? = null
        private var multipleContinuation: CancellableContinuation<List<ImportedDocument>>? = null
        private var multipleLimits: ImportedDocumentLimits? = null

        private val createDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri ->
            val continuation = exportContinuation ?: return@registerForActivityResult
            val bytes = exportBytes
            exportContinuation = null
            exportBytes = null
            platformScope.launch {
                val saved = if (uri == null || bytes == null) false else withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } != null
                    }.getOrDefault(false)
                }
                if (continuation.isActive) continuation.resume(saved)
            }
        }

        private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val continuation = importContinuation ?: return@registerForActivityResult
            val limits = importLimits
            importContinuation = null
            importLimits = null
            platformScope.launch {
                try {
                    val document = if (uri == null || limits == null) {
                        null
                    } else {
                        withContext(Dispatchers.IO) { readDocument(uri, limits) }
                    }
                    if (continuation.isActive) continuation.resume(document)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

        private val openDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            val continuation = multipleContinuation ?: return@registerForActivityResult
            val limits = multipleLimits
            multipleContinuation = null
            multipleLimits = null
            platformScope.launch {
                try {
                    val documents = if (limits == null) {
                        emptyList()
                    } else {
                        withContext(Dispatchers.IO) { readDocuments(uris, limits) }
                    }
                    if (continuation.isActive) continuation.resume(documents)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }

        override suspend fun export(name: String, contents: ByteArray): Boolean =
            withContext(Dispatchers.Main.immediate) {
                check(exportContinuation == null) { "Another export is already open" }
                suspendCancellableCoroutine { continuation ->
                    exportContinuation = continuation
                    exportBytes = contents.copyOf()
                    continuation.invokeOnCancellation {
                        if (exportContinuation === continuation) {
                            exportContinuation = null
                            exportBytes = null
                        }
                    }
                    createDocument.launch(name)
                }
            }

        override suspend fun import(
            extensions: Set<String>,
            limits: ImportedDocumentLimits,
        ): ImportedDocument? =
            withContext(Dispatchers.Main.immediate) {
                check(importContinuation == null) { "Another import is already open" }
                suspendCancellableCoroutine { continuation ->
                    importContinuation = continuation
                    importLimits = limits
                    continuation.invokeOnCancellation {
                        if (importContinuation === continuation) {
                            importContinuation = null
                            importLimits = null
                        }
                    }
                    openDocument.launch(mimeTypes(extensions))
                }
            }

        override suspend fun pickMany(
            extensions: Set<String>,
            limits: ImportedDocumentLimits,
        ): List<ImportedDocument> =
            withContext(Dispatchers.Main.immediate) {
                check(multipleContinuation == null) { "Another file picker is already open" }
                suspendCancellableCoroutine { continuation ->
                    multipleContinuation = continuation
                    multipleLimits = limits
                    continuation.invokeOnCancellation {
                        if (multipleContinuation === continuation) {
                            multipleContinuation = null
                            multipleLimits = null
                        }
                    }
                    openDocuments.launch(mimeTypes(extensions))
                }
            }

        private fun readDocuments(
            uris: List<Uri>,
            limits: ImportedDocumentLimits,
        ): List<ImportedDocument> {
            var acceptedBytes = 0L
            return uris.map { uri ->
                readDocument(uri, limits, acceptedBytes).also { acceptedBytes += it.contents.size }
            }
        }

        private fun readDocument(
            uri: Uri,
            limits: ImportedDocumentLimits,
            previouslyAcceptedBytes: Long = 0,
        ): ImportedDocument {
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val name = runCatching { displayName(uri) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: uri.lastPathSegment
                ?: "document"
            try {
                val descriptor = contentResolver.openFileDescriptor(uri, "r")
                    ?: throw ImportedDocumentReadException("Unable to open “$name”.")
                val declaredSize = try {
                    descriptor.statSize.takeIf { it >= 0 }
                } catch (error: Throwable) {
                    runCatching { descriptor.close() }
                    throw error
                }
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    ImportedDocument(
                        name = name,
                        contents = readBoundedImportedBytes(
                            name = name,
                            declaredSize = declaredSize,
                            limits = limits,
                            previouslyAcceptedBytes = previouslyAcceptedBytes,
                            read = input::read,
                        ),
                    )
                }
            } catch (error: ImportedDocumentReadException) {
                throw error
            } catch (error: Throwable) {
                throw ImportedDocumentReadException("Unable to read “$name”.", error)
            }
        }

        private fun displayName(uri: Uri): String {
            val fromProvider = contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            return fromProvider ?: uri.lastPathSegment ?: "document"
        }

        private fun mimeTypes(extensions: Set<String>): Array<String> {
            val values = extensions.map { extension ->
                when (extension.trimStart('.').lowercase()) {
                    "json", "shinsoubackup" -> "application/json"
                    "zip", "cbz" -> "application/zip"
                    "pdf" -> "application/pdf"
                    "jpg", "jpeg", "png", "webp", "gif", "avif" -> "image/*"
                    else -> "*/*"
                }
            }.distinct()
            return values.ifEmpty { listOf("*/*") }.toTypedArray()
        }
    }
}

private data class AndroidBackupScheduleKey(
    val enabled: Boolean,
    val intervalHours: Int,
    val lastBackupAt: Long?,
)
