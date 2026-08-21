package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.sync.network.decodeBase64Url
import dev.shinsou.kmp.sync.network.encodeBase64Url
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Lossless, body-free content metadata placed in schema-v2 events.
 *
 * Documents are split before they enter a SyncDraft. This keeps a large EPUB graph below the
 * Worker event limit while retaining exact ResourceRef, spine, CSS/font and block identities.
 * A receiver publishes nothing until every chunk is present and the SHA-256 digest matches.
 */
public object ContentSyncDocumentCodec {
    public data class EncodedDocument(
        val sha256: String,
        val chunksBase64Url: List<String>,
    ) {
        init {
            require(SHA256_HEX.matches(sha256)) { "Content document digest is invalid" }
            require(chunksBase64Url.isNotEmpty()) { "Content document needs at least one chunk" }
        }
    }

    public fun encodePublication(value: Publication): EncodedDocument =
        encode(Publication.serializer(), value.copy(acquisitions = emptyList()))

    public fun encodeAcquisition(value: Acquisition): EncodedDocument =
        encode(Acquisition.serializer(), value.copy(units = emptyList()))

    public fun encodeUnit(value: PublicationUnit): EncodedDocument =
        encode(PublicationUnit.serializer(), value.copy(manifestRevisions = emptyList()))

    public fun encodeManifest(value: ContentManifest): EncodedDocument =
        encode(ContentManifest.serializer(), value)

    public fun encodeRightsGrant(value: RightsGrant): EncodedDocument =
        encode(RightsGrant.serializer(), value)

    public fun encodeAnnotation(value: ContentAnnotation): EncodedDocument =
        encode(ContentAnnotation.serializer(), value)

    public fun encodeProjection(fields: Map<String, SyncValue>): EncodedDocument {
        require(fields.isNotEmpty()) { "Content sync projection cannot be empty" }
        require(fields.keys.all(String::isNotBlank)) { "Content sync projection field is blank" }
        val canonicalFields = fields.entries
            .sortedBy(Map.Entry<String, SyncValue>::key)
            .associateTo(linkedMapOf()) { (key, value) -> key to value }
        return encode(
            ContentSyncProjectionDocument.serializer(),
            ContentSyncProjectionDocument(canonicalFields),
        )
    }

    public fun decodePublication(fields: Map<String, LwwRegister<SyncValue>>): Publication? = decode(
        serializer = Publication.serializer(),
        fields = fields,
        digestField = ContentSyncFields.Publication.DOCUMENT_SHA256,
        countField = ContentSyncFields.Publication.DOCUMENT_CHUNK_COUNT,
        chunkPrefix = ContentSyncFields.Publication.DOCUMENT_CHUNK_PREFIX,
    )

    public fun decodeAcquisition(fields: Map<String, LwwRegister<SyncValue>>): Acquisition? = decode(
        serializer = Acquisition.serializer(),
        fields = fields,
        digestField = ContentSyncFields.Acquisition.DOCUMENT_SHA256,
        countField = ContentSyncFields.Acquisition.DOCUMENT_CHUNK_COUNT,
        chunkPrefix = ContentSyncFields.Acquisition.DOCUMENT_CHUNK_PREFIX,
    )

    public fun decodeUnit(fields: Map<String, LwwRegister<SyncValue>>): PublicationUnit? = decode(
        serializer = PublicationUnit.serializer(),
        fields = fields,
        digestField = ContentSyncFields.Unit.DOCUMENT_SHA256,
        countField = ContentSyncFields.Unit.DOCUMENT_CHUNK_COUNT,
        chunkPrefix = ContentSyncFields.Unit.DOCUMENT_CHUNK_PREFIX,
    )

    public fun decodeManifest(fields: Map<String, LwwRegister<SyncValue>>): ContentManifest? = decode(
        serializer = ContentManifest.serializer(),
        fields = fields,
        digestField = ContentSyncFields.Manifest.DOCUMENT_SHA256,
        countField = ContentSyncFields.Manifest.DOCUMENT_CHUNK_COUNT,
        chunkPrefix = ContentSyncFields.Manifest.DOCUMENT_CHUNK_PREFIX,
    )

