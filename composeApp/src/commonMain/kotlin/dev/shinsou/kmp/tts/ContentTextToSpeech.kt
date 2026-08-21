package dev.shinsou.kmp.tts

import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.access.ContentOperationDeniedException
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.reader.EpubSemanticDocument
import dev.shinsou.kmp.reader.EpubSemanticTextBlock
import dev.shinsou.kmp.reader.EpubSpineNavigation
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsOperationContext

public enum class SpeechPlaybackStatus {
    COMPLETED,
    CANCELLED,
    FAILED,
}

/** Bounded request passed to a platform-owned speech engine. */
public data class PlatformSpeechRequest(
    val utteranceId: String,
    val text: String,
    val localeTag: String? = null,
    val rate: Float = 1.0f,
    val pitch: Float = 1.0f,
) {
    init {
        requireSafeIdentifier(utteranceId, "Speech utterance id")
        require(text.isNotEmpty() && text.length <= MAX_PLATFORM_UTTERANCE_CHARS && isWellFormedText(text)) {
            "Speech utterance text is invalid"
        }
        localeTag?.let { requireLocaleTag(it) }
        require(rate.isFinite() && rate in MIN_RATE..MAX_RATE) { "Speech rate is invalid" }
        require(pitch.isFinite() && pitch in MIN_PITCH..MAX_PITCH) { "Speech pitch is invalid" }
    }

    override fun toString(): String =
        "PlatformSpeechRequest(utteranceId=$utteranceId, text=<redacted>, chars=${text.length})"
}

public data class PlatformSpeechResult(
    val utteranceId: String,
    val status: SpeechPlaybackStatus,
    val errorCode: String? = null,
) {
    init {
        requireSafeIdentifier(utteranceId, "Speech utterance id")
        errorCode?.let { requireSafeIdentifier(it, "Speech error code") }
        require(status == SpeechPlaybackStatus.FAILED || errorCode == null) {
            "Only failed speech can carry an error code"
        }
    }
}

public data class PlatformSpeechCapability(
    val available: Boolean,
    val unavailableReasonCode: String? = null,
) {
    init {
        unavailableReasonCode?.let { requireSafeIdentifier(it, "Speech unavailable reason") }
        require(available || unavailableReasonCode != null) {
            "An unavailable speech engine needs a reason code"
        }
    }

    public companion object {
        public val Available: PlatformSpeechCapability = PlatformSpeechCapability(available = true)
        public fun unavailable(reasonCode: String): PlatformSpeechCapability =
            PlatformSpeechCapability(available = false, unavailableReasonCode = reasonCode)
    }
}

/** Android, iOS, macOS, and Windows implement this boundary with their native speech engine. */
public interface PlatformTextToSpeechEngine {
    public val capability: PlatformSpeechCapability
        get() = PlatformSpeechCapability.Available

    public suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult
    public fun stop()
    public fun close() = stop()
}

/** Explicit fail-closed adapter used by previews and unsupported desktop platforms. */
public class UnavailablePlatformTextToSpeechEngine(
    reasonCode: String = "tts_unavailable",
) : PlatformTextToSpeechEngine {
    override val capability: PlatformSpeechCapability = PlatformSpeechCapability.unavailable(reasonCode)

    override suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult = PlatformSpeechResult(
        utteranceId = request.utteranceId,
        status = SpeechPlaybackStatus.FAILED,
        errorCode = requireNotNull(capability.unavailableReasonCode),
    )

    override fun stop(): Unit = Unit
}

public data class SpeakableTextDocument(
    val scope: ReadingScope,
    val resourceId: String,
    val blockId: String,
    val text: String,
    val access: ContentAccessRequest,
    /** Absolute UTF-16 start of [text] in the canonical resource. */
    val baseOffsetUtf16: Int = 0,
    val canonicalDocumentUtf16Length: Int = text.length,
) {
    init {
        requireSafeIdentifier(resourceId, "Speech resource id")
        requireSafeIdentifier(blockId, "Speech block id")
        require(text.length <= MAX_DOCUMENT_CHARS && isWellFormedText(text)) {
            "Speech document is invalid"
        }
        require(baseOffsetUtf16 >= 0 && canonicalDocumentUtf16Length >= baseOffsetUtf16 + text.length) {
            "Speech block offset is outside its canonical document"
        }
        requireScopeMatches(scope, access)
    }

    override fun toString(): String = "SpeakableTextDocument(text=<redacted>, chars=${text.length})"
}

