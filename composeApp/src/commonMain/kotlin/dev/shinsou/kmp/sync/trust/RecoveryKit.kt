package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.crypto.DeterministicCbor
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import dev.shinsou.kmp.sync.crypto.SodiumWrappedKey
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import dev.shinsou.kmp.sync.v2.SecretMaterial
import dev.shinsou.kmp.sync.v2.SyncSecretKey
import dev.shinsou.kmp.sync.v2.SyncSecretReadResult
import dev.shinsou.kmp.sync.v2.SyncSecretStore
import dev.shinsou.kmp.sync.v2.isAllowedSyncEndpoint
import dev.shinsou.kmp.sync.v2.requireSecret
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class RecoveryKitPublicMetadata(
    val endpoint: String,
    val instanceId: String,
    val userId: String,
    val recoverySigningPublicKey: String,
    val recoveryWrappingPublicKey: String,
    val createdAt: Long,
)

/** [exportedKit] is redacted secret material suitable for a QR/print export callback. */
data class GeneratedRecoveryKit(
    val metadata: RecoveryKitPublicMetadata,
    val exportedKit: SecretMaterial,
)

sealed class RecoveryKitException(message: String, cause: Throwable? = null) : IllegalStateException(message, cause) {
    class Malformed(message: String, cause: Throwable? = null) : RecoveryKitException(message, cause)
    class KeyMismatch(message: String) : RecoveryKitException(message)
    class AlreadyInstalled : RecoveryKitException("Recovery credentials already exist in the strict secret store")
    class ReplacementAlreadyStaged : RecoveryKitException("A pending Recovery Kit replacement already exists")
    class Storage(message: String, cause: Throwable? = null) : RecoveryKitException(message, cause)
}

/**
 * Versioned Recovery Kit export/import. Private keys are never returned as ordinary DTO fields;
 * the only durable destination accepted by this class is [SyncSecretStore].
 */
