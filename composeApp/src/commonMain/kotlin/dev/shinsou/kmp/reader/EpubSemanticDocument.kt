package dev.shinsou.kmp.reader

import dev.shinsou.kmp.acquisition.BoundedXmlParser
import dev.shinsou.kmp.acquisition.StrictTextDecoder
import dev.shinsou.kmp.acquisition.XmlContentNode
import dev.shinsou.kmp.acquisition.XmlElement
import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentBlobStoreException
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/** One exact XHTML text-node run and its stable EPUB CFI identity. */
public data class EpubSemanticTextBlock(
    val blockId: String,
    val startUtf16: Int,
    val endUtf16: Int,
    /** Point CFI whose terminal text offset is zero for this exact DOM text node. */
    val cfiBase: String,
) {
    init {
        require(blockId.isNotBlank() && blockId.none(Char::isWhitespace) && blockId.none(Char::isISOControl)) {
            "EPUB semantic block id is invalid"
        }
        require(startUtf16 >= 0 && endUtf16 > startUtf16) {
            "EPUB semantic block range is invalid"
        }
        require(cfiBase.endsWith(":0)")) { "EPUB semantic block CFI must start at its text node" }
    }
}

/**
 * Rebuildable semantic view of one spine resource.
 *
 * [canonicalText] preserves every admitted XML text node verbatim and inserts one `\n` only
 * between nodes. Consequently UTF-16 offsets are stable, while each block's CFI offset remains
 * relative to the publisher DOM text node rather than to the derived concatenation.
 */
