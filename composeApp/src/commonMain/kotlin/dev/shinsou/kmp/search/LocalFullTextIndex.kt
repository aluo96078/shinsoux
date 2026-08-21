package dev.shinsou.kmp.search

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.reader.cfiWithTextOffset
import dev.shinsou.kmp.reader.validate
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.rights.RightsOperationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable

/** One normalized token together with its authoritative UTF-16 offsets in the original text. */
public data class FullTextToken(
    val value: String,
    val startUtf16: Int,
    val endUtf16: Int,
) {
    init {
        require(value.isNotBlank() && value.length <= MAX_TOKEN_LENGTH) { "Search token is invalid" }
        require(startUtf16 >= 0 && endUtf16 > startUtf16) { "Search token range is invalid" }
    }
}

/**
 * Deterministic tokenizer for CJK and Latin-family text.
 *
 * Han, kana, and hangul code points are indexed individually so queries work without a platform
 * word breaker.  Other letters and digits are grouped into case-folded words.  Offsets always
 * remain UTF-16 code-unit offsets, matching [ReadingLocator.Text].
 */
public object CjkLatinFullTextTokenizer {
    public fun tokenize(text: String, maxTokens: Int = MAX_DOCUMENT_TOKENS): List<FullTextToken> {
        return tokenizeInternal(text, maxTokens) {}
    }

    /** Background variant which observes foreground cancellation during very large documents. */
    public suspend fun tokenizeCancellable(
        text: String,
        maxTokens: Int = MAX_DOCUMENT_TOKENS,
    ): List<FullTextToken> {
        val context = currentCoroutineContext()
        return tokenizeInternal(text, maxTokens) { context.ensureActive() }
    }

    private fun tokenizeInternal(
        text: String,
        maxTokens: Int,
        cancellationCheckpoint: () -> Unit,
    ): List<FullTextToken> {
        require(text.length <= MAX_DOCUMENT_CHARS) { "Search document is too large" }
        require(maxTokens in 1..MAX_DOCUMENT_TOKENS) { "Search token limit is invalid" }
        val output = ArrayList<FullTextToken>(minOf(text.length, maxTokens))
        var wordStart = -1
        var index = 0

        fun flushWord(end: Int) {
            if (wordStart < 0 || output.size >= maxTokens) return
            val exact = text.substring(wordStart, end)
            val normalized = exact.lowercase()
            if (normalized.isNotBlank() && normalized.length <= MAX_TOKEN_LENGTH) {
                output += FullTextToken(normalized, wordStart, end)
            }
            wordStart = -1
        }

        while (index < text.length && output.size < maxTokens) {
            if (index % TOKENIZER_CANCELLATION_INTERVAL_UTF16 == 0) cancellationCheckpoint()
            val width = utf16CodePointWidth(text, index)
            val codePoint = codePointAt(text, index, width)
            when {
                isCjkCodePoint(codePoint) -> {
                    flushWord(index)
                    output += FullTextToken(
                        value = text.substring(index, index + width).lowercase(),
                        startUtf16 = index,
                        endUtf16 = index + width,
                    )
                }

                width == 1 && text[index].isLetterOrDigit() -> {
                    if (wordStart < 0) wordStart = index
                }

                else -> flushWord(index)
            }
            index += width
        }
        flushWord(index)
        cancellationCheckpoint()
        return output
    }
}

