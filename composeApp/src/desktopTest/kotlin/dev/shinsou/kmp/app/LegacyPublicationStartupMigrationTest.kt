package dev.shinsou.kmp.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitSemantics
import dev.shinsou.kmp.content.ContentMigrationLookupStatus
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.ContentTransactionFailurePoint
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.LegacyAliasKey
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.local.LocalContentManager
import dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.ui.ImportedDocument
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LegacyPublicationStartupMigrationTest {
    @Test
    fun projectionAliasAndLedgerRollbackTogetherThenSurviveRestartIdempotently() {
        withDatabase { path ->
            val driver = driver(path)
            val foundation = foundation(driver)
            val snapshot = legacySnapshot()
            foundation.injectTransactionFailureForTesting(
                ContentTransactionFailurePoint.AFTER_PUBLICATION_WRITE,
            )

            assertFails { LegacyPublicationStartupMigration(foundation).migrate(snapshot) }
            assertTrue(foundation.publications.all().isEmpty())
            assertNull(
                foundation.aliases.resolve(
                    MigrationNamespaceId.LEGACY_MANGA_V1,
                    LegacyAliasKey.Manga(10, 77),
                ),
            )

            foundation.injectTransactionFailureForTesting(null)
            val migrated = LegacyPublicationStartupMigration(foundation).migrate(snapshot)
            assertEquals(LegacyPublicationMigrationStatus.MIGRATED, migrated.status)
            assertEquals(1, migrated.publicationCount)
            assertEquals(5, migrated.aliasCount)
            assertNotNull(
                foundation.aliases.resolve(
                    MigrationNamespaceId.LEGACY_MANGA_V1,
                    LegacyAliasKey.Manga(10, 77),
                ),
            )
            val publication = foundation.publications.all().single()
            assertEquals(listOf("One", "Two"), publication.units.map { it.title })

            // Backup v2 replaces the portable graph but deliberately retains the device-local
            // migration ledger. The exact source/result replay must repair the removed rows.
            foundation.transactions.commit(
                ContentCommitBatch<SyncDraft>(
                    commitId = "test-empty-portable-restore",
                    semantics = ContentCommitSemantics.REPLACE_PORTABLE_GRAPH,
                ),
            )
            assertTrue(foundation.publications.all().isEmpty())
            val repaired = LegacyPublicationStartupMigration(foundation).migrate(
                snapshot.copy(revision = 99),
            )
            assertEquals(
                LegacyPublicationMigrationStatus.REPAIRED_AFTER_PORTABLE_REPLACEMENT,
                repaired.status,
            )
            assertEquals(1, foundation.publications.all().size)
            driver.close()

            val reopenedDriver = driver(path)
            val reopened = foundation(reopenedDriver)
            val replay = LegacyPublicationStartupMigration(reopened).migrate(snapshot)
            assertEquals(LegacyPublicationMigrationStatus.UP_TO_DATE, replay.status)
            assertEquals(1, reopened.publications.all().size)
            val lookup = reopened.transactions.lookupMigrationLedger(
                namespace = "legacy-app-snapshot-v1",
                sourceDigestSha256 = assertNotNull(migrated.sourceDigestSha256),
                resultFingerprintSha256 = assertNotNull(migrated.resultFingerprintSha256),
            )
            assertEquals(ContentMigrationLookupStatus.REPLAY, lookup.status)
            reopenedDriver.close()
        }
    }

    @Test
    fun laterLegacyChapterIsAnAppendOnlyVersionedExtension() {
        withDatabase { path ->
            val driver = driver(path)
            val foundation = foundation(driver)
            val firstSnapshot = legacySnapshot().copy(
                chapters = legacySnapshot().chapters.take(1),
            ).validate()
            val first = LegacyPublicationStartupMigration(foundation).migrate(firstSnapshot)
            assertEquals(LegacyPublicationMigrationStatus.MIGRATED, first.status)
            assertEquals(0, foundation.publications.all().single().acquisitions.single().contentRevision)

            val second = LegacyPublicationStartupMigration(foundation).migrate(
                legacySnapshot().copy(revision = 2),
            )
            assertEquals(LegacyPublicationMigrationStatus.MIGRATED, second.status)
            val publication = foundation.publications.all().single()
            assertEquals(listOf("One", "Two"), publication.units.map { it.title })
            assertEquals(1, publication.acquisitions.single().contentRevision)
            assertEquals(
                LegacyPublicationMigrationStatus.UP_TO_DATE,
                LegacyPublicationStartupMigration(foundation)
                    .migrate(legacySnapshot().copy(revision = 2)).status,
            )
            driver.close()
        }
    }

    @Test
    fun typedLocalCompatibilityRowsRepairAfterEitherCrashBoundaryWithoutDuplication() = runTest {
        withDatabase { path ->
            val driver = driver(path)
            val foundation = foundation(driver)
            val importRepository = ShinsouRepository()
            val importing = LocalContentManager(
                repository = importRepository,
                fileSystem = EmptyFileSystem,
                contentFoundation = foundation,
                now = { 12_345L },
            )
            importing.importLocalDocuments(
                listOf(ImportedDocument("novel.txt", "body".encodeToByteArray())),
            )
            assertEquals(1, foundation.publications.all().size)

            // Simulate death after the shared transaction but before either compatibility row.
            val repairedRepository = ShinsouRepository()
            val repairing = LocalContentManager(
                repository = repairedRepository,
                fileSystem = EmptyFileSystem,
                contentFoundation = foundation,
                now = { 99_999L },
            )
            assertEquals(
                dev.shinsou.kmp.local.LocalTypedProjectionRepairResult(1, 1),
                repairing.repairTypedContentLegacyProjection(),
            )
            val repairedManga = repairedRepository.currentSnapshot.mangas.single()
            val repairedChapter = repairedRepository.currentSnapshot.chapters.single()
            assertEquals(12_345L, repairedManga.dateAdded)
            assertEquals(12_345L, repairedChapter.dateFetch)

            // Existing user state is not overwritten by an idempotent retry.
            repairedRepository.upsertManga(repairedManga.copy(favorite = false, notes = "keep"))
            assertEquals(
                dev.shinsou.kmp.local.LocalTypedProjectionRepairResult(0, 0),
                repairing.repairTypedContentLegacyProjection(),
            )
            assertEquals("keep", repairedRepository.currentSnapshot.mangas.single().notes)

            // Simulate the narrower death after Manga but before Chapter persistence.
            repairedRepository.deleteChapter(repairedChapter.id)
            assertEquals(
                LegacyPublicationMigrationStatus.UP_TO_DATE,
                LegacyPublicationStartupMigration(foundation)
                    .migrate(repairedRepository.currentSnapshot).status,
                "A partial typed compatibility Manga must not create a duplicate Publication",
            )
            assertEquals(
                dev.shinsou.kmp.local.LocalTypedProjectionRepairResult(0, 1),
                repairing.repairTypedContentLegacyProjection(),
            )
            assertEquals(1, repairedRepository.currentSnapshot.mangas.size)
            assertEquals(1, repairedRepository.currentSnapshot.chapters.size)

            // The compatibility row must not be projected back into a duplicate typed graph.
            assertEquals(
                LegacyPublicationMigrationStatus.UP_TO_DATE,
                LegacyPublicationStartupMigration(foundation)
                    .migrate(repairedRepository.currentSnapshot).status,
            )
            assertEquals(1, foundation.publications.all().size)
            driver.close()
        }
    }

    @Test
    fun reversibleExtensionLibraryRowsAreNotMigratedAsLegacyLocalPublications() {
        withDatabase { path ->
            val driver = driver(path)
            val foundation = foundation(driver)
            val sourceKey = dev.shinsou.kmp.domain.model.SourceKey(
                packageId = "zh.bilimanga",
                sourceId = "zh.bilimanga.novel",
            )
            val snapshot = AppSnapshot(
                mangas = listOf(
                    Manga(
                        id = 10,
                        source = 0,
                        favorite = true,
                        url = encodeExtensionLibraryPublicationUrl(
                            sourceKey,
                            "https://tw.linovelib.com/novel/42.html",
                        ),
                        title = "Extension favorite",
                    ),
                ),
            ).validate()

            assertEquals(
                LegacyPublicationMigrationStatus.UP_TO_DATE,
                LegacyPublicationStartupMigration(foundation).migrate(snapshot).status,
            )
            assertTrue(foundation.publications.all().isEmpty())
            driver.close()
        }
    }

    private fun legacySnapshot(): AppSnapshot = AppSnapshot(
        mangas = listOf(
            Manga(
                id = 10,
                source = 77,
                favorite = true,
                url = "/publication/10",
                title = "Legacy",
                notes = "preserve",
            ),
        ),
        chapters = listOf(
            Chapter(id = 101, mangaId = 10, url = "/one", name = "One", read = true),
            Chapter(id = 102, mangaId = 10, url = "/two", name = "Two", bookmark = true),
        ),
    ).validate()

    private fun driver(path: java.nio.file.Path): JdbcSqliteDriver =
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")

    private fun foundation(driver: JdbcSqliteDriver): ContentFoundationRuntime =
        ContentFoundationRuntime(driver, syncModeProvider = { ContentSyncMode.V2_ACTIVE })

    private inline fun withDatabase(block: (java.nio.file.Path) -> Unit) {
        val path = Files.createTempFile("shinsou-legacy-startup", ".sqlite")
        try {
            block(path)
        } finally {
            Files.deleteIfExists(path)
        }
    }
}

private object EmptyFileSystem : AppFileSystem {
    override suspend fun write(relativePath: String, bytes: ByteArray) = Unit
    override suspend fun read(relativePath: String): ByteArray? = null
    override suspend fun exists(relativePath: String): Boolean = false
    override suspend fun delete(relativePath: String): Boolean = false
    override suspend fun deleteTree(relativeDirectory: String): Boolean = false
    override suspend fun list(relativeDirectory: String): List<String> = emptyList()
    override fun uri(relativePath: String): String = "memory://$relativePath"
}
