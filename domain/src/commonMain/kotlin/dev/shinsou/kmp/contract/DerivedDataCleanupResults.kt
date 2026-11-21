package dev.shinsou.kmp.contract

/** What the foreground cleanup of revoked derived data removed for one reading scope. */
public data class RevokedContentCleanupResult(
    val searchDocumentsPurged: Int,
    val annotationsRedacted: Int,
    val speechStopped: Boolean,
)

/** What a background cleanup of derived data (search rows, annotations, speech) removed. */
public data class DerivedRightsCleanupResult(
    val targetsExamined: Int,
    val searchDocumentsExamined: Int,
    val searchDocumentsPurged: Int,
    val annotationsExamined: Int,
    val annotationsRedacted: Int,
    val concurrentAnnotationChangesDeferred: Int,
    val speechStopped: Boolean,
) {
    public operator fun plus(other: DerivedRightsCleanupResult): DerivedRightsCleanupResult =
        DerivedRightsCleanupResult(
            targetsExamined = targetsExamined + other.targetsExamined,
            searchDocumentsExamined = searchDocumentsExamined + other.searchDocumentsExamined,
            searchDocumentsPurged = searchDocumentsPurged + other.searchDocumentsPurged,
            annotationsExamined = annotationsExamined + other.annotationsExamined,
            annotationsRedacted = annotationsRedacted + other.annotationsRedacted,
            concurrentAnnotationChangesDeferred = concurrentAnnotationChangesDeferred +
                other.concurrentAnnotationChangesDeferred,
            speechStopped = speechStopped || other.speechStopped,
        )

    public companion object {
        public val Empty: DerivedRightsCleanupResult = DerivedRightsCleanupResult(0, 0, 0, 0, 0, 0, false)
    }
}