/** Plain text admitted into the local derived index. Body text is never serialized by this type. */
public data class SearchableTextDocument(
    val documentId: String,
    val scope: ReadingScope,
    val resourceId: String,
    val blockId: String,
    val text: String,
    val access: ContentAccessRequest,
    /** Absolute UTF-16 start of [text] in the canonical resource. */
    val baseOffsetUtf16: Int = 0,
    val canonicalDocumentUtf16Length: Int = text.length,
    /** Present only for EPUB-derived text; href/CFI remain authoritative over offset hints. */
    val epubAnchor: EpubSearchAnchor? = null,
) {
    init {
        requireSafeIdentifier(documentId, "Search document id")
        requireSafeIdentifier(resourceId, "Search resource id")
        requireSafeIdentifier(blockId, "Search block id")
        require(text.length <= MAX_DOCUMENT_CHARS) { "Search document is too large" }
        require(isWellFormedText(text)) { "Search document contains malformed text" }
        require(baseOffsetUtf16 >= 0 && canonicalDocumentUtf16Length >= baseOffsetUtf16 + text.length) {
            "Search block offset is outside its canonical document"
        }
        requireScopeMatches(scope, access)
        epubAnchor?.let { anchor ->
            anchor.validate()
            require(anchor.blockStartUtf16 <= baseOffsetUtf16) {
                "EPUB search segment starts before its semantic text node"
            }
        }
    }

    override fun toString(): String =
        "SearchableTextDocument(documentId=$documentId, text=<redacted>, chars=${text.length})"
}

@Serializable
public data class EpubSearchAnchor(
    val resourceHref: String,
    /** CFI for offset zero in the exact DOM text node represented by [blockId]. */
    val cfiBase: String,
    val blockStartUtf16: Int,
) {
    init { validate() }

    public fun validate() {
        require(resourceHref.isNotBlank() && resourceHref.length <= 4_096 &&
            resourceHref.none(Char::isWhitespace) && resourceHref.none(Char::isISOControl)
        ) { "EPUB search href is invalid" }
        require(cfiBase.startsWith("epubcfi(") && cfiBase.endsWith(":0)") && cfiBase.length <= 4_096) {
            "EPUB search CFI base is invalid"
        }
        require(blockStartUtf16 >= 0) { "EPUB search block offset is invalid" }
    }
}

public data class FullTextSearchHit(
    val documentId: String,
    val locator: ReadingLocator,
    val snippet: String,
    val matchedTerms: List<String>,
    val score: Int,
) {
    init {
        requireSafeIdentifier(documentId, "Search result document id")
        locator.validate()
        require(snippet.length <= MAX_SNIPPET_CHARS && isWellFormedText(snippet)) {
            "Search snippet is invalid"
        }
        require(matchedTerms.isNotEmpty() && matchedTerms.size <= MAX_QUERY_TOKENS) {
            "Search result needs bounded matched terms"
        }
        require(score > 0) { "Search score must be positive" }
    }
}

/**
 * One bounded derived-index transaction for a semantic manifest block.
 *
 * [blockId] deliberately remains the manifest block id so every hit resolves through the same
 * reader/annotation locator. Only [documentId] is segmented; this is derived state and therefore
 * does not alter portable identity or reading progress.
 */
internal data class FullTextDocumentSegment(
    val documentId: String,
    val blockId: String,
    val startUtf16: Int,
    val endUtf16: Int,
) {
    init {
        require(startUtf16 >= 0 && endUtf16 >= startUtf16)
        require(endUtf16 - startUtf16 <= MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH)
    }
}

/** Splits an arbitrarily large semantic block into surrogate-safe, transaction-bounded slices. */
internal fun fullTextDocumentSegments(
    representationId: String,
    block: TextBlock,
    canonicalText: String,
): List<FullTextDocumentSegment> = fullTextDocumentSegmentsLazy(
    representationId = representationId,
    block = block,
    canonicalText = canonicalText,
).toList()

/**
 * Lazy form used by the foreground reader so an unopened search never scans the complete book and
 * a cancelled search never materializes later slices from the current semantic block.
 */
