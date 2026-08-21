package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ImagePage
import dev.shinsou.kmp.content.TextBlock

/** Shared ordered-resource navigation contract for every reader representation. */
public interface UnifiedReaderNavigation {
    public val scope: ReadingScope
    public val representation: ContentRepresentation
    public val representationId: String get() = representation.representationId
    public val kind: ContentKind get() = representation.kind
    public val itemCount: Int

    /** Returns a stable locator at the start of the ordered item; index is only a hint in it. */
    public fun locatorAt(index: Int): ReadingLocator

    /** Resolves by stable resource/block identity and ignores stale materialized index hints. */
    public fun indexOf(locator: ReadingLocator): Int?

    public fun previous(locator: ReadingLocator): ReadingLocator? =
        indexOf(locator)?.minus(1)?.takeIf { it >= 0 }?.let(::locatorAt)

    public fun next(locator: ReadingLocator): ReadingLocator? =
        indexOf(locator)?.plus(1)?.takeIf { it < itemCount }?.let(::locatorAt)
}

public object UnifiedReaderNavigationFactory {
    public fun create(
        scope: ReadingScope,
        representation: ContentRepresentation,
        canonicalText: String? = null,
    ): UnifiedReaderNavigation = when (representation) {
        is ContentRepresentation.ImageSequence -> ImageSequenceNavigation(scope, representation)
        is ContentRepresentation.PlainText -> PlainTextNavigation(
            scope,
            representation,
            requireNotNull(canonicalText) { "Plain-text navigation requires canonical text" },
        )
        is ContentRepresentation.EpubSpine -> EpubSpineNavigation(scope, representation)
    }
}

/** Runtime content handed to the optional unified ReaderScreen seam. */
public data class UnifiedReaderContent(
    val navigation: UnifiedReaderNavigation,
    val initialLocator: ReadingLocator = navigation.locatorAt(0),
) {
    public val representation: ContentRepresentation get() = navigation.representation

    init {
        require(navigation.itemCount > 0) { "Unified reader content must be navigable" }
        require(navigation.indexOf(initialLocator) != null) {
            "Initial locator does not belong to the unified reader content"
        }
    }
}

public class ImageSequenceNavigation(
    override val scope: ReadingScope,
    override val representation: ContentRepresentation.ImageSequence,
) : UnifiedReaderNavigation {
    override val itemCount: Int get() = representation.pages.size

    init {
        scope.validate()
        representation.validate()
    }

    override fun locatorAt(index: Int): ReadingLocator.Image = locator(index, 0.0)

    public fun locator(index: Int, normalizedOffsetFraction: Double): ReadingLocator.Image {
        val page = pageAt(index)
        return ReadingLocator.Image(
            schemaVersion = scope.schemaVersion,
            scope = scope,
            pageResourceId = page.resourceId,
            pageIndexHint = index,
            normalizedOffsetFraction = normalizedOffsetFraction,
        )
    }

    override fun indexOf(locator: ReadingLocator): Int? {
        if (locator !is ReadingLocator.Image || locator.scope != scope) return null
        return representation.pages.indexOfFirst { page -> page.resourceId == locator.pageResourceId }
            .takeIf { it >= 0 }
    }

    private fun pageAt(index: Int): ImagePage {
        require(index in representation.pages.indices) { "Image reader index is out of range" }
        return representation.pages[index]
    }
}

