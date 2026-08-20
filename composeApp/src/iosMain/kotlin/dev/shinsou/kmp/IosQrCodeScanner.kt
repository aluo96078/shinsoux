package dev.shinsou.kmp

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCAction
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusNotDetermined
import platform.AVFoundation.AVCaptureConnection
import platform.AVFoundation.AVCaptureDevice
import platform.AVFoundation.AVCaptureDeviceInput
import platform.AVFoundation.AVCaptureMetadataOutput
import platform.AVFoundation.AVCaptureMetadataOutputObjectsDelegateProtocol
import platform.AVFoundation.AVCaptureOutput
import platform.AVFoundation.AVCaptureSession
import platform.AVFoundation.AVCaptureVideoPreviewLayer
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMetadataMachineReadableCodeObject
import platform.AVFoundation.AVMetadataObjectTypeQRCode
import platform.AVFoundation.authorizationStatusForMediaType
import platform.AVFoundation.requestAccessForMediaType
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSSelectorFromString
import platform.UIKit.UIButton
import platform.UIKit.UIButtonTypeSystem
import platform.UIKit.UIColor
import platform.UIKit.UIControlEventTouchUpInside
import platform.UIKit.UIControlStateNormal
import platform.UIKit.UIModalPresentationFullScreen
import platform.UIKit.UIViewController
import platform.UIKit.setAccessibilityLabel
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_queue_create
import kotlin.coroutines.resume

/** Camera QR scanner whose only output is the decoded in-memory string. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal class IosQrCodeScanner(
    private val presenterProvider: () -> UIViewController?,
) {
    private val scanMutex = Mutex()
    private var activeScanner: IosQrScannerViewController? = null

    suspend fun scan(): String? = scanMutex.withLock {
        if (!cameraAccessGranted()) return@withLock null

        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                if (activeScanner != null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val presenter = presenterProvider()
                if (presenter == null) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val controller = IosQrScannerViewController { value ->
                    activeScanner = null
                    if (continuation.isActive) continuation.resume(value)
                }
                if (!controller.ready) {
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                controller.modalPresentationStyle = UIModalPresentationFullScreen
                activeScanner = controller
                continuation.invokeOnCancellation {
                    dispatch_async(dispatch_get_main_queue()) {
                        if (activeScanner === controller) controller.cancel()
                    }
                }
                presenter.presentViewController(controller, animated = true, completion = null)
            }
        }
    }

    private suspend fun cameraAccessGranted(): Boolean = when (
        AVCaptureDevice.authorizationStatusForMediaType(AVMediaTypeVideo)
    ) {
        AVAuthorizationStatusAuthorized -> true
        AVAuthorizationStatusNotDetermined -> suspendCancellableCoroutine { continuation ->
            AVCaptureDevice.requestAccessForMediaType(AVMediaTypeVideo) { granted: Boolean ->
                if (continuation.isActive) continuation.resume(granted)
            }
        }
        else -> false
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private class IosQrScannerViewController(
    onComplete: (String?) -> Unit,
) : UIViewController(nibName = null, bundle = null), AVCaptureMetadataOutputObjectsDelegateProtocol {
    private val captureSession = AVCaptureSession()
    private val metadataOutput = AVCaptureMetadataOutput()
    private val previewLayer = AVCaptureVideoPreviewLayer(session = captureSession)
    private val sessionQueue = dispatch_queue_create("dev.aluo.shinsoux.qr-capture", null)
    private val closeButton = UIButton.buttonWithType(UIButtonTypeSystem)
    private var completion: ((String?) -> Unit)? = onComplete
    private var completed = false

    val ready: Boolean = configureCaptureSession()

    override fun viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor.blackColor

        previewLayer.videoGravity = AVLayerVideoGravityResizeAspectFill
        view.layer.addSublayer(previewLayer)

        closeButton.setTitle("✕", forState = UIControlStateNormal)
        closeButton.setTitleColor(UIColor.whiteColor, forState = UIControlStateNormal)
        closeButton.backgroundColor = UIColor.blackColor.colorWithAlphaComponent(0.55)
        closeButton.layer.cornerRadius = 22.0
        closeButton.setAccessibilityLabel("Cancel QR code scan")
        closeButton.addTarget(
            target = this,
            action = NSSelectorFromString("cancelScan"),
            forControlEvents = UIControlEventTouchUpInside,
        )
        view.addSubview(closeButton)
    }

    override fun viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
        val width = view.bounds.useContents { size.width }
        val topInset = view.safeAreaInsets.useContents { top }
        closeButton.setFrame(CGRectMake(
            x = (width - 64.0).coerceAtLeast(12.0),
            y = topInset + 8.0,
            width = 52.0,
            height = 44.0,
        ))
    }

    override fun viewWillAppear(animated: Boolean) {
        super.viewWillAppear(animated)
        dispatch_async(sessionQueue) {
            if (!captureSession.running) captureSession.startRunning()
        }
    }

    override fun viewDidDisappear(animated: Boolean) {
        super.viewDidDisappear(animated)
        if (!completed) completeWithoutDismissal()
    }

    override fun captureOutput(
        output: AVCaptureOutput,
        didOutputMetadataObjects: List<*>,
        fromConnection: AVCaptureConnection,
    ) {
        val value = didOutputMetadataObjects
            .filterIsInstance<AVMetadataMachineReadableCodeObject>()
            .firstNotNullOfOrNull { it.stringValue?.takeIf(String::isNotBlank) }
            ?: return
        finish(value)
    }

    @ObjCAction
    fun cancelScan() {
        cancel()
    }

    fun cancel() {
        finish(null)
    }

    private fun configureCaptureSession(): Boolean = memScoped {
        val device = AVCaptureDevice.defaultDeviceWithMediaType(AVMediaTypeVideo)
            ?: return@memScoped false
        val error = alloc<ObjCObjectVar<NSError?>>()
        val input = runCatching { AVCaptureDeviceInput(device = device, error = error.ptr) }
            .getOrNull()
            ?: return@memScoped false
        if (!captureSession.canAddInput(input)) return@memScoped false
        captureSession.addInput(input)

        if (!captureSession.canAddOutput(metadataOutput)) return@memScoped false
        captureSession.addOutput(metadataOutput)
        val qrType = AVMetadataObjectTypeQRCode ?: return@memScoped false
        if (qrType !in metadataOutput.availableMetadataObjectTypes) return@memScoped false
        metadataOutput.setMetadataObjectsDelegate(
            this@IosQrScannerViewController,
            queue = dispatch_get_main_queue(),
        )
        metadataOutput.metadataObjectTypes = listOf(qrType)
        true
    }

    private fun finish(value: String?) {
        if (completed) return
        completed = true
        stopCapture()
        val callback = completion
        completion = null
        dismissViewControllerAnimated(true) {
            callback?.invoke(value)
        }
    }

    private fun completeWithoutDismissal() {
        if (completed) return
        completed = true
        stopCapture()
        val callback = completion
        completion = null
        callback?.invoke(null)
    }

    private fun stopCapture() {
        metadataOutput.setMetadataObjectsDelegate(null, queue = null)
        dispatch_async(sessionQueue) {
            if (captureSession.running) captureSession.stopRunning()
        }
    }
}
