package dev.shinsou.kmp

import dev.shinsou.kmp.ui.ShellStateStore
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.ui.reader.ContentFeatures
import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.ui.AppLifecycleState
import dev.shinsou.kmp.ui.settings.BinaryDocumentExportSink
import dev.shinsou.kmp.ui.settings.BinaryDocumentExportSource
import dev.shinsou.kmp.plugin.contracts.BrowseCallbacks
import dev.shinsou.kmp.ui.settings.ByteArrayBinaryDocumentExportSource
import dev.shinsou.kmp.contract.ContentCallbacks
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.ImportedDocumentLimits
import dev.shinsou.kmp.contract.ImportedDocumentReadException
import dev.shinsou.kmp.contract.ImportedDocumentSource
import dev.shinsou.kmp.ui.settings.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyEventSink
import dev.shinsou.kmp.ui.reader.readerVolumeKeyShouldBeConsumed
import dev.shinsou.kmp.ui.RetainedDeepLinkQueue
import dev.shinsou.kmp.ui.ShinsouAppServices
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.ui.SystemBackGestureEvent
import dev.shinsou.kmp.ui.settings.mobileSecurityCapabilities
import dev.shinsou.kmp.contract.readBoundedImportedBytes
import dev.shinsou.kmp.contract.requireImportedDocumentSize
import dev.shinsou.kmp.ui.settings.writeCheckedTo
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSCocoaErrorDomain
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileReadNoPermissionError
import platform.Foundation.NSFileManager
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSLock
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSPOSIXErrorDomain
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.closeFile
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForReadingFromURL
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.writeToURL
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.SafariServices.SFSafariViewController
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIPasteboard
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.posix.EACCES
import platform.posix.EPERM
import kotlin.coroutines.resume

