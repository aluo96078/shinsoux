package dev.shinsou.kmp.search

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobStoreException
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.EpubSemanticDocumentFactory
import dev.shinsou.kmp.reader.EpubSpineNavigation
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

public data class FullTextIndexReconcileResult(
    val representationsExamined: Int,
    val representationsIndexed: Int,
    val documentsIndexed: Int,
    val unauthorizedRepresentations: Int,
    val unavailableRepresentations: Int,
    val staleDocumentsRemoved: Int,
    val representationsAlreadyCurrent: Int,
    val documentsAlreadyCurrent: Int,
)

/**
 * Rebuilds the derived index directly from the durable M1 graph, independently of reader UI.
 *
 * Only each unit's latest immutable manifest is indexed. Rights are checked with the exact
 * publication/acquisition/unit/manifest/revision scope before a body lease is opened, then checked
 * again by [DerivedLocalFullTextIndex.upsert]. Cancellation is observed between every body chunk
 * and bounded derived segment so this work can stay below foreground interaction priority.
 */
public class FoundationFullTextIndexReconciler(
    private val foundation: ContentFoundationRuntime,
    private val operationGate: ContentOperationGate,
    private val index: DerivedLocalFullTextIndex,
) {
    private val epubSemanticDocuments = EpubSemanticDocumentFactory(foundation.blobStore)

    public suspend fun reconcile(): FullTextIndexReconcileResult {
        currentCoroutineContext().ensureActive()

        var examined = 0
        var indexedRepresentations = 0
        var indexedDocuments = 0
        var unauthorized = 0
        var unavailable = 0
        var alreadyCurrentRepresentations = 0
        var alreadyCurrentDocuments = 0
        val durableDocumentIds = LinkedHashSet<String>()

        val publications = foundation.publications.all().sortedBy { it.key.value }
        publications.forEach { publication ->
            publication.acquisitions.sortedBy { it.id }.forEach { acquisition ->
                acquisition.units.sortedBy { it.key.value }.forEach { unit ->
                    val manifest = unit.latestManifest ?: return@forEach
                    manifest.representations
                        .filterIsInstance<ContentRepresentation.PlainText>()
                        .sortedBy(ContentRepresentation.PlainText::representationId)
                        .forEach { representation ->
                            currentCoroutineContext().ensureActive()
                            examined++
                            val readingScope = ReadingScope(
                                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                                publicationId = publication.key,
                                acquisitionId = acquisition.id,
                                unitId = unit.key,
                                contentRevision = manifest.contentRevision,
                            )
                            val access = ContentAccessRequest(
                                grantReference = acquisition.rightsGrantRef,
                                scope = RightsScope(
                                    publicationId = publication.key,
                                    acquisitionId = acquisition.id,
                                    unitId = unit.key,
                                    manifestId = manifest.manifestId,
                                    contentRevision = manifest.contentRevision,
                                ),
                                context = RightsOperationContext(
                                    textCharacters = representation.canonicalUtf16Length.toLong(),
                                ),
                            )

                            if (operationGate.decide(access, ContentOperation.SEARCH_INDEX) !=
                                RightsDecision.ALLOW
                            ) {
                                // Denied content is removed without opening its body. The id helper
                                // returns a safe upper bound when surrogate boundaries are unknown.
                                removeDocuments(
                                    representation.blocks.flatMap { block ->
                                        fullTextDocumentIds(representation.representationId, block)
                                    },
                                )
                                unauthorized++
                                yield()
                                return@forEach
                            }

                            var canonicalText: String? = null
                            try {
                                // Large semantic paragraphs remain one portable locator block, but
                                // each derived search row is bounded so cancellation can wait for at
                                // most one small SQLite transaction. Body text is needed only to
                                // avoid bisecting a UTF-16 surrogate pair at a segment boundary.
                                if (representation.blocks.any { block ->
                                        block.endUtf16 - block.startUtf16 >
                                            MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH
                                    }
                                ) {
                                    canonicalText = readCanonicalText(representation)
                                }
                                val segments = representation.blocks.flatMap { block ->
                                    canonicalText?.let { text ->
                                        fullTextDocumentSegments(representation.representationId, block, text)
                                    } ?: listOf(
                                        FullTextDocumentSegment(
                                            documentId = fullTextDocumentId(
                                                representation.representationId,
                                                block.blockId,
                                            ),
                                            blockId = block.blockId,
                                            startUtf16 = block.startUtf16,
                                            endUtf16 = block.endUtf16,
                                        ),
                                    )
                                }
                                val representationDocumentIds = segments.map(FullTextDocumentSegment::documentId)
                                durableDocumentIds += representationDocumentIds
                                val currentSegments = segments.map { segment ->
                                    currentCoroutineContext().ensureActive()
                                    index.isCurrent(
                                        documentId = segment.documentId,
                                        scope = readingScope,
                                        resourceId = representation.resource.id,
                                        blockId = segment.blockId,
                                        baseOffsetUtf16 = segment.startUtf16,
                                        canonicalDocumentUtf16Length = representation.canonicalUtf16Length,
                                        access = access,
                                    ).also { yield() }
                                }
                                val currentDocumentCount = currentSegments.count { it }
                                if (currentSegments.all { it }) {
                                    alreadyCurrentRepresentations++
                                    alreadyCurrentDocuments += currentDocumentCount
                                    return@forEach
                                }
                                val body = canonicalText ?: readCanonicalText(representation)
                                var representationDocumentsIndexed = 0
                                segments.forEachIndexed { segmentIndex, segment ->
                                    currentCoroutineContext().ensureActive()
                                    if (currentSegments[segmentIndex]) return@forEachIndexed
                                    index.upsertInBackground(
                                        SearchableTextDocument(
                                            documentId = segment.documentId,
                                            scope = readingScope,
                                            resourceId = representation.resource.id,
                                            blockId = segment.blockId,
                                            text = body.substring(segment.startUtf16, segment.endUtf16),
                                            access = access,
                                            baseOffsetUtf16 = segment.startUtf16,
                                            canonicalDocumentUtf16Length = body.length,
                                        ),
                                    )
                                    representationDocumentsIndexed++
                                    yield()
                                }
                                alreadyCurrentDocuments += currentDocumentCount
                                indexedDocuments += representationDocumentsIndexed
                                indexedRepresentations++
                            } catch (cancelled: CancellationException) {
                                // Every committed segment is independently valid and restart-safe.
                                // Do not perform an unbounded synchronous rollback while foreground
                                // is waiting; the conflated actor resumes missing segments later.
                                throw cancelled
                            } catch (_: ContentOperationDeniedException) {
                                removeDocuments(
                                    representation.blocks.flatMap { block ->
                                        fullTextDocumentIds(representation.representationId, block)
                                    },
                                )
                                unauthorized++
                            } catch (_: Throwable) {
                                // A missing, corrupt, malformed, or oversized body must never leave
                                // an older plaintext row searchable under the same representation id.
                                removeDocuments(
                                    representation.blocks.flatMap { block ->
                                        fullTextDocumentIds(representation.representationId, block)
                                    },
                                )
                                unavailable++
                            }
                            yield()
                        }
                    manifest.representations
                        .filterIsInstance<ContentRepresentation.EpubSpine>()
                        .sortedBy(ContentRepresentation.EpubSpine::representationId)
                        .forEach { representation ->
                            currentCoroutineContext().ensureActive()
                            examined++
                            val readingScope = ReadingScope(
                                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                                publicationId = publication.key,
                                acquisitionId = acquisition.id,
                                unitId = unit.key,
                                contentRevision = manifest.contentRevision,
                            )
                            val estimatedCharacters = representation.documents.sumOf { document ->
                                representation.packageGraph.resources.single { resource ->
                                    resource.id == document.resourceId && resource.href == document.href
                                }.resource.blob.byteSize
                            }
                            val access = ContentAccessRequest(
                                grantReference = acquisition.rightsGrantRef,
                                scope = RightsScope(
                                    publicationId = publication.key,
                                    acquisitionId = acquisition.id,
                                    unitId = unit.key,
                                    manifestId = manifest.manifestId,
                                    contentRevision = manifest.contentRevision,
                                ),
                                // Markup bytes are a conservative pre-read ceiling for the
                                // constrained search decision. Every derived row is checked again
                                // with the exact extracted UTF-16 length before it is committed.
                                context = RightsOperationContext(textCharacters = estimatedCharacters),
                            )
                            if (operationGate.decide(access, ContentOperation.SEARCH_INDEX) !=
                                RightsDecision.ALLOW
                            ) {
                                unauthorized++
                                yield()
                                return@forEach
                            }

                            val navigation = EpubSpineNavigation(readingScope, representation)
                            var representationIndexedCount = 0
                            var representationCurrentCount = 0
                            var representationDocumentCount = 0
                            try {
                                representation.documents.indices.forEach { documentIndex ->
                                    currentCoroutineContext().ensureActive()
                                    operationGate.requireAllowed(access, ContentOperation.SEARCH_INDEX)
                                    val semantic = epubSemanticDocuments.createCancellable(
                                        navigation = navigation,
                                        documentIndex = documentIndex,
                                    )
                                    val exactAccess = access.copy(
                                        context = RightsOperationContext(
                                            offlineBytes = access.context.offlineBytes,
                                            textCharacters = semantic.canonicalText.length.toLong(),
                                            watermarkApplied = access.context.watermarkApplied,
                                        ),
                                    )
                                    operationGate.requireAllowed(exactAccess, ContentOperation.SEARCH_INDEX)
                                    semantic.blocks.forEach { semanticBlock ->
                                        val textBlock = TextBlock(
                                            blockId = semanticBlock.blockId,
                                            startUtf16 = semanticBlock.startUtf16,
                                            endUtf16 = semanticBlock.endUtf16,
                                        )
                                        for (segment in fullTextDocumentSegmentsLazy(
                                            representation.representationId,
                                            textBlock,
                                            semantic.canonicalText,
                                        )) {
                                            currentCoroutineContext().ensureActive()
                                            val anchor = EpubSearchAnchor(
                                                resourceHref = semantic.resourceHref,
                                                cfiBase = semanticBlock.cfiBase,
                                                blockStartUtf16 = semanticBlock.startUtf16,
                                            )
                                            durableDocumentIds += segment.documentId
                                            representationDocumentCount++
                                            val current = index.isCurrent(
                                                documentId = segment.documentId,
                                                scope = readingScope,
                                                resourceId = semantic.resourceId,
                                                blockId = segment.blockId,
                                                baseOffsetUtf16 = segment.startUtf16,
                                                canonicalDocumentUtf16Length = semantic.canonicalText.length,
                                                access = exactAccess,
                                                epubAnchor = anchor,
                                            )
                                            if (current) {
                                                representationCurrentCount++
                                            } else {
                                                index.upsertInBackground(
                                                    SearchableTextDocument(
                                                        documentId = segment.documentId,
                                                        scope = readingScope,
                                                        resourceId = semantic.resourceId,
                                                        blockId = segment.blockId,
                                                        text = semantic.canonicalText.substring(
                                                            segment.startUtf16,
                                                            segment.endUtf16,
                                                        ),
                                                        access = exactAccess,
                                                        baseOffsetUtf16 = segment.startUtf16,
                                                        canonicalDocumentUtf16Length =
                                                            semantic.canonicalText.length,
                                                        epubAnchor = anchor,
                                                    ),
                                                )
                                                representationIndexedCount++
                                            }
                                            yield()
                                        }
                                    }
                                    yield()
                                }
                                alreadyCurrentDocuments += representationCurrentCount
                                indexedDocuments += representationIndexedCount
                                if (representationIndexedCount > 0) {
                                    indexedRepresentations++
                                } else if (representationDocumentCount > 0 &&
                                    representationCurrentCount == representationDocumentCount
                                ) {
                                    alreadyCurrentRepresentations++
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: ContentOperationDeniedException) {
                                unauthorized++
                            } catch (_: Throwable) {
                                unavailable++
                            }
                            yield()
                        }
                }
            }
        }

        currentCoroutineContext().ensureActive()
        val stale = index.retainOnlyInBackground(durableDocumentIds)
        return FullTextIndexReconcileResult(
            representationsExamined = examined,
            representationsIndexed = indexedRepresentations,
            documentsIndexed = indexedDocuments,
            unauthorizedRepresentations = unauthorized,
            unavailableRepresentations = unavailable,
            staleDocumentsRemoved = stale,
            representationsAlreadyCurrent = alreadyCurrentRepresentations,
            documentsAlreadyCurrent = alreadyCurrentDocuments,
        )
    }

    private suspend fun removeDocuments(documentIds: List<String>) {
        documentIds.forEach { documentId ->
            index.removeInBackground(documentId)
            yield()
        }
    }

    private suspend fun readCanonicalText(
        representation: ContentRepresentation.PlainText,
    ): String {
        val bytes = foundation.blobStore.readCancellable(representation.resource.blob)
        return try {
            bytes.decodeCanonicalUtf8Cancellable().also { text ->
                require(text.length == representation.canonicalUtf16Length) {
                    "Search body length does not match its manifest"
                }
            }
        } finally {
            bytes.fill(0)
        }
    }
}

/** Strict UTF-8 decoding with bounded cancellation gaps for multi-megabyte canonical bodies. */
private suspend fun ByteArray.decodeCanonicalUtf8Cancellable(): String {
    if (isEmpty()) return ""
    val output = StringBuilder(size)
    var start = 0
    while (start < size) {
        currentCoroutineContext().ensureActive()
        var end = minOf(start + UTF8_DECODE_SLICE_BYTES, size)
        if (end < size) {
            // Move a cut that landed inside a UTF-8 sequence back to its leading byte. The next
            // slice owns the complete sequence and strict decoding still rejects malformed input.
            while (end > start && (this[end].toInt() and 0xC0) == 0x80) end--
            require(end > start) { "Canonical UTF-8 sequence exceeds the decode slice" }
        }
        output.append(decodeToString(start, end, throwOnInvalidSequence = true))
        start = end
        yield()
    }
    currentCoroutineContext().ensureActive()
    return output.toString()
}

/** Stable across reader indexing and background reconciliation. */
public fun fullTextDocumentId(representationId: String, blockId: String): String =
    Sha256.hex("search:$representationId:$blockId".encodeToByteArray())

private const val UTF8_DECODE_SLICE_BYTES: Int = 64 * 1024

private suspend fun ContentBlobStore.readCancellable(reference: BlobRef): ByteArray {
    reference.validate()
    if (reference.byteSize > Int.MAX_VALUE.toLong()) {
        throw ContentBlobStoreException.SizeLimitExceeded(reference.byteSize, Int.MAX_VALUE.toLong())
    }
    if (reference.byteSize > maximumBlobSizeBytes) {
        throw ContentBlobStoreException.SizeLimitExceeded(reference.byteSize, maximumBlobSizeBytes)
    }
    currentCoroutineContext().ensureActive()
    val lease = openRead(reference) ?: throw ContentBlobStoreException.CorruptBlob(reference.blobId)
    val output = ByteArray(reference.byteSize.toInt())
    var offset = 0
    try {
        while (true) {
            currentCoroutineContext().ensureActive()
            val chunk = lease.readChunk() ?: break
            require(chunk.isNotEmpty()) { "Blob reader returned an empty chunk before EOF" }
            if (chunk.size > output.size - offset) {
                throw ContentBlobStoreException.SizeMismatch(reference.byteSize, (offset + chunk.size).toLong())
            }
            chunk.copyInto(output, destinationOffset = offset)
            offset += chunk.size
            chunk.fill(0)
            yield()
        }
        if (offset != output.size) {
            throw ContentBlobStoreException.SizeMismatch(reference.byteSize, offset.toLong())
        }
        return output
    } catch (failure: Throwable) {
        output.fill(0)
        throw failure
    } finally {
        lease.close()
    }
}
