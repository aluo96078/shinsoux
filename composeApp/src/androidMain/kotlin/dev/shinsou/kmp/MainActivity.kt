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
import androidx.activity.OnBackPressedCallback
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
import dev.shinsou.kmp.tts.AndroidTextToSpeechEngine
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.ImportedDocumentLimits
import dev.shinsou.kmp.ui.ImportedDocumentReadException
import dev.shinsou.kmp.ui.ImportedDocumentSource
import dev.shinsou.kmp.ui.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.BinaryDocumentExportSource
import dev.shinsou.kmp.ui.ByteArrayBinaryDocumentExportSource
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.readBoundedImportedBytes
import dev.shinsou.kmp.ui.requireImportedDocumentSize
import dev.shinsou.kmp.ui.writeBinaryDocumentWithFailureCleanup
import dev.shinsou.kmp.ui.writeCheckedTo
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import java.util.Locale
import java.nio.ByteBuffer
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
            platformTextToSpeechEngine = AndroidTextToSpeechEngine(applicationContext),
            shuYueMigrationSecretStore = AndroidShuYueMigrationSecretStore(applicationContext),
        )
        val syncRuntime = requireNotNull(composition.syncRuntime)
        appServices = AndroidAppServices(
            activity = this,
            documentLauncher = documentLauncher,
            browse = composition.browse,
            content = composition.content,
            contentFeatures = composition.contentFeatures,
            tracking = composition.tracking,
            cloudflareSync = composition.cloudflareSync,
            syncAwareSnapshotRestore = composition.syncAwareSnapshotRestore,
            portableContentBackupV2 = composition.portableContentBackupV2,
            shuYueMigration = composition.shuYueMigration,
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
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (!appServices.dispatchSystemBack()) finish()
                }
            },
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
        private var exportSource: BinaryDocumentExportSource? = null
        private var importContinuation: CancellableContinuation<ImportedDocument?>? = null
        private var importLimits: ImportedDocumentLimits? = null
        private var multipleContinuation: CancellableContinuation<List<ImportedDocument>>? = null
        private var multipleLimits: ImportedDocumentLimits? = null

        private val createDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/json"),
        ) { uri -> completeExport(uri) }

        private val createBinaryDocument = registerForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri -> completeExport(uri) }

        private fun completeExport(uri: Uri?) {
            val continuation = exportContinuation
            val source = exportSource
            exportContinuation = null
            exportSource = null
            if (continuation == null || source == null) {
                // CreateDocument may still return after its coroutine was cancelled. The URI is a
                // newly created provider document, so remove the otherwise orphaned empty file.
                if (uri != null) platformScope.launch(Dispatchers.IO) {
                    runCatching { contentResolver.delete(uri, null, null) }
                }
                return
            }
            platformScope.launch {
                val saved = if (uri == null) false else withContext(Dispatchers.IO) {
                    // Android SAF has no portable atomic-replace contract. Stream to the selected
                    // content URI, then best-effort delete the newly created document if encoding,
                    // declared-length validation, provider writing, or flush fails.
                    writeBinaryDocumentWithFailureCleanup(
                        source = source,
                        write = { streamingSource ->
                            val output = contentResolver.openOutputStream(uri, "wt")
                                ?: error("The document provider did not open the export URI")
                            output.use { stream ->
                                streamingSource.writeCheckedTo(
                                    BinaryDocumentExportSink { chunk -> stream.write(chunk) },
                                )
                                stream.flush()
                            }
                        },
                        discardPartial = { contentResolver.delete(uri, null, null) },
                    )
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
            beginExport(name, ByteArrayBinaryDocumentExportSource(contents), binary = false)

        override suspend fun export(name: String, source: BinaryDocumentExportSource): Boolean =
            beginExport(name, source, binary = true)

        private suspend fun beginExport(
            name: String,
            source: BinaryDocumentExportSource,
            binary: Boolean,
        ): Boolean =
            withContext(Dispatchers.Main.immediate) {
                check(exportContinuation == null) { "Another export is already open" }
                suspendCancellableCoroutine { continuation ->
                    exportContinuation = continuation
                    exportSource = source
                    continuation.invokeOnCancellation {
                        if (exportContinuation === continuation) {
                            exportContinuation = null
                            exportSource = null
                        }
                    }
                    if (binary) createBinaryDocument.launch(name) else createDocument.launch(name)
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
                readDocument(uri, limits, acceptedBytes).also { acceptedBytes += it.byteSize }
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
                if (limits.prefersRandomAccess(name)) {
                    runCatching { descriptor.close() }
                    val checkedSize = requireImportedDocumentSize(
                        name,
                        declaredSize,
                        limits,
                        previouslyAcceptedBytes,
                    )
                    return ImportedDocument(
                        name,
                        AndroidUriImportedDocumentSource(uri, name, checkedSize),
                    )
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

        private inner class AndroidUriImportedDocumentSource(
            private val uri: Uri,
            private val displayName: String,
            override val byteSize: Long,
        ) : ImportedDocumentSource {
            override fun read(offset: Long, byteCount: Int): ByteArray {
                require(offset in 0..byteSize && byteCount >= 0 &&
                    byteCount.toLong() <= byteSize - offset) { "Imported document read is out of bounds" }
                val output = ByteArray(byteCount)
                if (byteCount == 0) return output
                try {
                    val descriptor = contentResolver.openFileDescriptor(uri, "r")
                        ?: throw ImportedDocumentReadException("Unable to open “$displayName”.")
                    val actualSize = descriptor.statSize.takeIf { it >= 0 }
                    if (actualSize != null && actualSize != byteSize) {
                        descriptor.close()
                        throw ImportedDocumentReadException(
                            "“$displayName” changed while it was being imported.",
                        )
                    }
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        val channel = input.channel
                        channel.position(offset)
                        val buffer = ByteBuffer.wrap(output)
                        while (buffer.hasRemaining()) {
                            if (channel.read(buffer) <= 0) {
                                throw ImportedDocumentReadException(
                                    "“$displayName” changed while it was being imported.",
                                )
                            }
                        }
                        val finalSize = descriptor.statSize.takeIf { it >= 0 }
                        if (finalSize != null && finalSize != byteSize) {
                            throw ImportedDocumentReadException(
                                "“$displayName” changed while it was being imported.",
                            )
                        }
                    }
                    return output
                } catch (error: ImportedDocumentReadException) {
                    throw error
                } catch (error: Throwable) {
                    throw ImportedDocumentReadException("Unable to read “$displayName”.", error)
                }
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