class RecoveryKitManager(
    private val secretStore: SyncSecretStore,
    private val json: Json = Json.Default,
) {
    suspend fun generateAndInstall(
        endpoint: String,
        instanceId: String,
        userId: String,
        createdAt: Long,
        replaceExisting: Boolean = false,
    ): GeneratedRecoveryKit {
        SodiumSyncPrimitives.initialize()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        return try {
            val metadata = RecoveryKitPublicMetadata(
                endpoint = endpoint,
                instanceId = instanceId,
                userId = userId,
                recoverySigningPublicKey = encodeBase64Url(signing.publicKey),
                recoveryWrappingPublicKey = encodeBase64Url(wrapping.publicKey),
                createdAt = createdAt,
            ).also(::validateMetadata)
            val exported = encodeExport(metadata, signing.privateKey, wrapping.privateKey)
            installPrivateKeys(signing.privateKey, wrapping.privateKey, replaceExisting)
            GeneratedRecoveryKit(metadata, exported)
        } finally {
            SodiumSyncPrimitives.destroy(signing.privateKey)
            SodiumSyncPrimitives.destroy(wrapping.privateKey)
        }
    }

    suspend fun exportInstalled(metadata: RecoveryKitPublicMetadata): SecretMaterial {
        return exportFromStore(
            metadata,
            SyncSecretKey.RecoverySigningPrivateKey,
            SyncSecretKey.RecoveryWrappingPrivateKey,
        )
    }

    suspend fun exportStagedReplacement(metadata: RecoveryKitPublicMetadata): SecretMaterial {
        return exportFromStore(
            metadata,
            SyncSecretKey.PendingRecoverySigningPrivateKey,
            SyncSecretKey.PendingRecoveryWrappingPrivateKey,
        )
    }

    private suspend fun exportFromStore(
        metadata: RecoveryKitPublicMetadata,
        signingKey: SyncSecretKey,
        wrappingKey: SyncSecretKey,
    ): SecretMaterial {
        validateMetadata(metadata)
        SodiumSyncPrimitives.initialize()
        val signing = copySecret(secretStore.requireSecret(signingKey))
        val wrapping = copySecret(secretStore.requireSecret(wrappingKey))
        return try {
            encodeExport(metadata, signing, wrapping)
        } finally {
            SodiumSyncPrimitives.destroy(signing)
            SodiumSyncPrimitives.destroy(wrapping)
        }
    }

    /**
     * Generates the new keys named in a recovery claim without replacing the old signing key.
     * Call [activateStagedReplacement] only after `/v1/recovery/claim` commits successfully.
     */
    suspend fun stageReplacement(
        endpoint: String,
        instanceId: String,
        userId: String,
        createdAt: Long,
        replacePending: Boolean = false,
    ): GeneratedRecoveryKit {
        SodiumSyncPrimitives.initialize()
        val signing = SodiumSyncPrimitives.generateEd25519KeyPair()
        val wrapping = SodiumSyncPrimitives.generateX25519KeyPair()
        return try {
            val metadata = RecoveryKitPublicMetadata(
                endpoint = endpoint,
                instanceId = instanceId,
                userId = userId,
                recoverySigningPublicKey = encodeBase64Url(signing.publicKey),
                recoveryWrappingPublicKey = encodeBase64Url(wrapping.publicKey),
                createdAt = createdAt,
            ).also(::validateMetadata)
            val exported = encodeExport(metadata, signing.privateKey, wrapping.privateKey)
            installPrivateKeys(
                signingPrivateKey = signing.privateKey,
                wrappingPrivateKey = wrapping.privateKey,
                replaceExisting = replacePending,
                signingKey = SyncSecretKey.PendingRecoverySigningPrivateKey,
                wrappingKey = SyncSecretKey.PendingRecoveryWrappingPrivateKey,
                alreadyExists = { RecoveryKitException.ReplacementAlreadyStaged() },
            )
            GeneratedRecoveryKit(metadata, exported)
        } finally {
            SodiumSyncPrimitives.destroy(signing.privateKey)
            SodiumSyncPrimitives.destroy(wrapping.privateKey)
        }
    }

    suspend fun activateStagedReplacement() {
        val pendingSigning = secretSnapshot(SyncSecretKey.PendingRecoverySigningPrivateKey)
        val pendingWrapping = secretSnapshot(SyncSecretKey.PendingRecoveryWrappingPrivateKey)
        if (pendingSigning == null && pendingWrapping == null) return
        if (pendingSigning == null) {
            val current = secretSnapshot(SyncSecretKey.RecoveryWrappingPrivateKey)
            if (current == pendingWrapping) {
                secretStore.delete(SyncSecretKey.PendingRecoveryWrappingPrivateKey)
                return
            }
            throw RecoveryKitException.Storage("Pending Recovery Kit is only partially present")
        }
        if (pendingWrapping == null) {
            val current = secretSnapshot(SyncSecretKey.RecoverySigningPrivateKey)
            if (current == pendingSigning) {
                secretStore.delete(SyncSecretKey.PendingRecoverySigningPrivateKey)
                return
            }
            throw RecoveryKitException.Storage("Pending Recovery Kit is only partially present")
        }
        val signingBytes = copySecret(pendingSigning)
        val wrappingBytes = copySecret(pendingWrapping)
        try {
            installPrivateKeys(signingBytes, wrappingBytes, replaceExisting = true)
            // A cleanup failure leaves only an inert duplicate; retrying activation is safe.
            secretStore.delete(SyncSecretKey.PendingRecoverySigningPrivateKey)
            secretStore.delete(SyncSecretKey.PendingRecoveryWrappingPrivateKey)
        } catch (error: RecoveryKitException) {
            throw error
        } catch (error: Throwable) {
            throw RecoveryKitException.Storage("Unable to activate the staged Recovery Kit", error)
        } finally {
            SodiumSyncPrimitives.destroy(signingBytes)
            SodiumSyncPrimitives.destroy(wrappingBytes)
        }
    }

    suspend fun discardStagedReplacement() {
        try {
            secretStore.delete(SyncSecretKey.PendingRecoverySigningPrivateKey)
            secretStore.delete(SyncSecretKey.PendingRecoveryWrappingPrivateKey)
        } catch (error: Throwable) {
            throw RecoveryKitException.Storage("Unable to discard the staged Recovery Kit", error)
        }
    }

    /** Parses, validates, and writes both private keys directly to the strict secret store. */
    suspend fun importAndInstall(
        exportedKit: SecretMaterial,
        replaceExisting: Boolean = false,
    ): RecoveryKitPublicMetadata {
        SodiumSyncPrimitives.initialize()
        val decoded = decodeExport(exportedKit)
        return try {
            installPrivateKeys(decoded.signingPrivateKey, decoded.wrappingPrivateKey, replaceExisting)
            decoded.metadata
        } finally {
            SodiumSyncPrimitives.destroy(decoded.signingPrivateKey)
            SodiumSyncPrimitives.destroy(decoded.wrappingPrivateKey)
        }
    }

    /** Signs the exact sanitized canonical JSON accepted by `/v1/recovery/claim`. */
    suspend fun signRecoveryClaimManifest(canonicalManifestJson: String): String {
        return signCanonicalManifest(
            canonicalManifestJson = canonicalManifestJson,
            signingKey = SyncSecretKey.RecoverySigningPrivateKey,
            domain = RECOVERY_CLAIM_DOMAIN,
            maximumBytes = MAX_RECOVERY_CLAIM_BYTES,
            label = "Recovery claim",
        )
    }

    /**
     * Co-signs the first device identity. This is the offline trust edge that lets a fresh
     * Recovery install authenticate historical events from the now-revoked initial device.
     */
    suspend fun signInitialDeviceTrustManifest(canonicalManifestJson: String): String {
        return signCanonicalManifest(
            canonicalManifestJson = canonicalManifestJson,
            signingKey = SyncSecretKey.RecoverySigningPrivateKey,
            domain = INITIAL_DEVICE_RECOVERY_TRUST_DOMAIN,
            maximumBytes = MAX_RECOVERY_TRUST_MANIFEST_BYTES,
            label = "Initial device recovery trust",
        )
    }

    /**
     * Co-signs the predecessor root and recovered device with the staged successor root. The
     * resulting bidirectional lineage can be traversed backwards after any number of recoveries.
     */
    suspend fun signStagedRecoveryLineageManifest(canonicalManifestJson: String): String {
        return signCanonicalManifest(
            canonicalManifestJson = canonicalManifestJson,
            signingKey = SyncSecretKey.PendingRecoverySigningPrivateKey,
            domain = RECOVERY_LINEAGE_DOMAIN,
            maximumBytes = MAX_RECOVERY_TRUST_MANIFEST_BYTES,
            label = "Recovery lineage",
        )
    }

    private suspend fun signCanonicalManifest(
        canonicalManifestJson: String,
        signingKey: SyncSecretKey,
        domain: ByteArray,
        maximumBytes: Int,
        label: String,
    ): String {
        if (canonicalManifestJson.encodeToByteArray().size > maximumBytes) {
            throw RecoveryKitException.Malformed("$label manifest exceeds its size limit")
        }
        val parsed = try {
            json.parseToJsonElement(canonicalManifestJson)
        } catch (error: Throwable) {
            throw RecoveryKitException.Malformed("$label manifest is malformed", error)
        }
        if (canonicalSyncJson(parsed) != canonicalManifestJson) {
            throw RecoveryKitException.Malformed("$label manifest is not canonical JSON")
        }
        SodiumSyncPrimitives.initialize()
        val privateKey = copySecret(secretStore.requireSecret(signingKey))
        return try {
            encodeBase64Url(
                SodiumSyncPrimitives.signEd25519(
                    domain + canonicalManifestJson.encodeToByteArray(),
                    privateKey,
                ),
            )
        } finally {
            SodiumSyncPrimitives.destroy(privateKey)
        }
    }

    /** Unwraps and commitment-checks the active workspace key returned by a recovery challenge. */
    suspend fun unwrapRecoveryWorkspaceKey(
        recoveryWrappedKey: String,
        expectedKeyCommitment: String,
    ): SecretMaterial {
        if (!expectedKeyCommitment.isCanonicalSha256()) {
            throw RecoveryKitException.Malformed("Workspace key commitment is malformed")
        }
        val wrappedBytes = try {
            decodeBase64Url(recoveryWrappedKey)
        } catch (error: Throwable) {
            throw RecoveryKitException.Malformed("Recovery workspace envelope is not canonical base64url", error)
        }
        if (wrappedBytes.size !in MIN_WRAPPED_KEY_BYTES..MAX_WRAPPED_KEY_BYTES) {
            throw RecoveryKitException.Malformed("Recovery workspace envelope has an invalid size")
        }
        val envelope = decodeWrappedKey(wrappedBytes)
        SodiumSyncPrimitives.initialize()
        val privateKey = copySecret(secretStore.requireSecret(SyncSecretKey.RecoveryWrappingPrivateKey))
        val publicKey = try {
            SodiumSyncPrimitives.x25519PublicKey(privateKey)
        } catch (error: Throwable) {
            SodiumSyncPrimitives.destroy(privateKey)
            throw RecoveryKitException.KeyMismatch("Installed recovery wrapping key is invalid")
        }
        val plaintext = try {
            SodiumSyncPrimitives.unwrapKey(envelope, privateKey, publicKey, WORKSPACE_KEY_WRAP_CONTEXT)
        } catch (error: Throwable) {
            throw RecoveryKitException.KeyMismatch("Recovery workspace envelope could not be authenticated")
        } finally {
            SodiumSyncPrimitives.destroy(privateKey)
            SodiumSyncPrimitives.destroy(publicKey)
        }
        try {
            if (plaintext.size != WORKSPACE_KEY_BYTES) {
                throw RecoveryKitException.KeyMismatch("Recovered workspace key has an invalid size")
            }
            val commitment = encodeBase64Url(
                SodiumSyncPrimitives.sha256(WORKSPACE_KEY_COMMITMENT_DOMAIN + plaintext),
            )
            if (commitment != expectedKeyCommitment) {
                throw RecoveryKitException.KeyMismatch("Recovered workspace key does not match its commitment")
            }
            return SecretMaterial(plaintext.asList())
        } finally {
            SodiumSyncPrimitives.destroy(plaintext)
        }
    }

    private fun encodeExport(
        metadata: RecoveryKitPublicMetadata,
        signingPrivateKey: ByteArray,
        wrappingPrivateKey: ByteArray,
    ): SecretMaterial {
        validatePrivateKeyPairs(metadata, signingPrivateKey, wrappingPrivateKey)
        val proof = SodiumSyncPrimitives.signEd25519(
            RECOVERY_KIT_DOMAIN + canonicalSyncJson(JsonObject(publicManifest(metadata))).encodeToByteArray(),
            signingPrivateKey,
        )
        val fields = publicManifest(metadata).toMutableMap()
        fields["recoverySigningPrivateKey"] = JsonPrimitive(encodeBase64Url(signingPrivateKey))
        fields["recoveryWrappingPrivateKey"] = JsonPrimitive(encodeBase64Url(wrappingPrivateKey))
        fields["proofSignature"] = JsonPrimitive(encodeBase64Url(proof))
        val document = JsonObject(fields)
        val body = encodeBase64Url(canonicalSyncJson(document).encodeToByteArray())
        val bytes = (RECOVERY_KIT_PREFIX + body).encodeToByteArray()
        return try {
            if (bytes.size > MAX_RECOVERY_KIT_BYTES) {
                throw RecoveryKitException.Malformed("Recovery Kit exceeds its size limit")
            }
            SecretMaterial(bytes.asList())
        } finally {
            bytes.fill(0)
            proof.fill(0)
        }
    }

    private fun decodeExport(exportedKit: SecretMaterial): DecodedRecoveryKit {
        var encodedBytes: ByteArray? = null
        exportedKit.useBytes { encodedBytes = it.copyOf() }
        val bytes = requireNotNull(encodedBytes)
        try {
            if (bytes.size !in MIN_RECOVERY_KIT_BYTES..MAX_RECOVERY_KIT_BYTES) {
                throw RecoveryKitException.Malformed("Recovery Kit has an invalid size")
            }
            val encoded = try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (error: Throwable) {
                throw RecoveryKitException.Malformed("Recovery Kit is not valid UTF-8", error)
            }
            if (!encoded.startsWith(RECOVERY_KIT_PREFIX)) {
                throw RecoveryKitException.Malformed("Recovery Kit has an unsupported prefix")
            }
            val bodyBytes = try {
                decodeBase64Url(encoded.removePrefix(RECOVERY_KIT_PREFIX))
            } catch (error: Throwable) {
                throw RecoveryKitException.Malformed("Recovery Kit body is not canonical base64url", error)
            }
            val body = try {
                bodyBytes.decodeToString(throwOnInvalidSequence = true)
            } catch (error: Throwable) {
                throw RecoveryKitException.Malformed("Recovery Kit body is not valid UTF-8", error)
            } finally {
                bodyBytes.fill(0)
            }
            val document = try {
                json.parseToJsonElement(body) as? JsonObject
                    ?: throw RecoveryKitException.Malformed("Recovery Kit body is not an object")
            } catch (error: RecoveryKitException) {
                throw error
            } catch (error: Throwable) {
                throw RecoveryKitException.Malformed("Recovery Kit body is malformed", error)
            }
            if (canonicalSyncJson(document) != body || document.keys != RECOVERY_KIT_FIELDS) {
                throw RecoveryKitException.Malformed("Recovery Kit body is not canonical or has unknown fields")
            }
            val metadata = metadataFrom(document).also(::validateMetadata)
            val signingPrivateKey = decodeExact(
                document.requiredString("recoverySigningPrivateKey"),
                ED25519_PRIVATE_KEY_BYTES,
                "recovery signing private key",
            )
            val wrappingPrivateKey = decodeExact(
                document.requiredString("recoveryWrappingPrivateKey"),
                X25519_PRIVATE_KEY_BYTES,
                "recovery wrapping private key",
            )
            try {
                validatePrivateKeyPairs(metadata, signingPrivateKey, wrappingPrivateKey)
                val proof = decodeExact(
                    document.requiredString("proofSignature"),
                    ED25519_SIGNATURE_BYTES,
                    "Recovery Kit proof",
                )
                val signingPublicKey = decodeExact(
                    metadata.recoverySigningPublicKey,
                    PUBLIC_KEY_BYTES,
                    "recovery signing public key",
                )
                val valid = try {
                    SodiumSyncPrimitives.verifyEd25519(
                        RECOVERY_KIT_DOMAIN +
                            canonicalSyncJson(JsonObject(publicManifest(metadata))).encodeToByteArray(),
                        proof,
                        signingPublicKey,
                    )
                } finally {
                    proof.fill(0)
                    signingPublicKey.fill(0)
                }
                if (!valid) throw RecoveryKitException.KeyMismatch("Recovery Kit proof signature is invalid")
                return DecodedRecoveryKit(metadata, signingPrivateKey, wrappingPrivateKey)
            } catch (error: Throwable) {
                signingPrivateKey.fill(0)
                wrappingPrivateKey.fill(0)
                throw error
            }
        } finally {
            bytes.fill(0)
        }
    }

    private fun validatePrivateKeyPairs(
        metadata: RecoveryKitPublicMetadata,
        signingPrivateKey: ByteArray,
        wrappingPrivateKey: ByteArray,
    ) {
        if (signingPrivateKey.size != ED25519_PRIVATE_KEY_BYTES ||
            wrappingPrivateKey.size != X25519_PRIVATE_KEY_BYTES
        ) throw RecoveryKitException.KeyMismatch("Recovery Kit private key size is invalid")
        val signingPublicKey = decodeExact(
            metadata.recoverySigningPublicKey,
            PUBLIC_KEY_BYTES,
            "recovery signing public key",
        )
        val challenge = RECOVERY_KEY_PAIR_PROOF
        val proof = SodiumSyncPrimitives.signEd25519(challenge, signingPrivateKey)
        try {
            if (!SodiumSyncPrimitives.verifyEd25519(challenge, proof, signingPublicKey)) {
                throw RecoveryKitException.KeyMismatch("Recovery signing private key does not match its public key")
            }
        } finally {
            proof.fill(0)
            signingPublicKey.fill(0)
        }
        val derivedWrapping = SodiumSyncPrimitives.x25519PublicKey(wrappingPrivateKey)
        val expectedWrapping = decodeExact(
            metadata.recoveryWrappingPublicKey,
            PUBLIC_KEY_BYTES,
            "recovery wrapping public key",
        )
        try {
            if (!SodiumSyncPrimitives.constantTimeEquals(derivedWrapping, expectedWrapping)) {
                throw RecoveryKitException.KeyMismatch("Recovery wrapping private key does not match its public key")
            }
        } finally {
            derivedWrapping.fill(0)
            expectedWrapping.fill(0)
        }
    }

    private suspend fun installPrivateKeys(
        signingPrivateKey: ByteArray,
        wrappingPrivateKey: ByteArray,
        replaceExisting: Boolean,
        signingKey: SyncSecretKey = SyncSecretKey.RecoverySigningPrivateKey,
        wrappingKey: SyncSecretKey = SyncSecretKey.RecoveryWrappingPrivateKey,
        alreadyExists: () -> RecoveryKitException = { RecoveryKitException.AlreadyInstalled() },
    ) {
        val oldSigning = secretSnapshot(signingKey)
        val oldWrapping = secretSnapshot(wrappingKey)
        if (!replaceExisting && (oldSigning != null || oldWrapping != null)) throw alreadyExists()
        val signing = SecretMaterial(signingPrivateKey.asList())
        val wrapping = SecretMaterial(wrappingPrivateKey.asList())
        try {
            secretStore.write(signingKey, signing)
            secretStore.write(wrappingKey, wrapping)
            if (secretSnapshot(signingKey) != signing || secretSnapshot(wrappingKey) != wrapping) {
                throw RecoveryKitException.Storage("Recovery private key read-back verification failed")
            }
        } catch (error: Throwable) {
            val rollbackError = runCatching {
                restoreSecret(signingKey, oldSigning)
                restoreSecret(wrappingKey, oldWrapping)
            }.exceptionOrNull()
            if (rollbackError != null) {
                throw RecoveryKitException.Storage(
                    "Recovery private key installation failed and rollback was incomplete",
                    rollbackError,
                )
            }
            if (error is RecoveryKitException) throw error
            throw RecoveryKitException.Storage("Recovery private key installation failed", error)
        }
    }

    private suspend fun secretSnapshot(key: SyncSecretKey): SecretMaterial? = when (val result = secretStore.read(key)) {
        is SyncSecretReadResult.Available -> result.material
        SyncSecretReadResult.Missing -> null
        is SyncSecretReadResult.Unavailable -> throw RecoveryKitException.Storage(
            "Strict secret store is unavailable while reading ${key.redactedName}",
        )
        is SyncSecretReadResult.Corrupt -> throw RecoveryKitException.Storage(
            "Strict secret store reports corrupt ${key.redactedName}",
        )
    }

    private suspend fun restoreSecret(key: SyncSecretKey, old: SecretMaterial?) {
        if (old == null) secretStore.delete(key) else secretStore.write(key, old)
    }
}