public data class EpubSemanticDocument(
    val representationId: String,
    val resourceId: String,
    val resourceHref: String,
    val documentIndex: Int,
    val canonicalText: String,
    val blocks: List<EpubSemanticTextBlock>,
) {
    init {
        require(representationId.isNotBlank() && representationId.none(Char::isWhitespace)) {
            "EPUB semantic representation id is invalid"
        }
        require(resourceId.isNotBlank() && resourceId.none(Char::isWhitespace)) {
            "EPUB semantic resource id is invalid"
        }
        require(resourceHref.isNotBlank() && resourceHref.none(Char::isWhitespace)) {
            "EPUB semantic resource href is invalid"
        }
        require(documentIndex >= 0) { "EPUB semantic document index is invalid" }
        require(canonicalText.length <= MAX_EPUB_SEMANTIC_TEXT_UTF16) {
            "EPUB semantic text exceeds the configured limit"
        }
        require(blocks.isNotEmpty()) { "EPUB semantic document contains no readable text" }
        require(blocks.size <= MAX_EPUB_SEMANTIC_BLOCKS) { "EPUB semantic document has too many text nodes" }
        require(blocks.zipWithNext().all { (left, right) -> left.endUtf16 <= right.startUtf16 }) {
            "EPUB semantic blocks must be ordered and non-overlapping"
        }
        require(blocks.last().endUtf16 <= canonicalText.length) {
            "EPUB semantic block exceeds canonical text"
        }
        require(blocks.map(EpubSemanticTextBlock::blockId).distinct().size == blocks.size) {
            "EPUB semantic block ids must be unique"
        }
    }

    public fun blockFor(locator: ReadingLocator.Epub): EpubSemanticTextBlock? {
        if (locator.resourceId != resourceId || locator.resourceHref != resourceHref) return null
        val offset = locator.resolveOffset(canonicalText) ?: return null
        return blockAtOffset(offset, locator.direction)
    }

    public fun blockAtOffset(
        offsetUtf16: Int,
        direction: TextDirection = TextDirection.FORWARD,
    ): EpubSemanticTextBlock {
        require(offsetUtf16 in 0..canonicalText.length) { "EPUB semantic offset is out of range" }
        if (direction == TextDirection.BACKWARD) {
            blocks.lastOrNull { offsetUtf16 in (it.startUtf16 + 1)..it.endUtf16 }?.let { return it }
        }
        return blocks.firstOrNull { offsetUtf16 in it.startUtf16 until it.endUtf16 }
            ?: blocks.firstOrNull { it.startUtf16 >= offsetUtf16 }
            ?: blocks.last()
    }

    public fun locatorForProgression(
        navigation: EpubSpineNavigation,
        progression: Double,
        direction: TextDirection = TextDirection.FORWARD,
    ): ReadingLocator.Epub {
        require(progression.isFinite()) { "EPUB document progression is invalid" }
        val normalized = progression.coerceIn(0.0, 1.0)
        val requested = if (canonicalText.isEmpty()) 0 else {
            (canonicalText.length.toDouble() * normalized).toInt().coerceIn(0, canonicalText.length)
        }
        return locatorForOffset(navigation, nearestUtf16Boundary(canonicalText, requested), direction)
    }

    public fun locatorForOffset(
        navigation: EpubSpineNavigation,
        offsetUtf16: Int,
        direction: TextDirection = TextDirection.FORWARD,
        quote: TextQuote? = null,
    ): ReadingLocator.Epub {
        require(navigation.representationId == representationId) {
            "EPUB semantic document belongs to another representation"
        }
        val document = navigation.representation.documents.getOrNull(documentIndex)
        require(document?.resourceId == resourceId && document.href == resourceHref) {
            "EPUB semantic document does not match its spine identity"
        }
        require(offsetUtf16 in 0..canonicalText.length && isUtf16Boundary(canonicalText, offsetUtf16)) {
            "EPUB semantic locator offset is invalid"
        }
        val block = blockAtOffset(offsetUtf16, direction)
        val blockOffset = (offsetUtf16 - block.startUtf16).coerceIn(0, block.endUtf16 - block.startUtf16)
        return navigation.locator(
            documentIndex = documentIndex,
            cfi = cfiWithTextOffset(block.cfiBase, blockOffset),
            progression = progression(offsetUtf16, canonicalText.length),
            direction = direction,
            offsetHint = offsetUtf16,
            blockIdHint = block.blockId,
            quote = quote,
        )
    }

    /** Deterministic quote fallback for viewport progress and point selections. */
    public fun locatorForProgressionWithQuote(
        navigation: EpubSpineNavigation,
        progression: Double,
        direction: TextDirection = TextDirection.FORWARD,
    ): ReadingLocator.Epub {
        val point = locatorForProgression(navigation, progression, direction)
        val offset = requireNotNull(point.offsetHint)
        return point.copy(quote = quoteAt(offset))
    }

    /**
     * Resolves a browser selection back into the extractor's exact href/CFI identity.
     * Repeated quotes are disambiguated by the viewport progression and retain an occurrence
     * fallback so re-anchoring remains deterministic after process restart.
     */
    public fun rangeForSelection(
        navigation: EpubSpineNavigation,
        exactSelection: String,
        viewportProgression: Double,
    ): ReadingRange? {
        val exact = exactSelection
            .replace('\u0000', ' ')
            .take(MAX_EPUB_QUOTE_EXACT_UTF16)
        if (exact.isBlank() || !isWellFormedSelection(exact)) return null
        val targetOffset = (canonicalText.length.toDouble() * viewportProgression.coerceIn(0.0, 1.0)).toInt()
        val windowStart = maxOf(0, targetOffset - MAX_SELECTION_SEARCH_WINDOW_UTF16)
        val windowEnd = minOf(canonicalText.length, targetOffset + MAX_SELECTION_SEARCH_WINDOW_UTF16)
        val window = canonicalText.substring(windowStart, windowEnd)
        val occurrences = mutableListOf<Int>()
        var cursor = 0
        while (cursor <= window.length - exact.length && occurrences.size <= MAX_SELECTION_OCCURRENCES) {
            val localMatch = window.indexOf(exact, cursor)
            if (localMatch < 0) break
            val match = windowStart + localMatch
            if (isUtf16Boundary(canonicalText, match) &&
                isUtf16Boundary(canonicalText, match + exact.length)
            ) {
                occurrences += match
            }
            cursor = localMatch + 1
        }
        if (occurrences.isEmpty()) return null
        val start = occurrences.minWithOrNull(compareBy<Int> { kotlin.math.abs(it - targetOffset) }.thenBy { it })
            ?: return null
        val end = start + exact.length
        val prefixStart = nearestUtf16Boundary(
            canonicalText,
            maxOf(0, start - MAX_EPUB_QUOTE_CONTEXT_UTF16),
        )
        val suffixEnd = nearestUtf16Boundary(
            canonicalText,
            minOf(canonicalText.length, end + MAX_EPUB_QUOTE_CONTEXT_UTF16),
        )
        val quote = TextQuote(
            exact = exact,
            prefix = canonicalText.substring(prefixStart, start),
            suffix = canonicalText.substring(end, suffixEnd),
            occurrence = 0,
        )
        return ReadingRange(
            start = locatorForOffset(navigation, start, quote = quote),
            end = locatorForOffset(navigation, end, direction = TextDirection.BACKWARD),
            quote = quote,
        )
    }

    public fun rangeForBlock(
        navigation: EpubSpineNavigation,
        block: EpubSemanticTextBlock,
    ): ReadingRange {
        require(block in blocks) { "EPUB semantic block belongs to another document" }
        val quoteEnd = nearestUtf16Boundary(
            canonicalText,
            minOf(block.endUtf16, block.startUtf16 + MAX_EPUB_QUOTE_EXACT_UTF16),
        )
        val suffixEnd = nearestUtf16Boundary(
            canonicalText,
            minOf(canonicalText.length, quoteEnd + MAX_EPUB_QUOTE_CONTEXT_UTF16),
        )
        val quote = TextQuote(
            exact = canonicalText.substring(block.startUtf16, quoteEnd),
            suffix = canonicalText.substring(quoteEnd, suffixEnd),
        )
        return ReadingRange(
            start = locatorForOffset(navigation, block.startUtf16, quote = quote),
            end = locatorForOffset(
                navigation,
                block.endUtf16,
                direction = TextDirection.BACKWARD,
            ),
            quote = quote,
        )
    }

    public fun rangeForOffsets(
        navigation: EpubSpineNavigation,
        startUtf16: Int,
        endUtf16: Int,
    ): ReadingRange {
        require(startUtf16 in 0 until endUtf16 && endUtf16 <= canonicalText.length) {
            "EPUB semantic range is invalid"
        }
        require(isUtf16Boundary(canonicalText, startUtf16) && isUtf16Boundary(canonicalText, endUtf16)) {
            "EPUB semantic range splits a UTF-16 code point"
        }
        val quoteEnd = nearestUtf16Boundary(
            canonicalText,
            minOf(endUtf16, startUtf16 + MAX_EPUB_QUOTE_EXACT_UTF16),
        )
        val prefixStart = nearestUtf16Boundary(
            canonicalText,
            maxOf(0, startUtf16 - MAX_EPUB_QUOTE_CONTEXT_UTF16),
        )
        val suffixEnd = nearestUtf16Boundary(
            canonicalText,
            minOf(canonicalText.length, quoteEnd + MAX_EPUB_QUOTE_CONTEXT_UTF16),
        )
        val exact = canonicalText.substring(startUtf16, quoteEnd)
        val quote = TextQuote(
            exact = exact,
            prefix = canonicalText.substring(prefixStart, startUtf16),
            suffix = canonicalText.substring(quoteEnd, suffixEnd),
            occurrence = 0,
        )
        return ReadingRange(
            start = locatorForOffset(navigation, startUtf16, quote = quote),
            end = locatorForOffset(navigation, endUtf16, direction = TextDirection.BACKWARD),
            quote = quote,
        )
    }

    private fun quoteAt(offsetUtf16: Int): TextQuote {
        val start = nearestUtf16Boundary(canonicalText, offsetUtf16.coerceIn(0, canonicalText.length - 1))
        val end = nearestUtf16Boundary(
            canonicalText,
            minOf(canonicalText.length, start + MAX_EPUB_POINT_QUOTE_EXACT_UTF16),
        ).let { if (it == start) nearestUtf16Boundary(canonicalText, minOf(canonicalText.length, start + 2)) else it }
        val prefixStart = nearestUtf16Boundary(
            canonicalText,
            maxOf(0, start - MAX_EPUB_QUOTE_CONTEXT_UTF16),
        )
        val suffixEnd = nearestUtf16Boundary(
            canonicalText,
            minOf(canonicalText.length, end + MAX_EPUB_QUOTE_CONTEXT_UTF16),
        )
        return TextQuote(
            exact = canonicalText.substring(start, end),
            prefix = canonicalText.substring(prefixStart, start),
            suffix = canonicalText.substring(end, suffixEnd),
            occurrence = 0,
        )
    }
}