/** Notification observed by the Swift trace recorder for one ordered reader-volume event. */
public const val IOS_READER_VOLUME_KEY_TRACE_NOTIFICATION =
    "dev.aluo.shinsoux.reader-volume-keys.trace"

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosAppServices(
    /** Owned by [IosApp], which fills it from launch. */
    private val pendingDeepLinks: RetainedDeepLinkQueue,
    override val browse: BrowseCallbacks = BrowseCallbacks.None,
    override val content: ContentCallbacks = ContentCallbacks.None,
    override val contentFeatures: ContentFeatures? = null,
    override val mangaTranslation: dev.shinsou.kmp.manga.MangaTranslation? = null,
    override val appUpdates: dev.shinsou.kmp.update.AppUpdateChecker? = null,
    override val tracking: TrackingCoordinator? = null,
    override val portableContentBackupV2: PortableContentBackupV2UiController? = null,
    override val sync: SyncV3Service? = null,
    override val shuYueMigration: ShuYueMigrationUiController? = null,
) : ShinsouAppServices {
    private val readerVolumeKeyChannel = Channel<ReaderVolumeKeyEvent>(capacity = Channel.BUFFERED)
    private var readerVolumeKeyEventSink: ReaderVolumeKeyEventSink? = null
    private var readerVolumeKeyTraceSequence: Long = 0L
    private val systemBackChannel = Channel<Unit>(capacity = Channel.BUFFERED)
    // Gesture progress can arrive faster than Compose consumes it. Keep only the latest event so
    // the terminal Settled event replaces stale progress instead of being rejected by a full buffer.
    private val systemBackGestureChannel = Channel<SystemBackGestureEvent>(capacity = Channel.CONFLATED)
    private val lifecycleState = MutableStateFlow(AppLifecycleState.FOREGROUND)
    private val documentPickerMutex = Mutex()
    private var activeDocumentDelegate: IosDocumentPickerDelegate? = null
    private var fallbackSafariController: SFSafariViewController? = null

    init {
        // No import can be using an earlier process's picker copies.
        removePickerCopies()
    }

    override val deepLinks: Flow<ShinsouDeepLink> = pendingDeepLinks.events
    override val readerVolumeKeyEvents: Flow<ReaderVolumeKeyEvent> = readerVolumeKeyChannel.receiveAsFlow()
    override val systemBackEvents: Flow<Unit> = systemBackChannel.receiveAsFlow()
    override val systemBackGestureEvents: Flow<SystemBackGestureEvent> = systemBackGestureChannel.receiveAsFlow()
    override val appLifecycle: StateFlow<AppLifecycleState> = lifecycleState
    override val shellStateStore: ShellStateStore = IosShellStateStore
    override val securityCapabilities: PlatformSecurityCapabilities
        get() = mobileSecurityCapabilities(deviceOwnerAuthenticationAvailable())

    override fun deepLinkHandled(link: ShinsouDeepLink) {
        pendingDeepLinks.handled(link)
    }

    fun emitReaderVolumeKey(event: ReaderVolumeKeyEvent): Boolean {
        val readerOpen = IosReaderPresentationState.readerOpen
        val volumeKeysEnabled = IosApplicationContainer.repository.currentSnapshot.settings.reader.volumeKeys
        val monitoringEnabled = IosReaderVolumeKeyState.monitoringEnabled
        // While a reader is open and volume-key paging is configured, consume the hardware event
        // even if the current page cannot move. Returning false lets iOS apply a real volume change.
        if (
            !readerVolumeKeyShouldBeConsumed(
                readerOpen = readerOpen,
                volumeKeysEnabled = volumeKeysEnabled,
                monitoringEnabled = monitoringEnabled,
            )
        ) {
            traceReaderVolumeKey(
                event = "input",
                key = event,
                route = "gate_rejected",
                accepted = false,
                readerOpen = readerOpen,
                volumeKeysEnabled = volumeKeysEnabled,
                monitoringEnabled = monitoringEnabled,
            )
            return false
        }
        val sink = readerVolumeKeyEventSink
        if (sink != null) {
            val accepted = sink.dispatch(event)
            traceReaderVolumeKey(
                event = "input",
                key = event,
                route = "sink",
                accepted = accepted,
                readerOpen = readerOpen,
                volumeKeysEnabled = volumeKeysEnabled,
                monitoringEnabled = monitoringEnabled,
            )
            return accepted
        } else {
            val queued = readerVolumeKeyChannel.trySend(event).isSuccess
            traceReaderVolumeKey(
                event = "input",
                key = event,
                route = "channel",
                accepted = queued,
                readerOpen = readerOpen,
                volumeKeysEnabled = volumeKeysEnabled,
                monitoringEnabled = monitoringEnabled,
            )
            return queued
        }
    }

    /**
     * Emits a compact, non-sensitive line as the notification object. Swift should observe
     * [IOS_READER_VOLUME_KEY_TRACE_NOTIFICATION] and append `notification.object as String` to
     * its durable trace file. Every line has a monotonically increasing `seq` and fixed fields;
     * no manga/chapter/content identifiers cross this diagnostic boundary.
     */
    private fun traceReaderVolumeKey(
        event: String,
        key: ReaderVolumeKeyEvent? = null,
        route: String? = null,
        accepted: Boolean? = null,
        readerOpen: Boolean = IosReaderPresentationState.readerOpen,
        volumeKeysEnabled: Boolean = IosApplicationContainer.repository.currentSnapshot.settings.reader.volumeKeys,
        monitoringEnabled: Boolean = IosReaderVolumeKeyState.monitoringEnabled,
    ) {
        val sequence = ++readerVolumeKeyTraceSequence
        val payload = buildString {
            append("seq=").append(sequence)
            append(" event=").append(event)
            key?.let { append(" key=").append(it.name) }
            route?.let { append(" route=").append(it) }
            accepted?.let { append(" accepted=").append(if (it) 1 else 0) }
            append(" readerOpen=").append(if (readerOpen) 1 else 0)
            append(" configured=").append(if (volumeKeysEnabled) 1 else 0)
            append(" monitoring=").append(if (monitoringEnabled) 1 else 0)
            append(" infrastructure=").append(if (IosReaderVolumeKeyState.infrastructureEnabled) 1 else 0)
            append(" sink=").append(if (readerVolumeKeyEventSink != null) 1 else 0)
        }
        NSNotificationCenter.defaultCenter.postNotificationName(
            IOS_READER_VOLUME_KEY_TRACE_NOTIFICATION,
            `object` = payload,
        )
    }

    fun emitSystemBack(): Boolean = systemBackChannel.trySend(Unit).isSuccess

    fun emitSystemBackGestureProgress(fraction: Float): Boolean =
        systemBackGestureChannel.trySend(
            SystemBackGestureEvent.Progress(fraction.coerceIn(0f, 1f)),
        ).isSuccess

    fun emitSystemBackGestureSettled(committed: Boolean): Boolean =
        systemBackGestureChannel.trySend(SystemBackGestureEvent.Settled(committed)).isSuccess

    fun setApplicationForeground(foreground: Boolean) {
        lifecycleState.value = if (foreground) {
            AppLifecycleState.FOREGROUND
        } else {
            AppLifecycleState.BACKGROUND
        }
    }

    override fun openExternalUrl(url: String) {
        tryOpenExternalUrl(url)
    }

    override fun tryOpenExternalUrl(url: String): Boolean {
        val target = NSURL.URLWithString(url) ?: return false
        // Compose actions normally arrive on the main thread, but provisioning actions run in a
        // coroutine. Use the scene-safe iOS 10+ API on the main queue; the legacy `openURL:`
        // selector is deprecated on iOS 26 and can be ignored by a scene-based application.
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.openURL(
                url = target,
                options = emptyMap<Any?, Any?>(),
                completionHandler = { opened ->
                    // A device can have URL opening restricted (parental controls, an
                    // enterprise browser policy, or a transient scene transition). Keep the
                    // deployment flow usable by presenting the page in SafariServices when the
                    // external hand-off is rejected instead of silently doing nothing.
                    if (!opened) {
                        topViewController()?.let { presenter ->
                            val safari = SFSafariViewController(target)
                            fallbackSafariController = safari
                            presenter.presentViewController(safari, animated = true, completion = null)
                        }
                    }
                },
            )
        }
        return true
    }

    override fun shareText(title: String, text: String) {
        dispatch_async(dispatch_get_main_queue()) {
            val presenter = topViewController() ?: return@dispatch_async
            val controller = UIActivityViewController(
                activityItems = listOf(text),
                applicationActivities = null,
            )
            controller.popoverPresentationController?.sourceView = presenter.view
            presenter.presentViewController(controller, animated = true, completion = null)
        }
    }

    override fun copyText(label: String, text: String): Boolean {
        UIPasteboard.generalPasteboard.string = text
        return true
    }

    override suspend fun readClipboardText(): String? = UIPasteboard.generalPasteboard.string

    override suspend fun exportDocument(suggestedName: String, contents: String): Boolean {
        val fileName = safeFileName(suggestedName)
        val temporaryUrl = NSURL.fileURLWithPath(NSTemporaryDirectory() + fileName)
        val data = NSString.create(string = contents).dataUsingEncoding(NSUTF8StringEncoding) ?: return false
        if (!data.writeToURL(temporaryUrl, atomically = true)) return false

        return try {
            pickDocumentUrls(exportUrl = temporaryUrl, allowsMultipleSelection = false).isNotEmpty()
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(temporaryUrl.path.orEmpty(), error = null)
        }
    }

    /**
     * Into Photos with add-only access (the "add photos" prompt, NSPhotoLibraryAddUsageDescription).
     * Without that key in the running bundle the prompt would crash, so it falls back to Files.
     */
    override suspend fun saveImage(suggestedName: String, contents: ByteArray): Boolean {
        if (platform.Foundation.NSBundle.mainBundle.objectForInfoDictionaryKey("NSPhotoLibraryAddUsageDescription") == null) {
            return exportBinaryDocument(suggestedName, contents)
        }
        val status = suspendCancellableCoroutine { continuation ->
            platform.Photos.PHPhotoLibrary.requestAuthorizationForAccessLevel(platform.Photos.PHAccessLevelAddOnly) { value ->
                continuation.resume(value) {}
            }
        }
        if (status != platform.Photos.PHAuthorizationStatusAuthorized &&
            status != platform.Photos.PHAuthorizationStatusLimited
        ) {
            return false
        }
        val data = contents.usePinned { pinned ->
            platform.Foundation.NSData.create(bytes = pinned.addressOf(0), length = contents.size.toULong())
        }
        return suspendCancellableCoroutine { continuation ->
            platform.Photos.PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                {
                    val options = platform.Photos.PHAssetResourceCreationOptions().apply {
                        originalFilename = suggestedName
                    }
                    platform.Photos.PHAssetCreationRequest.creationRequestForAsset()
                        .addResourceWithType(platform.Photos.PHAssetResourceTypePhoto, data, options)
                },
            ) { success, _ -> continuation.resume(success) {} }
        }
    }

    override suspend fun exportBinaryDocument(suggestedName: String, contents: ByteArray): Boolean {
        return exportBinaryDocument(
            suggestedName,
            ByteArrayBinaryDocumentExportSource(contents),
        )
    }

    override suspend fun exportBinaryDocument(
        suggestedName: String,
        source: BinaryDocumentExportSource,
    ): Boolean {
        val fileName = safeFileName(suggestedName)
        val temporaryUrl = NSURL.fileURLWithPath(
            NSTemporaryDirectory() + "shinsou-export-${NSUUID().UUIDString}-$fileName",
        )
        val temporaryPath = temporaryUrl.path?.toPath() ?: return false
        val written = withContext(Dispatchers.Default) {
            runCatchingCancellable {
                val output = FileSystem.SYSTEM.sink(temporaryPath).buffer()
                try {
                    source.writeCheckedTo(BinaryDocumentExportSink { chunk -> output.write(chunk) })
                    output.flush()
                } finally {
                    output.close()
                }
            }.onFailure { Log.warn("Platform", it) { "Writing a binary document for export failed" } }.isSuccess
        }
        if (!written) {
            NSFileManager.defaultManager.removeItemAtPath(temporaryUrl.path.orEmpty(), error = null)
            return false
        }

        return try {
            pickDocumentUrls(exportUrl = temporaryUrl, allowsMultipleSelection = false).isNotEmpty()
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(temporaryUrl.path.orEmpty(), error = null)
        }
    }

    override suspend fun importDocument(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): ImportedDocument? {
        val picked = pickImportedDocumentUrls(allowsMultipleSelection = false)
        val selected = picked.acceptedOrDiscarded(acceptedExtensions).firstOrNull() ?: return null
        var document: ImportedDocument? = null
        var delivered = false
        try {
            withContext(Dispatchers.Default) { document = selected.readImportedDocument(limits) }
            delivered = true
            return document
        } finally {
            // Cancelled on the way back, the caller never gets the document to close (and delete its copy).
            if (!delivered) document?.close() ?: selected.discardPickerCopy()
        }
    }

    override suspend fun pickLocalFiles(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): List<ImportedDocument> {
        val selected = pickImportedDocumentUrls(allowsMultipleSelection = true).acceptedOrDiscarded(acceptedExtensions)
        val documents = mutableListOf<ImportedDocument>()
        var delivered = false
        try {
            withContext(Dispatchers.Default) {
                var acceptedBytes = 0L
                selected.forEach { url ->
                    documents += url.readImportedDocument(limits, acceptedBytes)
                    acceptedBytes += documents.last().byteSize
                }
            }
            delivered = true
            return documents
        } finally {
            if (!delivered) {
                // The import never sees these (a failure, or cancellation on the way back), so it
                // cannot close them and delete their copies.
                documents.forEach(ImportedDocument::close)
                selected.drop(documents.size).forEach { it.discardPickerCopy() }
            }
        }
    }

    override fun setKeepScreenOn(enabled: Boolean) {
        dispatch_async(dispatch_get_main_queue()) {
            UIApplication.sharedApplication.idleTimerDisabled = enabled
        }
    }

    override fun setSecureScreen(enabled: Boolean) {
        if (IosSecurityState.enabled == enabled) return
        IosSecurityState.enabled = enabled
        NSNotificationCenter.defaultCenter.postNotificationName(
            IosSecurityState.DID_CHANGE_NOTIFICATION,
            `object` = null,
        )
    }

    override fun setFullscreen(enabled: Boolean) {
        if (IosReaderPresentationState.fullscreen == enabled) return
        IosReaderPresentationState.fullscreen = enabled
        IosReaderPresentationState.notifyChanged()
    }

    override fun setReaderOrientation(orientation: ReaderOrientation?) {
        if (IosReaderPresentationState.orientation == orientation) return
        IosReaderPresentationState.orientation = orientation
        IosReaderPresentationState.notifyChanged()
    }

    override fun setReaderOpen(open: Boolean) {
        if (IosReaderPresentationState.readerOpen != open) {
            IosReaderPresentationState.readerOpen = open
            IosReaderPresentationState.notifyChanged()
        }
        traceReaderVolumeKey(event = "reader_open", readerOpen = open)
    }

    override fun setReaderVolumeKeyInfrastructureEnabled(enabled: Boolean) {
        IosReaderVolumeKeyState.setInfrastructureEnabled(enabled)
        traceReaderVolumeKey(event = "infrastructure", volumeKeysEnabled = enabled)
    }

    override fun setReaderVolumeKeyMonitoringEnabled(enabled: Boolean) {
        IosReaderVolumeKeyState.setMonitoringEnabled(enabled)
        traceReaderVolumeKey(event = "monitoring", monitoringEnabled = enabled)
    }

    override fun setReaderVolumeKeyEventSink(sink: ReaderVolumeKeyEventSink?) {
        readerVolumeKeyEventSink = sink
        traceReaderVolumeKey(event = "sink", accepted = sink != null)
    }

    override fun requestNotificationPermission() {
        val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge or UNAuthorizationOptionSound
        UNUserNotificationCenter.currentNotificationCenter()
            .requestAuthorizationWithOptions(options) { _, _ -> }
    }

    override suspend fun authenticate(reason: String): Boolean {
        val context = LAContext()
        if (!deviceOwnerAuthenticationAvailable(context)) return false

        return suspendCancellableCoroutine { continuation ->
            context.evaluatePolicy(
                policy = LAPolicyDeviceOwnerAuthentication,
                localizedReason = reason,
            ) { success, _ ->
                if (continuation.isActive) continuation.resume(success)
            }
            continuation.invokeOnCancellation { context.invalidate() }
        }
    }

    private fun deviceOwnerAuthenticationAvailable(context: LAContext = LAContext()): Boolean = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        context.canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr)
    }

    /** iOS applications must not terminate themselves; the system owns process lifecycle. */
    override fun requestApplicationClose() = Unit

    /**
     * Picks documents to import. They are opened where they are stored when the app can do that,
     * so their sizes are checked before anything is copied; a host that does not give the app
     * that access (LiveContainer) gets copies instead ([IosDocumentPickerMode]). Whether a picked
     * original can be opened is not asked here: a file the provider has not downloaded is not on
     * disk until its coordinated read, so only that read can tell ([readImportedDocument]).
     */
    private suspend fun pickImportedDocumentUrls(allowsMultipleSelection: Boolean): List<NSURL> =
        pickDocumentUrls(allowsMultipleSelection = allowsMultipleSelection, asCopy = IosDocumentPickerMode.copies)

    private suspend fun pickDocumentUrls(
        exportUrl: NSURL? = null,
        allowsMultipleSelection: Boolean,
        asCopy: Boolean = true,
    ): List<NSURL> {
        // What the picker returned, kept for cleanup until it is handed to the caller.
        var picked: List<NSURL> = emptyList()
        var delivered = false
        try {
            presentDocumentPicker(exportUrl, allowsMultipleSelection, asCopy) { picked = it }
            delivered = true
            return picked
        } finally {
            // Cancelled on the way back from the main thread: nobody gets the copies to delete.
            if (!delivered) picked.forEach { it.discardPickerCopy() }
        }
    }

    private suspend fun presentDocumentPicker(
        exportUrl: NSURL?,
        allowsMultipleSelection: Boolean,
        asCopy: Boolean,
        onPicked: (List<NSURL>) -> Unit,
    ): Unit = documentPickerMutex.withLock {
        withContext(Dispatchers.Main) {
            val presenter = topViewController() ?: return@withContext
            val result = CompletableDeferred<List<NSURL>>()
            val delegate = IosDocumentPickerDelegate(result)
            val picker = if (exportUrl != null) {
                UIDocumentPickerViewController(forExportingURLs = listOf(exportUrl))
            } else {
                // A copy is a plain file in this app's temporary directory, made before the app can
                // check its size. Opening the original in place needs security-scoped access that
                // hosts such as LiveContainer do not give the app (the picker never returned).
                UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData), asCopy = asCopy)
            }
            picker.allowsMultipleSelection = exportUrl == null && allowsMultipleSelection
            picker.delegate = delegate
            activeDocumentDelegate = delegate
            presenter.presentViewController(picker, animated = true, completion = null)
            try {
                onPicked(result.await())
            } finally {
                activeDocumentDelegate = null
            }
        }
    }
}

