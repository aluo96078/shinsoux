package dev.shinsou.kmp.download

import dev.shinsou.kmp.domain.model.DownloadQueueItem
import dev.shinsou.kmp.domain.model.DownloadSettings
import dev.shinsou.kmp.domain.model.DownloadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DownloadRemovalPolicyTest {
    private val queue = listOf(
        DownloadQueueItem("one", 1, 10, state = DownloadState.DOWNLOADED),
        DownloadQueueItem("two", 1, 11, state = DownloadState.DOWNLOADING),
        DownloadQueueItem("three", 2, 12, state = DownloadState.DOWNLOADED),
    )

    @Test
    fun removesOnlyCompletedDownloadsForNewlyReadChapters() {
        val ids = downloadIdsToRemoveAfterMarkedRead(
            settings = DownloadSettings(deleteAfterReading = true, removeAfterMarkedRead = true),
            queue = queue,
            chapterIds = setOf(10, 11),
            markedRead = true,
        )

        assertEquals(listOf("one"), ids)
    }

    @Test
    fun masterAndSecondarySettingsBothGateRemoval() {
        assertTrue(
            downloadIdsToRemoveAfterMarkedRead(
                DownloadSettings(deleteAfterReading = false, removeAfterMarkedRead = true),
                queue,
                setOf(10),
                markedRead = true,
            ).isEmpty(),
        )
        assertTrue(
            downloadIdsToRemoveAfterMarkedRead(
                DownloadSettings(deleteAfterReading = true, removeAfterMarkedRead = false),
                queue,
                setOf(10),
                markedRead = true,
            ).isEmpty(),
        )
    }

    @Test
    fun markingUnreadNeverRemovesDownloads() {
        assertTrue(
            downloadIdsToRemoveAfterMarkedRead(
                DownloadSettings(deleteAfterReading = true, removeAfterMarkedRead = true),
                queue,
                setOf(10),
                markedRead = false,
            ).isEmpty(),
        )
    }
}
