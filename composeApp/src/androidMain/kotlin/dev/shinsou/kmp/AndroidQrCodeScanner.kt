package dev.shinsou.kmp

import androidx.fragment.app.FragmentActivity
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Permissionless QR scanning hosted by Google Play services.
 *
 * The camera preview and camera permission remain in the Play services scanner UI. Shinsou X
 * receives only the decoded value and never receives, stores, or logs a camera frame.
 */
internal class AndroidQrCodeScanner(
    activity: FragmentActivity,
) {
    private val scanner = GmsBarcodeScanning.getClient(
        activity,
        GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .enableAutoZoom()
            .build(),
    )
    private val scanMutex = Mutex()

    suspend fun scan(): String? = scanMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            scanner.startScan()
                .addOnSuccessListener { barcode ->
                    val value = barcode.rawValue?.takeIf(String::isNotBlank)
                    if (continuation.isActive) continuation.resume(value)
                }
                .addOnCanceledListener {
                    if (continuation.isActive) continuation.resume(null)
                }
                .addOnFailureListener {
                    // Missing/outdated Play services and unavailable cameras degrade to the
                    // existing clipboard/manual-code flow. Never log an exception containing
                    // scanner internals or a decoded payload.
                    if (continuation.isActive) continuation.resume(null)
                }
        }
    }
}
