package dev.shinsou.kmp.app

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.content.SqlDriverPortableAliasResolver
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.LegacyPublicationBundle
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class LegacyPublicationProjectionRestartTest {
    @Test
    fun persistentAliasesAndWholeSnapshotOrderingSurviveDriverRestart() {
        val database = Files.createTempFile("shinsou-legacy-projection", ".sqlite")
        try {
            val snapshot = fixture()
            val first = project(database.toString(), snapshot)
            val reopened = project(database.toString(), snapshot)

            assertEquals(first, reopened, "A reopened alias ledger must retain every portable identity")
            val compatibilityBase = snapshot.copy(
                mangas = emptyList(),
                chapters = emptyList(),
                categories = emptyList(),
                mangaCategories = emptyList(),
            )
            JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}").let { driver ->
                val projection = LegacyPublicationProjection(SqlDriverPortableAliasResolver(driver))
                assertEquals(
                    snapshot,
                    projection.materialize(reopened.reversed(), compatibilityBase),
                )
                driver.close()
            }
        } finally {
            Files.deleteIfExists(database)
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
        }
    }

    private fun project(path: String, snapshot: AppSnapshot): List<LegacyPublicationBundle> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:$path")
        return try {
            LegacyPublicationProjection(SqlDriverPortableAliasResolver(driver)).project(snapshot)
        } finally {
            driver.close()
        }
    }

    private fun fixture(): AppSnapshot = AppSnapshot(
        revision = 42,
        mangas = listOf(
            Manga(id = 91, source = 9001, url = "/p/91", title = "Ninety one", notes = "keep"),
            Manga(id = 17, source = 7001, url = "/p/17", title = "Seventeen", favorite = true),
        ),
        chapters = listOf(
            Chapter(id = 9101, mangaId = 91, url = "/u/9101", name = "91 A", read = true),
            Chapter(id = 1701, mangaId = 17, url = "/u/1701", name = "17 A", bookmark = true),
            Chapter(id = 9102, mangaId = 91, url = "/u/9102", name = "91 B", lastPageRead = 8),
        ),
        categories = listOf(
            Category(id = 7, name = "Later", sort = 5, flags = 6),
            Category.Default,
        ),
        mangaCategories = listOf(
            MangaCategory(mangaId = 17, categoryId = 0),
            MangaCategory(mangaId = 91, categoryId = 7),
        ),
    ).validate()
}
