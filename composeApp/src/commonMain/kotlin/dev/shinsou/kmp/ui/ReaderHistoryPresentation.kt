package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text

/** Visual pages are rendition-specific and therefore travel separately from portable locators. */
internal data class ReaderProgressPosition(
    val locator: ReadingLocator,
    val pageIndex: Int,
    val pageCount: Int? = null,
) {
    init {
        require(pageIndex >= 0) { "Reader page index must be non-negative" }
        require(pageCount == null || pageCount > pageIndex) { "Reader page must be inside its page count" }
    }
}

/**
 * Orders observations before their asynchronous persistence work starts. Several page changes can
 * share one wall-clock millisecond, so equal observations are advanced as well.
 */
internal class ReaderProgressObservationClock(initialTimestamp: Long = 0L) {
    private var latestTimestamp: Long = initialTimestamp

    init {
        require(initialTimestamp >= 0L) { "Initial reader progress timestamp must be non-negative" }
    }

    fun next(observedAt: Long): Long {
        require(observedAt >= 0L) { "Reader progress timestamp must be non-negative" }
        val afterLatest = if (latestTimestamp == Long.MAX_VALUE) Long.MAX_VALUE else latestTimestamp + 1L
        return maxOf(observedAt, afterLatest).also { latestTimestamp = it }
    }
}

internal fun historyPositionLabel(chapter: Chapter, strings: ShinsouStrings): String =
    "${chapter.name} · ${strings.text("Page {0}", chapter.lastPageRead + 1)}"
