package dev.shinsou.kmp.reader

import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.sync.v2.HlcTimestamp
import dev.shinsou.kmp.sync.v2.ReaderPosition
import dev.shinsou.kmp.sync.v2.ReadingPositionRegister
import kotlin.test.Test
import kotlin.test.assertEquals

class ReaderPositionMappingTest {
    @Test
    fun pagersRoundTripLogicalIndicesInEveryDirection() {
        ReadingMode.entries
            .filterNot(::isContinuousReaderMode)
            .forEach { mode ->
                (0 until 8).forEach { logical ->
                    val physical = readerPhysicalPageIndex(logical, pageCount = 8, readingMode = mode)
                    assertEquals(logical, readerLogicalPageIndex(physical, 8, mode), mode.name)
                }
            }

        assertEquals(7, readerPhysicalPageIndex(0, 8, ReadingMode.PAGER_RTL))
        assertEquals(0, readerPhysicalPageIndex(0, 8, ReadingMode.PAGER_LTR))
        assertEquals(0, readerPhysicalPageIndex(0, 8, ReadingMode.PAGER_VERTICAL))
    }

    @Test
    fun continuousPositionRoundTripsADeviceIndependentOffset() {
        val position = continuousReaderPosition(
            readingMode = ReadingMode.WEBTOON,
            sample = ReaderViewportSample(pageIndex = 4, pageOffsetPixels = 375, pageExtentPixels = 1_000),
            pageCount = 10,
            resetEpoch = 3,
        )

        assertEquals(ReaderPosition(ReadingMode.WEBTOON, 4, 0.375, 3), position)
        assertEquals(750, restoredReaderPageOffsetPixels(position, pageExtentPixels = 2_000))
    }

    @Test
    fun invalidOrBoundaryViewportMeasurementsStaySafe() {
        assertEquals(
            ReaderPosition(ReadingMode.CONTINUOUS_VERTICAL, 0, 0.0),
            continuousReaderPosition(
                ReadingMode.CONTINUOUS_VERTICAL,
                ReaderViewportSample(-4, 99, 0),
                pageCount = 0,
            ),
        )
        assertEquals(
            999,
            restoredReaderPageOffsetPixels(
                ReaderPosition(ReadingMode.WEBTOON, 2, 1.0),
                pageExtentPixels = 1_000,
            ),
        )
    }

    @Test
    fun remotePositionRestoresInitiallyButNeverForcesAnOpenReader() {
        val initial = register(page = 5, wallMillis = 10, sessionId = "other")
        assertEquals(
            ReaderPositionUpdateDecision.RESTORE_ON_OPEN,
            readerPositionUpdateDecision(false, "local", null, initial),
        )
        assertEquals(
            ReaderPositionUpdateDecision.IGNORE,
            readerPositionUpdateDecision(true, "local", initial.hlc, initial),
        )

        val ownNewer = register(page = 6, wallMillis = 11, sessionId = "local")
        assertEquals(
            ReaderPositionUpdateDecision.IGNORE,
            readerPositionUpdateDecision(true, "local", initial.hlc, ownNewer),
        )

        val foreignNewer = register(page = 8, wallMillis = 12, sessionId = "other")
        assertEquals(
            ReaderPositionUpdateDecision.OFFER_APPLY,
            readerPositionUpdateDecision(true, "local", ownNewer.hlc, foreignNewer),
        )
    }

    private fun register(page: Int, wallMillis: Long, sessionId: String) = ReadingPositionRegister(
        position = ReaderPosition(ReadingMode.PAGER_LTR, page),
        hlc = HlcTimestamp(wallMillis, 0, "device"),
        sessionId = sessionId,
    )
}
