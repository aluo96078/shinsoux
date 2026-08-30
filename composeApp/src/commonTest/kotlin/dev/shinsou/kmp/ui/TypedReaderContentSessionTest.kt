package dev.shinsou.kmp.ui

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.UnifiedReaderContent
import dev.shinsou.kmp.reader.UnifiedReaderNavigationFactory
import dev.shinsou.kmp.rights.RightsScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypedReaderContentSessionTest {
    @Test
    fun keepsAVisualResumePageSeparateFromTheTextLocator() {
        val text = "第一段\n第二段"
        val scope = scope()
        val representation = ContentRepresentation.PlainText(
            representationId = "55555555-5555-4555-8555-555555555555",
            resource = ResourceRef(
                id = "body",
                blob = BlobRef(
                    blobId = "66666666-6666-4666-8666-666666666666",
                    schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                    digestAlgorithm = BlobRef.SHA_256,
                    plaintextDigest =
                        "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                    byteSize = 0,
                    mediaType = "text/plain",
                ),
            ),
            canonicalUtf16Length = text.length,
            blocks = listOf(TextBlock("paragraph", 0, text.length)),
        )
        val navigation = UnifiedReaderNavigationFactory.create(scope, representation, text)

        val session = TypedReaderContentSession(
            content = UnifiedReaderContent(navigation),
            canonicalText = text,
            access = ContentAccessRequest(
                grantReference = null,
                scope = RightsScope(
                    publicationId = scope.publicationId,
                    acquisitionId = scope.acquisitionId,
                    unitId = scope.unitId,
                    manifestId = "44444444-4444-4444-8444-444444444444",
                    contentRevision = scope.contentRevision,
                ),
            ),
            initialVisualPageIndex = 7,
            initialVisualPageCount = 10,
        )

        assertEquals(7, session.initialVisualPageIndex)
        assertEquals(10, session.initialVisualPageCount)
        assertEquals(0, navigation.indexOf(session.content.initialLocator))
        assertFailsWith<IllegalArgumentException> {
            TypedReaderContentSession(
                content = session.content,
                canonicalText = text,
                access = session.access,
                initialVisualPageIndex = -1,
            )
        }
    }

    private fun scope(): ReadingScope {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        return ReadingScope(
            schemaVersion = 1,
            publicationId = publication,
            acquisitionId = "22222222-2222-4222-8222-222222222222",
            unitId = UnitKey(publication, "33333333-3333-4333-8333-333333333333"),
            contentRevision = 1,
        )
    }
}
