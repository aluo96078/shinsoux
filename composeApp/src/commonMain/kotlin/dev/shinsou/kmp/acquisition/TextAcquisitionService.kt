package dev.shinsou.kmp.acquisition

import dev.shinsou.kmp.content.ContentBlobStore
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

public enum class TextEncodingHint {
    AUTO,
    UTF_8,
    UTF_16_LE,
    UTF_16_BE,
}

public enum class SourceTextEncoding(public val canonicalName: String) {
    UTF_8("UTF-8"),
    UTF_16_LE("UTF-16LE"),
    UTF_16_BE("UTF-16BE"),
}

public data class DecodedText(
    val text: String,
    val sourceEncoding: SourceTextEncoding,
    val hadByteOrderMark: Boolean,
)

/** Strict common-code decoder; malformed input is never silently replaced. */
public object StrictTextDecoder {
    public fun decode(
        bytes: ByteArray,
        hint: TextEncodingHint = TextEncodingHint.AUTO,
        normalizeLineEndings: Boolean = true,
        cancellationCheckpoint: () -> Unit = {},
    ): DecodedText {
        cancellationCheckpoint()
        require(!bytes.hasPrefix(UTF_32_LE_BOM) && !bytes.hasPrefix(UTF_32_BE_BOM)) {
            "UTF-32 text is not supported"
        }
        val bomEncoding = when {
            bytes.hasPrefix(UTF_8_BOM) -> SourceTextEncoding.UTF_8
            bytes.hasPrefix(UTF_16_LE_BOM) -> SourceTextEncoding.UTF_16_LE
            bytes.hasPrefix(UTF_16_BE_BOM) -> SourceTextEncoding.UTF_16_BE
            else -> null
        }
        val requestedEncoding = when (hint) {
            TextEncodingHint.AUTO -> bomEncoding ?: SourceTextEncoding.UTF_8
            TextEncodingHint.UTF_8 -> SourceTextEncoding.UTF_8
            TextEncodingHint.UTF_16_LE -> SourceTextEncoding.UTF_16_LE
            TextEncodingHint.UTF_16_BE -> SourceTextEncoding.UTF_16_BE
        }
        require(bomEncoding == null || bomEncoding == requestedEncoding) {
            "Text byte-order mark conflicts with the encoding hint"
        }
        val bomSize = when (bomEncoding) {
            SourceTextEncoding.UTF_8 -> UTF_8_BOM.size
            SourceTextEncoding.UTF_16_LE,
            SourceTextEncoding.UTF_16_BE,
            -> UTF_16_LE_BOM.size
            null -> 0
        }
        val decoded = when (requestedEncoding) {
            SourceTextEncoding.UTF_8 -> bytes.decodeUtf8Strict(bomSize, cancellationCheckpoint)
            SourceTextEncoding.UTF_16_LE -> bytes.decodeUtf16Strict(
                bomSize,
                littleEndian = true,
                cancellationCheckpoint = cancellationCheckpoint,
            )
            SourceTextEncoding.UTF_16_BE -> bytes.decodeUtf16Strict(
                bomSize,
                littleEndian = false,
                cancellationCheckpoint = cancellationCheckpoint,
            )
        }
        decoded.requireValidTextCharacters(cancellationCheckpoint)
        return DecodedText(
            text = if (normalizeLineEndings) {
                decoded.normalizeLineEndings(cancellationCheckpoint)
            } else {
                decoded
            },
            sourceEncoding = requestedEncoding,
            hadByteOrderMark = bomEncoding != null,
        )
    }
}

public data class TextAcquisitionPolicy(
    val maximumSourceBytes: Long = 64L * 1024 * 1024,
    val maximumBlocks: Int = 100_000,
    val maximumChapters: Int = 10_000,
) {
    init {
        require(maximumSourceBytes > 0 && maximumSourceBytes <= Int.MAX_VALUE) {
            "Maximum text source size must be positive and addressable"
        }
        require(maximumBlocks in 1..100_000) { "Maximum text block count must fit the content manifest" }
        require(maximumChapters > 0) { "Maximum text chapter count must be positive" }
    }
}

