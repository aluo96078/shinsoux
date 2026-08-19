package dev.shinsou.kmp

import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.ui.AppLifecycleState
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.ImportedDocumentLimits
import dev.shinsou.kmp.ui.ImportedDocumentReadException
import dev.shinsou.kmp.ui.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.ShinsouAppServices
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.ui.SystemBackGestureEvent
import dev.shinsou.kmp.ui.mobileSecurityCapabilities
import dev.shinsou.kmp.ui.readBoundedImportedBytes
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
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
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.closeFile
import platform.Foundation.dataUsingEncoding
import platform.Foundation.fileHandleForReadingFromURL
import platform.Foundation.readDataOfLength
import platform.Foundation.writeToURL
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.popoverPresentationController
import platform.UniformTypeIdentifiers.UTTypeData
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.coroutines.resume

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosAppServices(
    override val browse: BrowseCallbacks = BrowseCallbacks.None,
    override val content: ContentCallbacks = ContentCallbacks.None,
    override val tracking: TrackingCoordinator? = null,
    override val snapshotSync: SnapshotSyncController? = null,
) : ShinsouAppServices {
    private val deepLinkChannel = Channel<ShinsouDeepLink>(capacity = Channel.BUFFERED)
    private val readerVolumeKeyChannel = Channel<ReaderVolumeKeyEvent>(capacity = Channel.BUFFERED)
    private val systemBackChannel = Channel<Unit>(capacity = Channel.BUFFERED)
    // Gesture progress can arrive faster than Compose consumes it. Keep only the latest event so
    // the terminal Settled event replaces stale progress instead of being rejected by a full buffer.
    private val systemBackGestureChannel = Channel<SystemBackGestureEvent>(capacity = Channel.CONFLATED)
    private val lifecycleState = MutableStateFlow(AppLifecycleState.FOREGROUND)
    private val documentPickerMutex = Mutex()
    private var activeDocumentDelegate: IosDocumentPickerDelegate? = null

    override val deepLinks: Flow<ShinsouDeepLink> = deepLinkChannel.receiveAsFlow()
    override val readerVolumeKeyEvents: Flow<ReaderVolumeKeyEvent> = readerVolumeKeyChannel.receiveAsFlow()
    override val systemBackEvents: Flow<Unit> = systemBackChannel.receiveAsFlow()
    override val systemBackGestureEvents: Flow<SystemBackGestureEvent> = systemBackGestureChannel.receiveAsFlow()
    override val appLifecycle: StateFlow<AppLifecycleState> = lifecycleState
    override val securityCapabilities: PlatformSecurityCapabilities
        get() = mobileSecurityCapabilities(deviceOwnerAuthenticationAvailable())

    fun emitDeepLink(link: ShinsouDeepLink): Boolean = deepLinkChannel.trySend(link).isSuccess

    fun emitReaderVolumeKey(event: ReaderVolumeKeyEvent): Boolean {
        // Gate on the durable preference and the committed reader presentation. The mirrored
        // monitoring flag is only a notification pulse and can briefly be false while Compose
        // disposes/recreates effects during cold launch or root recomposition.
        if (!IosReaderPresentationState.readerOpen) return false
        if (!IosApplicationContainer.repository.currentSnapshot.settings.reader.volumeKeys) return false
        return readerVolumeKeyChannel.trySend(event).isSuccess
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
        val target = NSURL.URLWithString(url) ?: return
        dispatch_async(dispatch_get_main_queue()) {
            if (UIApplication.sharedApplication.canOpenURL(target)) {
                @Suppress("DEPRECATION")
                UIApplication.sharedApplication.openURL(target)
            }
        }
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

    override suspend fun importDocument(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): ImportedDocument? {
        val selected = pickDocumentUrls(allowsMultipleSelection = false)
            .firstOrNull()
            ?.takeIf { it.hasAcceptedExtension(acceptedExtensions) }
            ?: return null
        return withContext(Dispatchers.Default) { selected.readImportedDocument(limits) }
    }

    override suspend fun pickLocalFiles(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): List<ImportedDocument> {
        val selected = pickDocumentUrls(allowsMultipleSelection = true)
            .filter { it.hasAcceptedExtension(acceptedExtensions) }
        return withContext(Dispatchers.Default) {
            var acceptedBytes = 0L
            selected.map { url ->
                url.readImportedDocument(limits, acceptedBytes).also { acceptedBytes += it.contents.size }
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
        if (IosReaderPresentationState.readerOpen == open) return
        IosReaderPresentationState.readerOpen = open
        IosReaderPresentationState.notifyChanged()
    }

    override fun setReaderVolumeKeyInfrastructureEnabled(enabled: Boolean) {
        IosReaderVolumeKeyState.setInfrastructureEnabled(enabled)
    }

    override fun setReaderVolumeKeyMonitoringEnabled(enabled: Boolean) {
        IosReaderVolumeKeyState.setMonitoringEnabled(enabled)
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

    private suspend fun pickDocumentUrls(
        exportUrl: NSURL? = null,
        allowsMultipleSelection: Boolean,
    ): List<NSURL> = documentPickerMutex.withLock {
        withContext(Dispatchers.Main) {
            val presenter = topViewController() ?: return@withContext emptyList()
            val result = CompletableDeferred<List<NSURL>>()
            val delegate = IosDocumentPickerDelegate(result)
            val picker = if (exportUrl != null) {
                UIDocumentPickerViewController(forExportingURLs = listOf(exportUrl))
            } else {
                UIDocumentPickerViewController(forOpeningContentTypes = listOf(UTTypeData))
            }
            picker.allowsMultipleSelection = exportUrl == null && allowsMultipleSelection
            picker.delegate = delegate
            activeDocumentDelegate = delegate
            presenter.presentViewController(picker, animated = true, completion = null)
            try {
                result.await()
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

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.readImportedDocument(
    limits: ImportedDocumentLimits,
    previouslyAcceptedBytes: Long = 0,
): ImportedDocument {
    val granted = startAccessingSecurityScopedResource()
    val name = lastPathComponent ?: "document"
    return try {
        val declaredSize = declaredFileSize()
        val handle = NSFileHandle.fileHandleForReadingFromURL(this, error = null)
            ?: throw ImportedDocumentReadException("Unable to open “$name”.")
        try {
            ImportedDocument(
                name = name,
                contents = readBoundedImportedBytes(
                    name = name,
                    declaredSize = declaredSize,
                    limits = limits,
                    previouslyAcceptedBytes = previouslyAcceptedBytes,
                ) { destination, offset, length ->
                    handle.readDataOfLength(length.toULong()).copyInto(destination, offset, length)
                },
            )
        } finally {
            runCatching { handle.closeFile() }
        }
    } catch (error: ImportedDocumentReadException) {
        throw error
    } catch (error: Throwable) {
        throw ImportedDocumentReadException("Unable to read “$name”.", error)
    } finally {
        if (granted) stopAccessingSecurityScopedResource()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun NSURL.declaredFileSize(): Long? {
    val key = NSURLFileSizeKey ?: return null
    val value = resourceValuesForKeys(listOf(key), error = null)?.get(key) as? NSNumber
    return value?.longLongValue
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