internal fun fullTextDocumentSegmentsLazy(
    representationId: String,
    block: TextBlock,
    canonicalText: String,
): Sequence<FullTextDocumentSegment> = sequence {
    block.validate()
    require(block.endUtf16 <= canonicalText.length) { "Search block exceeds canonical text" }
    val blockLength = block.endUtf16 - block.startUtf16
    if (blockLength <= MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH) {
        yield(
            FullTextDocumentSegment(
                documentId = fullTextDocumentId(representationId, block.blockId),
                blockId = block.blockId,
                startUtf16 = block.startUtf16,
                endUtf16 = block.endUtf16,
            ),
        )
        return@sequence
    }

    var start = block.startUtf16
    var segmentIndex = 0
    while (start < block.endUtf16) {
        var end = minOf(start + MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH, block.endUtf16)
        if (
            end < block.endUtf16 && end > start &&
            canonicalText[end - 1].isHighSurrogate() && canonicalText[end].isLowSurrogate()
        ) {
            end--
        }
        check(end > start) { "Search segment did not advance" }
        yield(
            FullTextDocumentSegment(
                documentId = fullTextSegmentDocumentId(representationId, block.blockId, segmentIndex),
                blockId = block.blockId,
                startUtf16 = start,
                endUtf16 = end,
            ),
        )
        start = end
        segmentIndex++
    }
}

/** Computes the same stable ids without hydrating body text, for deny/remove paths. */
internal fun fullTextDocumentIds(representationId: String, block: TextBlock): List<String> {
    block.validate()
    val blockLength = block.endUtf16 - block.startUtf16
    if (blockLength <= MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH) {
        return listOf(fullTextDocumentId(representationId, block.blockId))
    }
    // Every surrogate-safe boundary may move back by one UTF-16 code unit, so use the minimum
    // guaranteed progress as an upper bound. Extra ids are harmless on deny/remove paths.
    val minimumSegmentProgress = MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH - 1
    val segmentCount = (blockLength + minimumSegmentProgress - 1) / minimumSegmentProgress
    return buildList(segmentCount + 1) {
        // Remove the pre-segmentation id as well when upgrading a derived index in place.
        add(fullTextDocumentId(representationId, block.blockId))
        repeat(segmentCount) { segmentIndex ->
            add(fullTextSegmentDocumentId(representationId, block.blockId, segmentIndex))
        }
    }
}

private fun fullTextSegmentDocumentId(
    representationId: String,
    blockId: String,
    segmentIndex: Int,
): String = fullTextDocumentId(representationId, "$blockId#segment-$segmentIndex")

public interface LocalFullTextIndex {
    public val documentCount: Int
    public fun upsert(document: SearchableTextDocument)
    public fun remove(documentId: String): Boolean
    public fun clear()
    /** Removes plaintext, tokens, quotes, and snippets whose current grant no longer permits indexing. */
    public fun purgeUnauthorized(): Int
    public fun search(query: String, limit: Int = DEFAULT_RESULT_LIMIT): List<FullTextSearchHit>
}

/**
 * Deterministic in-memory fixture for common contract tests. Production uses the shared-driver
 * [DerivedLocalFullTextIndex], so closing a runtime never discards the durable derived index.
 */
