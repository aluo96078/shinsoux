package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.blob.BlobPublishReceipt
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.content.blob.ContentBlobStage
import dev.shinsou.kmp.content.blob.ContentBlobStore
import dev.shinsou.kmp.content.blob.ContentBlobStoreException
import dev.shinsou.kmp.content.blob.PendingBlob
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.EXTENSION_CONTENT_NAMESPACE
import dev.shinsou.kmp.domain.model.Rfc9562UuidV5
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.framedIdentity
import dev.shinsou.kmp.plugin.network.PluginHttpRequest
import dev.shinsou.kmp.plugin.network.PluginNetworkClient
import dev.shinsou.kmp.plugin.network.PLUGIN_NETWORK_MAX_RESPONSE_BYTES
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink
import dev.shinsou.kmp.plugin.network.TypedReaderRemoteAssetScope

// R4-4 (docs/architecture/0006): moved out of ExtensionContentConsumerV2.kt without changing it.

/**
 * Production fetcher for legacy-backed v2 sources. Native packages must provide an explicit scope
 * resolver; an opaque SourceKey is never hashed or narrowed into a Long.
 */
internal class PluginNetworkExtensionResourceFetcherV2(
    private val contentScope: suspend (SourceKey) -> dev.shinsou.kmp.plugin.network.TypedReaderRemoteAssetScope?,
) : ExtensionResourceFetcherV2 {
    /** Compatibility constructor. Its unscoped client is no longer accepted for production. */
    @Deprecated("Pass an exact SourceKey content-scope resolver")
    public constructor(
        network: PluginNetworkClient,
        scopes: ExtensionNetworkScopeResolverV2 = ExtensionNetworkScopeResolverV2.LegacyOnly,
    ) : this(contentScope = { null })

    override suspend fun fetch(
        sourceKey: SourceKey,
        request: RemoteRequestPlanV2,
    ): ExtensionFetchedResourceV2 {
        request.validate()
        require(request.body == null) { "Host request-body references need a dedicated resolver" }
        require(request.method == HttpMethodV2.GET || request.method == HttpMethodV2.HEAD) {
            "Only body-free GET/HEAD extension resources can be fetched here"
        }
        val scope = requireNotNull(contentScope(sourceKey)) {
            "No exact content network policy is registered for ${sourceKey.canonicalId}"
        }
        require(scope.sourceKey == sourceKey) { "Content network scope identity mismatch" }
        val response = scope.network.execute(
            sourceId = scope.sourceId,
            request = PluginHttpRequest(
                method = request.method.name,
                url = request.effectiveUri,
                headers = request.headerHints,
                // V2 historically permits a larger declarative maximum than this transport's
                // global cap. Narrow it instead of rejecting an otherwise small compatible body.
                maxResponseBytes = minOf(
                    request.maxResponseBytes,
                    PLUGIN_NETWORK_MAX_RESPONSE_BYTES.toLong(),
                ).toInt(),
            ),
        )
        check(response.status in 200..299) { "HTTP ${response.status} while fetching extension content" }
        require(response.body.size.toLong() <= request.maxResponseBytes) {
            "Extension resource exceeded its declared response bound"
        }
        return ExtensionFetchedResourceV2(
            bytes = response.body,
            mediaType = response.headers.entries
                .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                ?.value
                ?.firstOrNull()
                ?.substringBefore(';')
                ?.trim(),
        )
    }
}

/**
 * Gives one extension representation stable blob identities without changing the shared blob
 * store's allocation policy. Acquisition services publish resources in a deterministic order, so
 * the scoped ordinal keeps equal bytes at different logical paths distinct while making an exact
 * replay claim the already committed reference. That lets the transaction consume a fresh dedupe
 * receipt without manufacturing a new immutable manifest revision.
 */
internal class StableExtensionBlobStore(
    private val delegate: ContentBlobStore,
    private val scope: String,
) : ContentBlobStore by delegate {
    private var nextOrdinal: Long = 0

    // Kotlin interface delegation forwards default methods to the delegate. Override both helpers
    // so their stage lifecycle is dispatched through this scoped store.
    override fun put(bytes: ByteArray, mediaType: String): BlobPublishReceipt {
        val stage = beginStage(bytes.size.toLong(), mediaType)
        return try {
            stage.append(bytes)
            publish(stage.seal())
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
    }

    override fun put(reference: BlobRef, bytes: ByteArray): BlobPublishReceipt {
        val stage = beginStage(reference.byteSize, reference.mediaType)
        return try {
            stage.append(bytes)
            publish(stage.seal(reference))
        } catch (error: Throwable) {
            stage.abort()
            throw error
        }
    }

    override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage {
        require(nextOrdinal < Long.MAX_VALUE) { "Extension representation has too many blob resources" }
        val ordinal = nextOrdinal++
        return StableExtensionBlobStage(
            delegate = delegate.beginStage(expectedSizeBytes, mediaType),
            mediaType = mediaType,
            stableBlobId = { digest ->
                Rfc9562UuidV5.derive(
                    EXTENSION_CONTENT_NAMESPACE,
                    framedIdentity("blob", scope, ordinal.toString(), mediaType, digest),
                )
            },
        )
    }

    override fun publish(candidate: PendingBlob): BlobPublishReceipt {
        val stable = candidate as? StableExtensionPendingBlob
            ?: throw ContentBlobStoreException.InvalidStage("Blob candidate belongs to another scoped store")
        return try {
            delegate.publish(stable.delegate)
        } catch (error: ContentBlobStoreException.InvalidStage) {
            // A concurrent exact materialization may already own the one live receipt. Reuse that
            // authenticated object; otherwise preserve the original fail-closed store error.
            delegate.claimExistingVerified(stable.reference)?.also { stable.stage.abort() } ?: throw error
        }
    }
}

internal class StableExtensionBlobStage(
    private val delegate: ContentBlobStage,
    private val mediaType: String,
    private val stableBlobId: (digest: String) -> String,
) : ContentBlobStage by delegate {
    private val digestSink = HashingSink.sha256(blackholeSink())
    private val digestBuffer = Buffer()

    override fun append(chunk: ByteArray) {
        delegate.append(chunk)
        if (chunk.isNotEmpty()) {
            digestBuffer.write(chunk)
            digestSink.write(digestBuffer, chunk.size.toLong())
        }
    }

    override fun seal(expected: BlobRef?): PendingBlob {
        val digestHex = digestSink.hash.hex()
        digestSink.close()
        val reference = expected ?: run {
            BlobRef(
                blobId = stableBlobId(digestHex),
                schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                digestAlgorithm = BlobRef.SHA_256,
                plaintextDigest = digestHex,
                byteSize = bytesWritten,
                mediaType = mediaType,
            )
        }
        return StableExtensionPendingBlob(delegate.seal(reference), delegate)
    }

    override fun abort() {
        runCatching { digestSink.close() }
        delegate.abort()
    }
}

private class StableExtensionPendingBlob(
    val delegate: PendingBlob,
    val stage: ContentBlobStage,
) : PendingBlob {
    override val reference: BlobRef get() = delegate.reference
}