public class PlainTextNavigation(
    override val scope: ReadingScope,
    override val representation: ContentRepresentation.PlainText,
    canonicalText: String,
) : UnifiedReaderNavigation {
    private val text: String = canonicalText
    override val itemCount: Int get() = representation.blocks.size

    init {
        scope.validate()
        representation.validate()
        require(canonicalText.length == representation.canonicalUtf16Length) {
            "Canonical text length does not match its representation"
        }
        require(isWellFormedUtf16(canonicalText)) { "Canonical text contains malformed UTF-16" }
        representation.blocks.forEach { block ->
            require(
                isUtf16Boundary(canonicalText, block.startUtf16) &&
                    isUtf16Boundary(canonicalText, block.endUtf16),
            ) {
                "Text block boundary splits a surrogate pair"
            }
        }
    }

    override fun locatorAt(index: Int): ReadingLocator.Text {
        val block = blockAt(index)
        return locator(
            blockId = block.blockId,
            offsetUtf16 = block.startUtf16,
        )
    }

    public fun locator(
        blockId: String,
        offsetUtf16: Int,
        direction: TextDirection = TextDirection.FORWARD,
        quote: TextQuote? = null,
    ): ReadingLocator.Text {
        val block = representation.blocks.firstOrNull { it.blockId == blockId }
            ?: throw IllegalArgumentException("Unknown text block id")
        require(offsetUtf16 in 0..representation.canonicalUtf16Length) {
            "Text locator offset is out of range"
        }
        require(isUtf16Boundary(text, offsetUtf16)) { "Text locator splits a surrogate pair" }
        return ReadingLocator.Text(
            schemaVersion = scope.schemaVersion,
            scope = scope,
            resourceId = representation.resource.id,
            blockId = block.blockId,
            offset = offsetUtf16,
            progression = progression(offsetUtf16, representation.canonicalUtf16Length),
            direction = direction,
            quote = quote,
        )
    }

    /** Finds the stable paragraph identity for an absolute UTF-16 document offset. */
    public fun locatorForOffset(
        offsetUtf16: Int,
        direction: TextDirection = TextDirection.FORWARD,
        quote: TextQuote? = null,
    ): ReadingLocator.Text {
        require(offsetUtf16 in 0..representation.canonicalUtf16Length) {
            "Text locator offset is out of range"
        }
        val block = representation.blocks.firstOrNull { candidate ->
            offsetUtf16 >= candidate.startUtf16 &&
                (offsetUtf16 < candidate.endUtf16 ||
                    offsetUtf16 == candidate.endUtf16 && candidate.endUtf16 == representation.canonicalUtf16Length)
        } ?: representation.blocks.lastOrNull { it.startUtf16 <= offsetUtf16 }
            ?: representation.blocks.first()
        return locator(block.blockId, offsetUtf16, direction, quote)
    }

    override fun indexOf(locator: ReadingLocator): Int? {
        if (locator !is ReadingLocator.Text || locator.scope != scope ||
            locator.resourceId != representation.resource.id
        ) return null
        return representation.blocks.indexOfFirst { it.blockId == locator.blockId }.takeIf { it >= 0 }
    }

    private fun blockAt(index: Int): TextBlock {
        require(index in representation.blocks.indices) { "Text reader index is out of range" }
        return representation.blocks[index]
    }
}

public class EpubSpineNavigation(
    override val scope: ReadingScope,
    override val representation: ContentRepresentation.EpubSpine,
) : UnifiedReaderNavigation {
    override val itemCount: Int get() = representation.documents.size

    init {
        scope.validate()
        representation.validate()
    }

    override fun locatorAt(index: Int): ReadingLocator.Epub = locator(
        documentIndex = index,
        cfi = startCfi(index),
        progression = 0.0,
    )

    public fun locator(
        documentIndex: Int,
        cfi: String,
        /** Progress within this exact spine resource, not a materialized spine-list fraction. */
        progression: Double? = 0.0,
        direction: TextDirection = TextDirection.FORWARD,
        offsetHint: Int? = null,
        blockIdHint: String? = null,
        quote: TextQuote? = null,
    ): ReadingLocator.Epub {
        val document = documentAt(documentIndex)
        return ReadingLocator.Epub(
            schemaVersion = scope.schemaVersion,
            scope = scope,
            resourceId = document.resourceId,
            resourceHref = document.href,
            cfi = cfi,
            progression = progression,
            direction = direction,
            spineIndexHint = documentIndex,
            offsetHint = offsetHint,
            blockIdHint = blockIdHint,
            quote = quote,
        )
    }

    /** Maps native browser scroll metrics onto the extractor's exact DOM/CFI identity. */
    public fun locatorForDocumentProgression(
        documentIndex: Int,
        progression: Double,
        semanticDocument: EpubSemanticDocument? = null,
    ): ReadingLocator.Epub {
        require(progression.isFinite()) { "EPUB document progression is invalid" }
        val normalized = progression.coerceIn(0.0, 1.0)
        return semanticDocument?.locatorForProgression(this, normalized)
            ?: locator(documentIndex, startCfi(documentIndex), progression = normalized)
    }

    /** Restores the scroll fraction only when the locator belongs to this spine document. */
    public fun documentProgression(locator: ReadingLocator.Epub): Double? {
        if (indexOf(locator) == null) return null
        return locator.progression?.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0)
    }

    override fun indexOf(locator: ReadingLocator): Int? {
        if (locator !is ReadingLocator.Epub || locator.scope != scope) return null
        return representation.documents.indexOfFirst { document ->
            document.resourceId == locator.resourceId && document.href == locator.resourceHref
        }.takeIf { it >= 0 }
    }

    public fun startCfi(documentIndex: Int): String {
        documentAt(documentIndex)
        val packageStep = (documentIndex + 1) * 2
        return "epubcfi(/6/$packageStep!/4/1:0)"
    }

    private fun documentAt(index: Int): EpubSpineDocument {
        require(index in representation.documents.indices) { "EPUB spine index is out of range" }
        return representation.documents[index]
    }

}

private fun progression(offset: Int, length: Int): Double =
    if (length == 0) 0.0 else offset.toDouble() / length.toDouble()

private fun isUtf16Boundary(text: String, offset: Int): Boolean =
    offset == 0 || offset == text.length ||
        !(text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate())

private fun isWellFormedUtf16(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        when {
            text[index].isHighSurrogate() -> {
                if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
                index += 2
            }
            text[index].isLowSurrogate() -> return false
            else -> index++
        }
    }
    return true
}