/** Bounded, script-free XHTML semantic extractor used by search, TTS and annotation. */
public object EpubSemanticExtractor {
    public fun extract(
        navigation: EpubSpineNavigation,
        documentIndex: Int,
        xhtmlBytes: ByteArray,
    ): EpubSemanticDocument = extract(
        navigation = navigation,
        documentIndex = documentIndex,
        xhtmlBytes = xhtmlBytes,
        cancellationCheckpoint = {},
    )

    internal fun extract(
        navigation: EpubSpineNavigation,
        documentIndex: Int,
        xhtmlBytes: ByteArray,
        cancellationCheckpoint: () -> Unit,
    ): EpubSemanticDocument {
        cancellationCheckpoint()
        require(xhtmlBytes.size.toLong() <= MAX_EPUB_SEMANTIC_XHTML_BYTES) {
            "EPUB XHTML exceeds the semantic extraction limit"
        }
        val spine = navigation.representation.documents.getOrNull(documentIndex)
            ?: throw IllegalArgumentException("EPUB semantic document index is out of range")
        val semanticXml = semanticXmlBytes(xhtmlBytes, cancellationCheckpoint)
        val root = BoundedXmlParser.parse(
            semanticXml,
            MAX_EPUB_SEMANTIC_XHTML_BYTES,
            cancellationCheckpoint,
        )
        require(root.localName.equals("html", ignoreCase = true)) {
            "EPUB content document has the wrong root element"
        }
        val collected = ArrayList<CollectedTextRun>()
        collectRootContent(root, navigation, documentIndex, collected, cancellationCheckpoint)
        require(collected.isNotEmpty()) { "EPUB content document contains no readable body text" }
        require(collected.size <= MAX_EPUB_SEMANTIC_BLOCKS) {
            "EPUB content document has too many text nodes"
        }

        val canonical = StringBuilder(minOf(MAX_EPUB_SEMANTIC_TEXT_UTF16, xhtmlBytes.size))
        val blocks = ArrayList<EpubSemanticTextBlock>(collected.size)
        collected.forEachIndexed { index, run ->
            if (index % SEMANTIC_CANCELLATION_INTERVAL == 0) cancellationCheckpoint()
            if (index > 0 && collected[index - 1].blockGroupPath != run.blockGroupPath) {
                canonical.append('\n')
            }
            val start = canonical.length
            canonical.append(run.text)
            require(canonical.length <= MAX_EPUB_SEMANTIC_TEXT_UTF16) {
                "EPUB semantic text exceeds the configured limit"
            }
            val end = canonical.length
            blocks += EpubSemanticTextBlock(
                blockId = Sha256.hex(
                    "epub-semantic:${navigation.representationId}:${spine.resourceId}:${run.cfiBase}"
                        .encodeToByteArray(),
                ),
                startUtf16 = start,
                endUtf16 = end,
                cfiBase = run.cfiBase,
            )
        }
        return EpubSemanticDocument(
            representationId = navigation.representationId,
            resourceId = spine.resourceId,
            resourceHref = spine.href,
            documentIndex = documentIndex,
            canonicalText = canonical.toString(),
            blocks = blocks,
        ).also { cancellationCheckpoint() }
    }

