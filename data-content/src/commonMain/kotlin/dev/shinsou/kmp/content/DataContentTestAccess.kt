package dev.shinsou.kmp.content

import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.search.InMemoryDerivedLocalFullTextIndex
import dev.shinsou.kmp.search.LocalFullTextIndex
import dev.shinsou.kmp.search.MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH as MAX_DOCUMENT_LENGTH
import dev.shinsou.kmp.testing.TestOnlyApi

/**
 * The tests of higher modules reach these internal declarations of :data-content only through
 * here; see [TestOnlyApi]. Tests that need nothing above :data-content live in its own test source
 * sets instead.
 */
@TestOnlyApi
public object DataContentTestAccess {
    /** The longest text one full-text document may hold, in UTF-16 units. */
    public const val MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH: Int = MAX_DOCUMENT_LENGTH

    /** Makes the next content transactions fail at [point]; null clears it. */
    public fun injectTransactionFailure(runtime: ContentFoundationRuntime, point: ContentTransactionFailurePoint?) {
        runtime.injectTransactionFailureForTesting(point)
    }

    /** How many blob stages [store] has begun. */
    public fun beginStageCount(store: SqlDriverContentBlobStore): Int = store.beginStageCountForTesting

    /** The in-memory transaction store that models the shared-transaction semantics (0007 A2). */
    public fun <D : Any> inMemoryTransactionStore(
        blobStore: InMemoryContentBlobStore,
        outboxAdapter: ContentOutboxAdapter<D>,
        syncModeProvider: () -> ContentSyncMode = { ContentSyncMode.V1_ACTIVE },
    ): InMemoryTransactions<D> = InMemoryTransactions(InMemorySharedContentTransactionStore(blobStore, outboxAdapter, syncModeProvider))

    /** [inMemoryTransactionStore]: the store, with the state and failure seam its tests inspect. */
    public class InMemoryTransactions<D : Any> internal constructor(
        private val store: InMemorySharedContentTransactionStore<D>,
    ) : SharedContentTransactionStore<D> by store {
        public val blobStore: InMemoryContentBlobStore get() = store.blobStore
        public val state: InMemoryContentTransactionState<D> get() = store.state
        public var failureInjection: ContentTransactionFailurePoint?
            get() = store.failureInjection
            set(value) {
                store.failureInjection = value
            }
    }

    /** The deterministic in-memory full-text index of the common contract tests. */
    public fun inMemoryFullTextIndex(operationGate: ContentOperationGate): LocalFullTextIndex =
        InMemoryDerivedLocalFullTextIndex(operationGate)
}