    public fun decodeRightsGrant(fields: Map<String, LwwRegister<SyncValue>>): RightsGrant? = decode(
        serializer = RightsGrant.serializer(),
        fields = fields,
        digestField = ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_SHA256,
        countField = ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_CHUNK_COUNT,
        chunkPrefix = ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_CHUNK_PREFIX,
    )

    public fun decodeAnnotation(fields: Map<String, LwwRegister<SyncValue>>): ContentAnnotation? = decode(
        serializer = ContentAnnotation.serializer(),
        fields = fields,
        digestField = ContentSyncFields.Annotation.DOCUMENT_SHA256,
        countField = ContentSyncFields.Annotation.DOCUMENT_CHUNK_COUNT,
        chunkPrefix = ContentSyncFields.Annotation.DOCUMENT_CHUNK_PREFIX,
    )

    public fun decodeCommittedAnnotation(
        fields: Map<String, LwwRegister<SyncValue>>,
    ): ContentAnnotation? {
        val documentDigest = (fields[ContentSyncFields.Annotation.DOCUMENT_SHA256]?.value as?
            SyncValue.StringValue)?.value ?: return null
        val committedDigest = (fields[ContentSyncFields.Annotation.COMMITTED_SHA256]?.value as?
            SyncValue.StringValue)?.value ?: return null
        if (committedDigest != documentDigest) return null
        return decodeAnnotation(fields)
    }

    public fun decodeProjection(
        fields: Map<String, LwwRegister<SyncValue>>,
        digestField: String,
        countField: String,
        chunkPrefix: String,
    ): Map<String, SyncValue>? = decode(
        serializer = ContentSyncProjectionDocument.serializer(),
        fields = fields,
        digestField = digestField,
        countField = countField,
        chunkPrefix = chunkPrefix,
    )?.fields

    public fun hasDocumentFields(
        fields: Map<String, LwwRegister<SyncValue>>,
        digestField: String,
        countField: String,
        chunkPrefix: String,
    ): Boolean = fields.keys.any { field ->
        field == digestField || field == countField || field.startsWith(chunkPrefix)
    }

    private fun <T> encode(serializer: KSerializer<T>, value: T): EncodedDocument {
        val bytes = JSON.encodeToString(serializer, value).encodeToByteArray()
        return EncodedDocument(
            sha256 = Sha256.hex(bytes),
            chunksBase64Url = bytes.asListChunks(DOCUMENT_CHUNK_BYTES).map(::encodeBase64Url),
        )
    }

    private fun <T> decode(
        serializer: KSerializer<T>,
        fields: Map<String, LwwRegister<SyncValue>>,
        digestField: String,
        countField: String,
        chunkPrefix: String,
    ): T? {
        val digest = (fields[digestField]?.value as? SyncValue.StringValue)?.value ?: return null
        if (!SHA256_HEX.matches(digest)) return null
        val countLong = (fields[countField]?.value as? SyncValue.LongValue)?.value ?: return null
        if (countLong !in 1..MAX_DOCUMENT_CHUNKS.toLong()) return null
        val count = countLong.toInt()
        val chunks = ArrayList<ByteArray>(count)
        var total = 0L
        repeat(count) { index ->
            val key = chunkField(chunkPrefix, index)
            val encoded = (fields[key]?.value as? SyncValue.StringValue)?.value ?: return null
            val decoded = runCatching { decodeBase64Url(encoded) }.getOrNull() ?: return null
            if (decoded.isEmpty() || decoded.size > DOCUMENT_CHUNK_BYTES) return null
            total += decoded.size
            if (total > MAX_DOCUMENT_BYTES) return null
            chunks += decoded
        }
        val bytes = ByteArray(total.toInt())
        var offset = 0
        chunks.forEach { chunk ->
            chunk.copyInto(bytes, offset)
            offset += chunk.size
        }
        if (Sha256.hex(bytes) != digest) return null
        return runCatching {
            JSON.decodeFromString(serializer, bytes.decodeToString(throwOnInvalidSequence = true))
        }.getOrNull()
    }

    internal fun headerFields(
        document: EncodedDocument,
        digestField: String,
        countField: String,
    ): Map<String, SyncValue> = mapOf(
        digestField to SyncValue.StringValue(document.sha256),
        countField to SyncValue.LongValue(document.chunksBase64Url.size.toLong()),
    )

