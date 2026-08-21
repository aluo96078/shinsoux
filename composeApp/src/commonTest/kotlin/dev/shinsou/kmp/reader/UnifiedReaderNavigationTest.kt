package dev.shinsou.kmp.reader

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ImagePage
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class UnifiedReaderNavigationTest {
    @Test
    fun allRepresentationsUseStableIdentityInsteadOfMaterializedIndexHints() {
        val image = ImageSequenceNavigation(scope(), imageRepresentation())
        val text = PlainTextNavigation(scope(), textRepresentation(), "a😀\n\nb")
        val epub = EpubSpineNavigation(scope(), epubRepresentation())
        val navigations: List<UnifiedReaderNavigation> = listOf(image, text, epub)

        navigations.forEach { navigation ->
            assertEquals(2, navigation.itemCount)
            val first = navigation.locatorAt(0)
            val second = navigation.next(first)
            assertEquals(1, navigation.indexOf(requireNotNull(second)))
            assertEquals(first, navigation.previous(second))
            assertEquals(navigation.representationId, UnifiedReaderContent(navigation).representation.representationId)
        }

        val staleImageHint = image.locator(0, 0.5).copy(pageIndexHint = 99)
        assertEquals(0, image.indexOf(staleImageHint))
        val staleEpubHint = epub.locatorAt(0).copy(spineIndexHint = 99)
        assertEquals(0, epub.indexOf(staleEpubHint))
    }

    @Test
    fun textOffsetsAreAbsoluteUtf16AndNeverSplitASupplementaryCharacter() {
        val navigation = PlainTextNavigation(scope(), textRepresentation(), "a😀\n\nb")
        val emoji = navigation.locatorForOffset(1, quote = TextQuote(exact = "😀"))

        assertEquals(1, emoji.offset)
        assertEquals("first", emoji.blockId)
        assertEquals(1, emoji.resolveOffset("a😀\n\nb"))
        assertFailsWith<IllegalArgumentException> { navigation.locatorForOffset(2) }
        assertEquals("second", navigation.locatorAt(1).blockId)
    }

    @Test
    fun factoryReturnsTheTypedAdaptersAndRejectsForeignLocatorScopes() {
        assertIs<ImageSequenceNavigation>(
            UnifiedReaderNavigationFactory.create(scope(), imageRepresentation()),
        )
        assertIs<PlainTextNavigation>(
            UnifiedReaderNavigationFactory.create(scope(), textRepresentation(), "a😀\n\nb"),
        )
        val epub = assertIs<EpubSpineNavigation>(
            UnifiedReaderNavigationFactory.create(scope(), epubRepresentation()),
        )
        val foreign = epub.locatorAt(0).copy(scope = scope(contentRevision = 8))
        assertNull(epub.indexOf(foreign))
        assertFailsWith<IllegalArgumentException> {
            UnifiedReaderNavigationFactory.create(scope(), textRepresentation())
        }
    }

    @Test
    fun textNavigationRejectsMetadataThatSplitsASupplementaryCharacter() {
        val invalid = textRepresentation().copy(
            blocks = listOf(
                TextBlock("first", 0, 2),
                TextBlock("second", 5, 6),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            PlainTextNavigation(scope(), invalid, "a😀\n\nb")
        }
    }

    private fun imageRepresentation(): ContentRepresentation.ImageSequence =
        ContentRepresentation.ImageSequence(
            representationId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            pages = listOf(
                ImagePage(ResourceRef("page-a", blob("11111111-1111-4111-8111-111111111111", "image/png"))),
                ImagePage(ResourceRef("page-b", blob("22222222-2222-4222-8222-222222222222", "image/png"))),
            ),
        )

    private fun textRepresentation(): ContentRepresentation.PlainText =
        ContentRepresentation.PlainText(
            representationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            resource = ResourceRef(
                "text-body",
                blob("33333333-3333-4333-8333-333333333333", "text/plain"),
            ),
            canonicalUtf16Length = 6,
            blocks = listOf(
                TextBlock("first", 0, 3),
                TextBlock("second", 5, 6),
            ),
        )

    private fun epubRepresentation(): ContentRepresentation.EpubSpine {
        val packageDocument = EpubResource(
            "package",
            "OPS/package.opf",
            ResourceRef(
                "package",
                blob("44444444-4444-4444-8444-444444444444", "application/oebps-package+xml"),
            ),
        )
        val first = EpubResource(
            "chapter-a",
            "OPS/a.xhtml",
            ResourceRef(
                "chapter-a",
                blob("55555555-5555-4555-8555-555555555555", "application/xhtml+xml"),
            ),
        )
        val second = EpubResource(
            "chapter-b",
            "OPS/b.xhtml",
            ResourceRef(
                "chapter-b",
                blob("66666666-6666-4666-8666-666666666666", "application/xhtml+xml"),
            ),
        )
        return ContentRepresentation.EpubSpine(
            representationId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            packageGraph = EpubPackage(
                archive = blob("77777777-7777-4777-8777-777777777777", "application/epub+zip"),
                packageDocumentId = packageDocument.id,
                resources = listOf(packageDocument, first, second),
            ),
            documents = listOf(
                EpubSpineDocument("spine-a", first.href, first.id),
                EpubSpineDocument("spine-b", second.href, second.id),
            ),
        )
    }

    private fun scope(contentRevision: Long = 7): ReadingScope {
        val publication = PublicationKey("88888888-8888-4888-8888-888888888888")
        return ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = "99999999-9999-4999-8999-999999999999",
            unitId = UnitKey(publication, "dddddddd-dddd-4ddd-8ddd-dddddddddddd"),
            contentRevision = contentRevision,
        )
    }

    private fun blob(id: String, mediaType: String): BlobRef = BlobRef(
        blobId = id,
        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
        digestAlgorithm = BlobRef.SHA_256,
        plaintextDigest = EMPTY_SHA256,
        byteSize = 0,
        mediaType = mediaType,
    )

    private companion object {
        const val EMPTY_SHA256: String =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