internal object IosSecurityState {
    const val DID_CHANGE_NOTIFICATION = "dev.aluo.shinsoux.secure-screen.changed"
    var enabled: Boolean = false
}

internal object IosReaderPresentationState {
    const val DID_CHANGE_NOTIFICATION = "dev.aluo.shinsoux.reader-presentation.changed"
    var orientation: ReaderOrientation? = null
    var fullscreen: Boolean = false
    var readerOpen: Boolean = false

    fun notifyChanged() {
        NSNotificationCenter.defaultCenter.postNotificationName(
            DID_CHANGE_NOTIFICATION,
            `object` = null,
        )
    }
}

internal object IosReaderVolumeKeyState {
    const val DID_CHANGE_NOTIFICATION = "dev.aluo.shinsoux.reader-volume-keys.changed"
    private const val INFRASTRUCTURE_PREFERENCE = "dev.aluo.shinsoux.reader-volume-keys.enabled"

    var infrastructureEnabled: Boolean = NSUserDefaults.standardUserDefaults
        .boolForKey(INFRASTRUCTURE_PREFERENCE)
        private set
    var monitoringEnabled: Boolean = false
        private set

    fun setInfrastructureEnabled(value: Boolean) {
        infrastructureEnabled = value
        NSUserDefaults.standardUserDefaults.setBool(value, forKey = INFRASTRUCTURE_PREFERENCE)
        // This is also a synchronization pulse. SwiftUI may have missed the first notification
        // while its root view and Compose controller were being installed, so an equal persisted
        // value must still be published on every composition start.
        notifyChanged()
    }