    internal fun chunkFields(
        document: EncodedDocument,
        chunkPrefix: String,
    ): List<Map<String, SyncValue>> = document.chunksBase64Url.mapIndexed { index, value ->
        mapOf(chunkField(chunkPrefix, index) to SyncValue.StringValue(value))
    }

    private fun chunkField(prefix: String, index: Int): String = prefix + index.toString().padStart(6, '0')

    private fun ByteArray.asListChunks(chunkBytes: Int): List<ByteArray> {
        if (isEmpty()) return listOf(ByteArray(0))
        return buildList {
            var offset = 0
            while (offset < this@asListChunks.size) {
                val end = minOf(this@asListChunks.size, offset + chunkBytes)
                add(copyOfRange(offset, end))
                offset = end
            }
        }
    }

    private val JSON = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = false
    }
    private val SHA256_HEX = Regex("^[0-9a-f]{64}$")
    // Base64url expands bytes by 4/3. A 16 KiB source chunk leaves ample room for mutation keys,
    // entity identities and canonical-CBOR structure inside the 28 KiB plaintext budget.
    private const val DOCUMENT_CHUNK_BYTES: Int = 16 * 1024
    private const val MAX_DOCUMENT_BYTES: Long = 16L * 1024 * 1024
    private const val MAX_DOCUMENT_CHUNKS: Int = 1_024
}

@Serializable
private data class ContentSyncProjectionDocument(
    val fields: Map<String, SyncValue>,
)

public data class ContentPublicationSyncDraftPlan(
    val drafts: List<SyncDraft>,
)

/** Builds deterministic, re-clockable metadata drafts for one complete publication graph. */
public object ContentPublicationSyncDraftFactory {
    public fun build(
        publication: Publication,
        rightsGrants: List<RightsGrant>,
        operationNamespace: String,
        createdAtMillis: Long,
    ): ContentPublicationSyncDraftPlan {
        publication.validate()
        require(operationNamespace.isNotBlank() && operationNamespace.length <= 4_096) {
            "Content sync operation namespace is invalid"
        }
        require(createdAtMillis >= 0) { "Content sync draft time cannot be negative" }
        val rightsByReference = rightsGrants.associateBy(RightsGrant::grantId)
        require(rightsByReference.size == rightsGrants.size) { "Content rights grant ids must be unique" }
        publication.acquisitions.forEach { acquisition ->
            acquisition.rightsGrantRef?.let { reference ->
                val grant = requireNotNull(rightsByReference[reference]) {
                    "Content acquisition is missing its exact rights grant"
                }
                require(grant.scope.publicationId == publication.key && grant.scope.acquisitionId == acquisition.id) {
                    "Content rights grant scope does not match its acquisition"
                }
            }
        }

        val mutations = buildList {
            addAll(publicationMutations(publication))
            publication.acquisitions.sortedBy(Acquisition::id).forEach { acquisition ->
                addAll(acquisitionMutations(publication, acquisition, rightsByReference[acquisition.rightsGrantRef]))
                acquisition.units.sortedBy { it.key.value }.forEach { unit ->
                    addAll(unitMutations(acquisition, unit))
                    unit.manifestRevisions.sortedBy(ContentManifest::contentRevision).forEach { manifest ->
                        addAll(manifestMutations(unit, manifest))
                    }
                }
            }
        }
        val namespaceHash = Sha256.hex(operationNamespace.encodeToByteArray())
        val drafts = CanonicalSyncDraftPacker.pack(
            mutations = mutations,
            createdAtMillis = createdAtMillis,
            draftId = { index ->
                "content-v2:$namespaceHash:${index.toString().padStart(6, '0')}"
            },
        )
        return ContentPublicationSyncDraftPlan(drafts)
    }

