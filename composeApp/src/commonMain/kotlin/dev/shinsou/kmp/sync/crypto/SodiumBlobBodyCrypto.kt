package dev.shinsou.kmp.sync.crypto

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.sync.v2.BLOB_BODY_SCHEMA_VERSION
import dev.shinsou.kmp.sync.v2.BinaryData
import dev.shinsou.kmp.sync.v2.BlobBodyCryptoV2
import dev.shinsou.kmp.sync.v2.BlobDekEnvelopeV2
import dev.shinsou.kmp.sync.v2.BlobPrivateManifestV2
import dev.shinsou.kmp.sync.v2.BlobTransferKeyV2
import dev.shinsou.kmp.sync.v2.BlobUploadIntentV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobChunkPlanV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobChunkV2
import dev.shinsou.kmp.sync.v2.EncryptedBlobPrivateManifestV2
import dev.shinsou.kmp.sync.v2.RemoteBlobBodyManifestRefV2
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.requireSecret
import kotlinx.serialization.json.Json

/** Production body-plane crypto backed by the reviewed KMP libsodium primitives. */
public class SodiumBlobBodyCryptoV2(
    private val secretStore: SyncSecretStore,
    private val json: Json = BlobBodyCryptoJson,
) : BlobBodyCryptoV2 {
    override suspend fun createUploadIntent(
        session: SyncSession,
        transferKey: BlobTransferKeyV2,
        blob: BlobRef,
        keyEpoch: Int,
        chunkSizeBytes: Int,
        createdAtEpochMillis: Long,
    ): BlobUploadIntentV2 {
        SodiumSyncPrimitives.initialize()
        blob.validate()
        transferKey.requireBoundTo(session)
        require(transferKey.blobId == blob.blobId) { "Blob transfer identity mismatch" }
        require(keyEpoch == session.activeKeyEpoch) { "A blob upload must use the active workspace epoch" }
        require(chunkSizeBytes in RemoteBlobBodyManifestRefV2.MIN_CHUNK_BYTES..
            RemoteBlobBodyManifestRefV2.MAX_CHUNK_BYTES)
        val dek = SodiumSyncPrimitives.randomBytes(KEY_BYTES)
        return try {
            val envelope = wrapDek(
                session = session,
                blobId = blob.blobId,
                keyEpoch = keyEpoch,
                dek = dek,
                previousEnvelopeHash = null,
            )
            BlobUploadIntentV2(
                transferKey = transferKey,
                manifestId = randomUuid(),
                blob = blob,
                keyEpoch = keyEpoch,
                chunkSizeBytes = chunkSizeBytes,
                dekEnvelope = envelope,
                createdAtEpochMillis = createdAtEpochMillis,
            )
        } finally {
            SodiumSyncPrimitives.destroy(dek)
        }
    }

    override suspend fun encryptChunk(
        session: SyncSession,
        intent: BlobUploadIntentV2,
        chunkIndex: Int,
        plaintext: ByteArray,
    ): EncryptedBlobChunkV2 {
        SodiumSyncPrimitives.initialize()
        require(chunkIndex >= 0 && plaintext.isNotEmpty() && plaintext.size <= intent.chunkSizeBytes)
        require(intent.blob.blobId == intent.dekEnvelope.blobId)
        val dek = unwrapDek(session, intent.dekEnvelope)
        return try {
            val nonce = deriveNonce(dek, intent.blob.blobId, intent.manifestId, "chunk:$chunkIndex")
            val aad = chunkAssociatedData(
                session = session,
                blobId = intent.blob.blobId,
                manifestId = intent.manifestId,
                chunkIndex = chunkIndex,
                plaintextBytes = plaintext.size,
            )
            val ciphertext = SodiumSyncPrimitives.aeadEncrypt(plaintext, dek, nonce, aad)
            val hash = SodiumSyncPrimitives.sha256(ciphertext)
            EncryptedBlobChunkV2(
                plan = EncryptedBlobChunkPlanV2(
                    index = chunkIndex,
                    ciphertextByteSize = ciphertext.size,
                    ciphertextSha256Base64Url = base64Url(hash),
                ),
                ciphertext = BinaryData.copyOf(ciphertext),
            ).also {
                SodiumSyncPrimitives.destroy(nonce)
                SodiumSyncPrimitives.destroy(aad)
                SodiumSyncPrimitives.destroy(ciphertext)
                SodiumSyncPrimitives.destroy(hash)
            }
        } finally {
            SodiumSyncPrimitives.destroy(dek)
        }
    }

    override suspend fun encryptPrivateManifest(
        session: SyncSession,
        intent: BlobUploadIntentV2,
        manifest: BlobPrivateManifestV2,
    ): EncryptedBlobPrivateManifestV2 {
        SodiumSyncPrimitives.initialize()
        require(manifest.blob == intent.blob) { "Private manifest does not match the upload intent" }
        val plaintext = json.encodeToString(BlobPrivateManifestV2.serializer(), manifest).encodeToByteArray()
        val dek = unwrapDek(session, intent.dekEnvelope)
        return try {
            val nonce = deriveNonce(dek, intent.blob.blobId, intent.manifestId, "private-manifest")
            val aad = manifestAssociatedData(session, intent.blob.blobId, intent.manifestId)
            val ciphertext = SodiumSyncPrimitives.aeadEncrypt(plaintext, dek, nonce, aad)
            val hash = SodiumSyncPrimitives.sha256(ciphertext)
            EncryptedBlobPrivateManifestV2(
                nonceBase64Url = base64Url(nonce),
                ciphertextBase64Url = base64Url(ciphertext),
                ciphertextSha256Base64Url = base64Url(hash),
                ciphertextByteSize = ciphertext.size,
            ).also {
                SodiumSyncPrimitives.destroy(nonce)
                SodiumSyncPrimitives.destroy(aad)
                SodiumSyncPrimitives.destroy(ciphertext)
                SodiumSyncPrimitives.destroy(hash)
            }
        } finally {
            SodiumSyncPrimitives.destroy(plaintext)
            SodiumSyncPrimitives.destroy(dek)
        }
    }

    override suspend fun decryptPrivateManifest(
        session: SyncSession,
        encrypted: EncryptedBlobPrivateManifestV2,
        envelope: BlobDekEnvelopeV2,
        manifestId: String,
    ): BlobPrivateManifestV2 {
        SodiumSyncPrimitives.initialize()
        val ciphertext = decodeBase64Url(encrypted.ciphertextBase64Url)
        val transportedNonce = decodeBase64Url(encrypted.nonceBase64Url)
        require(ciphertext.size == encrypted.ciphertextByteSize) { "Private blob manifest size mismatch" }
        val hash = SodiumSyncPrimitives.sha256(ciphertext)
        require(base64Url(hash) == encrypted.ciphertextSha256Base64Url) {
            "Private blob manifest ciphertext hash mismatch"
        }
        val dek = unwrapDek(session, envelope)
        return try {
            val expectedNonce = deriveNonce(dek, envelope.blobId, manifestId, "private-manifest")
            require(SodiumSyncPrimitives.constantTimeEquals(transportedNonce, expectedNonce)) {
                "Private blob manifest nonce mismatch"
            }
            val aad = manifestAssociatedData(session, envelope.blobId, manifestId)
            val plaintext = SodiumSyncPrimitives.aeadDecrypt(ciphertext, dek, expectedNonce, aad)
            try {
                val decoded = json.decodeFromString(BlobPrivateManifestV2.serializer(), plaintext.decodeToString())
                require(decoded.blob.blobId == envelope.blobId) { "Private blob manifest identity mismatch" }
                decoded
            } finally {
                SodiumSyncPrimitives.destroy(plaintext)
                SodiumSyncPrimitives.destroy(aad)
                SodiumSyncPrimitives.destroy(expectedNonce)
            }
        } finally {
            SodiumSyncPrimitives.destroy(ciphertext)
            SodiumSyncPrimitives.destroy(transportedNonce)
            SodiumSyncPrimitives.destroy(hash)
            SodiumSyncPrimitives.destroy(dek)
        }
    }

    override suspend fun decryptChunk(
        session: SyncSession,
        manifestId: String,
        envelope: BlobDekEnvelopeV2,
        chunk: EncryptedBlobChunkV2,
        expectedPlaintextBytes: Int,
    ): ByteArray {
        SodiumSyncPrimitives.initialize()
        require(expectedPlaintextBytes > 0)
        val ciphertext = chunk.ciphertext.copyBytes()
        var hash: ByteArray? = null
        var dek: ByteArray? = null
        var nonce: ByteArray? = null
        var aad: ByteArray? = null
        return try {
            require(ciphertext.size == chunk.plan.ciphertextByteSize)
            hash = SodiumSyncPrimitives.sha256(ciphertext)
            require(base64Url(requireNotNull(hash)) == chunk.plan.ciphertextSha256Base64Url) {
                "Blob chunk ciphertext hash mismatch"
            }
            dek = unwrapDek(session, envelope)
            nonce = deriveNonce(requireNotNull(dek), envelope.blobId, manifestId, "chunk:${chunk.plan.index}")
            aad = chunkAssociatedData(
                session = session,
                blobId = envelope.blobId,
                manifestId = manifestId,
                chunkIndex = chunk.plan.index,
                plaintextBytes = expectedPlaintextBytes,
            )
            val plaintext = SodiumSyncPrimitives.aeadDecrypt(
                ciphertext,
                requireNotNull(dek),
                requireNotNull(nonce),
                requireNotNull(aad),
            )
            if (plaintext.size != expectedPlaintextBytes) {
                SodiumSyncPrimitives.destroy(plaintext)
                throw IllegalArgumentException("Decrypted blob chunk size mismatch")
            }
            plaintext
        } finally {
            SodiumSyncPrimitives.destroy(ciphertext)
            hash?.let(SodiumSyncPrimitives::destroy)
            dek?.let(SodiumSyncPrimitives::destroy)
            nonce?.let(SodiumSyncPrimitives::destroy)
            aad?.let(SodiumSyncPrimitives::destroy)
        }
    }

    override suspend fun rewrapDek(
        session: SyncSession,
        previous: BlobDekEnvelopeV2,
        targetKeyEpoch: Int,
    ): BlobDekEnvelopeV2 {
        SodiumSyncPrimitives.initialize()
        require(targetKeyEpoch > previous.keyEpoch) { "A blob DEK re-wrap must advance the key epoch" }
        require(targetKeyEpoch == session.activeKeyEpoch) { "A blob DEK must be re-wrapped to the active epoch" }
        val dek = unwrapDek(session, previous)
        return try {
            wrapDek(
                session = session,
                blobId = previous.blobId,
                keyEpoch = targetKeyEpoch,
                dek = dek,
                previousEnvelopeHash = previous.envelopeSha256Base64Url,
            )
        } finally {
            SodiumSyncPrimitives.destroy(dek)
        }
    }

    private suspend fun wrapDek(
        session: SyncSession,
        blobId: String,
        keyEpoch: Int,
        dek: ByteArray,
        previousEnvelopeHash: String?,
    ): BlobDekEnvelopeV2 {
        val workspaceKey = copyRequiredSecret(SyncSecretKey.WorkspaceEpochKey(session.workspaceId, keyEpoch))
        return try {
            require(workspaceKey.size == KEY_BYTES) { "Workspace epoch key is corrupt" }
            val wrappingKey = deriveWrappingKey(workspaceKey, session.workspaceId, blobId, keyEpoch)
            val nonce = SodiumSyncPrimitives.randomBytes(NONCE_BYTES)
            val aad = envelopeAssociatedData(session, blobId, keyEpoch, previousEnvelopeHash)
            try {
                val wrapped = SodiumSyncPrimitives.aeadEncrypt(dek, wrappingKey, nonce, aad)
                val envelopeHash = hashEnvelope(
                    blobId = blobId,
                    keyEpoch = keyEpoch,
                    nonce = nonce,
                    wrappedDek = wrapped,
                    previousEnvelopeHash = previousEnvelopeHash,
                )
                BlobDekEnvelopeV2(
                    blobId = blobId,
                    keyEpoch = keyEpoch,
                    nonceBase64Url = base64Url(nonce),
                    wrappedDekBase64Url = base64Url(wrapped),
                    envelopeSha256Base64Url = base64Url(envelopeHash),
                    previousEnvelopeSha256Base64Url = previousEnvelopeHash,
                ).also {
                    SodiumSyncPrimitives.destroy(wrapped)
                    SodiumSyncPrimitives.destroy(envelopeHash)
                }
            } finally {
                SodiumSyncPrimitives.destroy(wrappingKey)
                SodiumSyncPrimitives.destroy(nonce)
                SodiumSyncPrimitives.destroy(aad)
            }
        } finally {
            SodiumSyncPrimitives.destroy(workspaceKey)
        }
    }

    private suspend fun unwrapDek(session: SyncSession, envelope: BlobDekEnvelopeV2): ByteArray {
        val nonce = decodeBase64Url(envelope.nonceBase64Url)
        val wrapped = decodeBase64Url(envelope.wrappedDekBase64Url)
        require(nonce.size == NONCE_BYTES && wrapped.size == KEY_BYTES + TAG_BYTES) {
            "Blob DEK envelope has invalid ciphertext geometry"
        }
        val actualHash = hashEnvelope(
            blobId = envelope.blobId,
            keyEpoch = envelope.keyEpoch,
            nonce = nonce,
            wrappedDek = wrapped,
            previousEnvelopeHash = envelope.previousEnvelopeSha256Base64Url,
        )
        require(base64Url(actualHash) == envelope.envelopeSha256Base64Url) { "Blob DEK envelope hash mismatch" }
        val workspaceKey = copyRequiredSecret(
            SyncSecretKey.WorkspaceEpochKey(session.workspaceId, envelope.keyEpoch),
        )
        return try {
            require(workspaceKey.size == KEY_BYTES) { "Workspace epoch key is corrupt" }
            val wrappingKey = deriveWrappingKey(workspaceKey, session.workspaceId, envelope.blobId, envelope.keyEpoch)
            val aad = envelopeAssociatedData(
                session,
                envelope.blobId,
                envelope.keyEpoch,
                envelope.previousEnvelopeSha256Base64Url,
            )
            try {
                SodiumSyncPrimitives.aeadDecrypt(wrapped, wrappingKey, nonce, aad).also {
                    require(it.size == KEY_BYTES) { "Blob DEK envelope did not contain a 256-bit key" }
                }
            } finally {
                SodiumSyncPrimitives.destroy(wrappingKey)
                SodiumSyncPrimitives.destroy(aad)
            }
        } finally {
            SodiumSyncPrimitives.destroy(nonce)
            SodiumSyncPrimitives.destroy(wrapped)
            SodiumSyncPrimitives.destroy(actualHash)
            SodiumSyncPrimitives.destroy(workspaceKey)
        }
    }

    private fun deriveWrappingKey(
        workspaceKey: ByteArray,
        workspaceId: String,
        blobId: String,
        keyEpoch: Int,
    ): ByteArray = SodiumSyncPrimitives.hkdfSha256(
        inputKeyMaterial = workspaceKey,
        salt = SodiumSyncPrimitives.sha256("workspace:$workspaceId:epoch:$keyEpoch".encodeToByteArray()),
        info = "shinsou-blob-dek-envelope-v2|$blobId".encodeToByteArray(),
        outputSize = KEY_BYTES,
    )

    private fun deriveNonce(
        dek: ByteArray,
        blobId: String,
        manifestId: String,
        purpose: String,
    ): ByteArray = SodiumSyncPrimitives.hkdfSha256(
        inputKeyMaterial = dek,
        salt = SodiumSyncPrimitives.sha256("shinsou-blob-nonce-v2|$blobId".encodeToByteArray()),
        info = "$manifestId|$purpose".encodeToByteArray(),
        outputSize = NONCE_BYTES,
    )

    private fun chunkAssociatedData(
        session: SyncSession,
        blobId: String,
        manifestId: String,
        chunkIndex: Int,
        plaintextBytes: Int,
    ): ByteArray = canonicalFields(
        "shinsou-blob-chunk-v2",
        session.instanceId,
        session.workspaceId,
        blobId,
        manifestId,
        chunkIndex.toString(),
        plaintextBytes.toString(),
    )

    private fun manifestAssociatedData(
        session: SyncSession,
        blobId: String,
        manifestId: String,
    ): ByteArray = canonicalFields(
        "shinsou-private-blob-manifest-v2",
        session.instanceId,
        session.workspaceId,
        blobId,
        manifestId,
        BLOB_BODY_SCHEMA_VERSION.toString(),
    )

    private fun envelopeAssociatedData(
        session: SyncSession,
        blobId: String,
        keyEpoch: Int,
        previousEnvelopeHash: String?,
    ): ByteArray = canonicalFields(
        "shinsou-blob-dek-envelope-v2",
        session.instanceId,
        session.workspaceId,
        blobId,
        keyEpoch.toString(),
        previousEnvelopeHash.orEmpty(),
    )

    private fun hashEnvelope(
        blobId: String,
        keyEpoch: Int,
        nonce: ByteArray,
        wrappedDek: ByteArray,
        previousEnvelopeHash: String?,
    ): ByteArray = SodiumSyncPrimitives.sha256(
        canonicalFields(
            "shinsou-blob-dek-envelope-hash-v2",
            blobId,
            keyEpoch.toString(),
            base64Url(nonce),
            base64Url(wrappedDek),
            previousEnvelopeHash.orEmpty(),
        ),
    )

    private suspend fun copyRequiredSecret(key: SyncSecretKey): ByteArray {
        val material: SecretMaterial = secretStore.requireSecret(key)
        var result: ByteArray? = null
        material.useBytes { result = it.copyOf() }
        return requireNotNull(result)
    }

    private fun base64Url(value: ByteArray): String = SodiumSyncPrimitives.base64UrlEncode(value)

    private fun decodeBase64Url(value: String): ByteArray = SodiumSyncPrimitives.base64UrlDecode(value)

    private fun randomUuid(): String {
        val bytes = SodiumSyncPrimitives.randomBytes(16)
        try {
            bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
            bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
            val hex = bytes.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
            return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
                "${hex.substring(16, 20)}-${hex.substring(20)}"
        } finally {
            SodiumSyncPrimitives.destroy(bytes)
        }
    }

    private fun canonicalFields(vararg values: String): ByteArray {
        val output = ArrayList<Byte>()
        values.forEach { value ->
            val bytes = value.encodeToByteArray()
            val size = bytes.size
            output += (size ushr 24).toByte()
            output += (size ushr 16).toByte()
            output += (size ushr 8).toByte()
            output += size.toByte()
            bytes.forEach(output::add)
        }
        return output.toByteArray()
    }

    private companion object {
        const val KEY_BYTES: Int = 32
        const val NONCE_BYTES: Int = 12
        const val TAG_BYTES: Int = 16
    }
}

private val BlobBodyCryptoJson = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
    allowStructuredMapKeys = true
}