public data class TextAcquisitionRequest(
    val target: LocalAcquisitionTarget,
    val sourceBytes: ByteArray,
    val encodingHint: TextEncodingHint = TextEncodingHint.AUTO,
)

public data class TextChapter(
    val chapterId: String,
    val title: String,
    val startUtf16: Int,
    val endUtf16: Int,
    val blockIds: List<String>,
) {
    init {
        require(chapterId.isNotBlank() && chapterId.none(Char::isWhitespace)) { "Text chapter id is invalid" }
        require(title.none(Char::isISOControl)) { "Text chapter title contains control characters" }
        require(startUtf16 >= 0 && endUtf16 >= startUtf16) { "Text chapter range is invalid" }
        require(blockIds.distinct().size == blockIds.size) { "Text chapter block ids must be unique" }
    }
}

public data class TextAcquisitionMetadata(
    val canonicalText: String,
    val sourceEncoding: SourceTextEncoding,
    val hadByteOrderMark: Boolean,
    val chapters: List<TextChapter>,
) {
    init {
        require(canonicalText.isWellFormedUtf16()) { "Canonical text is malformed" }
        require(chapters.isNotEmpty()) { "Text acquisition needs at least one chapter" }
        require(chapters.zipWithNext().all { (left, right) -> left.endUtf16 <= right.startUtf16 }) {
            "Text chapters must be ordered and non-overlapping"
        }
        require(chapters.all { it.endUtf16 <= canonicalText.length }) { "Text chapter exceeds the document" }
    }
}