    private fun publicationMutations(publication: Publication): List<SyncMutation> {
        val key = SyncEntityKey.publication(publication.key.value)
        val document = ContentSyncDocumentCodec.encodePublication(publication)
        val projection = ContentSyncDocumentCodec.encodeProjection(mapOf(
            ContentSyncFields.Publication.TITLE to SyncValue.StringValue(publication.title),
            ContentSyncFields.Publication.AUTHOR to SyncValue.StringListValue(publication.authors),
            ContentSyncFields.Publication.DESCRIPTION to SyncValue.nullable(publication.description),
            ContentSyncFields.Publication.ACQUISITION_IDS to SyncValue.StringListValue(
                publication.acquisitions.map(Acquisition::id).sorted(),
            ),
        ))
        val base = ContentSyncDocumentCodec.headerFields(
            document,
            ContentSyncFields.Publication.DOCUMENT_SHA256,
            ContentSyncFields.Publication.DOCUMENT_CHUNK_COUNT,
        ) + ContentSyncDocumentCodec.headerFields(
            projection,
            ContentSyncFields.Publication.PROJECTION_SHA256,
            ContentSyncFields.Publication.PROJECTION_CHUNK_COUNT,
        )
        return buildList {
            add(PublicationPatchV2(key, base))
            ContentSyncDocumentCodec.chunkFields(
                document,
                ContentSyncFields.Publication.DOCUMENT_CHUNK_PREFIX,
            ).forEach { add(PublicationPatchV2(key, it)) }
            ContentSyncDocumentCodec.chunkFields(
                projection,
                ContentSyncFields.Publication.PROJECTION_CHUNK_PREFIX,
            ).forEach { add(PublicationPatchV2(key, it)) }
        }
    }

    private fun acquisitionMutations(
        publication: Publication,
        acquisition: Acquisition,
        rightsGrant: RightsGrant?,
    ): List<SyncMutation> {
        val key = SyncEntityKey.acquisition(acquisition.id)
        val parent = SyncEntityKey.publication(publication.key.value)
        val document = ContentSyncDocumentCodec.encodeAcquisition(acquisition)
        val projection = ContentSyncDocumentCodec.encodeProjection(mapOf(
            ContentSyncFields.Acquisition.SOURCE_KEY to
                SyncValue.nullable(acquisition.sourceBinding?.sourceKey?.canonicalId),
            ContentSyncFields.Acquisition.REMOTE_CANONICAL_ID to
                SyncValue.nullable(acquisition.sourceBinding?.remoteEntityKey?.canonicalId),
            ContentSyncFields.Acquisition.RIGHTS_GRANT_ID to
                SyncValue.nullable(acquisition.rightsGrantRef?.value),
            ContentSyncFields.Acquisition.AVAILABILITY to SyncValue.StringValue(acquisition.availability.name),
            ContentSyncFields.Acquisition.CONTENT_REVISION to SyncValue.LongValue(acquisition.contentRevision),
            ContentSyncFields.Acquisition.UNIT_IDS to SyncValue.StringListValue(
                acquisition.units.map { it.key.value }.sorted(),
            ),
        ))
        var base = ContentSyncDocumentCodec.headerFields(
            document,
            ContentSyncFields.Acquisition.DOCUMENT_SHA256,
            ContentSyncFields.Acquisition.DOCUMENT_CHUNK_COUNT,
        ) + ContentSyncDocumentCodec.headerFields(
            projection,
            ContentSyncFields.Acquisition.PROJECTION_SHA256,
            ContentSyncFields.Acquisition.PROJECTION_CHUNK_COUNT,
        )
        val mutations = mutableListOf<SyncMutation>()
        rightsGrant?.let { grant ->
            val rightsDocument = ContentSyncDocumentCodec.encodeRightsGrant(grant)
            base += ContentSyncDocumentCodec.headerFields(
                rightsDocument,
                ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_SHA256,
                ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_CHUNK_COUNT,
            )
            ContentSyncDocumentCodec.chunkFields(
                rightsDocument,
                ContentSyncFields.Acquisition.RIGHTS_DOCUMENT_CHUNK_PREFIX,
            ).forEach { fields -> mutations += AcquisitionPatchV2(key, parent, fields) }
        }
        mutations.add(0, AcquisitionPatchV2(key, parent, base))
        mutations += ContentSyncDocumentCodec.chunkFields(
            document,
            ContentSyncFields.Acquisition.DOCUMENT_CHUNK_PREFIX,
        ).map { AcquisitionPatchV2(key, parent, it) }
        mutations += ContentSyncDocumentCodec.chunkFields(
            projection,
            ContentSyncFields.Acquisition.PROJECTION_CHUNK_PREFIX,
        ).map { AcquisitionPatchV2(key, parent, it) }
        return mutations
    }