/** One EPUB DOM text node using the same href/CFI map as renderer progress and search. */
public data class EpubSpeakableTextDocument(
    val navigation: EpubSpineNavigation,
    val semanticDocument: EpubSemanticDocument,
    val block: EpubSemanticTextBlock,
    val access: ContentAccessRequest,
) {
    public val text: String
        get() = semanticDocument.canonicalText.substring(block.startUtf16, block.endUtf16)

    init {
        require(block in semanticDocument.blocks) { "EPUB speech block belongs to another document" }
        require(semanticDocument.representationId == navigation.representationId) {
            "EPUB speech semantic map belongs to another representation"
        }
        require(text.length <= MAX_DOCUMENT_CHARS && isWellFormedText(text)) {
            "EPUB speech document is invalid"
        }
        requireScopeMatches(navigation.scope, access)
    }

    override fun toString(): String =
        "EpubSpeakableTextDocument(resourceId=${semanticDocument.resourceId}, text=<redacted>, chars=${text.length})"
}

/** One platform utterance with a durable locator range for progress/highlighting. */
public data class SpeechSegment(
    val segmentIndex: Int,
    val utteranceId: String,
    val text: String,
    val range: ReadingRange,
) {
    init {
        require(segmentIndex >= 0) { "Speech segment index must be non-negative" }
        requireSafeIdentifier(utteranceId, "Speech utterance id")
        require(text.isNotEmpty() && text.length <= MAX_PLATFORM_UTTERANCE_CHARS && isWellFormedText(text)) {
            "Speech segment text is invalid"
        }
        range.validate()
    }

    override fun toString(): String =
        "SpeechSegment(index=$segmentIndex, utteranceId=$utteranceId, text=<redacted>)"
}

public data class ContentSpeechResult(
    val completedSegments: Int,
    val finalStatus: SpeechPlaybackStatus,
    val resumeLocator: ReadingLocator.Text?,
) {
    init { require(completedSegments >= 0) { "Completed segment count must be non-negative" } }
}

public data class EpubContentSpeechResult(
    val completedSegments: Int,
    val finalStatus: SpeechPlaybackStatus,
    val resumeLocator: ReadingLocator.Epub?,
) {
    init { require(completedSegments >= 0) { "Completed segment count must be non-negative" } }
}

/**
 * Segments text on the host and rechecks TTS permission before every native utterance.  A revoked
 * grant therefore stops playback at the next bounded segment instead of becoming a long lease.
 */
