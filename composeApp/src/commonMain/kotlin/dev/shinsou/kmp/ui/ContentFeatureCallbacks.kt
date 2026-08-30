package dev.shinsou.kmp.ui

import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.reader.UnifiedReaderContent

/** Trusted host result that pairs materialized reader bytes with their exact current grant scope. */
public class TypedReaderContentSession(
    public val content: UnifiedReaderContent,
    canonicalText: String? = null,
    public val access: ContentAccessRequest,
    /**
     * Rendition-specific page restored from local history. A text locator remains the durable,
     * reflow-safe position; the reader consumes this hint only for its first visual pagination.
     */
    public val initialVisualPageIndex: Int? = null,
    /** Page count paired with the restored visual page under the saved rendition/document. */
    public val initialVisualPageCount: Int? = null,
) {
    private val retainedCanonicalText: String? = canonicalText

    public val canonicalText: String?
        get() = retainedCanonicalText

    init {
        require(initialVisualPageIndex == null || initialVisualPageIndex >= 0) {
            "Initial reader page index must be non-negative"
        }
        require(initialVisualPageCount == null || initialVisualPageCount > 0) {
            "Initial reader page count must be positive"
        }
        require(initialVisualPageCount == null || initialVisualPageIndex == null ||
            initialVisualPageIndex < initialVisualPageCount
        ) { "Initial reader page must be inside its document" }
        val reading = content.navigation.scope
        val rights = access.scope
        require(rights.publicationId == reading.publicationId &&
            rights.acquisitionId == reading.acquisitionId &&
            rights.unitId == reading.unitId &&
            rights.contentRevision == reading.contentRevision
        ) { "Reader content and rights scope do not match" }
        when (val representation = content.representation) {
            is ContentRepresentation.PlainText -> requireNotNull(canonicalText) {
                "A plain-text reader session needs canonical text"
            }.also { text ->
                require(text.length == representation.canonicalUtf16Length) {
                    "Reader canonical text length does not match its representation"
                }
            }
            is ContentRepresentation.ImageSequence,
            is ContentRepresentation.EpubSpine,
            -> require(canonicalText == null) {
                "Only plain-text sessions may retain canonical text"
            }
        }
    }

    override fun toString(): String =
        "TypedReaderContentSession(kind=${content.representation.kind}, " +
            "initialVisualPageIndex=$initialVisualPageIndex, " +
            "initialVisualPageCount=$initialVisualPageCount, canonicalText=<redacted>)"
}