    public suspend fun extractCancellable(
        navigation: EpubSpineNavigation,
        documentIndex: Int,
        xhtmlBytes: ByteArray,
    ): EpubSemanticDocument {
        val context = currentCoroutineContext()
        yield()
        return extract(navigation, documentIndex, xhtmlBytes) { context.ensureActive() }
    }

    private fun collectRootContent(
        root: XmlElement,
        navigation: EpubSpineNavigation,
        documentIndex: Int,
        output: MutableList<CollectedTextRun>,
        cancellationCheckpoint: () -> Unit,
    ) {
        var elementOrdinal = 0
        root.content.forEach { node ->
            cancellationCheckpoint()
            if (node !is XmlContentNode.Element) return@forEach
            elementOrdinal++
            val child = node.value
            if (child.localName.equals("body", ignoreCase = true)) {
                collectElement(
                    element = child,
                    elementPath = "/${elementOrdinal * 2}",
                    blockGroupPath = "/${elementOrdinal * 2}",
                    packageStep = (documentIndex + 1) * 2,
                    ignored = false,
                    output = output,
                    cancellationCheckpoint = cancellationCheckpoint,
                )
            }
        }
        require(output.isNotEmpty()) {
            "EPUB content document has no readable body element"
        }
    }

    private fun collectElement(
        element: XmlElement,
        elementPath: String,
        blockGroupPath: String,
        packageStep: Int,
        ignored: Boolean,
        output: MutableList<CollectedTextRun>,
        cancellationCheckpoint: () -> Unit,
    ) {
        cancellationCheckpoint()
        val localName = element.localName.lowercase()
        val skip = ignored || localName in NON_SEMANTIC_ELEMENTS
        val currentBlockGroup = if (localName in SEMANTIC_BLOCK_ELEMENTS) elementPath else blockGroupPath
        var elementOrdinal = 0
        element.content.forEach { node ->
            cancellationCheckpoint()
            when (node) {
                is XmlContentNode.Text -> {
                    // EPUB CFI character-data steps are positioned between element siblings:
                    // `/1` before the first element, `/3` after it, `/5` after the second, etc.
                    // The bounded parser coalesces adjacent character data, so the number of
                    // preceding element siblings is the authoritative DOM text-node identity.
                    val textStep = elementOrdinal * 2 + 1
                    if (!skip && node.value.isNotBlank()) {
                        output += CollectedTextRun(
                            text = node.value,
                            cfiBase = "epubcfi(/6/$packageStep!$elementPath/$textStep:0)",
                            blockGroupPath = currentBlockGroup,
                        )
                    }
                }
                is XmlContentNode.Element -> {
                    elementOrdinal++
                    collectElement(
                        element = node.value,
                        elementPath = "$elementPath/${elementOrdinal * 2}",
                        blockGroupPath = currentBlockGroup,
                        packageStep = packageStep,
                        ignored = skip,
                        output = output,
                        cancellationCheckpoint = cancellationCheckpoint,
                    )
                }
            }
        }
    }
}

