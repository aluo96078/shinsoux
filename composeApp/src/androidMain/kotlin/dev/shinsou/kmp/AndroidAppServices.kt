package dev.shinsou.kmp

import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.PowerManager

import android.Manifest
import android.app.Activity
import android.app.KeyguardManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import dev.shinsou.kmp.app.ContentFeatureRuntime
import dev.shinsou.kmp.domain.model.ReaderOrientation
import dev.shinsou.kmp.backup.SyncAwareSnapshotRestore
import dev.shinsou.kmp.tracking.TrackingCoordinator
import dev.shinsou.kmp.sync.SnapshotSyncController
import dev.shinsou.kmp.sync.v2.CloudflareSyncUiController
import dev.shinsou.kmp.ui.AppLifecycleState
import dev.shinsou.kmp.ui.BinaryDocumentExportSource
import dev.shinsou.kmp.ui.BrowseCallbacks
import dev.shinsou.kmp.ui.ContentCallbacks
import dev.shinsou.kmp.ui.ImportedDocument
import dev.shinsou.kmp.ui.ImportedDocumentLimits
import dev.shinsou.kmp.ui.PlatformSecurityCapabilities
import dev.shinsou.kmp.ui.ReaderVolumeKeyEvent
import dev.shinsou.kmp.ui.ReaderVolumeKeyEventSink
import dev.shinsou.kmp.ui.RetainedDeepLinkQueue
import dev.shinsou.kmp.ui.ShinsouAppServices
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.mobileSecurityCapabilities
import dev.shinsou.kmp.ui.portability.PortableContentBackupV2UiController
import dev.shinsou.kmp.ui.portability.ShuYueMigrationUiController
import java.util.concurrent.Executor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

internal interface AndroidDocumentLauncher {
    suspend fun export(name: String, contents: ByteArray): Boolean
    suspend fun export(name: String, source: BinaryDocumentExportSource): Boolean
    suspend fun import(extensions: Set<String>, limits: ImportedDocumentLimits): ImportedDocument?
    suspend fun pickMany(extensions: Set<String>, limits: ImportedDocumentLimits): List<ImportedDocument>
}