/** Decodes, maps and publishes one canonical UTF-8 text representation. */
public class TextAcquisitionService(
    private val blobStore: ContentBlobStore,
    private val identityDeriver: LocalAcquisitionIdentityDeriver = LocalAcquisitionIdentityDeriver(),
    private val policy: TextAcquisitionPolicy = TextAcquisitionPolicy(),
    private val authorizeOfflineStore: (byteCount: Long) -> Unit = {},
    private val cancellationCheckpoint: () -> Unit = {},
) {
    public fun acquire(request: TextAcquisitionRequest): ContentAcquisitionResult<TextAcquisitionMetadata> {
        cancellationCheckpoint()
        require(request.sourceBytes.size.toLong() <= policy.maximumSourceBytes) {
            "Text source exceeds the configured size limit"
        }
        val decoded = StrictTextDecoder.decode(
            request.sourceBytes,
            request.encodingHint,
            cancellationCheckpoint = cancellationCheckpoint,
        )
        val canonicalByteSize = decoded.text.utf8ByteSize(cancellationCheckpoint)
        require(canonicalByteSize <= policy.maximumSourceBytes) {
            "Canonical UTF-8 text exceeds the configured size limit"
        }
        val blocks = buildBlocks(request.target, decoded.text)
        val chapters = buildChapters(request.target, decoded.text, blocks)
        require(blocks.size <= policy.maximumBlocks) { "Text contains too many locator blocks" }
        require(chapters.size <= policy.maximumChapters) { "Text contains too many chapters" }

        // Parsing and all bounded-map validation happens before this first externally visible write.
        authorizeOfflineStore(canonicalByteSize)
        val published = publishCanonicalText(decoded.text, canonicalByteSize)
        val target = request.target
        val representation = ContentRepresentation.PlainText(
            representationId = identityDeriver.representationId(target, LocalContentFormat.PLAIN_TEXT),
            resource = ResourceRef(
                id = identityDeriver.resourceId(target, LocalContentFormat.PLAIN_TEXT, TEXT_LOGICAL_PATH),
                blob = published.reference,
            ),
            canonicalUtf16Length = decoded.text.length,
            sourceEncoding = decoded.sourceEncoding.canonicalName,
            blocks = blocks,
        )
        val manifest = ContentManifest(
            manifestId = identityDeriver.manifestId(
                target,
                LocalContentFormat.PLAIN_TEXT,
                Sha256.hex(
                    (published.reference.plaintextDigest + "\u0000" + decoded.sourceEncoding.canonicalName)
                        .encodeToByteArray(),
                ),
            ),
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = target.contentRevision,
            representations = listOf(representation),
            declaredSizeBytes = published.reference.byteSize,
        )
        val unit = PublicationUnit(
            key = UnitKey(
                target.publicationKey,
                identityDeriver.unitId(target, LocalContentFormat.PLAIN_TEXT),
            ),
            title = target.unitTitle,
            manifestRevisions = listOf(manifest),
        )
        val acquisition = Acquisition(
            id = identityDeriver.acquisitionId(target, LocalContentFormat.PLAIN_TEXT),
            origin = AcquisitionOrigin.LocalText,
            units = listOf(unit),
            contentRevision = target.contentRevision,
            acquiredAtEpochMillis = target.acquiredAtEpochMillis,
        )
        return ContentAcquisitionResult(
            publicationDraft = Publication(
                key = target.publicationKey,
                title = target.publicationTitle,
                acquisitions = listOf(acquisition),
            ),
            metadata = TextAcquisitionMetadata(
                canonicalText = decoded.text,
                sourceEncoding = decoded.sourceEncoding,
                hadByteOrderMark = decoded.hadByteOrderMark,
                chapters = chapters,
            ),
            publishedBlobs = listOf(published),
        )
    }

    private fun buildBlocks(target: LocalAcquisitionTarget, text: String): List<TextBlock> {
        val ranges = paragraphRanges(text, cancellationCheckpoint).ifEmpty { listOf(TextRange(0, 0)) }
        val occurrences = HashMap<String, Int>()
        return ranges.map { range ->
            cancellationCheckpoint()
            val digest = text.sha256Hex(range.start, range.end, cancellationCheckpoint)
            val occurrence = occurrences[digest] ?: 0
            occurrences[digest] = occurrence + 1
            TextBlock(
                blockId = identityDeriver.blockId(target, digest, occurrence),
                startUtf16 = range.start,
                endUtf16 = range.end,
            )
        }
    }

    private fun buildChapters(
        target: LocalAcquisitionTarget,
        text: String,
        blocks: List<TextBlock>,
    ): List<TextChapter> {
        val headings = textLines(text, cancellationCheckpoint).mapNotNull { line ->
            cancellationCheckpoint()
            val title = if (line.end - line.start <= MAX_CHAPTER_HEADING_LENGTH) {
                recognizedChapterTitle(text.substring(line.start, line.end))
            } else {
                null
            }
            title?.let { ChapterHeading(line.start, it) }
        }
        val seeds = buildList {
            if (headings.isEmpty()) {
                add(ChapterHeading(0, target.unitTitle.ifBlank { target.publicationTitle }))
            } else {
                val first = headings.first()
                if (first.start > 0 && !text.isRangeBlank(0, first.start, cancellationCheckpoint)) {
                    add(ChapterHeading(0, target.unitTitle.ifBlank { target.publicationTitle }))
                }
                addAll(headings)
            }
        }
        val titleOccurrences = HashMap<String, Int>()
        return seeds.mapIndexed { index, seed ->
            cancellationCheckpoint()
            val end = seeds.getOrNull(index + 1)?.start ?: text.length
            val occurrence = titleOccurrences[seed.title] ?: 0
            titleOccurrences[seed.title] = occurrence + 1
            val ids = buildList {
                blocks.forEach { block ->
                    cancellationCheckpoint()
                    val overlaps = if (block.startUtf16 == block.endUtf16) {
                        block.startUtf16 in seed.start..end
                    } else {
                        block.startUtf16 < end && block.endUtf16 > seed.start
                    }
                    if (overlaps) add(block.blockId)
                }
            }
            TextChapter(
                chapterId = identityDeriver.chapterId(target, seed.title, occurrence),
                title = seed.title,
                startUtf16 = seed.start,
                endUtf16 = end,
                blockIds = ids,
            )
        }.ifEmpty {
            listOf(
                TextChapter(
                    chapterId = identityDeriver.chapterId(target, target.unitTitle, 0),
                    title = target.unitTitle,
                    startUtf16 = 0,
                    endUtf16 = text.length,
                    blockIds = blocks.map(TextBlock::blockId),
                ),
            )
        }
    }

    private fun publishCanonicalText(text: String, byteSize: Long): dev.shinsou.kmp.content.BlobPublishReceipt {
        val stage = blobStore.beginStage(byteSize, TEXT_MEDIA_TYPE)
        return try {
            text.forEachUtf8Chunk(cancellationCheckpoint, stage::append)
            blobStore.publish(stage.seal())
        } catch (failure: Throwable) {
            stage.abort()
            throw failure
        }
    }
}