/** Reads one immutable XHTML body and verifies its declared digest before semantic parsing. */
public class EpubSemanticDocumentFactory(
    private val blobStore: ContentBlobStore,
) {
    public fun create(
        navigation: EpubSpineNavigation,
        documentIndex: Int,
        resourceReadGate: EpubResourceReadGate = EpubResourceReadGate.Direct,
    ): EpubSemanticDocument {
        val document = navigation.representation.documents.getOrNull(documentIndex)
            ?: throw IllegalArgumentException("EPUB semantic document index is out of range")
        val resource = navigation.representation.packageGraph.resources.singleOrNull { candidate ->
            candidate.id == document.resourceId && candidate.href == document.href
        } ?: throw IllegalArgumentException("EPUB semantic spine resource is unavailable")
        require(
            resource.mediaType.equals("application/xhtml+xml", ignoreCase = true) ||
                resource.mediaType.equals("text/html", ignoreCase = true),
        ) { "EPUB semantic resource is not an HTML document" }
        val reference = resource.resource.blob
        require(reference.byteSize <= MAX_EPUB_SEMANTIC_XHTML_BYTES) {
            "EPUB XHTML exceeds the semantic extraction limit"
        }
        val bytes = requireNotNull(resourceReadGate.read { blobStore.read(reference) }) {
            "EPUB semantic resource read was denied or unavailable"
        }
        try {
            require(
                bytes.size.toLong() == reference.byteSize &&
                    Sha256.hex(bytes) == reference.plaintextDigest,
            ) { "EPUB semantic resource failed integrity verification" }
            return EpubSemanticExtractor.extract(navigation, documentIndex, bytes)
        } finally {
            bytes.fill(0)
        }
    }

    /** Chunked/cancellable body hydration for reader-on-demand and low-priority indexing. */
    public suspend fun createCancellable(
        navigation: EpubSpineNavigation,
        documentIndex: Int,
        resourceReadGate: EpubResourceReadGate = EpubResourceReadGate.Direct,
    ): EpubSemanticDocument {
        val document = navigation.representation.documents.getOrNull(documentIndex)
            ?: throw IllegalArgumentException("EPUB semantic document index is out of range")
        val resource = navigation.representation.packageGraph.resources.singleOrNull { candidate ->
            candidate.id == document.resourceId && candidate.href == document.href
        } ?: throw IllegalArgumentException("EPUB semantic spine resource is unavailable")
        require(
            resource.mediaType.equals("application/xhtml+xml", ignoreCase = true) ||
                resource.mediaType.equals("text/html", ignoreCase = true),
        ) { "EPUB semantic resource is not an HTML document" }
        val reference = resource.resource.blob
        require(reference.byteSize <= MAX_EPUB_SEMANTIC_XHTML_BYTES) {
            "EPUB XHTML exceeds the semantic extraction limit"
        }
        val authorizationProbe = requireNotNull(resourceReadGate.read { byteArrayOf(1) }) {
            "EPUB semantic resource read was denied or unavailable"
        }
        authorizationProbe.fill(0)
        val bytes = blobStore.readSemanticBodyCancellable(reference)
        try {
            currentCoroutineContext().ensureActive()
            require(Sha256.hex(bytes) == reference.plaintextDigest) {
                "EPUB semantic resource failed integrity verification"
            }
            currentCoroutineContext().ensureActive()
            return EpubSemanticExtractor.extractCancellable(navigation, documentIndex, bytes)
        } finally {
            bytes.fill(0)
        }
    }
}