public class RightsEnforcedTextToSpeechService(
    private val operationGate: ContentOperationGate,
    private val platformEngine: PlatformTextToSpeechEngine,
) {
    public fun segments(
        document: SpeakableTextDocument,
        startUtf16: Int = 0,
        maxSegmentChars: Int = DEFAULT_SEGMENT_CHARS,
    ): List<SpeechSegment> {
        require(startUtf16 in 0..document.text.length && isUtf16Boundary(document.text, startUtf16)) {
            "Speech start offset is invalid"
        }
        require(maxSegmentChars in MIN_SEGMENT_CHARS..MAX_PLATFORM_UTTERANCE_CHARS) {
            "Speech segment size is invalid"
        }
        val ranges = splitText(document.text, startUtf16, maxSegmentChars)
        return ranges.mapIndexed { index, range ->
            val exact = document.text.substring(range.first, range.last + 1)
            val absoluteStart = document.baseOffsetUtf16 + range.first
            val absoluteEnd = document.baseOffsetUtf16 + range.last + 1
            val start = ReadingLocator.Text(
                schemaVersion = document.scope.schemaVersion,
                scope = document.scope,
                resourceId = document.resourceId,
                blockId = document.blockId,
                offset = absoluteStart,
                progression = progression(absoluteStart, document.canonicalDocumentUtf16Length),
                quote = document.text.quoteAt(range.first, minOf(range.last + 1, range.first + QUOTE_EXACT_CHARS)),
            )
            val end = ReadingLocator.Text(
                schemaVersion = document.scope.schemaVersion,
                scope = document.scope,
                resourceId = document.resourceId,
                blockId = document.blockId,
                offset = absoluteEnd,
                progression = progression(absoluteEnd, document.canonicalDocumentUtf16Length),
            )
            SpeechSegment(
                segmentIndex = index,
                utteranceId = stableUtteranceId(document, range.first, range.last + 1),
                text = exact,
                range = ReadingRange(
                    start,
                    end,
                    quote = document.text.quoteAt(
                        range.first,
                        minOf(range.last + 1, range.first + QUOTE_EXACT_CHARS),
                    ),
                ),
            )
        }
    }

    public fun segments(
        document: EpubSpeakableTextDocument,
        startUtf16: Int = 0,
        maxSegmentChars: Int = DEFAULT_SEGMENT_CHARS,
    ): List<SpeechSegment> {
        val text = document.text
        require(startUtf16 in 0..text.length && isUtf16Boundary(text, startUtf16)) {
            "EPUB speech start offset is invalid"
        }
        require(maxSegmentChars in MIN_SEGMENT_CHARS..MAX_PLATFORM_UTTERANCE_CHARS) {
            "Speech segment size is invalid"
        }
        return splitText(text, startUtf16, maxSegmentChars).mapIndexed { index, range ->
            val absoluteStart = document.block.startUtf16 + range.first
            val absoluteEnd = document.block.startUtf16 + range.last + 1
            SpeechSegment(
                segmentIndex = index,
                utteranceId = stableUtteranceId(document, absoluteStart, absoluteEnd),
                text = text.substring(range.first, range.last + 1),
                range = document.semanticDocument.rangeForOffsets(
                    navigation = document.navigation,
                    startUtf16 = absoluteStart,
                    endUtf16 = absoluteEnd,
                ),
            )
        }
    }

    public suspend fun speak(
        document: SpeakableTextDocument,
        startUtf16: Int = 0,
        localeTag: String? = null,
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
        maxSegmentChars: Int = DEFAULT_SEGMENT_CHARS,
    ): ContentSpeechResult {
        val segments = segments(document, startUtf16, maxSegmentChars)
        if (segments.isEmpty()) {
            return ContentSpeechResult(0, SpeechPlaybackStatus.COMPLETED, null)
        }
        val actualCharacters = document.text.length - startUtf16
        val access = document.access.withActualTextCharacters(actualCharacters)
        var completed = 0
        var resume: ReadingLocator.Text? = segments.first().range.start as ReadingLocator.Text
        try {
            for (segment in segments) {
                val result = operationGate.executeSuspending(access, ContentOperation.TTS) {
                    platformEngine.speak(
                        PlatformSpeechRequest(
                            utteranceId = segment.utteranceId,
                            text = segment.text,
                            localeTag = localeTag,
                            rate = rate,
                            pitch = pitch,
                        ),
                    )
                }
                if (result.status != SpeechPlaybackStatus.COMPLETED) {
                    resume = segment.range.start as ReadingLocator.Text
                    return ContentSpeechResult(completed, result.status, resume)
                }
                completed++
                resume = segment.range.end as ReadingLocator.Text
            }
        } catch (denied: ContentOperationDeniedException) {
            // A native adapter may have queued work even after its completion callback. Always
            // flush it when the next bounded authorization check observes revocation.
            platformEngine.stop()
            throw denied
        }
        return ContentSpeechResult(completed, SpeechPlaybackStatus.COMPLETED, resume)
    }

    public suspend fun speak(
        document: EpubSpeakableTextDocument,
        startUtf16: Int = 0,
        localeTag: String? = null,
        rate: Float = 1.0f,
        pitch: Float = 1.0f,
        maxSegmentChars: Int = DEFAULT_SEGMENT_CHARS,
    ): EpubContentSpeechResult {
        val segments = segments(document, startUtf16, maxSegmentChars)
        if (segments.isEmpty()) {
            return EpubContentSpeechResult(0, SpeechPlaybackStatus.COMPLETED, null)
        }
        val access = document.access.withActualTextCharacters(
            document.semanticDocument.canonicalText.length,
        )
        var completed = 0
        var resume = segments.first().range.start as ReadingLocator.Epub
        try {
            for (segment in segments) {
                val result = operationGate.executeSuspending(access, ContentOperation.TTS) {
                    platformEngine.speak(
                        PlatformSpeechRequest(
                            utteranceId = segment.utteranceId,
                            text = segment.text,
                            localeTag = localeTag,
                            rate = rate,
                            pitch = pitch,
                        ),
                    )
                }
                if (result.status != SpeechPlaybackStatus.COMPLETED) {
                    resume = segment.range.start as ReadingLocator.Epub
                    return EpubContentSpeechResult(completed, result.status, resume)
                }
                completed++
                resume = segment.range.end as ReadingLocator.Epub
            }
        } catch (denied: ContentOperationDeniedException) {
            platformEngine.stop()
            throw denied
        }
        return EpubContentSpeechResult(completed, SpeechPlaybackStatus.COMPLETED, resume)
    }

    public fun stop() {
        platformEngine.stop()
    }

    public fun close() {
        platformEngine.close()
    }
}