private data class TextRange(val start: Int, val end: Int)
private data class TextLine(val start: Int, val end: Int)
private data class ChapterHeading(val start: Int, val title: String)

private fun paragraphRanges(
    text: String,
    cancellationCheckpoint: () -> Unit,
): List<TextRange> {
    if (text.isEmpty()) return emptyList()
    val ranges = ArrayList<TextRange>()
    var paragraphStart: Int? = null
    var paragraphEnd = 0
    textLines(text, cancellationCheckpoint).forEach { line ->
        cancellationCheckpoint()
        if (text.isRangeBlank(line.start, line.end, cancellationCheckpoint)) {
            val start = paragraphStart
            if (start != null) {
                ranges += TextRange(start, paragraphEnd)
                paragraphStart = null
            }
        } else {
            if (paragraphStart == null) paragraphStart = line.start
            paragraphEnd = line.end
        }
    }
    paragraphStart?.let { ranges += TextRange(it, paragraphEnd) }
    return ranges
}

private fun textLines(
    text: String,
    cancellationCheckpoint: () -> Unit,
): List<TextLine> {
    if (text.isEmpty()) return listOf(TextLine(0, 0))
    val lines = ArrayList<TextLine>()
    var start = 0
    var index = 0
    while (index <= text.length) {
        if (index % TEXT_CANCELLATION_CHECKPOINT_CHARS == 0) cancellationCheckpoint()
        if (index == text.length || text[index] == '\n') {
            lines += TextLine(start, index)
            start = index + 1
        }
        index++
    }
    cancellationCheckpoint()
    return lines
}

private fun recognizedChapterTitle(line: String): String? {
    val value = line.trim()
    if (value.isEmpty() || value.length > MAX_CHAPTER_HEADING_LENGTH) return null
    val markdown = MARKDOWN_HEADING.matchEntire(value)?.groupValues?.get(1)?.trim()
    if (!markdown.isNullOrEmpty()) return markdown
    return value.takeIf { LATIN_CHAPTER.matches(it) || CJK_CHAPTER.matches(it) }
}

