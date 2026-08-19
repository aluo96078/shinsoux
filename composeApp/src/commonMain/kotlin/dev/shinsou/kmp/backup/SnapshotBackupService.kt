package dev.shinsou.kmp.backup

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.data.withoutPortableSecrets
import dev.shinsou.kmp.domain.model.BackupStatus
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

object SnapshotBackupService {
    fun create(
        snapshot: AppSnapshot,
        createdAt: Long,
        appVersion: String = "",
        deviceId: String? = null,
    ): BackupEnvelope = BackupEnvelope(
        createdAt = createdAt,
        appVersion = appVersion,
        deviceId = deviceId,
        snapshot = snapshot.validate().withoutPortableSecrets(),
    )

    fun encode(envelope: BackupEnvelope): String {
        val validated = envelope.validate()
        return AppSnapshotJson.format.encodeToString(
            validated.copy(snapshot = validated.snapshot.withoutPortableSecrets()),
        )
    }

    fun decode(encoded: String): BackupEnvelope = AppSnapshotJson.format.decodeFromString<BackupEnvelope>(encoded).validate()

    /**
     * Applies selected backup domains with replace semantics while preserving unselected domains.
     * Dependent records that cannot refer to an existing selected parent are skipped and reported.
     */
    fun restore(
        current: AppSnapshot,
        envelope: BackupEnvelope,
        selection: RestoreSelection = RestoreSelection.All,
        restoredAt: Long = envelope.createdAt,
    ): RestoreResult {
        current.validate()
        val backup = envelope.validate().snapshot

        val mangas = if (selection.library) backup.mangas else current.mangas
        val mangaIds = mangas.mapTo(mutableSetOf()) { it.id }

        val rawCategories = if (selection.categories) backup.categories else current.categories
        val categories = if (rawCategories.any { it.id == 0L }) rawCategories else listOf(dev.shinsou.kmp.domain.model.Category.Default) + rawCategories
        val categoryIds = categories.mapTo(mutableSetOf()) { it.id }

        val sourceLinks = if (selection.categories) backup.mangaCategories else current.mangaCategories
        val mangaCategories = sourceLinks.filter { it.mangaId in mangaIds && it.categoryId in categoryIds }

        val sourceChapters = if (selection.chapters) backup.chapters else current.chapters
        val chapters = sourceChapters.filter { it.mangaId in mangaIds }
        val chapterIds = chapters.mapTo(mutableSetOf()) { it.id }

        val sourceHistories = if (selection.history) backup.histories else current.histories
        val histories = sourceHistories.filter { it.chapterId in chapterIds }

        val sourceTracks = if (selection.tracks) backup.tracks else current.tracks
        val tracks = sourceTracks.filter { it.mangaId in mangaIds }

        val selectedSettings = if (selection.settings) backup.settings else current.settings
        val settings = selectedSettings.copy(
            advanced = selectedSettings.advanced.copy(
                // Secure settings are local and are never imported from a portable backup.
                proxyApiKey = current.settings.advanced.proxyApiKey,
            ),
        )
        val repositories = if (selection.repositories) backup.extensionRepositories else current.extensionRepositories

        // Runtime-only queues remain local, but must never retain dangling references after restore.
        val updates = current.updates.filter { it.mangaId in mangaIds && it.chapterId in chapterIds }
        val downloads = current.downloadQueue.filter { it.mangaId in mangaIds && it.chapterId in chapterIds }

        val restored = current.copy(
            revision = current.revision + 1,
            settings = settings,
            backupState = current.backupState.copy(
                status = BackupStatus.COMPLETED,
                lastRestoreAt = restoredAt,
                errorMessage = null,
            ),
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = mangaCategories,
            histories = histories,
            updates = updates,
            downloadQueue = downloads,
            tracks = tracks,
            extensionRepositories = repositories,
        ).validate()

        val counts = RestoreCounts(
            mangas = if (selection.library) mangas.size else 0,
            categories = if (selection.categories) categories.size else 0,
            categoryLinks = if (selection.categories) mangaCategories.size else 0,
            chapters = if (selection.chapters) chapters.size else 0,
            histories = if (selection.history) histories.size else 0,
            tracks = if (selection.tracks) tracks.size else 0,
            repositories = if (selection.repositories) repositories.size else 0,
            skippedChapters = if (selection.chapters) sourceChapters.size - chapters.size else 0,
            skippedHistories = if (selection.history) sourceHistories.size - histories.size else 0,
            skippedTracks = if (selection.tracks) sourceTracks.size - tracks.size else 0,
            skippedCategoryLinks = if (selection.categories) sourceLinks.size - mangaCategories.size else 0,
        )
        return RestoreResult(restored, selection, counts)
    }
}

fun ShinsouRepository.createBackupEnvelope(
    createdAt: Long,
    appVersion: String = "",
    deviceId: String? = null,
): BackupEnvelope = SnapshotBackupService.create(currentSnapshot, createdAt, appVersion, deviceId)

suspend fun ShinsouRepository.restoreBackup(
    envelope: BackupEnvelope,
    selection: RestoreSelection = RestoreSelection.All,
    restoredAt: Long = envelope.createdAt,
): RestoreResult {
    val result = SnapshotBackupService.restore(currentSnapshot, envelope, selection, restoredAt)
    val persisted = replaceSnapshot(result.snapshot)
    return result.copy(snapshot = persisted)
}