private data class DecodedRecoveryKit(
    val metadata: RecoveryKitPublicMetadata,
    val signingPrivateKey: ByteArray,
    val wrappingPrivateKey: ByteArray,
)

private fun publicManifest(metadata: RecoveryKitPublicMetadata): Map<String, kotlinx.serialization.json.JsonElement> = mapOf(
    "format" to JsonPrimitive(RECOVERY_KIT_FORMAT),
    "version" to JsonPrimitive(RECOVERY_KIT_VERSION),
    "endpoint" to JsonPrimitive(metadata.endpoint),
    "instanceId" to JsonPrimitive(metadata.instanceId),
    "userId" to JsonPrimitive(metadata.userId),
    "recoverySigningPublicKey" to JsonPrimitive(metadata.recoverySigningPublicKey),
    "recoveryWrappingPublicKey" to JsonPrimitive(metadata.recoveryWrappingPublicKey),
    "createdAt" to JsonPrimitive(metadata.createdAt),
)

private fun metadataFrom(document: JsonObject): RecoveryKitPublicMetadata {
    if (document.requiredString("format") != RECOVERY_KIT_FORMAT ||
        document.requiredLong("version") != RECOVERY_KIT_VERSION.toLong()
    ) throw RecoveryKitException.Malformed("Recovery Kit format or version is unsupported")
    return RecoveryKitPublicMetadata(
        endpoint = document.requiredString("endpoint"),
        instanceId = document.requiredString("instanceId"),
        userId = document.requiredString("userId"),
        recoverySigningPublicKey = document.requiredString("recoverySigningPublicKey"),
        recoveryWrappingPublicKey = document.requiredString("recoveryWrappingPublicKey"),
        createdAt = document.requiredLong("createdAt"),
    )
}