private fun ByteArray.decodeUtf8Strict(
    offset: Int,
    cancellationCheckpoint: () -> Unit,
): String {
    val output = StringBuilder(size - offset)
    var cursor = offset
    var nextCheckpoint = offset

    fun raw(at: Int): Int {
        require(at < size) { "UTF-8 text is truncated" }
        return this[at].toInt() and 0xff
    }

    fun continuation(at: Int): Int {
        val value = raw(at)
        require(value in 0x80..0xbf) { "UTF-8 text has an invalid continuation byte" }
        return value and 0x3f
    }

    fun appendCodePoint(value: Int) {
        if (value <= 0xffff) {
            output.append(value.toChar())
        } else {
            val supplementary = value - 0x10000
            output.append((0xd800 + (supplementary ushr 10)).toChar())
            output.append((0xdc00 + (supplementary and 0x3ff)).toChar())
        }
    }

    while (cursor < size) {
        if (cursor >= nextCheckpoint) {
            cancellationCheckpoint()
            nextCheckpoint = cursor + TEXT_CANCELLATION_CHECKPOINT_BYTES
        }
        val first = raw(cursor)
        when {
            first <= 0x7f -> {
                output.append(first.toChar())
                cursor++
            }
            first in 0xc2..0xdf -> {
                appendCodePoint(((first and 0x1f) shl 6) or continuation(cursor + 1))
                cursor += 2
            }
            first in 0xe0..0xef -> {
                val secondRaw = raw(cursor + 1)
                require(secondRaw in 0x80..0xbf) { "UTF-8 text has an invalid continuation byte" }
                require(first != 0xe0 || secondRaw >= 0xa0) { "UTF-8 text has an overlong sequence" }
                require(first != 0xed || secondRaw <= 0x9f) { "UTF-8 text encodes a surrogate" }
                appendCodePoint(
                    ((first and 0x0f) shl 12) or
                        ((secondRaw and 0x3f) shl 6) or
                        continuation(cursor + 2),
                )
                cursor += 3
            }
            first in 0xf0..0xf4 -> {
                val secondRaw = raw(cursor + 1)
                require(secondRaw in 0x80..0xbf) { "UTF-8 text has an invalid continuation byte" }
                require(first != 0xf0 || secondRaw >= 0x90) { "UTF-8 text has an overlong sequence" }
                require(first != 0xf4 || secondRaw <= 0x8f) { "UTF-8 text exceeds Unicode" }
                appendCodePoint(
                    ((first and 0x07) shl 18) or
                        ((secondRaw and 0x3f) shl 12) or
                        (continuation(cursor + 2) shl 6) or
                        continuation(cursor + 3),
                )
                cursor += 4
            }
            else -> throw IllegalArgumentException("UTF-8 text has an invalid leading byte")
        }
    }
    cancellationCheckpoint()
    return output.toString()
}

private fun ByteArray.decodeUtf16Strict(
    offset: Int,
    littleEndian: Boolean,
    cancellationCheckpoint: () -> Unit,
): String {
    require((size - offset) % 2 == 0) { "UTF-16 text has an incomplete code unit" }
    val output = StringBuilder((size - offset) / 2)
    var cursor = offset
    var nextCheckpoint = offset
    while (cursor < size) {
        if (cursor >= nextCheckpoint) {
            cancellationCheckpoint()
            nextCheckpoint = cursor + TEXT_CANCELLATION_CHECKPOINT_BYTES
        }
        val first = this[cursor].toInt() and 0xff
        val second = this[cursor + 1].toInt() and 0xff
        val codeUnit = if (littleEndian) first or (second shl 8) else (first shl 8) or second
        output.append(codeUnit.toChar())
        cursor += 2
    }
    cancellationCheckpoint()
    return output.toString()
}

private fun String.requireValidTextCharacters(cancellationCheckpoint: () -> Unit) {
    var index = 0
    while (index < length) {
        if (index % TEXT_CANCELLATION_CHECKPOINT_CHARS == 0) cancellationCheckpoint()
        val value = this[index]
        require(value != '\u0000') { "Text contains a NUL character" }
        when {
            value.isHighSurrogate() -> {
                require(index + 1 < length && this[index + 1].isLowSurrogate()) {
                    "Text contains malformed UTF-16"
                }
                index += 2
            }
            value.isLowSurrogate() -> throw IllegalArgumentException("Text contains malformed UTF-16")
            else -> index++
        }
    }
    cancellationCheckpoint()
}

