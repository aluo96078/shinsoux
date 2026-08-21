package dev.shinsou.kmp.reader

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
public enum class TextOffsetUnit {
    UTF_16_CODE_UNIT,
}

@Serializable
public enum class TextDirection {
    FORWARD,
    BACKWARD,
}

/** Mandatory common identity for every progress/search/TTS/annotation anchor. */
@Serializable
public data class ReadingScope(
    val schemaVersion: Int,
    val publicationId: PublicationKey,
    val acquisitionId: String,
    val unitId: UnitKey,
    val contentRevision: Long,
) {
    init { validate() }

    public fun validate(): Unit {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported reading locator schema version $schemaVersion"
        }
        publicationId.validate()
        requireUuid(acquisitionId, "Reading acquisition id")
        unitId.validate()
        require(unitId.publicationKey == publicationId) {
            "Reading unit must belong to the scoped publication"
        }
        require(contentRevision >= 0) { "Reading content revision must be non-negative" }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** A quote retained so a text anchor can survive reflow or a changed rendition. */
@Serializable
public data class TextQuote(
    val exact: String,
    val prefix: String = "",
    val suffix: String = "",
    val occurrence: Int = 0,
) {
    init { validate() }

    public fun validate(): Unit {
        require(exact.isNotEmpty() && exact.length <= MAX_QUOTE_EXACT_LENGTH) {
            "Text quote exact value must be bounded and non-empty"
        }
        require(prefix.length <= MAX_QUOTE_CONTEXT_LENGTH && suffix.length <= MAX_QUOTE_CONTEXT_LENGTH) {
            "Text quote context is too long"
        }
        require(listOf(exact, prefix, suffix).all(::isWellFormedText)) {
            "Text quote contains invalid UTF-16/control characters"
        }
        require(occurrence in 0..MAX_QUOTE_OCCURRENCE) {
            "Text quote occurrence must be bounded and non-negative"
        }
    }

    public fun findIn(text: String): Int? {
        var cursor = 0
        var seen = 0
        while (cursor <= text.length - exact.length) {
            val index = text.indexOf(exact, cursor)
            if (index < 0) return null
            // A UTF-16 offset is not allowed to point between the two code units of a
            // supplementary code point.  `indexOf` itself is code-unit based and can find a
            // substring at such a position when a malformed/partial selector is supplied, so
            // keep the boundary check here as well as in matchesAt.
            if (isUtf16Boundary(text, index) && matchesAt(text, index)) {
                if (seen == occurrence) return index
                seen++
            }
            // Advance by one code unit instead of exact.length.  This makes occurrence
            // semantics deterministic even for overlapping matches (for example `aba` in
            // `ababa`) while the boundary check prevents a match inside a surrogate pair.
            cursor = index + 1
        }
        return null
    }

    public fun matchesAt(text: String, offset: Int): Boolean {
        if (offset < 0 || offset > text.length || exact.length > text.length - offset) return false
        if (!isUtf16Boundary(text, offset) || !isUtf16Boundary(text, offset + exact.length)) return false
        if (!text.regionMatches(offset, exact, 0, exact.length)) return false
        if (prefix.isNotEmpty()) {
            val prefixStart = offset - prefix.length
            if (prefixStart < 0 || !isUtf16Boundary(text, prefixStart) ||
                !isUtf16Boundary(text, offset) ||
                !text.regionMatches(prefixStart, prefix, 0, prefix.length)
            ) return false
        }
        if (suffix.isNotEmpty()) {
            val suffixStart = offset + exact.length
            if (suffixStart > text.length || suffix.length > text.length - suffixStart ||
                !isUtf16Boundary(text, suffixStart) ||
                !isUtf16Boundary(text, suffixStart + suffix.length) ||
                !text.regionMatches(suffixStart, suffix, 0, suffix.length)
            ) return false
        }
        return true
    }
}

/** Coordinates shared by progress, search, TTS and annotation clients. */
@Serializable
public sealed interface ReadingLocator {
    public val schemaVersion: Int
    public val scope: ReadingScope
    /** Common semantic cursor used by search/TTS consumers without erasing the locator variant. */
    public val progression: Double?
    /** Canonical UTF-16 hint when this locator addresses text; absent for image locations. */
    public val offset: Int?
    /** Stable semantic block hint when one exists; CFI/href remain authoritative for EPUB. */
    public val blockId: String?

    public fun resolveOffset(text: String): Int?

    @Serializable
    @SerialName("image")
    public data class Image(
        override val schemaVersion: Int,
        override val scope: ReadingScope,
        /** Stable page resource identity; pageIndexHint is materialized-only. */
        val pageResourceId: String,
        val pageIndexHint: Int? = null,
        val normalizedOffsetFraction: Double = 0.0,
    ) : ReadingLocator {
        init { validate() }

        public val pageIndex: Int? get() = pageIndexHint
        override val progression: Double get() = normalizedOffsetFraction
        override val offset: Int? get() = null
        override val blockId: String? get() = null
        override fun resolveOffset(text: String): Int? = null
        public fun validate(): Unit = validateReadingLocator(this)
    }

    @Serializable
    @SerialName("text")
    public data class Text(
        override val schemaVersion: Int,
        override val scope: ReadingScope,
        val resourceId: String,
        override val blockId: String,
        override val offset: Int,
        val offsetUnit: TextOffsetUnit = TextOffsetUnit.UTF_16_CODE_UNIT,
        override val progression: Double? = null,
        val direction: TextDirection = TextDirection.FORWARD,
        val quote: TextQuote? = null,
    ) : ReadingLocator {
        init { validate() }

        public fun validate(): Unit = validateReadingLocator(this)

        override fun resolveOffset(text: String): Int? {
            if (offset <= text.length && isUtf16Boundary(text, offset) &&
                (quote == null || quote.matchesAt(text, offset))
            ) return offset
            return quote?.findIn(text)
        }
    }

    @Serializable
    @SerialName("epub")
    public data class Epub(
        override val schemaVersion: Int,
        override val scope: ReadingScope,
        val resourceId: String,
        /** Safe package href is authoritative; spine/offset are materialized hints. */
        val resourceHref: String,
        val cfi: String,
        override val progression: Double? = null,
        val direction: TextDirection = TextDirection.FORWARD,
        val spineIndexHint: Int? = null,
        val offsetHint: Int? = null,
        /** Derived semantic hint only; [resourceHref] and [cfi] remain portable authority. */
        val blockIdHint: String? = null,
        val offsetUnit: TextOffsetUnit = TextOffsetUnit.UTF_16_CODE_UNIT,
        val quote: TextQuote? = null,
    ) : ReadingLocator {
        init { validate() }

        public val spineIndex: Int? get() = spineIndexHint
        override val offset: Int? get() = offsetHint
        override val blockId: String? get() = blockIdHint
        public fun validate(): Unit = validateReadingLocator(this)

        override fun resolveOffset(text: String): Int? {
            val offset = offsetHint
            if (offset != null && offset <= text.length && isUtf16Boundary(text, offset) &&
                (quote == null || quote.matchesAt(text, offset))
            ) {
                return offset
            }
            return quote?.findIn(text)
        }
    }
}

/** A range contains two points; text length is never encoded into one locator. */
@Serializable
public data class ReadingRange(
    val start: ReadingLocator,
    val end: ReadingLocator,
    val quote: TextQuote? = null,
) {
    init { validate() }

    public fun validate(): Unit {
        start.validate()
        end.validate()
        require(start.scope == end.scope) { "Reading range endpoints must share publication/acquisition/unit/revision" }
        require(start::class == end::class) { "Reading range endpoints must share locator kind" }
        when {
            start is ReadingLocator.Text && end is ReadingLocator.Text -> {
                if (start.resourceId == end.resourceId && start.blockId == end.blockId) {
                    require(start.offset <= end.offset) { "Text range start must not follow end" }
                }
            }
            start is ReadingLocator.Epub && end is ReadingLocator.Epub -> {
                if (start.resourceId == end.resourceId && start.resourceHref == end.resourceHref) {
                    val startOffset = start.offsetHint
                    val endOffset = end.offsetHint
                    if (startOffset != null && endOffset != null) {
                        require(startOffset <= endOffset) { "EPUB range start must not follow end" }
                    }
                }
            }
            start is ReadingLocator.Image && end is ReadingLocator.Image -> Unit
        }
        quote?.validate()
    }

    public fun resolveTextStart(text: String): Int? = quote?.findIn(text) ?: start.resolveTextOffset(text)
}

public typealias ImageLocator = ReadingLocator.Image
public typealias TextLocator = ReadingLocator.Text
public typealias EpubLocator = ReadingLocator.Epub

public fun ReadingLocator.validate(): Unit = validateReadingLocator(this)

public fun validateReadingLocator(locator: ReadingLocator): Unit {
    require(locator.schemaVersion == ReadingScope.CURRENT_SCHEMA_VERSION) {
        "Unsupported reading locator schema version ${locator.schemaVersion}"
    }
    require(locator.scope.schemaVersion == locator.schemaVersion) {
        "Locator and scope schema versions must agree"
    }
    locator.scope.validate()
    when (locator) {
        is ReadingLocator.Image -> {
            requireIdentifier(locator.pageResourceId, "Image page resource id")
            require(locator.pageIndexHint == null || locator.pageIndexHint >= 0) {
                "Image page index hint must be non-negative"
            }
            require(locator.normalizedOffsetFraction.isFinite() &&
                locator.normalizedOffsetFraction in 0.0..1.0) {
                "Image offset must be between 0 and 1"
            }
        }

        is ReadingLocator.Text -> {
            requireIdentifier(locator.resourceId, "Text resource id")
            requireIdentifier(locator.blockId, "Text block id")
            require(locator.offset >= 0) { "Text offset must be non-negative" }
            require(locator.offsetUnit == TextOffsetUnit.UTF_16_CODE_UNIT) {
                "Only UTF-16 code-unit text offsets are supported"
            }
            require(locator.progression == null ||
                (locator.progression.isFinite() && locator.progression in 0.0..1.0)) {
                "Text progression must be between 0 and 1 when present"
            }
            locator.quote?.validate()
        }

        is ReadingLocator.Epub -> {
            requireIdentifier(locator.resourceId, "EPUB resource id")
            requireSafeHref(locator.resourceHref, "EPUB resource href")
            requireCfi(locator.cfi)
            require(locator.progression == null ||
                (locator.progression.isFinite() && locator.progression in 0.0..1.0)) {
                "EPUB progression must be between 0 and 1 when present"
            }
            require(locator.spineIndexHint == null || locator.spineIndexHint >= 0) {
                "EPUB spine index hint must be non-negative"
            }
            require(locator.offsetHint == null || locator.offsetHint >= 0) {
                "EPUB offset hint must be non-negative"
            }
            locator.blockIdHint?.let { requireIdentifier(it, "EPUB semantic block id hint") }
            require(locator.offsetHint == null || locator.offsetUnit == TextOffsetUnit.UTF_16_CODE_UNIT) {
                "EPUB offset hint must use UTF-16 code units"
            }
            locator.quote?.validate()
        }
    }
}

public fun ReadingLocator.resolveTextOffset(text: String): Int? = when (this) {
    is ReadingLocator.Image -> null
    is ReadingLocator.Text -> resolveOffset(text)
    is ReadingLocator.Epub -> resolveOffset(text)
}

private fun requireUuid(value: String, label: String) {
    require(UUID_PATTERN.matches(value) && value != NIL_UUID) {
        "$label must be a lowercase non-NIL UUID"
    }
}

private fun requireIdentifier(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_IDENTIFIER_LENGTH &&
        value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "$label must be bounded and printable"
    }
}

