package dev.shinsou.kmp.content.acquisition

import dev.shinsou.kmp.content.blob.ContentBlobStore
import dev.shinsou.kmp.domain.content.ContentManifest
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.content.MAX_STORABLE_TEXT_BLOCKS
import dev.shinsou.kmp.domain.content.ResourceRef
import dev.shinsou.kmp.domain.content.TextBlock
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.crypto.Sha256
import okio.Buffer
import okio.HashingSink
import okio.blackholeSink

public enum class TextEncodingHint {
    AUTO,
    UTF_8,
    UTF_16_LE,
    UTF_16_BE,

    /** The user chose legacy Simplified Chinese for a BOM-less text. */
    GB18030,

    /** The user chose legacy Traditional Chinese for a BOM-less text. */
    BIG5,
}

public enum class SourceTextEncoding(public val canonicalName: String) {
    UTF_8("UTF-8"),
    UTF_16_LE("UTF-16LE"),
    UTF_16_BE("UTF-16BE"),

    /** Legacy Simplified Chinese text (a superset of GBK and GB2312); plain-text imports only. */
    GB18030("GB18030"),

    /** Legacy Traditional Chinese text; plain-text imports only. */
    BIG5("Big5"),
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
            TextEncodingHint.GB18030,
            TextEncodingHint.BIG5,
            -> throw IllegalArgumentException("Strict decoding covers Unicode encodings only")
        }
        require(bomEncoding == null || bomEncoding == requestedEncoding) {
            "Text byte-order mark conflicts with the encoding hint"
        }
        val bomSize = when (bomEncoding) {
            SourceTextEncoding.UTF_8 -> UTF_8_BOM.size
            SourceTextEncoding.UTF_16_LE,
            SourceTextEncoding.UTF_16_BE,
            -> UTF_16_LE_BOM.size
            SourceTextEncoding.GB18030,
            SourceTextEncoding.BIG5,
            null,
            -> 0
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
            SourceTextEncoding.GB18030,
            SourceTextEncoding.BIG5,
            -> error("Strict decoding covers Unicode encodings only")
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
    /**
     * A text with more paragraphs than this gets blocks of several paragraphs. The default is
     * what a stored manifest holds; the manifest's own limit of 100,000 is more than can be stored.
     */
    val maximumBlocks: Int = MAX_STORABLE_TEXT_BLOCKS,
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
    /**
     * [sourceBytes] as [decodeTextSource] already decoded them, so a large file is not decoded
     * twice. It must be the decoding of these bytes.
     */
    val decoded: DecodedText? = null,
)

private fun decodeChosenLegacyText(
    bytes: ByteArray,
    encoding: SourceTextEncoding,
    cancellationCheckpoint: () -> Unit,
): TextDecodingOutcome.Legacy {
    val decoded = LegacyChineseTextDecoder.decodeAs(bytes, encoding, cancellationCheckpoint)
        ?: throw IllegalArgumentException("Text is not valid ${encoding.canonicalName}")
    return TextDecodingOutcome.Legacy(
        decoded = decoded,
        candidates = listOf(LegacyTextCandidate(encoding, decoded.text.previewOf())),
        confident = true,
    )
}

/** How [decodeTextSource] read a text file. */
public sealed interface TextDecodingOutcome {
    public val decoded: DecodedText

    /** UTF-8 or UTF-16: the bytes have one reading. */
    public data class Unicode(override val decoded: DecodedText) : TextDecodingOutcome

    /**
     * A legacy Chinese encoding, or UTF-8 text that may be legacy text misread. [decoded] is the
     * reading used when nobody chooses, one of [candidates] (which can include
     * [SourceTextEncoding.UTF_8]): the UTF-8 one whenever the bytes are valid UTF-8, else the
     * likelier legacy one. [recommended] is the likelier reading to offer the user, which for
     * valid UTF-8 is a legacy one. When [confident] is false more than one reading is plausible
     * and the user should choose; it is always false when the bytes are valid UTF-8.
     */
    public data class Legacy(
        override val decoded: DecodedText,
        val candidates: List<LegacyTextCandidate>,
        val confident: Boolean,
        val recommended: SourceTextEncoding = decoded.sourceEncoding,
    ) : TextDecodingOutcome
}

