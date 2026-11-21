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

    /** The deterministic in-memory full-text index of the common contract tests. */
    public fun inMemoryFullTextIndex(operationGate: ContentOperationGate): LocalFullTextIndex =
        InMemoryDerivedLocalFullTextIndex(operationGate)
}
