package dev.shinsou.kmp.concurrent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.annotation.SqlDriverContentAnnotationStore
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentOutboxAdapter
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.SqlDriverPortableAliasResolver
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.domain.model.LegacyAliasKey
import dev.shinsou.kmp.domain.model.MigrationNamespaceId
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.RightsDecision
import dev.shinsou.kmp.search.DerivedLocalFullTextIndex
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Regression coverage for synchronous APIs sharing foreground/background store state. */
class SynchronousStoreContentionTest {
    @Test
    fun blobStoreWaitsForAnActivePublisherInsteadOfThrowingRetry() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val store = InMemoryContentBlobStore(
            clock = {
                entered.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out releasing blob publish" }
                100L
            },
            configuredStoreInstanceId = "contention-store",
        )
        val stage = store.beginStage(expectedSizeBytes = 4, mediaType = "text/plain")
        stage.append("body".encodeToByteArray())
        val pending = stage.seal()

        val (_, count) = runContendedCalls(
            entered = entered,
            release = release,
            first = { store.publish(pending) },
            second = { store.count },
        )

        assertEquals(1, count)
        assertEquals(1, store.pendingReceiptCount)
    }

    @Test
    fun inMemoryContentTransactionReadWaitsForCommitInsteadOfThrowingRetry() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val transactions = InMemorySharedContentTransactionStore(
            blobStore = InMemoryContentBlobStore(),
            outboxAdapter = StringOutboxAdapter,
            syncModeProvider = {
                entered.countDown()
                check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out releasing content commit" }
                ContentSyncMode.INACTIVE
            },
        )

        val (_, state) = runContendedCalls(
            entered = entered,
            release = release,
            first = {
                transactions.commit(
                    ContentCommitBatch<String>(
                        commitId = "contention-commit",
                        metadata = listOf(ContentMetadataMutation("contention", "committed")),
                    ),
                )
            },
            second = { transactions.state },
        )

        assertEquals("committed", state.metadata["contention"])
    }

    @Test
    fun annotationStoreWaitsForAnActiveSqlReadInsteadOfThrowingRetry() = withBlockingDriver {
        _, blocking ->
        val store = SqlDriverContentAnnotationStore(blocking)
        blocking.arm { sql -> "content_annotations" in sql.lowercase() }

        val (found, rows) = runContendedCalls(
            entered = blocking.entered,
            release = blocking.release,
            first = { store.find("11111111-1111-4111-8111-111111111111") },
            second = { store.list(includeTombstones = true) },
        )

        assertNull(found)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun aliasResolverWaitsForAnActiveSqlReadInsteadOfThrowingRetry() = withBlockingDriver {
        _, blocking ->
        val resolver = SqlDriverPortableAliasResolver(blocking)
        blocking.arm { sql -> "content_transaction_aliases" in sql.lowercase() }
        val namespace = MigrationNamespaceId.LEGACY_MANGA_V1
        val alias = LegacyAliasKey.Manga(id = 7, source = 9)

        val (first, second) = runContendedCalls(
            entered = blocking.entered,
            release = blocking.release,
            first = { resolver.resolve(namespace, alias) },
            second = { resolver.resolve(namespace, alias) },
        )

        assertNull(first)
        assertNull(second)
    }

    @Test
    fun searchStoreWaitsForAnActiveSqlReadInsteadOfThrowingRetry() = withBlockingDriver {
        _, blocking ->
        val index = DerivedLocalFullTextIndex(blocking, AllowAllOperationGate)
        blocking.arm { sql -> "content_search_documents" in sql.lowercase() }

        val (first, second) = runContendedCalls(
            entered = blocking.entered,
            release = blocking.release,
            first = { index.documentCount },
            second = { index.documentCount },
        )

        assertEquals(0, first)
        assertEquals(0, second)
    }

    private fun <T> withBlockingDriver(block: (JdbcSqliteDriver, BlockingSqlDriver) -> T): T {
        val database = Files.createTempFile("shinsou-lock-contention", ".sqlite")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${database.toAbsolutePath()}")
        return try {
            block(driver, BlockingSqlDriver(driver))
        } finally {
            runCatching { driver.close() }
            Files.deleteIfExists(database)
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
        }
    }

    private fun <A, B> runContendedCalls(
        entered: CountDownLatch,
        release: CountDownLatch,
        first: () -> A,
        second: () -> B,
    ): Pair<A, B> {
        val executor = Executors.newFixedThreadPool(2)
        val secondStarted = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        return try {
            val firstFuture = executor.submit<A> { first() }
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "First operation did not take the lock")
            val secondFuture = executor.submit<B> {
                secondStarted.countDown()
                try {
                    second()
                } finally {
                    secondFinished.countDown()
                }
            }
            assertTrue(secondStarted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), "Second operation did not start")
            assertFalse(
                secondFinished.await(CONTENTION_OBSERVATION_MILLIS, TimeUnit.MILLISECONDS),
                "Normal lock contention must wait instead of returning or throwing a retry error",
            )
            release.countDown()
            firstFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) to
                secondFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    private class BlockingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        @Volatile
        private var predicate: ((String) -> Boolean)? = null

        fun arm(predicate: (String) -> Boolean) {
            check(this.predicate == null) { "Blocking SQL driver can be armed only once" }
            this.predicate = predicate
        }

        override fun execute(
            identifier: Int?,
            sql: String,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<Long> {
            blockOnce(sql)
            return delegate.execute(identifier, sql, parameters, binders)
        }

        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            blockOnce(sql)
            return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        }

        private fun blockOnce(sql: String) {
            val current = predicate ?: return
            if (!current(sql)) return
            predicate = null
            entered.countDown()
            check(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "Timed out releasing SQL operation" }
        }
    }

    private object StringOutboxAdapter : ContentOutboxAdapter<String> {
        override fun validate(draft: String) = Unit
        override fun id(draft: String): String = draft
        override fun fingerprint(draft: String): ByteArray = draft.encodeToByteArray()
        override fun isRepresentableByCurrentV1(draft: String): Boolean = true
    }

    private object AllowAllOperationGate : ContentOperationGate {
        override fun decide(request: ContentAccessRequest, operation: ContentOperation): RightsDecision =
            RightsDecision.ALLOW

        override fun requireAllowed(request: ContentAccessRequest, operation: ContentOperation) = Unit

        override fun <T> execute(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: () -> T,
        ): T = block()

        override suspend fun <T> executeSuspending(
            request: ContentAccessRequest,
            operation: ContentOperation,
            block: suspend () -> T,
        ): T = block()
    }

    private companion object {
        const val TIMEOUT_SECONDS = 5L
        const val CONTENTION_OBSERVATION_MILLIS = 150L
    }
}