private fun validateMetadata(metadata: RecoveryKitPublicMetadata) {
    if (!isAllowedSyncEndpoint(metadata.endpoint) || metadata.endpoint.length > MAX_ENDPOINT_CHARS ||
        metadata.endpoint.any(Char::isWhitespace)
    ) throw RecoveryKitException.Malformed("Recovery Kit endpoint is invalid")
    if (!RECOVERY_UUID_PATTERN.matches(metadata.instanceId) || !RECOVERY_UUID_PATTERN.matches(metadata.userId)) {
        throw RecoveryKitException.Malformed("Recovery Kit identity is invalid")
    }
    if (metadata.createdAt < 0 || metadata.createdAt > MAX_SAFE_JSON_INTEGER) {
        throw RecoveryKitException.Malformed("Recovery Kit timestamp is invalid")
    }
    decodeExact(metadata.recoverySigningPublicKey, PUBLIC_KEY_BYTES, "recovery signing public key").fill(0)
    decodeExact(metadata.recoveryWrappingPublicKey, PUBLIC_KEY_BYTES, "recovery wrapping public key").fill(0)
}

private fun decodeWrappedKey(bytes: ByteArray): SodiumWrappedKey {
    val objectValue = try {
        DeterministicCbor.decode(bytes) as? JsonObject
            ?: throw RecoveryKitException.Malformed("Recovery workspace envelope is not a CBOR map")
    } catch (error: RecoveryKitException) {
        throw error
    } catch (error: Throwable) {
        throw RecoveryKitException.Malformed("Recovery workspace envelope is not deterministic CBOR", error)
    }
    if (objectValue.keys != WRAPPED_KEY_FIELDS ||
        objectValue.requiredString("cipherSuite") != "X25519_HKDF_SHA256_CHACHA20_POLY1305"
    ) throw RecoveryKitException.Malformed("Recovery workspace envelope suite or fields are invalid")
    return SodiumWrappedKey(
        ephemeralPublicKey = decodeExact(
            objectValue.requiredString("ephemeralPublicKey"),
            PUBLIC_KEY_BYTES,
            "ephemeral wrapping key",
        ),
        nonce = decodeExact(objectValue.requiredString("nonce"), AEAD_NONCE_BYTES, "workspace envelope nonce"),
        ciphertext = decodeRange(
            objectValue.requiredString("ciphertext"),
            AEAD_TAG_BYTES,
            WORKSPACE_KEY_BYTES + AEAD_TAG_BYTES,
            "workspace envelope ciphertext",
        ),
    )
}

