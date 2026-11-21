package dev.shinsou.kmp.search

import dev.shinsou.kmp.domain.content.EpubSpineNavigation

/**
 * The text of one EPUB spine document as the index stores it: the canonical text, cut into the
 * publisher's text nodes, each with the CFI its offsets are relative to.
 */
public class EpubIndexText(
    public val resourceId: String,
    public val resourceHref: String,
    public val canonicalText: String,
    public val blocks: List<Block>,
) {
    /** One text node of [canonicalText]; [cfiBase] is its point CFI at text offset zero. */
    public class Block(
        public val blockId: String,
        public val startUtf16: Int,
        public val endUtf16: Int,
        public val cfiBase: String,
    )
}

/**
 * Reads one EPUB spine document's text for indexing, cancellably. The reader's semantic
 * extraction supplies it, so the index and the reader agree on offsets and CFIs.
 */
public fun interface EpubIndexTextReader {
    public suspend fun read(navigation: EpubSpineNavigation, documentIndex: Int): EpubIndexText
}
