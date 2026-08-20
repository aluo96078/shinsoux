package dev.shinsou.kmp.reader

import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.ReaderPosition
import dev.shinsou.kmp.sync.v2.ReadingPositionRegister
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val READER_OFFSET_PRECISION = 10_000.0

/** A platform-neutral sample of the first visible item in a continuous reader. */
data class ReaderViewportSample(
    val pageIndex: Int,
    val pageOffsetPixels: Int,
    val pageExtentPixels: Int,
)

enum class ReaderPositionUpdateDecision {
    IGNORE,
    RESTORE_ON_OPEN,
    OFFER_APPLY,
}

fun isContinuousReaderMode(readingMode: ReadingMode): Boolean =
    readingMode == ReadingMode.WEBTOON || readingMode == ReadingMode.CONTINUOUS_VERTICAL

/** Converts a logical source page into the physical pager index rendered by Compose. */
fun readerPhysicalPageIndex(
    logicalPageIndex: Int,
    pageCount: Int,
    readingMode: ReadingMode,
): Int {
    if (pageCount <= 0) return 0
    val logical = logicalPageIndex.coerceIn(0, pageCount - 1)
    return if (readingMode == ReadingMode.PAGER_RTL) pageCount - 1 - logical else logical
}

/** Converts a Compose pager index back into the source's logical page index. */
fun readerLogicalPageIndex(
    physicalPageIndex: Int,
    pageCount: Int,
    readingMode: ReadingMode,
): Int {
    if (pageCount <= 0) return 0
    val physical = physicalPageIndex.coerceIn(0, pageCount - 1)
    return if (readingMode == ReadingMode.PAGER_RTL) pageCount - 1 - physical else physical
}

/** Normalises a persisted or remote position for the reader mode that is currently displayed. */
fun coerceReaderPosition(
    position: ReaderPosition,
    readingMode: ReadingMode,
    pageCount: Int,
): ReaderPosition = ReaderPosition(
    readingMode = readingMode,
    pageIndex = if (pageCount <= 0) 0 else position.pageIndex.coerceIn(0, pageCount - 1),
    normalizedOffsetFraction = if (isContinuousReaderMode(readingMode)) {
        position.normalizedOffsetFraction.coerceIn(0.0, 1.0)
    } else {
        0.0
    },
    resetEpoch = position.resetEpoch,
)

fun pagedReaderPosition(
    readingMode: ReadingMode,
    logicalPageIndex: Int,
    pageCount: Int,
    resetEpoch: Long = 0,
): ReaderPosition {
    require(!isContinuousReaderMode(readingMode)) { "Continuous modes require a viewport sample" }
    val page = if (pageCount <= 0) 0 else logicalPageIndex.coerceIn(0, pageCount - 1)
    return ReaderPosition(readingMode, page, normalizedOffsetFraction = 0.0, resetEpoch = resetEpoch)
}

/** Encodes a Webtoon/continuous viewport without leaking device-specific pixel offsets. */
fun continuousReaderPosition(
    readingMode: ReadingMode,
    sample: ReaderViewportSample,
    pageCount: Int,
    resetEpoch: Long = 0,
): ReaderPosition {
    require(isContinuousReaderMode(readingMode)) { "A continuous position requires a continuous reader mode" }
    val page = if (pageCount <= 0) 0 else sample.pageIndex.coerceIn(0, pageCount - 1)
    val fraction = if (sample.pageExtentPixels <= 0) {
        0.0
    } else {
        (sample.pageOffsetPixels.coerceAtLeast(0).toDouble() / sample.pageExtentPixels.toDouble())
            .coerceIn(0.0, 1.0)
            .let { (it * READER_OFFSET_PRECISION).roundToLong() / READER_OFFSET_PRECISION }
    }
    return ReaderPosition(readingMode, page, fraction, resetEpoch)
}

/** Reconstructs a best-effort local pixel offset after the target item has been measured. */
fun restoredReaderPageOffsetPixels(position: ReaderPosition, pageExtentPixels: Int): Int {
    if (!isContinuousReaderMode(position.readingMode) || pageExtentPixels <= 0) return 0
    return (position.normalizedOffsetFraction * pageExtentPixels.toDouble())
        .roundToInt()
        .coerceIn(0, (pageExtentPixels - 1).coerceAtLeast(0))
}

/**
 * Distinguishes the initial restore from an update arriving while the same chapter is already
 * open. A foreign session is only offered after its HLC is newer than the last observed register.
 */
fun readerPositionUpdateDecision(
    initialized: Boolean,
    activeSessionId: String,
    lastObservedHlc: HlcTimestamp?,
    incoming: ReadingPositionRegister?,
): ReaderPositionUpdateDecision {
    if (incoming == null) return ReaderPositionUpdateDecision.IGNORE
    if (!initialized) return ReaderPositionUpdateDecision.RESTORE_ON_OPEN
    if (lastObservedHlc != null && incoming.hlc <= lastObservedHlc) return ReaderPositionUpdateDecision.IGNORE
    return if (incoming.sessionId == activeSessionId) {
        ReaderPositionUpdateDecision.IGNORE
    } else {
        ReaderPositionUpdateDecision.OFFER_APPLY
    }
}
