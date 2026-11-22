package dev.shinsou.kmp

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import dev.shinsou.kmp.app.App
import dev.shinsou.kmp.app.ShinsouComposition
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.navigation.DeepLinkParser
import dev.shinsou.kmp.network.installConfiguredImageLoader
import dev.shinsou.kmp.network.createPlatformHttpClient
import dev.shinsou.kmp.plugin.trust.SandboxOnlyScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.browser.AndroidBrowserUserAgentProvider
import dev.shinsou.kmp.plugin.browser.AndroidPluginBrowserSessionTransport
import dev.shinsou.kmp.tts.AndroidTextToSpeechEngine
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.ImportedDocumentLimits
import dev.shinsou.kmp.contract.ImportedDocumentReadException
import dev.shinsou.kmp.contract.ImportedDocumentSource
import dev.shinsou.kmp.ui.settings.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.settings.BinaryDocumentExportSource
import dev.shinsou.kmp.ui.settings.ByteArrayBinaryDocumentExportSource
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyPressTracker
import dev.shinsou.kmp.ui.RetainedDeepLinkQueue
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.contract.readBoundedImportedBytes
import dev.shinsou.kmp.contract.requireImportedDocumentSize
import dev.shinsou.kmp.ui.ShinsouStartupScreen
import dev.shinsou.kmp.ui.screens.SchemaTooNewNotice
import dev.shinsou.kmp.content.schema.SchemaTooNewException
import dev.shinsou.kmp.ui.settings.writeBinaryDocumentWithFailureCleanup
import dev.shinsou.kmp.ui.settings.writeCheckedTo
import dev.shinsou.kmp.ui.i18n.shinsouStringsFor
import java.util.Locale
import java.nio.ByteBuffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** The log tag of the line the release workflow's device check waits for. */
internal const val STARTUP_READY_TAG = "ShinsouReady"

class MainActivity : FragmentActivity() {
    private val platformScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val documentLauncher = ActivityDocumentLauncher()
    private lateinit var composition: ShinsouComposition
    private lateinit var appServices: AndroidAppServices
    private val readerVolumeKeyPressTracker = ReaderVolumeKeyPressTracker()