/** Re-anchors a derived hit/utterance within the same exact DOM text node. */
public fun ReadingLocator.Epub.withRelativeTextOffset(
    deltaUtf16: Int,
    progression: Double?,
    quote: TextQuote? = null,
): ReadingLocator.Epub {
    require(deltaUtf16 >= 0) { "EPUB relative text offset must be non-negative" }
    val currentCfiOffset = cfiTextOffset(cfi)
    val currentDocumentOffset = requireNotNull(offsetHint) {
        "EPUB locator needs a canonical UTF-16 offset hint"
    }
    return copy(
        cfi = cfiWithTextOffset(cfi, currentCfiOffset + deltaUtf16),
        progression = progression,
        offsetHint = currentDocumentOffset + deltaUtf16,
        quote = quote,
    )
}

private data class CollectedTextRun(
    val text: String,
    val cfiBase: String,
    val blockGroupPath: String,
)

/**
 * EPUB XHTML commonly carries a legacy public DOCTYPE. We never resolve it (or any entity from
 * it), but the semantic parser may safely discard one bounded declaration without weakening the
 * stricter OPF/container parser used during acquisition.
 */
private fun semanticXmlBytes(source: ByteArray, cancellationCheckpoint: () -> Unit): ByteArray {
    var text = StrictTextDecoder.decode(
        source,
        normalizeLineEndings = true,
        cancellationCheckpoint = cancellationCheckpoint,
    ).text
    cancellationCheckpoint()
    val doctypeStart = text.indexOf("<!DOCTYPE", ignoreCase = true)
    if (doctypeStart >= 0) {
        val doctypeEnd = text.indexOf('>', doctypeStart + 9)
        require(doctypeEnd >= 0 && doctypeEnd - doctypeStart <= MAX_EPUB_DOCTYPE_CHARS) {
            "EPUB XHTML DOCTYPE is invalid or too large"
        }
        val declaration = text.substring(doctypeStart, doctypeEnd + 1)
        require('[' !in declaration && ']' !in declaration && declaration.count { it == '<' } == 1) {
            "EPUB XHTML internal entity subsets are not supported"
        }
        require(text.substring(0, doctypeStart).none { it == '<' } ||
            text.substring(0, doctypeStart).trimStart().startsWith("<?xml")) {
            "EPUB XHTML DOCTYPE must precede the document element"
        }
        text = text.removeRange(doctypeStart, doctypeEnd + 1)
    }
    // The bounded parser receives canonical UTF-8 bytes, so keep an optional declaration honest.
    XML_DECLARATION_ENCODING.find(text.take(MAX_XML_DECLARATION_SCAN_CHARS))?.let { match ->
        cancellationCheckpoint()
        text = text.replaceRange(
            match.range,
            match.groupValues[1] + "UTF-8" + match.groupValues[3],
        )
    }
    cancellationCheckpoint()
    return text.encodeToByteArray()
}