private fun String.isWellFormedUtf16(): Boolean {
    var index = 0
    while (index < length) {
        when {
            this[index].isHighSurrogate() -> {
                if (index + 1 >= length || !this[index + 1].isLowSurrogate()) return false
                index += 2
            }
            this[index].isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}

private fun String.normalizeLineEndings(cancellationCheckpoint: () -> Unit): String {
    val output = StringBuilder(length)
    var index = 0
    while (index < length) {
        if (index % TEXT_CANCELLATION_CHECKPOINT_CHARS == 0) cancellationCheckpoint()
        val value = this[index]
        if (value == '\r') {
            output.append('\n')
            index += if (index + 1 < length && this[index + 1] == '\n') 2 else 1
        } else {
            output.append(value)
            index++
        }
    }
    cancellationCheckpoint()
    return output.toString()
}

private fun String.utf8ByteSize(cancellationCheckpoint: () -> Unit): Long {
    var total = 0L
    var index = 0
    while (index < length) {
        if (index % TEXT_CANCELLATION_CHECKPOINT_CHARS == 0) cancellationCheckpoint()
        val value = this[index]
        when {
            value.code <= 0x7f -> total++
            value.code <= 0x7ff -> total += 2
            value.isHighSurrogate() -> {
                total += 4
                index++
            }
            else -> total += 3
        }
        index++
    }
    cancellationCheckpoint()
    return total
}

private fun String.forEachUtf8Chunk(
    cancellationCheckpoint: () -> Unit,
    consume: (ByteArray) -> Unit,
) {
    forEachUtf8Chunk(0, length, cancellationCheckpoint, consume)
}

private fun String.forEachUtf8Chunk(
    start: Int,
    end: Int,
    cancellationCheckpoint: () -> Unit,
    consume: (ByteArray) -> Unit,
) {
    require(start in 0..end && end <= length) { "Text UTF-8 range is invalid" }
    var cursor = start
    while (cursor < end) {
        cancellationCheckpoint()
        var chunkEnd = minOf(cursor + TEXT_UTF8_CHUNK_CHARS, end)
        if (chunkEnd < end && this[chunkEnd - 1].isHighSurrogate() && this[chunkEnd].isLowSurrogate()) {
            chunkEnd--
        }
        consume(substring(cursor, chunkEnd).encodeToByteArray())
        cursor = chunkEnd
    }
    cancellationCheckpoint()
}

private fun String.sha256Hex(
    start: Int,
    end: Int,
    cancellationCheckpoint: () -> Unit,
): String {
    val hashing = HashingSink.sha256(blackholeSink())
    val buffer = Buffer()
    return try {
        forEachUtf8Chunk(start, end, cancellationCheckpoint) { chunk ->
            buffer.write(chunk)
            hashing.write(buffer, chunk.size.toLong())
        }
        hashing.hash.hex()
    } finally {
        hashing.close()
    }
}

private fun String.isRangeBlank(
    start: Int,
    end: Int,
    cancellationCheckpoint: () -> Unit,
): Boolean {
    require(start in 0..end && end <= length) { "Text range is invalid" }
    var index = start
    while (index < end) {
        if ((index - start) % TEXT_CANCELLATION_CHECKPOINT_CHARS == 0) cancellationCheckpoint()
        if (!this[index].isWhitespace()) return false
        index++
    }
    cancellationCheckpoint()
    return true
}

private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { index -> this[index] == prefix[index] }

private val UTF_8_BOM = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
private val UTF_16_LE_BOM = byteArrayOf(0xff.toByte(), 0xfe.toByte())
private val UTF_16_BE_BOM = byteArrayOf(0xfe.toByte(), 0xff.toByte())
private val UTF_32_LE_BOM = byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00, 0x00)
private val UTF_32_BE_BOM = byteArrayOf(0x00, 0x00, 0xfe.toByte(), 0xff.toByte())
private val MARKDOWN_HEADING = Regex("^#{1,6}\\s+(.+?)\\s*#*$")
private val LATIN_CHAPTER = Regex("^(?:chapter|part|book)\\s+[0-9ivxlcdm]+(?:[\\s:.-]+.*)?$", RegexOption.IGNORE_CASE)
private val CJK_CHAPTER = Regex("^第.{1,32}[章回卷节節篇部](?:[\\s:：.-]*.*)?$")
private const val MAX_CHAPTER_HEADING_LENGTH: Int = 160
private const val TEXT_MEDIA_TYPE: String = "text/plain"
private const val TEXT_CANCELLATION_CHECKPOINT_BYTES: Int = 64 * 1024
private const val TEXT_CANCELLATION_CHECKPOINT_CHARS: Int = 32 * 1024
private const val TEXT_UTF8_CHUNK_CHARS: Int = 16 * 1024
private const val TEXT_LOGICAL_PATH: String = "body.txt"
