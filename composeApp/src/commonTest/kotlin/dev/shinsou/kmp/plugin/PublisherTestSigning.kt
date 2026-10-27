package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.sync.crypto.SodiumKeyPair
import dev.shinsou.kmp.sync.crypto.SodiumSyncPrimitives
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Test publisher: signs `shinsou-signed-envelope-v1` documents the way a publisher's tooling does. */
internal fun publisherRoot(key: SodiumKeyPair) = RepositoryTrustRoot(
    SodiumSyncPrimitives.base64UrlEncode(key.publicKey),
    PublisherFingerprint.of(key.publicKey),
)

internal suspend fun signedPublisherEnvelope(
    key: SodiumKeyPair,
    repository: String,
    type: RepositorySignedDocumentType,
    documentId: String,
    sequence: Long,
    payload: JsonElement,
): JsonObject {
    val root = publisherRoot(key)
    val signature = SodiumSyncPrimitives.signEd25519(
        RepositorySignedDocumentVerifier.signingMessage(repository, type, documentId, sequence, root.fingerprint, payload),
        key.privateKey,
    )
    return buildJsonObject {
        put("format", RepositorySignedDocumentVerifier.ENVELOPE_FORMAT)
        put("algorithm", "Ed25519")
        put("keyFingerprint", root.fingerprint)
        put("publicKey", root.publicKeyBase64Url)
        put("payloadType", type.wireValue)
        put("sequence", sequence)
        put("payload", payload)
        put("signature", SodiumSyncPrimitives.base64UrlEncode(signature))
    }
}

/** A `publisher-revocations` document: what a publisher must publish before anything of it installs. */
internal suspend fun signedRevocations(
    key: SodiumKeyPair,
    repository: String,
    publisherId: String,
    artifacts: List<String> = emptyList(),
    expiresAt: Long,
    sequence: Long = 1,
): JsonObject = signedPublisherEnvelope(
    key, repository, RepositorySignedDocumentType.REVOCATIONS, PublisherRevocationStore.REVOCATIONS_DOCUMENT_ID, sequence,
    buildJsonObject {
        put("publisherId", publisherId)
        put("revokedArtifacts", JsonArray(artifacts.map(::JsonPrimitive)))
        put("expiresAt", expiresAt)
    },
)