    private fun unitMutations(acquisition: Acquisition, unit: PublicationUnit): List<SyncMutation> {
        val key = SyncEntityKey.publicationUnit(unit.key.value)
        val parent = SyncEntityKey.acquisition(acquisition.id)
        val document = ContentSyncDocumentCodec.encodeUnit(unit)
        val projection = ContentSyncDocumentCodec.encodeProjection(mapOf(
            ContentSyncFields.Unit.TITLE to SyncValue.StringValue(unit.title),
            ContentSyncFields.Unit.SOURCE_ORDER to SyncValue.LongValue((unit.ordinal ?: 0).toLong()),
            ContentSyncFields.Unit.ORDINAL to
                (unit.ordinal?.let { SyncValue.LongValue(it.toLong()) } ?: SyncValue.NullValue),
            ContentSyncFields.Unit.REMOTE_CANONICAL_ID to
                SyncValue.nullable(unit.sourceBinding?.remoteEntityKey?.canonicalId),
            ContentSyncFields.Unit.MANIFEST_IDS to SyncValue.StringListValue(
                unit.manifestRevisions.map(ContentManifest::manifestId).sorted(),
            ),
        ))
        val base = ContentSyncDocumentCodec.headerFields(
            document,
            ContentSyncFields.Unit.DOCUMENT_SHA256,
            ContentSyncFields.Unit.DOCUMENT_CHUNK_COUNT,
        ) + ContentSyncDocumentCodec.headerFields(
            projection,
            ContentSyncFields.Unit.PROJECTION_SHA256,
            ContentSyncFields.Unit.PROJECTION_CHUNK_COUNT,
        )
        return buildList {
            add(PublicationUnitPatchV2(key, parent, base))
            ContentSyncDocumentCodec.chunkFields(
                document,
                ContentSyncFields.Unit.DOCUMENT_CHUNK_PREFIX,
            ).forEach { add(PublicationUnitPatchV2(key, parent, it)) }
            ContentSyncDocumentCodec.chunkFields(
                projection,
                ContentSyncFields.Unit.PROJECTION_CHUNK_PREFIX,
            ).forEach { add(PublicationUnitPatchV2(key, parent, it)) }
        }
    }

    private fun manifestMutations(unit: PublicationUnit, manifest: ContentManifest): List<SyncMutation> {
        val key = SyncEntityKey.contentManifest(manifest.manifestId)
        val parent = SyncEntityKey.publicationUnit(unit.key.value)
        val document = ContentSyncDocumentCodec.encodeManifest(manifest)
        val projection = ContentSyncDocumentCodec.encodeProjection(mapOf(
            ContentSyncFields.Manifest.CONTENT_REVISION to SyncValue.LongValue(manifest.contentRevision),
            ContentSyncFields.Manifest.CONTENT_KIND to SyncValue.StringListValue(
                manifest.representations.map { it.kind.name },
            ),
            ContentSyncFields.Manifest.BLOB_IDS to SyncValue.StringListValue(
                manifest.referencedBlobs.map { it.blobId }.sorted(),
            ),
            ContentSyncFields.Manifest.REPRESENTATION_ID to SyncValue.StringListValue(
                manifest.representations.map { it.representationId }.sorted(),
            ),
        ))
        val base = ContentSyncDocumentCodec.headerFields(
            document,
            ContentSyncFields.Manifest.DOCUMENT_SHA256,
            ContentSyncFields.Manifest.DOCUMENT_CHUNK_COUNT,
        ) + ContentSyncDocumentCodec.headerFields(
            projection,
            ContentSyncFields.Manifest.PROJECTION_SHA256,
            ContentSyncFields.Manifest.PROJECTION_CHUNK_COUNT,
        )
        return buildList {
            add(ContentManifestPatchV2(key, parent, base))
            ContentSyncDocumentCodec.chunkFields(
                document,
                ContentSyncFields.Manifest.DOCUMENT_CHUNK_PREFIX,
            ).forEach { add(ContentManifestPatchV2(key, parent, it)) }
            ContentSyncDocumentCodec.chunkFields(
                projection,
                ContentSyncFields.Manifest.PROJECTION_CHUNK_PREFIX,
            ).forEach { add(ContentManifestPatchV2(key, parent, it)) }
        }
    }
}