internal class AndroidAppServices(
    private val activity: FragmentActivity,
    private val documentLauncher: AndroidDocumentLauncher,
    override val browse: BrowseCallbacks = BrowseCallbacks.None,
    override val content: ContentCallbacks = ContentCallbacks.None,
    override val contentFeatures: ContentFeatureRuntime? = null,
    override val tracking: TrackingCoordinator? = null,
    override val snapshotSync: SnapshotSyncController? = null,
    override val cloudflareSync: CloudflareSyncUiController? = null,
    override val syncAwareSnapshotRestore: SyncAwareSnapshotRestore? = null,
    override val portableContentBackupV2: PortableContentBackupV2UiController? = null,
    override val shuYueMigration: ShuYueMigrationUiController? = null,
    private val stringsProvider: () -> ShinsouStrings = { ShinsouStrings() },
) : ShinsouAppServices {
    private val pendingDeepLinks = RetainedDeepLinkQueue()
    private val lifecycleState = MutableStateFlow(AppLifecycleState.FOREGROUND)
    private val mutableReaderVolumeKeyEvents = MutableSharedFlow<ReaderVolumeKeyEvent>(extraBufferCapacity = 2)
    private val qrCodeScanner = AndroidQrCodeScanner(activity)
    private var previousRequestedOrientation: Int? = null
    @Volatile
    private var monitorReaderVolumeKeys: Boolean = false
    @Volatile
    private var readerVolumeKeyEventSink: ReaderVolumeKeyEventSink? = null
    @Volatile
    private var systemBackHandler: (() -> Boolean)? = null

    override val deepLinks: Flow<ShinsouDeepLink> = pendingDeepLinks.events
    override val appLifecycle: StateFlow<AppLifecycleState> = lifecycleState
    override val readerVolumeKeyEvents: Flow<ReaderVolumeKeyEvent> = mutableReaderVolumeKeyEvents.asSharedFlow()
    override val securityCapabilities: PlatformSecurityCapabilities
        get() = mobileSecurityCapabilities(deviceOwnerAuthenticationAvailable())

    fun emitDeepLink(link: ShinsouDeepLink): Boolean = pendingDeepLinks.tryEnqueue(link)

    fun emitForeground() {
        lifecycleState.value = AppLifecycleState.FOREGROUND
    }

    fun emitBackground() {
        lifecycleState.value = AppLifecycleState.BACKGROUND
    }

    override fun deepLinkHandled(link: ShinsouDeepLink) {
        pendingDeepLinks.handled(link)
    }

    override fun openExternalUrl(url: String) {
        tryOpenExternalUrl(url)
    }

    override fun tryOpenExternalUrl(url: String): Boolean = runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }.getOrDefault(false)

    override fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, title))
    }

    override fun copyText(label: String, text: String): Boolean = runCatching {
        activity.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(label, text))
        true
    }.getOrDefault(false)

    override suspend fun readClipboardText(): String? = runCatching {
        val clipboard = activity.getSystemService(ClipboardManager::class.java)
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(activity)?.toString()
    }.getOrNull()

    override suspend fun scanQrCode(): String? = qrCodeScanner.scan()

    override suspend fun exportDocument(suggestedName: String, contents: String): Boolean =
        documentLauncher.export(suggestedName, contents.encodeToByteArray())

    override suspend fun exportBinaryDocument(suggestedName: String, contents: ByteArray): Boolean =
        documentLauncher.export(suggestedName, contents.copyOf())

    override suspend fun exportBinaryDocument(
        suggestedName: String,
        source: BinaryDocumentExportSource,
    ): Boolean = documentLauncher.export(suggestedName, source)

    /** Into the gallery under Pictures/Shinsou X; no storage permission is needed from API 29. */
    override suspend fun saveImage(suggestedName: String, contents: ByteArray): Boolean =
        withContext(Dispatchers.IO) {
            val resolver = activity.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, suggestedName)
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, imageMimeType(contents))
                put(
                    android.provider.MediaStore.Images.Media.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_PICTURES + "/Shinsou X",
                )
                put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY),
                values,
            ) ?: return@withContext false
            // Published only once the pending flag is cleared on exactly this row.
            val written = runCatching {
                requireNotNull(resolver.openOutputStream(uri)).use { it.write(contents) }
                resolver.update(
                    uri,
                    android.content.ContentValues().apply { put(android.provider.MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                ) == 1
            }.getOrDefault(false)
            // A half-written entry would show up broken in the gallery.
            if (!written) runCatching { resolver.delete(uri, null, null) }
            written
        }

    override suspend fun importDocument(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): ImportedDocument? = documentLauncher.import(acceptedExtensions, limits)

    override suspend fun pickLocalFiles(
        acceptedExtensions: Set<String>,
        limits: ImportedDocumentLimits,
    ): List<ImportedDocument> = documentLauncher.pickMany(acceptedExtensions, limits)

    override fun setKeepScreenOn(enabled: Boolean) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun setSecureScreen(enabled: Boolean) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun setFullscreen(enabled: Boolean) {
        activity.runOnUiThread {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (enabled) hide(WindowInsetsCompat.Type.systemBars())
                else show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    override fun setReaderOrientation(orientation: ReaderOrientation?) {
        activity.runOnUiThread {
            if (orientation == null) {
                val previous = previousRequestedOrientation ?: return@runOnUiThread
                previousRequestedOrientation = null
                activity.requestedOrientation = previous
                return@runOnUiThread
            }

            if (previousRequestedOrientation == null) {
                previousRequestedOrientation = activity.requestedOrientation
            }
            val requested = when (orientation) {
                ReaderOrientation.FREE -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                ReaderOrientation.PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                ReaderOrientation.LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                ReaderOrientation.SENSOR_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                ReaderOrientation.SENSOR_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
            if (activity.requestedOrientation != requested) {
                activity.requestedOrientation = requested
            }
        }
    }

    override fun setReaderVolumeKeyMonitoringEnabled(enabled: Boolean) {
        monitorReaderVolumeKeys = enabled
    }

    override fun setReaderVolumeKeyEventSink(sink: ReaderVolumeKeyEventSink?) {
        readerVolumeKeyEventSink = sink
    }

    /** Returns true only while Reader owns the hardware volume buttons. */
    fun emitReaderVolumeKey(event: ReaderVolumeKeyEvent): Boolean {
        if (!monitorReaderVolumeKeys) return false
        // Activity key dispatch and Compose state both run on Android's UI thread. Deliver
        // directly so a press cannot be dropped by a small SharedFlow buffer or handled after
        // the reader/session that owned it has already been replaced.
        val sink = readerVolumeKeyEventSink
        if (sink != null) return sink.dispatch(event)
        return mutableReaderVolumeKeyEvents.tryEmit(event)
    }

    fun shouldInterceptReaderVolumeKeys(): Boolean = monitorReaderVolumeKeys

    override fun setSystemBackHandler(handler: (() -> Boolean)?) {
        systemBackHandler = handler
    }

    /** Returns whether the common navigation stack consumed the Android system-back request. */
    fun dispatchSystemBack(): Boolean = systemBackHandler?.invoke() == true

    fun notificationsAllowed(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    override fun backgroundDownloadsRestricted(): Boolean =
        !activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(activity.packageName)

    override fun openBackgroundDownloadSettings() {
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${activity.packageName}"),
        )
        runCatching { activity.startActivity(direct) }
            .recoverCatching { activity.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                NOTIFICATION_REQUEST,
            )
        }
    }

    override suspend fun authenticate(reason: String): Boolean = suspendCancellableCoroutine { continuation ->
        val executor = Executor { command -> activity.runOnUiThread(command) }
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (continuation.isActive) continuation.resume(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resume(false)
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(stringsProvider().text("Shinsou X"))
            .setSubtitle(reason)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_WEAK or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setDeviceCredentialAllowed(true)
                }
            }
            .build()
        prompt.authenticate(info)
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
    }

    private fun deviceOwnerAuthenticationAvailable(): Boolean {
        val deviceCredentialAvailable = activity
            .getSystemService(KeyguardManager::class.java)
            ?.isDeviceSecure == true
        val biometricAvailable = runCatching {
            BiometricManager.from(activity).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
                BiometricManager.BIOMETRIC_SUCCESS
        }.getOrDefault(false)
        return deviceCredentialAvailable || biometricAvailable
    }

    override fun requestApplicationClose() = activity.finish()

    companion object {
        private const val NOTIFICATION_REQUEST = 1401

        fun openAppSettings(activity: Activity) {
            activity.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", activity.packageName, null)
                },
            )
        }
    }
}

/** From the leading bytes, so a gallery entry is typed by what it holds rather than its URL. */
internal fun imageMimeType(bytes: ByteArray): String {
    fun at(index: Int) = bytes.getOrNull(index)?.toInt()?.and(0xFF) ?: -1
    return when {
        at(0) == 0x89 && at(1) == 0x50 && at(2) == 0x4E && at(3) == 0x47 -> "image/png"
        at(0) == 0x47 && at(1) == 0x49 && at(2) == 0x46 -> "image/gif"
        at(0) == 0x52 && at(1) == 0x49 && at(2) == 0x46 && at(3) == 0x46 &&
            at(8) == 0x57 && at(9) == 0x45 && at(10) == 0x42 && at(11) == 0x50 -> "image/webp"
        else -> "image/jpeg"
    }
}