    fun setMonitoringEnabled(value: Boolean) {
        monitoringEnabled = value
        notifyChanged()
    }

    private fun notifyChanged() {
        dispatch_async(dispatch_get_main_queue()) {
            NSNotificationCenter.defaultCenter.postNotificationName(
                DID_CHANGE_NOTIFICATION,
                `object` = null,
            )
        }
    }
}

@OptIn(BetaInteropApi::class)
private class IosDocumentPickerDelegate(
    private val result: CompletableDeferred<List<NSURL>>,
) : NSObject(), UIDocumentPickerDelegateProtocol {
    override fun documentPicker(
        controller: UIDocumentPickerViewController,
        didPickDocumentsAtURLs: List<*>,
    ) {
        result.complete(didPickDocumentsAtURLs.filterIsInstance<NSURL>())
    }

    override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
        result.complete(emptyList())
    }
}

/** One picked document to read: its display name and the limits it is checked against. */
private class ImportedDocumentRead(
    val name: String,
    val limits: ImportedDocumentLimits,
    val previouslyAcceptedBytes: Long,
)

/**
 * Reads a picked document. The picker's copy is this app's own file. An original opened in place
 * is the file provider's: it is read inside a coordinated read ([coordinatedRead]), so the
 * provider and other writers do not change it meanwhile. An original the sandbox does not let
 * this app open makes the picker hand back copies from then on ([importFailure]). Blocks, so it
 * runs off the main thread.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.readImportedDocument(
    limits: ImportedDocumentLimits,
    previouslyAcceptedBytes: Long = 0,
): ImportedDocument {
    val granted = startAccessingSecurityScopedResource()
    val read = ImportedDocumentRead(lastPathComponent ?: "document", limits, previouslyAcceptedBytes)
    val inPlace = !isPickerCopy()
    return try {
        if (inPlace) {
            // Refused here when the size is known already: coordinating the read can make the
            // provider download the file first. It is checked again on the coordinated file.
            declaredFileSize()?.let { requireImportedDocumentSize(read.name, it, limits, previouslyAcceptedBytes) }
            coordinatedRead(read.name) { coordinated -> coordinated.readCheckedDocument(read, original = true) }
        } else {
            readCheckedDocument(read, original = false)
        }
    } catch (error: Throwable) {
        discardPickerCopy()
        throw importFailure(error, read.name, inPlace)
    } finally {
        if (granted) stopAccessingSecurityScopedResource()
    }
}

/**
 * What the import reports for [error], which reading the document named [name] threw. When the
 * sandbox refused an original opened [inPlace] ([OpenInPlaceDeniedException]), this host does not
 * let the app open picked files where they are stored: the picker copies from now on
 * ([IosDocumentPickerMode.rememberCopies]) and the user is told to choose the file again. That is
 * decided here, on the failure of the coordinated read itself, and for nothing else: a file that
 * is missing, one its provider could not download, a network failure or a cancellation says
 * nothing about the host, and remembering copies for one would give up the size check before
 * copying for every later import.
 */
