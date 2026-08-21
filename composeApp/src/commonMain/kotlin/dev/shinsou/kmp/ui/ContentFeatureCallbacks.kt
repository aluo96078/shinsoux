package dev.shinsou.kmp.ui

import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.reader.UnifiedReaderContent

/** Trusted host result that pairs materialized reader bytes with their exact current grant scope. */
public class TypedReaderContentSession(
    public val content: UnifiedReaderContent,
    canonicalText: String? = null,
    public val access: ContentAccessRequest,
) {
    private val retainedCanonicalText: String? = canonicalText

    public val canonicalText: String?
        get() = retainedCanonicalText

    init {
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
        "TypedReaderContentSession(kind=${content.representation.kind}, canonicalText=<redacted>)"
}