/** One reading of a text: its encoding and the start of the text it gives. */
public data class LegacyTextCandidate(
    val encoding: SourceTextEncoding,
    val preview: String,
)

/**
 * Decodes a text file for import. [hint] AUTO reads a BOM or strict UTF-8, then the legacy
 * Chinese encodings; a GB18030 or BIG5 hint decodes only that encoding. Throws
 * IllegalArgumentException when no allowed encoding reads the bytes.
 */
public fun decodeTextSource(
    bytes: ByteArray,
    hint: TextEncodingHint = TextEncodingHint.AUTO,
    cancellationCheckpoint: () -> Unit = {},
): TextDecodingOutcome {
    val legacyHint = when (hint) {
        TextEncodingHint.GB18030 -> SourceTextEncoding.GB18030
        TextEncodingHint.BIG5 -> SourceTextEncoding.BIG5
        else -> null
    }
    if (legacyHint != null) return decodeChosenLegacyText(bytes, legacyHint, cancellationCheckpoint)
    val unicode = try {
        StrictTextDecoder.decode(bytes, hint, cancellationCheckpoint = cancellationCheckpoint)
    } catch (failure: IllegalArgumentException) {
        if (hint != TextEncodingHint.AUTO || bytes.hasUnicodeByteOrderMark()) throw failure
        null
    }
    return if (unicode == null) {
        LegacyChineseTextDecoder.decode(bytes, cancellationCheckpoint)
            ?: throw IllegalArgumentException("Text is not valid UTF-8, UTF-16, GB18030 or Big5")
    } else {
        // Some short legacy text is valid UTF-8 too (GBK 小说 reads as С˵): offer its readings.
        unicode.takeIf { hint == TextEncodingHint.AUTO && !it.hadByteOrderMark }
            ?.let { LegacyChineseTextDecoder.decodeAlongsideUtf8(bytes, it, cancellationCheckpoint) }
            ?: TextDecodingOutcome.Unicode(unicode)
    }
}

/**
 * [decodeTextSource] with [TextEncodingHint.AUTO] for a user who asked to choose the encoding:
 * every reading the bytes have (UTF-8, GB18030, Big5) is a candidate and the outcome is never
 * confident, also where automatic detection is sure. Detection cannot tell every legacy text from
 * UTF-8: the GBK bytes of `小学` are valid UTF-8 in one script (`Сѧ`), which reads like any
 * Cyrillic text. The decoded and recommended readings stay the automatic ones. Text with one
 * reading (a byte order mark, plain ASCII, bytes only one encoding accepts) is returned as
 * [decodeTextSource] returns it.
 */