private fun JsonObject.requiredString(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        ?: throw RecoveryKitException.Malformed("Recovery Kit field '$name' is not a string")

private fun JsonObject.requiredLong(name: String): Long =
    (this[name] as? JsonPrimitive)?.takeUnless { it.isString }?.content?.toLongOrNull()
        ?: throw RecoveryKitException.Malformed("Recovery Kit field '$name' is not an integer")

private fun decodeExact(value: String, size: Int, label: String): ByteArray =
    decodeRange(value, size, size, label)

private fun decodeRange(value: String, minimum: Int, maximum: Int, label: String): ByteArray {
    val decoded = try {
        decodeBase64Url(value)
    } catch (error: Throwable) {
        throw RecoveryKitException.Malformed("$label is not canonical base64url", error)
    }
    if (decoded.size !in minimum..maximum) {
        decoded.fill(0)
        throw RecoveryKitException.Malformed("$label has an invalid size")
    }
    return decoded
}

private fun copySecret(material: SecretMaterial): ByteArray {
    var copy: ByteArray? = null
    material.useBytes { copy = it.copyOf() }
    return requireNotNull(copy)
}

private const val RECOVERY_KIT_FORMAT = "shinsou-recovery-kit"
private const val RECOVERY_KIT_VERSION = 1
private const val RECOVERY_KIT_PREFIX = "shinsou-recovery-v1."
private const val MIN_RECOVERY_KIT_BYTES = 256
private const val MAX_RECOVERY_KIT_BYTES = 16 * 1024
private const val MAX_RECOVERY_CLAIM_BYTES = 256 * 1024
private const val MAX_RECOVERY_TRUST_MANIFEST_BYTES = 16 * 1024
private const val MAX_ENDPOINT_CHARS = 2_048
private const val PUBLIC_KEY_BYTES = 32
private const val ED25519_PRIVATE_KEY_BYTES = 64
private const val ED25519_SIGNATURE_BYTES = 64
private const val X25519_PRIVATE_KEY_BYTES = 32
private const val AEAD_NONCE_BYTES = 12
private const val AEAD_TAG_BYTES = 16
private const val WORKSPACE_KEY_BYTES = 32
private const val MIN_WRAPPED_KEY_BYTES = 64
private const val MAX_WRAPPED_KEY_BYTES = 16 * 1024
private const val MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L

private val RECOVERY_KIT_DOMAIN = "shinsou:recovery-kit:v1\u0000".encodeToByteArray()
private val RECOVERY_CLAIM_DOMAIN = "shinsou:recovery-claim:v1\u0000".encodeToByteArray()
private val INITIAL_DEVICE_RECOVERY_TRUST_DOMAIN =
    "shinsou:initial-device-recovery-trust:v1\u0000".encodeToByteArray()
private val RECOVERY_LINEAGE_DOMAIN = "shinsou:recovery-lineage:v1\u0000".encodeToByteArray()
private val RECOVERY_KEY_PAIR_PROOF = "shinsou:recovery-kit-key-pair:v1\u0000".encodeToByteArray()
private val WORKSPACE_KEY_WRAP_CONTEXT = "shinsou:workspace-key-envelope:v1".encodeToByteArray()
private val WORKSPACE_KEY_COMMITMENT_DOMAIN = "shinsou:workspace-key-commitment:v1\u0000".encodeToByteArray()
private val RECOVERY_UUID_PATTERN = Regex(
    "^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$",
)
private val RECOVERY_KIT_FIELDS = setOf(
    "format", "version", "endpoint", "instanceId", "userId", "recoverySigningPublicKey",
    "recoveryWrappingPublicKey", "createdAt", "recoverySigningPrivateKey",
    "recoveryWrappingPrivateKey", "proofSignature",
)
private val WRAPPED_KEY_FIELDS = setOf("cipherSuite", "ephemeralPublicKey", "nonce", "ciphertext")
