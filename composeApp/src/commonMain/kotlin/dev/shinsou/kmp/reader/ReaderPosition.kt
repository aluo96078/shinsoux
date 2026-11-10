package dev.shinsou.kmp.reader

import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.sync.v3.HlcTimestamp
import kotlinx.serialization.Serializable

/** Where a reader stands inside one chapter: a page, plus how far down it in continuous modes. */
@Serializable
data class ReaderPosition(
    val readingMode: ReadingMode,
    val pageIndex: Int,
    val normalizedOffsetFraction: Double = 0.0,
    val resetEpoch: Long = 0,
) {
    init {
        require(pageIndex >= 0) { "Reader page index cannot be negative" }
        require(resetEpoch >= 0) { "Reader reset epoch cannot be negative" }
        require(normalizedOffsetFraction.isFinite() && normalizedOffsetFraction in 0.0..1.0) {
            "Reader offset must be a finite value between zero and one"
        }
        if (readingMode != ReadingMode.WEBTOON && readingMode != ReadingMode.CONTINUOUS_VERTICAL) {
            require(normalizedOffsetFraction == 0.0) { "Paged reader positions must use a zero offset" }
        }
    }
}

/** A [ReaderPosition] with the time and reading session that produced it. */
@Serializable
data class ReadingPositionRegister(
    val position: ReaderPosition,
    val hlc: HlcTimestamp,
    val sessionId: String,
) {
    init {
        require(sessionId.isNotBlank()) { "Reader session id cannot be blank" }
    }
}
