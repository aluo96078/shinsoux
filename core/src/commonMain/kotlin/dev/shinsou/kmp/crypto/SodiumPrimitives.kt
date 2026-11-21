@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shinsou.kmp.crypto

import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.signature.Signature
import com.ionspin.kotlin.crypto.util.Base64Variants
import com.ionspin.kotlin.crypto.util.LibsodiumUtil
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw key pair used at the crypto boundary. Private bytes must only enter protected storage. */
public data class SodiumKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

/**
 * Reviewed libsodium primitives shared by Android, iOS, macOS, and Windows/JVM; they verify the
 * signatures of plugin repositories and publishers.
 *
 * Initialization is explicit because the JVM binding extracts and loads a native library. No
 * operation silently falls back to a weaker or plaintext implementation.
 */
public object SodiumPrimitives {
    private val initializationMutex = Mutex()
    @Volatile
    private var initialized = false

    public suspend fun initialize() {
        if (initialized) return
        initializationMutex.withLock {
            if (initialized) return
            initializePlatformLibsodium()
            initialized = true
        }
    }

    public fun sha256(value: ByteArray): ByteArray {
        requireReady()
        return Hash.sha256(value.asUByteArray()).asByteArray().copyOf()
    }

    public fun generateEd25519KeyPair(): SodiumKeyPair {
        requireReady()
        val pair = Signature.keypair()
        return SodiumKeyPair(
            publicKey = pair.publicKey.asByteArray().copyOf(),
            privateKey = pair.secretKey.asByteArray().copyOf(),
        )
    }

    public fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray {
        requireReady()
        require(privateKey.size == ED25519_PRIVATE_KEY_BYTES) { "Invalid Ed25519 private key" }
        return Signature.detached(message.asUByteArray(), privateKey.asUByteArray()).asByteArray().copyOf()
    }

    public fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        requireReady()
        if (signature.size != ED25519_SIGNATURE_BYTES || publicKey.size != ED25519_PUBLIC_KEY_BYTES) return false
        // libsodium signals a bad signature by throwing; that is the expected "invalid" answer.
        return runCatching {
            Signature.verifyDetached(
                signature.asUByteArray(),
                message.asUByteArray(),
                publicKey.asUByteArray(),
            )
        }.isSuccess
    }

    public fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
        requireReady()
        if (first.size != second.size) return false
        return LibsodiumUtil.memcmp(first.asUByteArray(), second.asUByteArray())
    }

    public fun base64UrlEncode(value: ByteArray): String {
        requireReady()
        return LibsodiumUtil.toBase64(value.asUByteArray(), Base64Variants.URLSAFE_NO_PADDING)
    }

    public fun destroy(value: ByteArray) {
        if (value.isEmpty()) return
        if (initialized) LibsodiumUtil.memzero(value.asUByteArray()) else value.fill(0)
    }

    private fun requireReady() {
        check(initialized) { "Cryptography has not been initialised" }
    }

    private const val ED25519_PUBLIC_KEY_BYTES = 32
    private const val ED25519_PRIVATE_KEY_BYTES = 64
    private const val ED25519_SIGNATURE_BYTES = 64
}
