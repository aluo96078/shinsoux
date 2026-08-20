@file:OptIn(ExperimentalUnsignedTypes::class)

package dev.shinsou.kmp.sync.crypto

import com.ionspin.kotlin.crypto.LibsodiumInitializer
import com.ionspin.kotlin.crypto.aead.AuthenticatedEncryptionWithAssociatedData
import com.ionspin.kotlin.crypto.hash.Hash
import com.ionspin.kotlin.crypto.scalarmult.ScalarMultiplication
import com.ionspin.kotlin.crypto.signature.Signature
import com.ionspin.kotlin.crypto.util.Base64Variants
import com.ionspin.kotlin.crypto.util.LibsodiumRandom
import com.ionspin.kotlin.crypto.util.LibsodiumUtil
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw key pair used at the crypto boundary. Private bytes must only enter SyncSecretStore. */
data class SodiumKeyPair(
    val publicKey: ByteArray,
    val privateKey: ByteArray,
)

/** X25519 + HKDF-SHA256 + ChaCha20-Poly1305 key envelope. */
data class SodiumWrappedKey(
    val ephemeralPublicKey: ByteArray,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
)

/**
 * Reviewed libsodium primitives shared by Android, iOS, macOS, and Windows/JVM.
 *
 * Initialization is explicit because the JVM binding extracts and loads a native library. No
 * operation silently falls back to a weaker or plaintext implementation.
 */