internal class InMemoryDerivedLocalFullTextIndex(
    private val operationGate: ContentOperationGate,
) : LocalFullTextIndex {
    private data class IndexedDocument(
        val source: SearchableTextDocument,
        val tokens: List<FullTextToken>,
    )

    private val documents = LinkedHashMap<String, IndexedDocument>()
    private val indexMutex = SynchronousLock()

    override val documentCount: Int
        get() = withIndexLock {
            purgeUnauthorizedLocked()
            documents.size
        }

    override fun upsert(document: SearchableTextDocument) {
        // The rights context is canonical-document sized even when indexing one locator block;
        // splitting a document must never bypass a MaxTextChars restriction.
        val access = document.access.withActualTextCharacters(document.canonicalDocumentUtf16Length)
        operationGate.execute(access, ContentOperation.SEARCH_INDEX) {
            val indexed = IndexedDocument(
                source = document.copy(access = access),
                tokens = CjkLatinFullTextTokenizer.tokenize(document.text),
            )
            withIndexLock { documents[document.documentId] = indexed }
        }
    }

    override fun remove(documentId: String): Boolean {
        requireSafeIdentifier(documentId, "Search document id")
        return withIndexLock { documents.remove(documentId) != null }
    }

    override fun clear() {
        withIndexLock { documents.clear() }
    }

    override fun purgeUnauthorized(): Int = withIndexLock { purgeUnauthorizedLocked() }

    private fun purgeUnauthorizedLocked(): Int {
        val deniedIds = documents.values
            .filter { indexed ->
                operationGate.decide(indexed.source.access, ContentOperation.SEARCH_INDEX) !=
                    RightsDecision.ALLOW
            }
            .map { it.source.documentId }
        deniedIds.forEach(documents::remove)
        return deniedIds.size
    }

    override fun search(query: String, limit: Int): List<FullTextSearchHit> {
        require(query.length in 1..MAX_QUERY_CHARS && isWellFormedText(query)) {
            "Search query is invalid"
        }
        require(limit in 1..MAX_RESULT_LIMIT) { "Search result limit is invalid" }
        val queryTokens = CjkLatinFullTextTokenizer.tokenize(query, MAX_QUERY_TOKENS)
            .map(FullTextToken::value)
            .distinct()
        if (queryTokens.isEmpty()) return emptyList()

        return withIndexLock {
            purgeUnauthorizedLocked()
            documents.values.asSequence()
                .mapNotNull { indexed -> indexed.toHit(queryTokens) }
                .sortedWith(
                    compareByDescending<FullTextSearchHit>(FullTextSearchHit::score)
                        .thenBy(FullTextSearchHit::documentId),
                )
                .take(limit)
                .toList()
        }
    }

    private fun IndexedDocument.toHit(queryTokens: List<String>): FullTextSearchHit? {
        val byValue = tokens.groupBy(FullTextToken::value)
        if (queryTokens.any { it !in byValue }) return null
        val first = queryTokens.asSequence()
            .mapNotNull { byValue[it]?.firstOrNull() }
            .minByOrNull(FullTextToken::startUtf16)
            ?: return null
        val quote = source.text.quoteAt(first.startUtf16, first.endUtf16)
        val absoluteOffset = source.baseOffsetUtf16 + first.startUtf16
        val locator = source.locatorForSearchMatch(absoluteOffset, quote)
        val snippetStart = safeBoundaryAtOrBefore(source.text, maxOf(0, first.startUtf16 - SNIPPET_CONTEXT_CHARS))
        val snippetEnd = safeBoundaryAtOrAfter(
            source.text,
            minOf(source.text.length, first.endUtf16 + SNIPPET_CONTEXT_CHARS),
        )
        val boundedSnippetEnd = safeBoundaryAtOrBefore(
            source.text,
            minOf(snippetEnd, snippetStart + MAX_SNIPPET_CHARS),
        )
        val score = queryTokens.sumOf { token -> byValue.getValue(token).size }
        return FullTextSearchHit(
            documentId = source.documentId,
            locator = locator,
            snippet = source.text.substring(snippetStart, boundedSnippetEnd),
            matchedTerms = queryTokens,
            score = score,
        )
    }

    private inline fun <T> withIndexLock(block: () -> T): T {
        return indexMutex.withLock(block)
    }
}

internal fun SearchableTextDocument.locatorForSearchMatch(
    absoluteOffset: Int,
    quote: TextQuote,
): ReadingLocator {
    val normalizedProgression = if (canonicalDocumentUtf16Length == 0) {
        0.0
    } else {
        absoluteOffset.toDouble() / canonicalDocumentUtf16Length
    }
    val anchor = epubAnchor
    return if (anchor == null) {
        ReadingLocator.Text(
            schemaVersion = scope.schemaVersion,
            scope = scope,
            resourceId = resourceId,
            blockId = blockId,
            offset = absoluteOffset,
            progression = normalizedProgression,
            quote = quote,
        )
    } else {
        ReadingLocator.Epub(
            schemaVersion = scope.schemaVersion,
            scope = scope,
            resourceId = resourceId,
            resourceHref = anchor.resourceHref,
            cfi = cfiWithTextOffset(anchor.cfiBase, absoluteOffset - anchor.blockStartUtf16),
            progression = normalizedProgression,
            offsetHint = absoluteOffset,
            blockIdHint = blockId,
            quote = quote,
        )
    }
}