private fun splitText(text: String, start: Int, limit: Int): List<IntRange> {
    if (start == text.length) return emptyList()
    val output = ArrayList<IntRange>()
    var cursor = start
    while (cursor < text.length) {
        var end = minOf(text.length, cursor + limit)
        end = safeBoundaryAtOrBefore(text, end)
        if (end <= cursor) end = safeBoundaryAtOrAfter(text, minOf(text.length, cursor + 2))
        if (end < text.length) {
            val preferredFloor = cursor + (limit / 2)
            var candidate = end
            while (candidate > preferredFloor) {
                val previous = text[candidate - 1]
                if (previous == '\n' || previous == '\r' || previous == '.' || previous == '!' ||
                    previous == '?' || previous == '\u3002' || previous == '\uff01' || previous == '\uff1f' ||
                    previous == '\uff1b'
                ) {
                    end = candidate
                    break
                }
                candidate--
            }
        }
        output += cursor until end
        cursor = end
        while (cursor < text.length && text[cursor].isWhitespace()) cursor++
    }
    return output
}

private fun ContentAccessRequest.withActualTextCharacters(length: Int): ContentAccessRequest = copy(
    context = RightsOperationContext(
        offlineBytes = context.offlineBytes,
        textCharacters = length.toLong(),
        watermarkApplied = context.watermarkApplied,
    ),
)

private fun stableUtteranceId(document: SpeakableTextDocument, start: Int, end: Int): String =
    "tts-${document.scope.unitId.value}-${document.baseOffsetUtf16 + start}-${document.baseOffsetUtf16 + end}"

private fun stableUtteranceId(document: EpubSpeakableTextDocument, start: Int, end: Int): String =
    "tts-${document.navigation.scope.unitId.value}-${document.semanticDocument.documentIndex}-$start-$end"

private fun progression(offset: Int, length: Int): Double = if (length == 0) 0.0 else offset.toDouble() / length

private fun String.quoteAt(start: Int, end: Int): TextQuote {
    require(start in 0 until end && end <= length)
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
        "Speech document and rights scope do not match"
    }
}

private fun requireLocaleTag(value: String) {
    require(value.length in 2..64 && LOCALE_PATTERN.matches(value)) { "Speech locale tag is invalid" }
}

private fun requireSafeIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH &&
        value.none(Char::isWhitespace) && value.none(Char::isISOControl)) {
        "$label must be bounded and printable"
    }
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

private fun isUtf16Boundary(text: String, offset: Int): Boolean =
    offset in 0..text.length && (offset == 0 || offset == text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()))

private fun safeBoundaryAtOrBefore(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (!isUtf16Boundary(text, index)) index--
    return index
}

private fun safeBoundaryAtOrAfter(text: String, requested: Int): Int {
    var index = requested.coerceIn(0, text.length)
    if (!isUtf16Boundary(text, index)) index++
    return index
}

private const val MAX_IDENTIFIER_LENGTH: Int = 512
private const val MAX_DOCUMENT_CHARS: Int = 5_000_000
private const val MIN_SEGMENT_CHARS: Int = 64
private const val DEFAULT_SEGMENT_CHARS: Int = 1_000
private const val MAX_PLATFORM_UTTERANCE_CHARS: Int = 4_000
private const val QUOTE_EXACT_CHARS: Int = 256
private const val QUOTE_CONTEXT_CHARS: Int = 64
private const val MIN_RATE: Float = 0.25f
private const val MAX_RATE: Float = 4.0f
private const val MIN_PITCH: Float = 0.5f
private const val MAX_PITCH: Float = 2.0f
private val LOCALE_PATTERN = Regex("[A-Za-z]{2,8}(?:-[A-Za-z0-9]{1,8})*")