object SodiumSyncPrimitives {
    private val initializationMutex = Mutex()
    @Volatile
    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        initializationMutex.withLock {
            if (initialized) return
            LibsodiumInitializer.initialize()
            initialized = true
        }
    }

    fun randomBytes(size: Int): ByteArray {
        requireReady()
        require(size in 1..MAX_RANDOM_BYTES) { "Invalid random byte count" }
        return LibsodiumRandom.buf(size).asByteArray().copyOf()
    }

    fun sha256(value: ByteArray): ByteArray {
        requireReady()
        return Hash.sha256(value.asUByteArray()).asByteArray().copyOf()
    }

    fun generateEd25519KeyPair(): SodiumKeyPair {
        requireReady()
        val pair = Signature.keypair()
        return SodiumKeyPair(
            publicKey = pair.publicKey.asByteArray().copyOf(),
            privateKey = pair.secretKey.asByteArray().copyOf(),
        )
    }

    fun signEd25519(message: ByteArray, privateKey: ByteArray): ByteArray {
        requireReady()
        require(privateKey.size == ED25519_PRIVATE_KEY_BYTES) { "Invalid Ed25519 private key" }
        return Signature.detached(message.asUByteArray(), privateKey.asUByteArray()).asByteArray().copyOf()
    }

    /** Libsodium Ed25519 secret keys are seed || public-key; derive without persisting a duplicate. */
    fun ed25519PublicKey(privateKey: ByteArray): ByteArray {
        requireReady()
        require(privateKey.size == ED25519_PRIVATE_KEY_BYTES) { "Invalid Ed25519 private key" }
        return privateKey.copyOfRange(
            ED25519_PRIVATE_KEY_BYTES - ED25519_PUBLIC_KEY_BYTES,
            ED25519_PRIVATE_KEY_BYTES,
        )
    }

    fun verifyEd25519(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        requireReady()
        if (signature.size != ED25519_SIGNATURE_BYTES || publicKey.size != ED25519_PUBLIC_KEY_BYTES) return false
        return runCatching {
            Signature.verifyDetached(
                signature.asUByteArray(),
                message.asUByteArray(),
                publicKey.asUByteArray(),
            )
        }.isSuccess
    }

    fun generateX25519KeyPair(): SodiumKeyPair {
        requireReady()
        val privateKey = randomBytes(X25519_KEY_BYTES)
        val publicKey = x25519PublicKey(privateKey)
        return SodiumKeyPair(publicKey = publicKey, privateKey = privateKey)
    }

    /** Derives the public half so an imported Recovery Kit cannot substitute its wrapping key. */
    fun x25519PublicKey(privateKey: ByteArray): ByteArray {
        requireReady()
        require(privateKey.size == X25519_KEY_BYTES) { "Invalid X25519 private key" }
        return ScalarMultiplication.scalarMultiplicationBase(privateKey.asUByteArray())
            .asByteArray()
            .copyOf()
    }

    fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        requireReady()
        require(privateKey.size == X25519_KEY_BYTES) { "Invalid X25519 private key" }
        require(peerPublicKey.size == X25519_KEY_BYTES) { "Invalid X25519 public key" }
        return ScalarMultiplication.scalarMultiplication(
            privateKey.asUByteArray(),
            peerPublicKey.asUByteArray(),
        ).asByteArray().copyOf()
    }

    fun hkdfSha256(
        inputKeyMaterial: ByteArray,
        salt: ByteArray = ByteArray(0),
        info: ByteArray = ByteArray(0),
        outputSize: Int = SHA256_BYTES,
    ): ByteArray {
        requireReady()
        require(outputSize in 1..(255 * SHA256_BYTES)) { "Invalid HKDF output size" }
        val resolvedSalt = if (salt.isEmpty()) ByteArray(SHA256_BYTES) else salt
        val pseudoRandomKey = hmacSha256(resolvedSalt, inputKeyMaterial)
        val output = ByteArray(outputSize)
        var previous = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < outputSize) {
            previous = hmacSha256(pseudoRandomKey, previous + info + byteArrayOf(counter.toByte()))
            val copied = minOf(previous.size, outputSize - offset)
            previous.copyInto(output, destinationOffset = offset, endIndex = copied)
            offset += copied
            counter++
        }
        destroy(pseudoRandomKey)
        destroy(previous)
        return output
    }

    fun aeadEncrypt(
        plaintext: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        requireReady()
        require(key.size == AEAD_KEY_BYTES) { "Invalid ChaCha20-Poly1305 key" }
        require(nonce.size == AEAD_NONCE_BYTES) { "Invalid ChaCha20-Poly1305 nonce" }
        return AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfEncrypt(
            plaintext.asUByteArray(),
            associatedData.asUByteArray(),
            nonce.asUByteArray(),
            key.asUByteArray(),
        ).asByteArray().copyOf()
    }

    fun aeadDecrypt(
        ciphertext: ByteArray,
        key: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
    ): ByteArray {
        requireReady()
        require(key.size == AEAD_KEY_BYTES) { "Invalid ChaCha20-Poly1305 key" }
        require(nonce.size == AEAD_NONCE_BYTES) { "Invalid ChaCha20-Poly1305 nonce" }
        require(ciphertext.size >= AEAD_TAG_BYTES) { "Invalid ChaCha20-Poly1305 ciphertext" }
        return AuthenticatedEncryptionWithAssociatedData.chaCha20Poly1305IetfDecrypt(
            ciphertext.asUByteArray(),
            associatedData.asUByteArray(),
            nonce.asUByteArray(),
            key.asUByteArray(),
        ).asByteArray().copyOf()
    }

    fun wrapKey(
        keyToWrap: ByteArray,
        recipientPublicKey: ByteArray,
        context: ByteArray,
    ): SodiumWrappedKey {
        requireReady()
        require(keyToWrap.isNotEmpty()) { "A key envelope cannot be empty" }
        val ephemeral = generateX25519KeyPair()
        val sharedSecret = x25519(ephemeral.privateKey, recipientPublicKey)
        val wrappingKey = hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = sha256(context),
            info = KEY_WRAP_INFO + ephemeral.publicKey + recipientPublicKey,
            outputSize = AEAD_KEY_BYTES,
        )
        val nonce = randomBytes(AEAD_NONCE_BYTES)
        return try {
            SodiumWrappedKey(
                ephemeralPublicKey = ephemeral.publicKey,
                nonce = nonce,
                ciphertext = aeadEncrypt(keyToWrap, wrappingKey, nonce, context),
            )
        } finally {
            destroy(ephemeral.privateKey)
            destroy(sharedSecret)
            destroy(wrappingKey)
        }
    }

    fun unwrapKey(
        wrapped: SodiumWrappedKey,
        recipientPrivateKey: ByteArray,
        recipientPublicKey: ByteArray,
        context: ByteArray,
    ): ByteArray {
        requireReady()
        val sharedSecret = x25519(recipientPrivateKey, wrapped.ephemeralPublicKey)
        val wrappingKey = hkdfSha256(
            inputKeyMaterial = sharedSecret,
            salt = sha256(context),
            info = KEY_WRAP_INFO + wrapped.ephemeralPublicKey + recipientPublicKey,
            outputSize = AEAD_KEY_BYTES,
        )
        return try {
            aeadDecrypt(wrapped.ciphertext, wrappingKey, wrapped.nonce, context)
        } finally {
            destroy(sharedSecret)
            destroy(wrappingKey)
        }
    }

    fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
        requireReady()
        if (first.size != second.size) return false
        return LibsodiumUtil.memcmp(first.asUByteArray(), second.asUByteArray())
    }

    fun base64UrlEncode(value: ByteArray): String {
        requireReady()
        return LibsodiumUtil.toBase64(value.asUByteArray(), Base64Variants.URLSAFE_NO_PADDING)
    }

    fun base64UrlDecode(value: String): ByteArray {
        requireReady()
        require(value.length <= MAX_BASE64_LENGTH) { "Encoded value is too large" }
        return LibsodiumUtil.fromBase64(value, Base64Variants.URLSAFE_NO_PADDING).asByteArray().copyOf()
    }

    fun destroy(value: ByteArray) {
        if (value.isEmpty()) return
        if (initialized) LibsodiumUtil.memzero(value.asUByteArray()) else value.fill(0)
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val normalized = when {
            key.size > HMAC_BLOCK_BYTES -> sha256(key)
            else -> key.copyOf()
        }.copyOf(HMAC_BLOCK_BYTES)
        val innerPad = ByteArray(HMAC_BLOCK_BYTES) { index -> (normalized[index].toInt() xor 0x36).toByte() }
        val outerPad = ByteArray(HMAC_BLOCK_BYTES) { index -> (normalized[index].toInt() xor 0x5c).toByte() }
        return try {
            sha256(outerPad + sha256(innerPad + message))
        } finally {
            destroy(normalized)
            destroy(innerPad)
            destroy(outerPad)
        }
    }

    private fun requireReady() {
        check(initialized) { "Sync cryptography has not been initialised" }
    }

    private val KEY_WRAP_INFO = "shinsou-sync-v1-key-wrap".encodeToByteArray()
    private const val SHA256_BYTES = 32
    private const val HMAC_BLOCK_BYTES = 64
    private const val ED25519_PUBLIC_KEY_BYTES = 32
    private const val ED25519_PRIVATE_KEY_BYTES = 64
    private const val ED25519_SIGNATURE_BYTES = 64
    private const val X25519_KEY_BYTES = 32
    private const val AEAD_KEY_BYTES = 32
    private const val AEAD_NONCE_BYTES = 12
    private const val AEAD_TAG_BYTES = 16
    private const val MAX_RANDOM_BYTES = 1024 * 1024
    private const val MAX_BASE64_LENGTH = 16 * 1024 * 1024
}