private fun ContentAccessRequest.withActualTextCharacters(length: Int): ContentAccessRequest = copy(
    context = RightsOperationContext(
        offlineBytes = context.offlineBytes,
        textCharacters = length.toLong(),
        watermarkApplied = context.watermarkApplied,
    ),
)

private fun String.quoteAt(start: Int, end: Int): TextQuote {
    val prefixStart = safeBoundaryAtOrAfter(this, maxOf(0, start - QUOTE_CONTEXT_CHARS))
    val suffixEnd = safeBoundaryAtOrBefore(this, minOf(length, end + QUOTE_CONTEXT_CHARS))
    return TextQuote(
        exact = substring(start, end),
        prefix = substring(prefixStart, start),
        suffix = substring(end, suffixEnd),
    )
}

private fun requireScopeMatches(scope: ReadingScope, access: ContentAccessRequest) {
    val rights = access.scope
    require(rights.publicationId == scope.publicationId &&
        rights.acquisitionId == scope.acquisitionId &&
        rights.unitId == scope.unitId &&
        rights.contentRevision == scope.contentRevision) {
        "Search document and rights scope do not match"
    }
}

private fun codePointAt(text: String, index: Int, width: Int): Int = if (width == 1) {
    text[index].code
} else {
    val high = text[index].code - 0xD800
    val low = text[index + 1].code - 0xDC00
    0x10000 + (high shl 10) + low
}

private fun utf16CodePointWidth(text: String, index: Int): Int =
    if (text[index].isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate()) 2 else 1

private fun isCjkCodePoint(value: Int): Boolean =
    value in 0x3400..0x4DBF || value in 0x4E00..0x9FFF || value in 0xF900..0xFAFF ||
        value in 0x20000..0x2EBEF || value in 0x3040..0x30FF || value in 0x31F0..0x31FF ||
        value in 0xAC00..0xD7AF

private fun safeBoundaryAtOrBefore(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index--
    return index
}

private fun safeBoundaryAtOrAfter(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (index in 1 until text.length && text[index - 1].isHighSurrogate() && text[index].isLowSurrogate()) index++
    return index
}

private fun isWellFormedText(value: String): Boolean {
    for (index in value.indices) {
        val character = value[index]
        if (character == '\u0000' || (character.isISOControl() && character !in "\n\r\t")) return false
        if (character.isHighSurrogate()) {
            if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) return false
        } else if (character.isLowSurrogate()) {
            if (index == 0 || !value[index - 1].isHighSurrogate()) return false
        }
    }
    return true
}

private fun requireSafeIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH &&
        value.none(Char::isWhitespace) && value.none(Char::isISOControl)) {
        "$label must be bounded and printable"
    }
}

private const val MAX_IDENTIFIER_LENGTH: Int = 512
private const val MAX_TOKEN_LENGTH: Int = 256
private const val MAX_DOCUMENT_CHARS: Int = 5_000_000
private const val MAX_DOCUMENT_TOKENS: Int = 1_000_000
private const val TOKENIZER_CANCELLATION_INTERVAL_UTF16: Int = 4_096
private const val MAX_QUERY_CHARS: Int = 512
private const val MAX_QUERY_TOKENS: Int = 64
private const val DEFAULT_RESULT_LIMIT: Int = 25
private const val MAX_RESULT_LIMIT: Int = 100
private const val QUOTE_CONTEXT_CHARS: Int = 64
private const val SNIPPET_CONTEXT_CHARS: Int = 96
private const val MAX_SNIPPET_CHARS: Int = 256
internal const val MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH: Int = 16 * 1024