private fun requireSafeHref(value: String, label: String) {
    require(value.isNotBlank() && value.length <= MAX_HREF_LENGTH) { "$label must be bounded and non-blank" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace) && !value.contains('\\')) {
        "$label is unsafe"
    }
    require(!value.startsWith('/') && !value.startsWith("//") &&
        !value.matches(Regex("(?i)^[A-Za-z][A-Za-z0-9+.-]*:.*"))) { "$label must be relative" }
    // Decode repeatedly so an attacker cannot hide traversal, a scheme, a backslash, or a
    // control character behind `%25` (for example `%252e%252e` or `%2568ttp%253a`).  Every pass
    // validates the escape syntax and the final value is checked below.  The small fixed bound
    // is intentional: a path that keeps changing after this many passes is rejected rather than
    // treated as a capability.
    val decodedReference = repeatedlyPercentDecode(value)
    require(decodedReference.none(Char::isISOControl) && !decodedReference.contains('\\')) {
        "$label contains unsafe encoded characters"
    }
    val rawPath = value.substringBefore('#').substringBefore('?')
    val decodedFull = repeatedlyPercentDecode(rawPath)
    require(!decodedFull.startsWith('/') && !decodedFull.startsWith("//") &&
        !decodedFull.contains("://") &&
        !decodedFull.matches(Regex("(?i)^[A-Za-z][A-Za-z0-9+.-]*:.*"))) {
        "$label must remain relative after decoding"
    }
    require(decodedFull.split('/').none { it == ".." }) { "$label contains traversal" }
}

