package dev.shinsou.kmp.ui.reader

import dev.shinsou.kmp.plugin.v2.ExtensionUnitPageProgressV2
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text

/** One-based page label for detail screens; the total is shown only when it was measured. */
fun readingProgressPageLabel(strings: ShinsouStrings, pageIndex: Int, pageCount: Int?): String {
    val page = pageIndex.coerceAtLeast(0) + 1
    return if (pageCount != null && pageCount >= page) {
        strings.text(StringKey.PAGE_X_X, page, pageCount)
    } else {
        strings.text(StringKey.PAGE_X, page)
    }
}

/** "Last read" hint under the continue button: the chapter title plus its page when unfinished. */
fun extensionContinueReadingDetail(
    strings: ShinsouStrings,
    unitTitle: String,
    progress: ExtensionUnitPageProgressV2?,
): String {
    val target = progress?.let {
        "$unitTitle · ${readingProgressPageLabel(strings, it.pageIndex, it.pageCount)}"
    } ?: unitTitle
    return strings.text(StringKey.LAST_READ_X, target)
}