private fun importFailure(error: Throwable, name: String, inPlace: Boolean): ImportedDocumentReadException = when {
    error is OpenInPlaceDeniedException && inPlace -> {
        IosDocumentPickerMode.rememberCopies()
        ImportedDocumentReadException(
            "“$name” could not be opened where it is stored. Choose it again; it will be copied into the app first.",
            error,
        )
    }
    error is ImportedDocumentReadException -> error
    // A picker copy the app could not open: its own file, so nothing about the host.
    error is OpenInPlaceDeniedException -> ImportedDocumentReadException(error.message ?: "Unable to open “$name”.", error)
    else -> ImportedDocumentReadException("Unable to read “$name”.", error)
}

/**
 * Checks this file's size against the limits, then reads it: a small document into memory, a
 * random-access one (an EPUB or archive) through a file this app owns. For an [original] that
 * file is a snapshot made here, inside the coordinated read and only after the size check, so a
 * file over the limits is never copied; the import then reads the snapshot, which nothing else
 * changes, instead of the provider's file. A picker copy is that file already.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.readCheckedDocument(read: ImportedDocumentRead, original: Boolean): ImportedDocument {
    val (declaredSize, sizeError) = fileSizeOrError()
    if (!read.limits.prefersRandomAccess(read.name)) return readIntoMemory(read, declaredSize)
    // A size the sandbox would not give is its refusal of the file, not a file of unknown size;
    // nothing after the size check would get to open the file and find that out.
    if (declaredSize == null) openInPlaceDenial(sizeError, "Unable to open “${read.name}”.")?.let { throw it }
    val checkedSize = requireImportedDocumentSize(read.name, declaredSize, read.limits, read.previouslyAcceptedBytes)
    val owned = if (original) copyToImportSnapshot(read.name, checkedSize) else this
    return ImportedDocument(read.name, IosUrlImportedDocumentSource(owned, read.name, checkedSize))
}

/** Reads this whole file, bounded by the limits, and deletes it when it is a picker copy. */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.readIntoMemory(read: ImportedDocumentRead, declaredSize: Long?): ImportedDocument {
    val (opened, openError) = capturingNSError { error -> NSFileHandle.fileHandleForReadingFromURL(this, error = error) }
    val handle = opened ?: throw openFailure(openError, "Unable to open “${read.name}”.")
    return try {
        ImportedDocument(
            name = read.name,
            contents = readBoundedImportedBytes(
                name = read.name,
                declaredSize = declaredSize,
                limits = read.limits,
                previouslyAcceptedBytes = read.previouslyAcceptedBytes,
            ) { destination, offset, length ->
                handle.readDataOfLength(length.toULong()).copyInto(destination, offset, length)
            },
        )
    } finally {
        runCatching { handle.closeFile() } // closing a read-only handle; nothing to lose
        // The bytes are in memory now; the picker's copy is no longer needed.
        discardPickerCopy()
    }
}

