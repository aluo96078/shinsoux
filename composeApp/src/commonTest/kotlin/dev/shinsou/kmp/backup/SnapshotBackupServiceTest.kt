package dev.shinsou.kmp.backup

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.AdvancedSettings
import dev.shinsou.kmp.domain.model.AppearanceSettings
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SnapshotBackupServiceTest {
    @Test
    fun portableBackupRedactsProxySecretAndRestorePreservesLocalSecret() {
        val source = AppSnapshot(
            settings = AppSettings(advanced = AdvancedSettings(proxyApiKey = "source-secret")),
        )
        val envelope = SnapshotBackupService.create(source, createdAt = 10)
        val encoded = SnapshotBackupService.encode(envelope)
        val current = AppSnapshot(
            settings = AppSettings(advanced = AdvancedSettings(proxyApiKey = "local-secret")),
        )

        val restored = SnapshotBackupService.restore(current, SnapshotBackupService.decode(encoded))

        assertTrue("source-secret" !in encoded)
        assertEquals("", envelope.snapshot.settings.advanced.proxyApiKey)
        assertEquals("local-secret", restored.snapshot.settings.advanced.proxyApiKey)
    }

    @Test
    fun versionedEnvelopeRoundTripsCompleteSnapshot() {
        val snapshot = AppSnapshot(
            revision = 7,
            mangas = listOf(Manga(id = 1, source = 2, favorite = true, url = "/m", title = "M")),
            mangaCategories = listOf(MangaCategory(1, Category.Default.id)),
        )
        val envelope = SnapshotBackupService.create(snapshot, createdAt = 1_234, appVersion = "1.2.3", deviceId = "mac")

        val encoded = SnapshotBackupService.encode(envelope)
        val decoded = SnapshotBackupService.decode(encoded)

        assertEquals(SHINSOU_BACKUP_FORMAT_VERSION, decoded.formatVersion)
        assertEquals("1.2.3", decoded.appVersion)
        assertEquals("mac", decoded.deviceId)
        assertEquals(snapshot, decoded.snapshot)
        assertFailsWith<BackupFormatException> {
            SnapshotBackupService.encode(envelope.copy(formatVersion = SHINSOU_BACKUP_FORMAT_VERSION + 1))
        }
    }

    @Test
    fun selectiveRestoreChangesOnlyRequestedDomains() {
        val current = AppSnapshot(
            revision = 3,
            settings = AppSettings(appearance = AppearanceSettings(theme = ThemeMode.LIGHT)),
            mangas = listOf(Manga(id = 1, source = 1, favorite = true, url = "/local", title = "Local")),
            mangaCategories = listOf(MangaCategory(1, Category.Default.id)),
            extensionRepositories = listOf(ExtensionRepo("https://local", "Local repo")),
        )
        val backup = AppSnapshot(
            revision = 9,
            settings = AppSettings(appearance = AppearanceSettings(theme = ThemeMode.DARK, amoledDark = true)),
            mangas = listOf(Manga(id = 2, source = 2, favorite = true, url = "/backup", title = "Backup")),
            mangaCategories = listOf(MangaCategory(2, Category.Default.id)),
            extensionRepositories = listOf(ExtensionRepo("https://backup", "Backup repo")),
        )
        val envelope = SnapshotBackupService.create(backup, createdAt = 2_000)
        val selection = RestoreSelection(
            library = false,
            categories = false,
            chapters = false,
            history = false,
            tracks = false,
            settings = true,
            repositories = true,
        )

        val result = SnapshotBackupService.restore(current, envelope, selection, restoredAt = 3_000)

        assertEquals("Local", result.snapshot.mangas.single().title)
        assertEquals(ThemeMode.DARK, result.snapshot.settings.appearance.theme)
        assertTrue(result.snapshot.settings.appearance.amoledDark)
        assertEquals("https://backup", result.snapshot.extensionRepositories.single().baseUrl)
        assertEquals(3_000, result.snapshot.backupState.lastRestoreAt)
        assertEquals(1, result.counts.repositories)
        assertEquals(0, result.counts.mangas)
    }

    @Test
    fun restoreSkipsChildrenWhoseUnselectedParentsAreMissing() {
        val current = AppSnapshot(
            mangas = listOf(Manga(id = 1, source = 1, url = "/local", title = "Local")),
        )
        val backup = AppSnapshot(
            mangas = listOf(Manga(id = 2, source = 2, url = "/backup", title = "Backup")),
            chapters = listOf(Chapter(id = 20, mangaId = 2, url = "/chapter", name = "Chapter")),
        )
        val envelope = SnapshotBackupService.create(backup, createdAt = 1)
        val selection = RestoreSelection(
            library = false,
            categories = false,
            chapters = true,
            history = false,
            tracks = false,
            settings = false,
            repositories = false,
        )

        val result = SnapshotBackupService.restore(current, envelope, selection)

        assertTrue(result.snapshot.chapters.isEmpty())
        assertEquals(1, result.counts.skippedChapters)
        result.snapshot.validate()
    }

    @Test
    fun repositoryRestorePersistsSelectedBackup() = runTest {
        var persisted = ""
        val repository = ShinsouRepository(persist = { persisted = it })
        repository.upsertManga(Manga(source = 1, url = "/old", title = "Old"))
        val backup = SnapshotBackupService.create(
            AppSnapshot(mangas = listOf(Manga(id = 8, source = 8, url = "/new", title = "New"))),
            createdAt = 10,
        )

        val result = repository.restoreBackup(backup, RestoreSelection.LibraryOnly, restoredAt = 11)
        repository.flushPersistence()

        assertEquals("New", repository.snapshot.value.mangas.single().title)
        assertEquals(repository.snapshot.value, result.snapshot)
        assertEquals(repository.snapshot.value, ShinsouRepository.decodeSnapshot(persisted))
        repository.closePersistence()
    }
}
