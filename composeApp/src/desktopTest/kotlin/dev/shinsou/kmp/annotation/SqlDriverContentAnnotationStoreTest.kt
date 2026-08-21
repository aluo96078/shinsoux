package dev.shinsou.kmp.annotation

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SqlDriverContentAnnotationStoreTest {
    @Test
    fun annotationSurvivesReopenAndUsesUpdatedAtCompareAndSet() {
        val database = Files.createTempFile("shinsou-annotation", ".sqlite")
        try {
            val created = annotation()
            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                SqlDriverContentAnnotationStore(driver).put(created)
                driver.close()
            }

            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                val store = SqlDriverContentAnnotationStore(driver)
                assertEquals(created, store.find(created.annotationId))
                val updated = created.copy(body = "updated", updatedAtEpochMillis = 2)
                assertFailsWith<AnnotationConflictException> {
                    store.put(updated, expectedUpdatedAtEpochMillis = 0)
                }
                store.put(updated, expectedUpdatedAtEpochMillis = 1)
                assertEquals(listOf(updated), store.list(created.scope))
                driver.close()
            }

            JdbcSqliteDriver("jdbc:sqlite:$database").let { driver ->
                val reopened = SqlDriverContentAnnotationStore(driver)
                assertEquals("updated", reopened.find(created.annotationId)?.body)
                driver.close()
            }
        } finally {
            Files.deleteIfExists(database)
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
        }
    }

    @Test
    fun scopedReadDoesNotDecodeAnUnrelatedMalformedRow() {
        val database = Files.createTempFile("shinsou-annotation-scope", ".sqlite")
        val driver = JdbcSqliteDriver("jdbc:sqlite:$database")
        try {
            val expected = annotation()
            val store = SqlDriverContentAnnotationStore(driver)
            store.put(expected)
            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO content_annotations(
                      annotation_id, publication_id, acquisition_id, unit_id,
                      content_revision, state, created_at, updated_at, annotation_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                parameters = 9,
            ) {
                bindString(0, "55555555-5555-4555-8555-555555555555")
                bindString(1, "66666666-6666-4666-8666-666666666666")
                bindString(2, "77777777-7777-4777-8777-777777777777")
                bindString(3, "88888888-8888-4888-8888-888888888888")
                bindLong(4, 0)
                bindString(5, ContentAnnotationState.ACTIVE.name)
                bindLong(6, 1)
                bindLong(7, 1)
                bindString(8, "not-json")
            }.value

            assertEquals(listOf(expected), store.list(expected.scope))
            assertFailsWith<Exception> { store.list(includeTombstones = true) }
        } finally {
            driver.close()
            Files.deleteIfExists(database)
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
        }
    }

    private fun annotation(): ContentAnnotation {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        val scope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = "22222222-2222-4222-8222-222222222222",
            unitId = UnitKey(publication, "33333333-3333-4333-8333-333333333333"),
            contentRevision = 0,
        )
        val quote = TextQuote(exact = "selected", prefix = "alpha ", suffix = " omega")
        return ContentAnnotation(
            schemaVersion = ContentAnnotation.CURRENT_SCHEMA_VERSION,
            annotationId = "44444444-4444-4444-8444-444444444444",
            kind = ContentAnnotationKind.NOTE,
            range = ReadingRange(
                start = ReadingLocator.Text(
                    schemaVersion = scope.schemaVersion,
                    scope = scope,
                    resourceId = "body",
                    blockId = "paragraph-1",
                    offset = 6,
                    quote = quote,
                ),
                end = ReadingLocator.Text(
                    schemaVersion = scope.schemaVersion,
                    scope = scope,
                    resourceId = "body",
                    blockId = "paragraph-1",
                    offset = 14,
                ),
                quote = quote,
            ),
            body = "note",
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1,
        )
    }
}
