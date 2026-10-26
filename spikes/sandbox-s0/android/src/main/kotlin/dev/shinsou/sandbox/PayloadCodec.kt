package dev.shinsou.sandbox

import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.OsConstants
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.Executor

/**
 * Chooses inline vs fd-backed transport. The two large-payload strategies are kept side by side
 * for the S0 comparison; [Strategy] is a measurement switch, production picks one.
 */
class PayloadCodec(
    private val writer: Executor,
    var strategy: Strategy = Strategy.PIPE,
    private val inlineMax: Int = 256 * 1024,
    private val maxTotal: Long = 4L shl 20,
) {
    enum class Strategy { PIPE, SHARED_MEMORY }

    fun encode(bytes: ByteArray): Payload =
        if (bytes.size <= inlineMax) Payload.inline(bytes)
        else when (strategy) {
            Strategy.PIPE -> encodePipe(bytes)
            Strategy.SHARED_MEMORY -> encodeShm(bytes)
        }

    fun decode(p: Payload): ByteArray {
        require(p.length in 0..maxTotal) { "payload over limit" }
        p.inline?.let { return it }
        p.pipe?.let { return decodePipe(it, p.length) }
        p.shm?.let { return decodeShm(it, p.length) }
        throw IllegalArgumentException("empty payload")
    }

    // --- Option A: pipe, length-prefixed frames (reuses FrameCodec chunking) -------------------
    private fun encodePipe(bytes: ByteArray): Payload {
        val (read, write) = ParcelFileDescriptor.createPipe()
        // The pipe buffer is 64 KiB, so a writer must run concurrently with the reader.
        writer.execute {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                    FrameCodec.write(BufferedOutputStream(out, 64 * 1024), 0, 0, bytes)
                }
            } catch (_: IOException) {
                // reader went away (cancel/crash): nothing to deliver
            }
        }
        return Payload.pipe(read, bytes.size)
    }

    private fun decodePipe(read: ParcelFileDescriptor, length: Int): ByteArray =
        ParcelFileDescriptor.AutoCloseInputStream(read).use { input ->
            // A hostile peer can lie about length; FrameCodec enforces the real cap while reading.
            val m = FrameCodec.read(input, maxTotal) ?: throw IOException("empty pipe")
            if (m.payload.size != length) throw IOException("length mismatch")
            m.payload
        }

    // --- Option B: SharedMemory, one region, receiver maps read-only ---------------------------
    private fun encodeShm(bytes: ByteArray): Payload {
        val region = SharedMemory.create("ssc-payload", bytes.size)
        val map = region.mapReadWrite()
        try {
            map.put(bytes)
        } finally {
            SharedMemory.unmap(map)
        }
        region.setProtect(OsConstants.PROT_READ) // receiver (and we) can no longer write
        return Payload.shm(region, bytes.size)
    }

    private fun decodeShm(region: SharedMemory, length: Int): ByteArray {
        // The sender is untrusted: size must be checked before mapping and copying.
        if (length > region.size) throw IOException("length exceeds region")
        val map = region.mapReadOnly()
        try {
            val out = ByteArray(length)
            map.get(out)
            return out // copy out so later writes by a malicious peer cannot race our parser
        } finally {
            SharedMemory.unmap(map)
            region.close()
        }
    }
}