private fun requireCfi(value: String) {
    require(value.length in 10..MAX_CFI_LENGTH && value.startsWith("epubcfi(") && value.endsWith(')')) {
        "EPUB CFI must be a bounded epubcfi() value"
    }
    val body = value.substring(CFI_PREFIX.length, value.length - 1)
    require(body.isNotEmpty() && body[0] == '/') { "EPUB CFI must contain an absolute path" }
    require(value.none(Char::isISOControl) && value.none(Char::isWhitespace)) {
        "EPUB CFI contains control or whitespace"
    }
    require('%' !in value && '\\' !in value) {
        "EPUB CFI must not contain encoded or host-path escapes"
    }
    requireCfiPath(body)
}

/**
 * Validate the point-CFI subset used by a [ReadingLocator.Epub].  Range CFIs are intentionally
 * not accepted here: a range has two ReadingLocator endpoints and accepting a second comma-
 * separated path would make it ambiguous which endpoint was being persisted.
 */
private fun requireCfiPath(body: String) {
    var index = 0
    var pathCount = 0
    var sawIndirection = false
    while (index < body.length) {
        require(body[index] == '/') { "EPUB CFI path step must start with '/'" }
        index++
        val numberStart = index
        while (index < body.length && body[index].isDigit()) index++
        require(index > numberStart) { "EPUB CFI path step is missing its numeric index" }
        // CFI path indexes are element positions and zero is not a valid element step.  Text
        // offsets are handled by the `:0` suffix below and may legitimately be zero.
        require(body.substring(numberStart, index).toLongOrNull()?.let { it > 0L } == true) {
            "EPUB CFI path step must be positive"
        }
        pathCount++
        if (index < body.length && body[index] == '[') {
            index = consumeCfiAssertion(body, index)
        }
        if (index < body.length && body[index] == ':') {
            index++
            val offsetStart = index
            while (index < body.length && body[index].isDigit()) index++
            require(index > offsetStart) { "EPUB CFI text offset is missing" }
            require(index == body.length || body[index] == ';') {
                "EPUB CFI has trailing characters after its text offset"
            }
            if (index < body.length && body[index] == ';') {
                index = consumeCfiSideBias(body, index)
                require(index == body.length) { "EPUB CFI side bias must terminate the path" }
            }
        }
        if (index == body.length) break
        if (body[index] == '!') {
            require(!sawIndirection) { "EPUB CFI has too many indirection markers" }
            sawIndirection = true
            index++
            require(index < body.length && body[index] == '/') {
                "EPUB CFI indirection must be followed by a path"
            }
            continue
        }
        require(body[index] == '/') {
            "EPUB CFI contains malformed path punctuation"
        }
    }
    require(pathCount > 0) { "EPUB CFI path is empty" }
}