/**
 * Runs [read] on this provider-owned file inside a coordinated read (NSFileCoordinator): the file
 * provider makes the file current (downloading it when needed), presenters with unsaved changes
 * write them first, and writers wait until [read] returns. A security-scoped grant only permits
 * access; it coordinates nothing. [read] gets the URL to use for the duration of the read. Blocks
 * the calling thread, so it runs off the main thread.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun <T : Any> NSURL.coordinatedRead(name: String, read: (NSURL) -> T): T {
    var outcome: Result<T>? = null
    val original = this
    val (_, coordinationError) = capturingNSError { error ->
        NSFileCoordinator(filePresenter = null).coordinateReadingItemAtURL(
            url = original,
            options = 0u,
            error = error,
        ) { coordinated ->
            // Kept, not thrown: a Kotlin exception must not unwind through the coordinator's frames.
            outcome = runCatching { read(coordinated ?: original) }
        }
    }
    // The accessor does not run when the coordinator could not grant the read.
    return (outcome ?: Result.failure(openFailure(coordinationError, "Unable to open “$name”."))).getOrThrow()
}

/** Runs [call] with somewhere to put an NSError; gives back what it returned and the error it left, if any. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private inline fun <T> capturingNSError(call: (CPointer<ObjCObjectVar<NSError?>>) -> T): Pair<T, NSError?> = memScoped {
    val error = alloc<ObjCObjectVar<NSError?>>()
    call(error.ptr) to error.value
}

/**
 * An original picked to be opened in place that the sandbox did not let this app open. Its
 * message is that of the ordinary failure, which is what a picker copy failing this way reports.
 */
private class OpenInPlaceDeniedException(message: String) : Exception(message)

/** The failure, with [message], of opening or copying a picked file that left [error]. */
private fun openFailure(error: NSError?, message: String): Exception =
    openInPlaceDenial(error, message) ?: ImportedDocumentReadException(message)

/** [error] as the sandbox's refusal of a picked file, with [message]; null when it is not one. */
private fun openInPlaceDenial(error: NSError?, message: String): OpenInPlaceDeniedException? =
    error?.takeIf { isOpenInPlaceDenial(it.domain, it.code) }?.let { OpenInPlaceDeniedException(message) }

