package dev.shinsou.kmp.backup

import dev.shinsou.kmp.data.AppSnapshot
import kotlinx.serialization.Serializable

const val SHINSOU_BACKUP_FORMAT: String = "dev.shinsou.kmp.snapshot"
const val SHINSOU_BACKUP_FORMAT_VERSION: Int = 1

/** Versioned JSON envelope for `.shinsoubackup` exports. */
@Serializable
data class BackupEnvelope(
    val format: String = SHINSOU_BACKUP_FORMAT,
    val formatVersion: Int = SHINSOU_BACKUP_FORMAT_VERSION,
    val createdAt: Long,
    val appVersion: String = "",
    val deviceId: String? = null,
    val snapshot: AppSnapshot,
) {
    fun validate(): BackupEnvelope {
        if (format != SHINSOU_BACKUP_FORMAT) {
            throw BackupFormatException("Not a Shinsou X backup: $format")
        }
        if (formatVersion !in 1..SHINSOU_BACKUP_FORMAT_VERSION) {
            throw BackupFormatException("Unsupported backup format version: $formatVersion")
        }
        if (createdAt < 0) throw BackupFormatException("Backup creation time cannot be negative")
        snapshot.validate()
        return this
    }
}

/** Controls which independent domains are restored from a complete backup envelope. */
data class RestoreSelection(
    val library: Boolean = true,
    val categories: Boolean = true,
    val chapters: Boolean = true,
    val history: Boolean = true,
    val tracks: Boolean = true,
    val settings: Boolean = true,
    val repositories: Boolean = true,
) {
    companion object {
        val All = RestoreSelection()
        val LibraryOnly = RestoreSelection(
            categories = false,
            chapters = false,
            history = false,
            tracks = false,
            settings = false,
            repositories = false,
        )
    }
}

data class RestoreCounts(
    val mangas: Int = 0,
    val categories: Int = 0,
    val categoryLinks: Int = 0,
    val chapters: Int = 0,
    val histories: Int = 0,
    val tracks: Int = 0,
    val repositories: Int = 0,
    val skippedChapters: Int = 0,
    val skippedHistories: Int = 0,
    val skippedTracks: Int = 0,
    val skippedCategoryLinks: Int = 0,
)

data class RestoreResult(
    val snapshot: AppSnapshot,
    val selection: RestoreSelection,
    val counts: RestoreCounts,
)

class BackupFormatException(message: String) : IllegalArgumentException(message)