public fun decodeTextSourceOfferingEveryReading(
    bytes: ByteArray,
    cancellationCheckpoint: () -> Unit = {},
): TextDecodingOutcome {
    val automatic = decodeTextSource(bytes, TextEncodingHint.AUTO, cancellationCheckpoint)
    return if (automatic.decoded.hadByteOrderMark) {
        automatic
    } else {
        LegacyChineseTextDecoder.offeringEveryReading(bytes, automatic, cancellationCheckpoint)
    }
}

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
        val decoded = decodeSourceText(request)
        val canonicalByteSize = decoded.text.utf8ByteSize(cancellationCheckpoint)
        require(canonicalByteSize <= policy.maximumSourceBytes) {
            "Canonical UTF-8 text exceeds the configured size limit"
        }
        val headings = chapterHeadings(decoded.text)
        val blocks = buildBlocks(request.target, decoded.text, headings)
        require(blocks.size <= policy.maximumBlocks) { "Text contains too many locator blocks" }
        val chapters = buildChapters(request.target, decoded.text, blocks, headings)
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

    /**
     * Unicode text is decoded strictly. Without a byte-order mark or an explicit hint, text that is
     * not valid UTF-8 is tried as the legacy Chinese encodings most downloaded novels still use.
     * Valid UTF-8 always stays UTF-8 here: nobody is asked, and [decodeTextSource] keeps a legacy
     * reading of it for the user to choose.
     */
    private fun decodeSourceText(request: TextAcquisitionRequest): DecodedText =
        request.decoded ?: decodeTextSource(request.sourceBytes, request.encodingHint, cancellationCheckpoint).decoded

    /**
     * One block per paragraph, the unit a reading position is kept by. A text with more
     * paragraphs than [TextAcquisitionPolicy.maximumBlocks] gets blocks of several consecutive
     * paragraphs instead (see [mergedBlockRanges]); the reader lays out the whole text by offset,
     * so a block is never a unit of display.
     */
    private fun buildBlocks(
        target: LocalAcquisitionTarget,
        text: String,
        headings: List<ChapterHeading>,
    ): List<TextBlock> {
        val paragraphs = paragraphRanges(text, cancellationCheckpoint).ifEmpty { listOf(TextRange(0, 0)) }
        val ranges = if (paragraphs.size <= policy.maximumBlocks) {
            paragraphs
        } else {
            mergedBlockRanges(paragraphs, headings.map(ChapterHeading::start), policy.maximumBlocks)
        }
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

    private fun chapterHeadings(text: String): List<ChapterHeading> = chapterHeadings(text, cancellationCheckpoint)

    private fun buildChapters(
        target: LocalAcquisitionTarget,
        text: String,
        blocks: List<TextBlock>,
        headings: List<ChapterHeading>,
    ): List<TextChapter> {
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
        // Blocks are ordered and disjoint, and chapters are ordered, so the blocks overlapping a
        // chapter start at or after the first block that ended after the previous chapter began.
        // Scanning from there keeps the mapping linear instead of chapters × blocks.
        var firstCandidate = 0
        return seeds.mapIndexed { index, seed ->
            cancellationCheckpoint()
            val end = seeds.getOrNull(index + 1)?.start ?: text.length
            val occurrence = titleOccurrences[seed.title] ?: 0
            titleOccurrences[seed.title] = occurrence + 1
            while (firstCandidate < blocks.size && !blocks[firstCandidate].mayOverlapFrom(seed.start)) {
                firstCandidate++
            }
            val ids = buildList {
                var cursor = firstCandidate
                while (cursor < blocks.size && blocks[cursor].startUtf16 <= end) {
                    if (cursor % TEXT_CANCELLATION_CHECKPOINT_BLOCKS == 0) cancellationCheckpoint()
                    if (blocks[cursor].overlaps(seed.start, end)) add(blocks[cursor].blockId)
                    cursor++
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

    private fun publishCanonicalText(text: String, byteSize: Long): dev.shinsou.kmp.content.blob.BlobPublishReceipt {
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

/** True unless this block lies wholly before [start], so no chapter from [start] on can reach it. */
private fun TextBlock.mayOverlapFrom(start: Int): Boolean =
    if (startUtf16 == endUtf16) startUtf16 >= start else endUtf16 > start

private fun TextBlock.overlaps(start: Int, end: Int): Boolean =
    if (startUtf16 == endUtf16) startUtf16 in start..end else startUtf16 < end && endUtf16 > start

/**
 * Decodes BOM-less text in the legacy Chinese encodings. Big5 text almost always decodes as
 * GB18030 too, and short GBK text can be valid Big5, so when both succeed the candidate with more
 * of the most common Han characters is the likelier one; misread text is made of rare characters.
 * Only a clear lead is trusted: a short text can favour the wrong reading, so it is left to the user.
 */
private object LegacyChineseTextDecoder {
    fun decode(bytes: ByteArray, cancellationCheckpoint: () -> Unit): TextDecodingOutcome.Legacy? =
        outcomeOf(legacyReadings(bytes, cancellationCheckpoint))

    /**
     * The readings of a valid UTF-8 text when it looks like legacy text misread: its UTF-8
     * reading mixes unrelated scripts (GBK 小说 reads as Cyrillic С and a modifier letter) and a
     * legacy reading is mostly common Han characters, as real Chinese text is and a misread is
     * not. Null for text that reads naturally as UTF-8: ASCII, Chinese, Japanese, accented Latin.
     *
     * The outcome is never confident and its automatic reading stays UTF-8. The bytes are valid
     * UTF-8, and multilingual text (40 lines of `λ = côte`) can score like a legacy novel
     * (`位 = c么te`), so a legacy reading replaces valid UTF-8 only when the user chooses it: it is
     * the recommended reading, never the decoded one a caller that cannot ask would store.
     */
    fun decodeAlongsideUtf8(
        bytes: ByteArray,
        utf8: DecodedText,
        cancellationCheckpoint: () -> Unit,
    ): TextDecodingOutcome.Legacy? {
        val utf8Score = utf8.text.commonHanScore()
        val likelier = if (utf8Score < MIN_CONFIDENT_SCORE && utf8.text.mixesUnrelatedScripts()) {
            legacyReadings(bytes, cancellationCheckpoint).filter { (reading, score) ->
                score > utf8Score * CONFIDENT_SCORE_RATIO && reading.text.isMostlyCommonHan(score)
            }
        } else {
            emptyList()
        }
        // UTF-8 first: it wins a tie.
        return likelier.takeIf { it.isNotEmpty() }
            ?.let { outcomeOf(listOf(utf8 to utf8Score) + it) }
            ?.let { likeliest ->
                likeliest.copy(decoded = utf8, confident = false, recommended = likeliest.decoded.sourceEncoding)
            }
    }

    /**
     * [automatic], the outcome for BOM-less [bytes], with every reading of them as a candidate
     * for the user to choose from; [automatic] itself when they have one reading only. A legacy
     * reading that gives the same text as UTF-8 (plain ASCII) is not another reading.
     */
    fun offeringEveryReading(
        bytes: ByteArray,
        automatic: TextDecodingOutcome,
        cancellationCheckpoint: () -> Unit,
    ): TextDecodingOutcome {
        val utf8 = automatic.decoded.takeIf { it.sourceEncoding == SourceTextEncoding.UTF_8 }
        val offered = if (utf8 == null) {
            // Bytes that are not valid UTF-8 already list every legacy reading they have.
            automatic as? TextDecodingOutcome.Legacy
        } else {
            // UTF-8 first: it is the reading kept on a tie.
            outcomeOf(
                listOf(utf8 to utf8.text.commonHanScore()) +
                    legacyReadings(bytes, cancellationCheckpoint).filter { (reading, _) -> reading.text != utf8.text },
            )
        }
        return offered?.takeIf { it.candidates.size > 1 }?.copy(
            decoded = automatic.decoded,
            confident = false,
            recommended = (automatic as? TextDecodingOutcome.Legacy)?.recommended ?: automatic.decoded.sourceEncoding,
        ) ?: automatic
    }

    /**
     * Whether the sampled non-ASCII characters come from more than one script, or include C1
     * controls or modifier letters, which a legacy text read as UTF-8 scatters and real text
     * rarely has. Punctuation and symbols belong to no script.
     */
    private fun String.mixesUnrelatedScripts(): Boolean {
        val scripts = mutableSetOf<UnicodeScriptGroup>()
        val sampleEnd = minOf(length, LEGACY_SCORE_SAMPLE_CHARS)
        for (index in 0 until sampleEnd) {
            when (val group = scriptGroupOf(this[index])) {
                null -> Unit
                UnicodeScriptGroup.MISREAD_MARKER -> return true
                else -> scripts += group
            }
        }
        return scripts.size > 1
    }

    private fun scriptGroupOf(char: Char): UnicodeScriptGroup? = when (char.code) {
        in BASIC_LATIN, in LATIN_1_PUNCTUATION, MULTIPLICATION_SIGN, DIVISION_SIGN, in PUNCTUATION_AND_SYMBOLS -> null
        in C1_CONTROLS, in SPACING_MODIFIER_LETTERS -> UnicodeScriptGroup.MISREAD_MARKER
        in LATIN_1_AND_EXTENDED_LETTERS, in LATIN_EXTENDED_ADDITIONAL -> UnicodeScriptGroup.LATIN
        in GREEK_AND_COPTIC -> UnicodeScriptGroup.GREEK
        in CYRILLIC_AND_SUPPLEMENT -> UnicodeScriptGroup.CYRILLIC
        in HEBREW_AND_ARABIC, in ARABIC_SUPPLEMENT -> UnicodeScriptGroup.HEBREW_ARABIC
        in CJK_AND_HANGUL, in CJK_COMPATIBILITY_IDEOGRAPHS, in HALFWIDTH_AND_FULLWIDTH_FORMS -> UnicodeScriptGroup.EAST_ASIAN
        else -> UnicodeScriptGroup.OTHER
    }

    private enum class UnicodeScriptGroup { LATIN, GREEK, CYRILLIC, HEBREW_ARABIC, EAST_ASIAN, OTHER, MISREAD_MARKER }

    private fun legacyReadings(bytes: ByteArray, cancellationCheckpoint: () -> Unit): List<Pair<DecodedText, Int>> =
        LEGACY_CHINESE_ENCODINGS.mapNotNull { encoding ->
            decodeAs(bytes, encoding, cancellationCheckpoint)?.let { it to it.text.commonHanScore() }
        }

    private fun outcomeOf(readings: List<Pair<DecodedText, Int>>): TextDecodingOutcome.Legacy? {
        // maxByOrNull keeps the first of equal scores, so the order of the readings breaks ties.
        val (best, bestScore) = readings.maxByOrNull { (_, score) -> score } ?: return null
        val runnerUp = readings.filter { (reading, _) -> reading !== best }.maxOfOrNull { (_, score) -> score }
        // The readings share whatever ASCII the file starts with; the previews start where they differ.
        val previewStart = previewStartOf(readings.map { (reading, _) -> reading.text })
        return TextDecodingOutcome.Legacy(
            decoded = best,
            candidates = readings.map { (reading, _) ->
                LegacyTextCandidate(reading.sourceEncoding, reading.text.previewOf(previewStart))
            },
            confident = runnerUp == null ||
                (bestScore >= MIN_CONFIDENT_SCORE && bestScore >= runnerUp * CONFIDENT_SCORE_RATIO),
        )
    }

    fun decodeAs(bytes: ByteArray, encoding: SourceTextEncoding, cancellationCheckpoint: () -> Unit): DecodedText? {
        cancellationCheckpoint()
        return platformDecodeLegacyText(bytes, encoding)
            ?.takeIf { it.hasValidTextCharacters(cancellationCheckpoint) }
            ?.let { text ->
                DecodedText(
                    text = text.normalizeLineEndings(cancellationCheckpoint),
                    sourceEncoding = encoding,
                    hadByteOrderMark = false,
                )
            }
    }

    private fun String.commonHanScore(): Int {
        var score = 0
        val sampleEnd = minOf(length, LEGACY_SCORE_SAMPLE_CHARS)
        for (index in 0 until sampleEnd) {
            if (this[index] in COMMON_HAN_CHARACTERS) score++
        }
        return score
    }

    /** At least [MOSTLY_COMMON_PERCENT] of the sampled Han characters are common ones ([score]). */
    private fun String.isMostlyCommonHan(score: Int): Boolean {
        val sampleEnd = minOf(length, LEGACY_SCORE_SAMPLE_CHARS)
        var han = 0
        for (index in 0 until sampleEnd) {
            if (this[index] in '\u4E00'..'\u9FFF') han++
        }
        return han > 0 && score * PERCENT >= han * MOSTLY_COMMON_PERCENT
    }

    private fun String.hasValidTextCharacters(cancellationCheckpoint: () -> Unit): Boolean =
        try {
            requireValidTextCharacters(cancellationCheckpoint)
            true
        } catch (_: IllegalArgumentException) {
            false
        }

    /**
     * GB18030 first: on a tie (a short text with none of the common characters) the far more
     * common encoding of downloaded novels wins.
     */
    private val LEGACY_CHINESE_ENCODINGS = listOf(SourceTextEncoding.GB18030, SourceTextEncoding.BIG5)
    private const val LEGACY_SCORE_SAMPLE_CHARS: Int = 256 * 1024

    /**
     * A reading is trusted without asking only with this many common characters and this many
     * times the other reading's. A real novel's right reading has thousands, its misreading few.
     */
    private const val MIN_CONFIDENT_SCORE: Int = 64
    private const val CONFIDENT_SCORE_RATIO: Int = 3

    /** Real Chinese text is about half common characters; a misread, a few percent. */
    private const val MOSTLY_COMMON_PERCENT: Int = 30
    private const val PERCENT: Int = 100

    /** About 500 of the most frequent Han characters, in simplified and traditional forms. */
    private val COMMON_HAN_CHARACTERS: Set<Char> = (
            "的一是不了人我在有他这中大来上个国到说们为子和你地出道也时年得就那要下以生会自着" +
            "去之过家学对可她里后小么心多天而能好都然没日于起还发成事只作当想看文无开手十用主" +
            "行方又如前所本见经头面公同三已老从动两长知民样现分将外但身些与高意进把法此实回二" +
            "理美点月明其种声全工己话儿者向情部正名定女问力机给等几很业最间新什打便位因重被走" +
            "电四第门相次东政海口使教西再平真听世气信北少关并内加化由却代军产入先山五太水万市" +
            "眼体别处总才场师书比住员九笑性通目华报立马命张活难神数件安表原车白应路期叫死常提" +
            "感金何更反合放做系计或司利受光王果亲界及今京务制解各任至清物台象记边共风战干接它" +
            "许八特觉望直服毛林题建南度统色字请交爱让认算论百吃义科怎元社术结六功指思非流每青" +
            "管夫连远资队跟带花快条院变联言权往展该领传近留红治决周保达办运武半候七必城父强步" +
            "完革深区即求品士转量空甚众技轻程告江语英基派满式李息写呢识极令黄德收脸钱党倒未持" +
            "取设始版双历越史商千片容研像找友孩站广改议形委早房音火际则首单据导影失拿网香似斯" +
            "专石若兵弟谁校读志飞观争究包组造落视济喜离虽坏兴切龙吗吧啊哪這們個國說為來時會過" +
            "學對裡後麼沒還發從動兩長現樣將與實點兒話問機給幾業間經頭聽氣開見種聲進當於無師書" +
            "軍總張報東電車員難數體別處場應記邊風戰幹讓認論義結條強遠帶變聯權運區轉輕極黃臉錢" +
            "黨歷愛視雙響離雖壞興龍嗎親並產萬門關內華馬誰讀飛觀爭網專隊廣議導單據歲寫識許覺統" +
            "題請團設資連領傳紅決達辦歡"
        ).toSet()
}

/**
 * Where the previews of [texts] start: a little before the first character the readings differ
 * in, so the user sees what tells them apart rather than a shared ASCII header. That lead is the
 * same text in every reading, so one index fits them all; it never splits a surrogate pair.
 */
private fun previewStartOf(texts: List<String>): Int {
    if (texts.size < 2) return 0
    val first = texts.first()
    val shortest = texts.minOf(String::length)
    var differsAt = 0
    while (differsAt < shortest && texts.all { it[differsAt] == first[differsAt] }) differsAt++
    var start = (differsAt - LEGACY_PREVIEW_LEAD_CHARS).coerceAtLeast(0)
    if (start > 0 && first[start].isLowSurrogate()) start--
    return start
}

/** [this] text from [start] on one line, for the user to tell two readings apart. */
private fun String.previewOf(start: Int = 0): String {
    val preview = StringBuilder()
    if (start > 0) preview.append('…')
    var pendingSpace = false
    for (index in start until length) {
        val char = this[index]
        // Never end on half of a surrogate pair.
        if (preview.length >= LEGACY_PREVIEW_CHARS - if (char.isHighSurrogate()) 1 else 0) break
        if (char.isWhitespace()) {
            pendingSpace = preview.isNotEmpty()
        } else {
            if (pendingSpace) preview.append(' ')
            pendingSpace = false
            preview.append(char)
        }
    }
    return preview.toString()
}

private const val LEGACY_PREVIEW_CHARS: Int = 120

/** How much of the shared text a preview keeps before the first difference, for context. */
private const val LEGACY_PREVIEW_LEAD_CHARS: Int = 16

/**
 * Decodes [bytes] in a legacy (non-Unicode) [encoding] with the platform's converter. Returns null
 * when the platform lacks the encoding or the bytes are not valid in it; never substitutes.
 */
internal expect fun platformDecodeLegacyText(bytes: ByteArray, encoding: SourceTextEncoding): String?

private fun ByteArray.hasUnicodeByteOrderMark(): Boolean =
    hasPrefix(UTF_8_BOM) || hasPrefix(UTF_16_LE_BOM) || hasPrefix(UTF_16_BE_BOM) ||
        hasPrefix(UTF_32_BE_BOM)

private data class TextRange(val start: Int, val end: Int)
private data class TextLine(val start: Int, val end: Int)
private data class ChapterHeading(val start: Int, val title: String)

/** The lines of [text] recognized as chapter headings, in order. */
private fun chapterHeadings(text: String, cancellationCheckpoint: () -> Unit): List<ChapterHeading> =
    textLines(text, cancellationCheckpoint).mapNotNull { line ->
        cancellationCheckpoint()
        val title = if (line.end - line.start <= MAX_CHAPTER_HEADING_LENGTH) {
            recognizedChapterTitle(text.substring(line.start, line.end))
        } else {
            null
        }
        title?.let { ChapterHeading(line.start, it) }
    }

/**
 * Where the chapters of [text] begin: the starts of the lines an import recognizes as chapter
 * headings, ascending. A stored text keeps no chapter list, so the reader asks again to lay a
 * long text out a chapter at a time. Pure and safe off the main thread;
 * [cancellationCheckpoint] is called every so often and may throw to stop.
 */
public fun textChapterHeadingStarts(text: String, cancellationCheckpoint: () -> Unit = {}): List<Int> =
    chapterHeadings(text, cancellationCheckpoint).map(ChapterHeading::start)

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

/**
 * [paragraphs] merged into at most [maximumBlocks] ranges of consecutive paragraphs, for a text
 * with more paragraphs than that. A range runs from its first paragraph's start to its last
 * one's end, blank lines between them included.
 *
 * No range crosses a chapter heading: the paragraph holding one of [chapterStarts] (ascending
 * heading line offsets) always begins a range, so a chapter still begins at a block start
 * wherever it did with one block per paragraph. Every range takes the same number of paragraphs,
 * the smallest that fits, give or take one within a chapter and less in a chapter shorter than
 * that. The result depends on the text alone.
 */
private fun mergedBlockRanges(
    paragraphs: List<TextRange>,
    chapterStarts: List<Int>,
    maximumBlocks: Int,
): List<TextRange> {
    val sections = chapterSectionSizes(paragraphs, chapterStarts)
    require(sections.size <= maximumBlocks) { "Text contains more chapters than it may have locator blocks" }
    val paragraphsPerBlock = smallestParagraphsPerBlock(sections, maximumBlocks)
    val merged = ArrayList<TextRange>()
    var sectionStart = 0
    sections.forEach { size ->
        val blockCount = blockCountOf(size, paragraphsPerBlock)
        repeat(blockCount) { index ->
            // Cut points at whole multiples of size / blockCount: block sizes differ by one at most.
            val first = sectionStart + (size.toLong() * index / blockCount).toInt()
            val last = sectionStart + (size.toLong() * (index + 1) / blockCount).toInt() - 1
            merged += TextRange(paragraphs[first].start, paragraphs[last].end)
        }
        sectionStart += size
    }
    return merged
}

/** How many paragraphs each run between two chapter headings has; the runs cover [paragraphs] in order. */
private fun chapterSectionSizes(paragraphs: List<TextRange>, chapterStarts: List<Int>): List<Int> {
    val sizes = ArrayList<Int>()
    var nextHeading = 0
    var size = 0
    paragraphs.forEach { paragraph ->
        // A heading is a non-blank line, so it lies inside a paragraph.
        var holdsHeading = false
        while (nextHeading < chapterStarts.size && chapterStarts[nextHeading] <= paragraph.end) {
            holdsHeading = true
            nextHeading++
        }
        if (holdsHeading && size > 0) {
            sizes += size
            size = 0
        }
        size++
    }
    if (size > 0) sizes += size
    return sizes
}

/** The fewest paragraphs per block that fit [sections] into [maximumBlocks] blocks; there are no more sections than that. */
private fun smallestParagraphsPerBlock(sections: List<Int>, maximumBlocks: Int): Int {
    fun fits(paragraphsPerBlock: Int): Boolean =
        sections.sumOf { blockCountOf(it, paragraphsPerBlock).toLong() } <= maximumBlocks
    var tooFew = 1
    var enough = sections.max()
    while (enough - tooFew > 1) {
        val middle = tooFew + (enough - tooFew) / 2
        if (fits(middle)) enough = middle else tooFew = middle
    }
    return enough
}

private fun blockCountOf(paragraphs: Int, paragraphsPerBlock: Int): Int =
    (paragraphs + paragraphsPerBlock - 1) / paragraphsPerBlock

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
private const val TEXT_CANCELLATION_CHECKPOINT_BLOCKS: Int = 1024
private const val TEXT_UTF8_CHUNK_CHARS: Int = 16 * 1024
private const val TEXT_LOGICAL_PATH: String = "body.txt"

// The Unicode blocks a misread legacy Chinese text is told apart by ([LegacyChineseTextDecoder]).
/** U+0000-U+007F: ASCII, shared by every reading. */
private val BASIC_LATIN: IntRange = 0x0000..0x007F

/** U+0080-U+009F: C1 controls, which no real text holds: the mark of a misreading. */
private val C1_CONTROLS: IntRange = 0x0080..0x009F

/** U+00A0-U+00BF: Latin-1 punctuation and symbols. */
private val LATIN_1_PUNCTUATION: IntRange = 0x00A0..0x00BF

/** U+00D7, the multiplication sign. */
private const val MULTIPLICATION_SIGN: Int = 0x00D7

/** U+00F7, the division sign. */
private const val DIVISION_SIGN: Int = 0x00F7

/** U+00C0-U+024F: Latin-1 letters and Latin Extended-A and -B. */
private val LATIN_1_AND_EXTENDED_LETTERS: IntRange = 0x00C0..0x024F

/** U+02B0-U+02FF: spacing modifier letters, a mark of a misreading too. */
private val SPACING_MODIFIER_LETTERS: IntRange = 0x02B0..0x02FF

/** U+0370-U+03FF: Greek and Coptic. */
private val GREEK_AND_COPTIC: IntRange = 0x0370..0x03FF

/** U+0400-U+052F: Cyrillic and Cyrillic Supplement. */
private val CYRILLIC_AND_SUPPLEMENT: IntRange = 0x0400..0x052F

/** U+0590-U+06FF: Hebrew and Arabic. */
private val HEBREW_AND_ARABIC: IntRange = 0x0590..0x06FF

/** U+0750-U+077F: Arabic Supplement. */
private val ARABIC_SUPPLEMENT: IntRange = 0x0750..0x077F

/** U+1E00-U+1EFF: Latin Extended Additional. */
private val LATIN_EXTENDED_ADDITIONAL: IntRange = 0x1E00..0x1EFF

/** U+2000-U+2BFF: general punctuation, symbols and arrows, shared by every script. */
private val PUNCTUATION_AND_SYMBOLS: IntRange = 0x2000..0x2BFF

/** U+2E80-U+D7FF: CJK radicals, kana, CJK ideographs and Hangul. */
private val CJK_AND_HANGUL: IntRange = 0x2E80..0xD7FF

/** U+F900-U+FAFF: CJK compatibility ideographs. */
private val CJK_COMPATIBILITY_IDEOGRAPHS: IntRange = 0xF900..0xFAFF

/** U+FF00-U+FFEF: halfwidth and fullwidth forms. */
private val HALFWIDTH_AND_FULLWIDTH_FORMS: IntRange = 0xFF00..0xFFEF