/**
 * Whether an NSError of this [domain] and [code] says the system refused this app permission to
 * read a file, as the sandbox does for a picked original in a host that does not give the app its
 * security-scoped access: Foundation reports NSFileReadNoPermissionError (257), and a call that
 * surfaces errno directly EPERM or EACCES. Only the error's own domain and code count, not one it
 * wraps: "no such file" (260, ENOENT), a file provider's download, network or sign-in failures
 * (NSFileProviderErrorDomain, NSURLErrorDomain), and a cancelled read (3072) have other codes and
 * are ordinary import failures, whatever lies beneath them.
 */
internal fun isOpenInPlaceDenial(domain: String?, code: Long): Boolean = when (domain) {
    NSCocoaErrorDomain -> code == NSFileReadNoPermissionError
    NSPOSIXErrorDomain -> code == EPERM.toLong() || code == EACCES.toLong()
    else -> false
}

/**
 * Copies this file, whose size was checked as [expectedSize], into the app's import snapshots.
 * The caller holds a coordinated read, so the copy is of one consistent state of the file.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.copyToImportSnapshot(name: String, expectedSize: Long): NSURL {
    val manager = NSFileManager.defaultManager
    val directory = "${NSTemporaryDirectory().trimEnd('/')}/$IMPORT_SNAPSHOT_DIRECTORY"
    manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
    val snapshot = NSURL.fileURLWithPath("$directory/${NSUUID().UUIDString}")
    val (copied, copyError) = capturingNSError { error -> manager.copyItemAtURL(this, toURL = snapshot, error = error) }
    if (!copied || snapshot.declaredFileSize() != expectedSize) {
        manager.removeItemAtURL(snapshot, error = null)
        throw if (copied) {
            ImportedDocumentReadException("“$name” changed while it was being imported.")
        } else {
            // Reading the original is what the sandbox can refuse; a snapshot that cannot be
            // written reports a write error (513), which is not a denial of the original.
            openFailure(copyError, "Unable to copy “$name” for import.")
        }
    }
    return snapshot
}

/** In the app's temporary directory: the snapshots of originals an import reads ([copyToImportSnapshot]). */
private const val IMPORT_SNAPSHOT_DIRECTORY = "shinsou-import-snapshots"

/**
 * Whether the document picker hands back copies instead of the originals. Copies work in every
 * host but are made before their size can be checked, so they are used only where opening in
 * place does not work: an app run by another app (LiveContainer runs it from its own data
 * container), or after the sandbox refused the coordinated read of a picked original once
 * ([isOpenInPlaceDenial]). A file that merely is not there yet does not count: an iCloud Drive or
 * other provider's item that is not downloaded appears on disk only inside its coordinated read.
 */
internal object IosDocumentPickerMode {
    private const val COPIES_PREFERENCE = "dev.aluo.shinsoux.document-picker.copies"

    val copies: Boolean
        get() = hosted || NSUserDefaults.standardUserDefaults.boolForKey(COPIES_PREFERENCE)

    fun rememberCopies() {
        NSUserDefaults.standardUserDefaults.setBool(true, forKey = COPIES_PREFERENCE)
    }

    /**
     * An installed app runs from its own bundle container with its own data container as home;
     * anything else is a host running it (or a layout this check does not know, which copies).
     */
    private val hosted: Boolean by lazy {
        val bundle = NSBundle.mainBundle.bundlePath
        val home = NSHomeDirectory()
        !(INSTALLED_BUNDLE.containsMatchIn(bundle) && INSTALLED_HOME.containsMatchIn(home))
    }

    private val INSTALLED_BUNDLE = Regex("/Containers/Bundle/Application/[0-9A-Fa-f-]{36}/[^/]+\\.app/?$")
    private val INSTALLED_HOME = Regex("/Containers/Data/Application/[0-9A-Fa-f-]{36}/?$")
}

/** Keeps the accepted picker results and deletes the copies of the rest. */
private fun List<NSURL>.acceptedOrDiscarded(acceptedExtensions: Set<String>): List<NSURL> =
    partition { it.hasAcceptedExtension(acceptedExtensions) }
        .also { (_, rejected) -> rejected.forEach { it.discardPickerCopy() } }
        .first

/** Deletes this file if it is a picker copy this app owns; originals are never touched. */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.discardPickerCopy() {
    if (isPickerCopy()) NSFileManager.defaultManager.removeItemAtURL(this, error = null)
}

/**
 * Whether this file is a copy this app owns and deletes: the document picker copies picked files
 * into `tmp/<bundle id>-Inbox`, and an import snapshots an original into
 * `tmp/`[IMPORT_SNAPSHOT_DIRECTORY].
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.isPickerCopy(): Boolean {
    val path = standardizedURL?.path ?: return false
    return pickerCopyDirectories().any { path.startsWith("$it/") }
}

@OptIn(ExperimentalForeignApi::class)
private fun pickerCopyDirectories(): List<String> {
    val temporary = NSURL.fileURLWithPath(NSTemporaryDirectory()).standardizedURL?.path ?: return emptyList()
    val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(temporary, error = null).orEmpty()
    return names.filterIsInstance<String>()
        .filter { it.endsWith("-Inbox") || it == IMPORT_SNAPSHOT_DIRECTORY }
        .map { "$temporary/$it" }
}

/**
 * Removes every picker copy and import snapshot, for those a crash or a caller that never closed
 * its document left behind. Only safe when no import can be running, so it runs at launch.
 */