private fun cfiTextOffset(cfi: String): Int {
    val colon = cfi.lastIndexOf(':')
    require(colon >= 0 && cfi.endsWith(')')) { "EPUB CFI has no terminal text offset" }
    return cfi.substring(colon + 1, cfi.length - 1).toIntOrNull()
        ?: throw IllegalArgumentException("EPUB CFI terminal text offset is invalid")
}

internal fun cfiWithTextOffset(cfi: String, offsetUtf16: Int): String {
    require(offsetUtf16 >= 0) { "EPUB CFI text offset must be non-negative" }
    val colon = cfi.lastIndexOf(':')
    require(colon >= 0 && cfi.endsWith(')')) { "EPUB CFI has no terminal text offset" }
    cfi.substring(colon + 1, cfi.length - 1).toIntOrNull()
        ?: throw IllegalArgumentException("EPUB CFI terminal text offset is invalid")
    return cfi.substring(0, colon + 1) + offsetUtf16 + ')'
}

private fun progression(offsetUtf16: Int, lengthUtf16: Int): Double =
    if (lengthUtf16 == 0) 0.0 else offsetUtf16.toDouble() / lengthUtf16.toDouble()

private fun nearestUtf16Boundary(text: String, requested: Int): Int {
    var offset = requested.coerceIn(0, text.length)
    if (!isUtf16Boundary(text, offset)) offset--
    return offset
}

private fun isUtf16Boundary(text: String, offset: Int): Boolean =
    offset == 0 || offset == text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

private fun isWellFormedSelection(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val character = value[index]
        if (character.isISOControl() && character !in "\n\r\t") return false
        when {
            character.isHighSurrogate() -> {
                if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
                index += 2
            }
            character.isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private suspend fun ContentBlobStore.readSemanticBodyCancellable(
    reference: dev.shinsou.kmp.content.BlobRef,
): ByteArray {
    reference.validate()
    if (reference.byteSize > Int.MAX_VALUE.toLong() || reference.byteSize > maximumBlobSizeBytes) {
        throw ContentBlobStoreException.SizeLimitExceeded(
            reference.byteSize,
            minOf(Int.MAX_VALUE.toLong(), maximumBlobSizeBytes),
        )
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

private val NON_SEMANTIC_ELEMENTS: Set<String> = setOf(
    "head",
    "script",
    "style",
    "template",
    "noscript",
    "iframe",
    "object",
)
private val SEMANTIC_BLOCK_ELEMENTS: Set<String> = setOf(
    "address", "article", "aside", "blockquote", "dd", "div", "dl", "dt", "figcaption",
    "figure", "footer", "h1", "h2", "h3", "h4", "h5", "h6", "header", "li", "main",
    "nav", "ol", "p", "pre", "section", "table", "tbody", "td", "tfoot", "th", "thead",
    "tr", "ul",
)

private const val MAX_EPUB_SEMANTIC_XHTML_BYTES: Long = 16L * 1024 * 1024
private const val MAX_EPUB_SEMANTIC_TEXT_UTF16: Int = 5_000_000
private const val MAX_EPUB_SEMANTIC_BLOCKS: Int = 100_000
private const val MAX_EPUB_QUOTE_EXACT_UTF16: Int = 256
private const val MAX_EPUB_POINT_QUOTE_EXACT_UTF16: Int = 48
private const val MAX_EPUB_QUOTE_CONTEXT_UTF16: Int = 64
private const val MAX_SELECTION_OCCURRENCES: Int = 10_000
private const val MAX_SELECTION_SEARCH_WINDOW_UTF16: Int = 128 * 1024
private const val SEMANTIC_CANCELLATION_INTERVAL: Int = 256
private const val MAX_EPUB_DOCTYPE_CHARS: Int = 2_048
private const val MAX_XML_DECLARATION_SCAN_CHARS: Int = 1_024
private val XML_DECLARATION_ENCODING =
    Regex("(?i)(<\\?xml[^>]*?encoding\\s*=\\s*([\"']))[^\"']+([\"'][^>]*?\\?>)")
