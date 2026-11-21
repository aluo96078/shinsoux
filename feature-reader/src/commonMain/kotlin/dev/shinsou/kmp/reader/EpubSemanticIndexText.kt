package dev.shinsou.kmp.reader

import dev.shinsou.kmp.search.EpubIndexText
import dev.shinsou.kmp.search.EpubIndexTextReader

/** This semantic extraction as the search index's source of EPUB text. */
public fun EpubSemanticDocumentFactory.asEpubIndexTextReader(): EpubIndexTextReader =
    EpubIndexTextReader { navigation, documentIndex ->
        val semantic = createCancellable(navigation = navigation, documentIndex = documentIndex)
        EpubIndexText(
            resourceId = semantic.resourceId,
            resourceHref = semantic.resourceHref,
            canonicalText = semantic.canonicalText,
            blocks = semantic.blocks.map { block ->
                EpubIndexText.Block(block.blockId, block.startUtf16, block.endUtf16, block.cfiBase)
            },
        )
    }