@OptIn(ExperimentalForeignApi::class)
private fun removePickerCopies() {
    pickerCopyDirectories().forEach { NSFileManager.defaultManager.removeItemAtPath(it, error = null) }
}

@OptIn(ExperimentalForeignApi::class)
private class IosUrlImportedDocumentSource(
    private val url: NSURL,
    private val displayName: String,
    override val byteSize: Long,
) : ImportedDocumentSource {
    private val lock = NSLock()

    /**
     * One handle for the whole import. Reopening the file (and re-granting its security scope) for
     * every chunk made a large EPUB cost thousands of opens. [close] releases it after the import;
     * the archive digest check after import still catches a file changed underneath.
     */
    private var handle: NSFileHandle? = null
    private var granted = false

    override fun read(offset: Long, byteCount: Int): ByteArray {
        require(offset in 0..byteSize && byteCount >= 0 &&
            byteCount.toLong() <= byteSize - offset) { "Imported document read is out of bounds" }
        val output = ByteArray(byteCount)
        if (byteCount == 0) return output
        lock.lock()
        try {
            val reader = openHandle()
            reader.seekToFileOffset(offset.toULong())
            val actual = reader.readDataOfLength(byteCount.toULong())
                .copyInto(output, offset = 0, requested = byteCount)
            // The held file's own length, not its path's: data appended to a file opened in
            // place stays within byteSize for every read, so the digest would never see it.
            if (actual != byteCount || reader.seekToEndOfFile() != byteSize.toULong()) {
                throw ImportedDocumentReadException("“$displayName” changed while it was being imported.")
            }
            return output
        } catch (error: ImportedDocumentReadException) {
            throw error
        } catch (error: Throwable) {
            throw ImportedDocumentReadException("Unable to read “$displayName”.", error)
        } finally {
            lock.unlock()
        }
    }

    override fun close() {
        lock.lock()
        try {
            handle?.let { runCatching { it.closeFile() } } // closing a read-only handle; nothing to lose
            handle = null
            if (granted) url.stopAccessingSecurityScopedResource()
            granted = false
            // The picker's copy or an import snapshot: this app's file, not read again.
            url.discardPickerCopy()
        } finally {
            lock.unlock()
        }
    }

    private fun openHandle(): NSFileHandle {
        handle?.let { return it }
        if (!granted) granted = url.startAccessingSecurityScopedResource()
        if (url.declaredFileSize() != byteSize) {
            throw ImportedDocumentReadException("“$displayName” changed while it was being imported.")
        }
        return (NSFileHandle.fileHandleForReadingFromURL(url, error = null)
            ?: throw ImportedDocumentReadException("Unable to open “$displayName”."))
            .also { handle = it }
    }
}

private fun NSURL.declaredFileSize(): Long? = fileSizeOrError().first

/**
 * This file's size, or null with the error that kept it from being read, if there was one.
 * Measured now: an NSURL caches its resource values, and a size cached before a coordinated read
 * can be stale inside it (the provider brought the file up to date, or it grew), which would let
 * a file over the limits be copied whole before the copy is found to differ.
 */
@OptIn(ExperimentalForeignApi::class)
private fun NSURL.fileSizeOrError(): Pair<Long?, NSError?> {
    val key = NSURLFileSizeKey ?: return null to null
    removeCachedResourceValueForKey(key)
    val (values, error) = capturingNSError { error -> resourceValuesForKeys(listOf(key), error = error) }
    return (values?.get(key) as? NSNumber)?.longLongValue to error
}

private fun NSURL.hasAcceptedExtension(acceptedExtensions: Set<String>): Boolean {
    if (acceptedExtensions.isEmpty()) return true
    val accepted = acceptedExtensions.mapTo(mutableSetOf()) { it.trimStart('.').lowercase() }
    return pathExtension?.lowercase() in accepted
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.copyInto(destination: ByteArray, offset: Int, requested: Int): Int {
    val size = length.toInt()
    if (size <= 0) return 0
    if (size > requested) return size
    val chunk = bytes?.reinterpret<ByteVar>()?.readBytes(size)
        ?: throw ImportedDocumentReadException("The document provider returned unreadable data.")
    chunk.copyInto(destination, destinationOffset = offset)
    return size
}

private fun safeFileName(value: String): String {
    val leaf = value.substringAfterLast('/').substringAfterLast('\\').trim()
    return leaf.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "shinsou-export.json" }
}

private fun topViewController(): UIViewController? {
    val windows = UIApplication.sharedApplication.windows.filterIsInstance<UIWindow>()
    val keyWindow = windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull()
    var controller = keyWindow?.rootViewController
    while (controller?.presentedViewController != null) {
        controller = controller.presentedViewController
    }
    return controller
}