    /** The process-wide link queue ([Companion.deepLinks]). */
    private val deepLinks get() = Companion.deepLinks

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    // Before the stores are open there is nothing to go back from.
                    if (!::appServices.isInitialized || !appServices.dispatchSystemBack()) finish()
                }
            },
        )
        // A recreated activity (rotation, process death) or one reopened from Recents gets the
        // intent it was first launched with again. A link already followed is not followed again
        // (the saved state reopens where the user went after it); one still waiting (behind the
        // lock, a confirmation) stays queued in this process, or, after process death, is
        // queued again from the links kept aside (PendingDeepLinkFile), onNewIntent's included.
        // This activity's links and kept file are its own, even beside another in the same task;
        // a recreation (same process or after its death) keeps the identity.
        activityId = savedInstanceState?.getString(KEY_ACTIVITY) ?: java.util.UUID.randomUUID().toString()
        val fromHistory = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0
        when {
            savedInstanceState == null -> {
                pendingLinkFile.clear()
                removePendingLinksOfGoneTasks()
                if (!fromHistory) handleIntent(intent)
            }
            // Same process: the queue still has them (even if the stores are still opening).
            savedInstanceState.getString(KEY_PROCESS) == PROCESS_TOKEN -> Unit
            // After process death: only the links that were still waiting.
            else -> {
                val waiting = savedInstanceState.getIntArray(KEY_PENDING_LINKS) ?: IntArray(0)
                val kept = pendingLinkFile.take(waiting)
                if (kept != null) {
                    kept.forEach(::handleLink)
                } else {
                    // The file could not be kept: the launch intent's own link is still known.
                    intent.dataString?.takeIf { it.hashCode() in waiting }?.let(::handleLink)
                }
            }
        }
        // The process-wide core is opened once; a recreated activity reattaches to it at once.
        openedRuntime?.let { runtime ->
            attach(runtime)
            return
        }
        // Opening the stores (snapshot, content database, migrations, the pre-upgrade copy) runs
        // off the main thread; the activity shows a progress screen until it is done.
        setContent { ShinsouStartupScreen() }
        platformScope.launch {
            when (val startup = openAndroidApp(applicationContext)) {
                is AndroidStartup.TooNew -> setContent { SchemaTooNewNotice(startup.error) }
                is AndroidStartup.Ready -> {
                    openedRuntime = startup.runtime
                    attach(startup.runtime)
                }
            }
        }
    }

    /** Stopped while the stores were still opening: [attach] then owes the background work. */
    private var stoppedWhileOpening = false

    /** Binds this activity to the opened [runtime] and shows the app. */
    private fun attach(runtime: AndroidAppRuntime) {
        val repository = runtime.repository
        composition = runtime.composition
        appServices = AndroidAppServices(
            activity = this,
            documentLauncher = documentLauncher,
            pendingDeepLinks = deepLinks,
            ownsLink = ::receivedHere,
            onDeepLinkHandled = ::forgetHandledLink,
            browse = composition.browse,
            content = composition.content,
            contentFeatures = composition.contentFeatures,
            mangaTranslation = composition.mangaTranslation,
            appUpdates = composition.appUpdates,
            tracking = composition.tracking,
            portableContentBackupV2 = composition.portableContentBackupV2,
            sync = composition.sync,
            shuYueMigration = composition.shuYueMigration,
            stringsProvider = runtime::strings,
        )
        setContent {
            val snapshot by repository.snapshot.collectAsState()
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
            )
            LaunchedEffect(Unit) {
                // The release workflow starts the APK on an emulator and waits for this line: the
                // stores are open and the shell has drawn a frame. A fixed text, nothing else.
                withFrameNanos { }
                android.util.Log.i(STARTUP_READY_TAG, "The app shell is shown")
            }
        }
        platformScope.launch { runtime.start() }
        AndroidSyncScheduler.ensureScheduled(applicationContext)
        platformScope.launch {
            // Without notifications the download progress is invisible and the work looks
            // ignorable to the system; ask once per process, when downloads first start.
            composition.backgroundDownloads.status.first { it != null }
            if (!notificationsRequested) {
                notificationsRequested = true
                if (!appServices.notificationsAllowed()) appServices.requestNotificationPermission()
            }
        }
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
        // onStart/onStop skipped the services while the stores were opening: deliver the state
        // the activity is actually in now. Not yet started (created anew, e.g. after rotation)
        // needs nothing; onStart follows.
        when {
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ->
                platformScope.launch { composition.onForeground() }
            stoppedWhileOpening -> {
                appServices.emitBackground()
                backgroundUnlessAnotherStarted()
            }
        }
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
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Some OEMs reset repeatCount while a key is still physically held. Track the
                    // complete DOWN/UP pair so one press can never become multiple page actions.
                    if (readerVolumeKeyPressTracker.shouldDispatchDown(readerEvent, event.repeatCount)) {
                        appServices.emitReaderVolumeKey(readerEvent)
                    }
                }
                KeyEvent.ACTION_UP -> readerVolumeKeyPressTracker.release(readerEvent)
            }
            // Consume both DOWN and UP so Android does not change volume or display its HUD.
            return true
        }
        if (readerEvent != null && event.action != KeyEvent.ACTION_DOWN) {
            readerVolumeKeyPressTracker.release(readerEvent)
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStart() {
        super.onStart()
        startedActivities++
        stoppedWhileOpening = false
        if (::appServices.isInitialized) appServices.emitForeground()
        AndroidBackgroundDownloads.newRun()
        if (::composition.isInitialized) platformScope.launch { composition.onForeground() }
        AndroidBackgroundDownloads.ensureRunning(applicationContext)
    }

    override fun onStop() {
        readerVolumeKeyPressTracker.clear()
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (::appServices.isInitialized) appServices.emitBackground()
        if (::composition.isInitialized) {
            backgroundUnlessAnotherStarted()
        } else {
            stoppedWhileOpening = true
        }
        super.onStop()
    }

    /** This window left the screen: the shared composition follows only when no other is started. */
    private fun backgroundUnlessAnotherStarted() {
        platformScope.launch { if (startedActivities == 0) composition.onBackground() }
    }

    override fun onDestroy() {
        // The user closed it (Back before the link was answered): its links are not followed by
        // a later launch in this process. A recreation keeps them.
        if (isFinishing && !isChangingConfigurations) dropOwnLinks()
        // The core is process-wide (AndroidAppRuntime): closing it here stopped downloads whenever
        // the activity went away. The process ending releases it.
        super.onDestroy()
        platformScope.cancel()
    }

    /** Drops the waiting links this activity received, from the queue and its file. */
    private fun dropOwnLinks() {
        deepLinks.discardWhere(::receivedHere)
        pendingLinkFile.clear()
    }

    private fun handleIntent(intent: Intent?) {
        intent?.dataString?.let(::handleLink)
    }

    private fun handleLink(data: String) {
        val link = DeepLinkParser.parse(data) ?: return
        deepLinks.tryEnqueue(link, LinkDelivery(data, taskId, activityId), ::receivedHere)
    }

    /** Identifies this activity across recreation ([KEY_ACTIVITY]); set first thing in onCreate. */
    private lateinit var activityId: String

    /** A link this activity received: the one that takes it, beside another activity or window. */
    private fun receivedHere(tag: Any?): Boolean = (tag as? LinkDelivery)?.activityId == activityId

    /** A handled link is dropped from its task's kept links, whichever task handled it. */
    private fun forgetHandledLink(tag: Any?) {
        val delivery = tag as? LinkDelivery ?: return
        PendingDeepLinkFile.forActivity(applicationContext.noBackupFilesDir, delivery.taskId, delivery.activityId).forget(delivery.url)
    }

    /** This activity's kept links: another activity keeps (and restores) its own. */
    private val pendingLinkFile get() = PendingDeepLinkFile.forActivity(noBackupFilesDir, taskId, activityId)

    private fun removePendingLinksOfGoneTasks() {
        val live = getSystemService(android.app.ActivityManager::class.java)?.appTasks
            ?.mapNotNullTo(hashSetOf()) { task -> runCatching { task.taskInfo.taskId }.getOrNull() }
            ?: return
        PendingDeepLinkFile.removeAllBut(noBackupFilesDir, live + taskId)
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
                    // Best-effort cleanup of an empty file; the export already reported failure.
                    runCatchingCancellable { contentResolver.delete(uri, null, null) }
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
            // Not every provider offers persistable grants; the one-shot grant is enough to import.
            runCatching {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // A provider without a display name falls back to the URI's last segment.
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
                    runCatching { descriptor.close() } // cleanup only: the original error is rethrown
                    throw error
                }
                if (limits.prefersRandomAccess(name)) {
                    runCatching { descriptor.close() } // a size probe only; closing it cannot lose data
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Which links still waited, as 32-bit hashes: never the URL (it can carry a token). The
        // URLs themselves stay in the app's own storage, for a recreation after process death.
        // Only this activity's own links: another keeps (and restores) the ones it received.
        val waiting = deepLinks.pendingTags.filter(::receivedHere).mapNotNull { (it as? LinkDelivery)?.url }
        outState.putIntArray(KEY_PENDING_LINKS, waiting.map(String::hashCode).toIntArray())
        pendingLinkFile.keep(waiting)
        outState.putString(KEY_PROCESS, PROCESS_TOKEN)
        outState.putString(KEY_ACTIVITY, activityId)
    }

    private companion object {
        private const val KEY_PENDING_LINKS = "shinsou.pendingLinks"
        private const val KEY_PROCESS = "shinsou.process"
        private const val KEY_ACTIVITY = "shinsou.activity"

        /** Tells a recreation in this process from one after process death (a new token). */
        private val PROCESS_TOKEN = java.util.UUID.randomUUID().toString()

        /**
         * Links, kept from the moment they arrive until the shell handles them: per process, so a
         * recreated activity still has the ones its predecessor had not handled, and an intent
         * received while the stores open (a notification tap) cannot erase the launch link.
         */
        val deepLinks = RetainedDeepLinkQueue()

        /**
         * Activities between onStart and onStop, process-wide (main thread only). The shared
         * composition is in the background only while none is: a window that stopped does not
         * close realtime sync under another one the user still sees.
         */
        var startedActivities = 0

        /** Per process: the system itself stops asking after the user declines twice. */
        @Volatile
        var notificationsRequested = false

        /** The process-wide core once this process opened it; never closed while the process lives. */
        @Volatile
        var openedRuntime: AndroidAppRuntime? = null
    }
}

/** The result of opening the app's stores. */
private sealed interface AndroidStartup {
    /** A newer Shinsou X wrote the library: the activity asks for the update instead (R3-1). */
    class TooNew(val error: SchemaTooNewException) : AndroidStartup

    class Ready(val runtime: AndroidAppRuntime) : AndroidStartup
}

/**
 * Opens the process-wide core on [ioDispatcher]: the snapshot, the content database (its schema
 * check, migrations and pre-upgrade copy) and the app composition.
 */
private suspend fun openAndroidApp(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
): AndroidStartup = withContext(ioDispatcher) {
    val tooNew = AndroidSharedState.get(context).schemaTooNew
    if (tooNew != null) {
        AndroidStartup.TooNew(tooNew)
    } else {
        AndroidStartup.Ready(AndroidAppRuntime.get(context).also { it.composition.openBeforeShowing() })
    }
}

private data class AndroidBackupScheduleKey(
    val enabled: Boolean,
    val intervalHours: Int,
    val lastBackupAt: Long?,
)

/** One received link, as its queue tag: its URL and the activity (and its task) that received it ([MainActivity]). */
internal class LinkDelivery(val url: String, val taskId: Int, val activityId: String)