private fun consumeCfiAssertion(body: String, start: Int): Int {
    var index = start + 1
    var length = 0
    while (index < body.length) {
        when (val character = body[index]) {
            ']' -> {
                require(length > 0) { "EPUB CFI assertion is empty" }
                return index + 1
            }
            '^' -> {
                require(index + 1 < body.length && body[index + 1] in "[](),^") {
                    "EPUB CFI assertion has an invalid escape"
                }
                index++
            }
            '[', '!' -> require(false) { "EPUB CFI assertion is malformed" }
            else -> {
                require(!character.isISOControl() && !character.isWhitespace() && character != '%') {
                    "EPUB CFI assertion contains unsafe text"
                }
            }
        }
        length++
        index++
    }
    require(false) { "EPUB CFI assertion is unterminated" }
    return index
}

private fun consumeCfiSideBias(body: String, start: Int): Int {
    require(body.startsWith(";s=", start)) { "EPUB CFI side-bias syntax is invalid" }
    val biasStart = start + 3
    require(biasStart < body.length && body[biasStart] in "ab") {
        "EPUB CFI side-bias value is invalid"
    }
    return biasStart + 1
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

private fun repeatedlyPercentDecode(value: String): String {
    var current = value
    repeat(MAX_PERCENT_DECODE_PASSES) {
        val decoded = percentDecodeOnce(current)
        if (decoded == current) return current
        current = decoded
    }
    // A changing value after the bounded number of passes is almost certainly a nested escape
    // payload.  Reject it instead of allowing a later consumer to decode it as a path.
    require(percentDecodeOnce(current) == current) { "Too many nested percent escapes" }
    return current
}

private fun percentDecodeOnce(value: String): String {
    val output = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        if (value[index] == '%') {
            require(index + 2 < value.length) { "Incomplete percent escape" }
            val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
            require(byte != null) { "Invalid percent escape" }
            output.append(byte.toChar())
            index += 3
            continue
        }
        output.append(value[index++])
    }
    return output.toString()
}

private fun isUtf16Boundary(text: String, offset: Int): Boolean {
    if (offset < 0 || offset > text.length) return false
    return offset == 0 || offset == text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())
}

private const val MAX_QUOTE_EXACT_LENGTH: Int = 4_096
private const val MAX_QUOTE_CONTEXT_LENGTH: Int = 512
private const val MAX_QUOTE_OCCURRENCE: Int = 1_000_000
private const val MAX_CFI_LENGTH: Int = 4_096
private const val MAX_IDENTIFIER_LENGTH: Int = 512
private const val MAX_HREF_LENGTH: Int = 4_096
private const val MAX_PERCENT_DECODE_PASSES: Int = 4
private const val CFI_PREFIX: String = "epubcfi("
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
