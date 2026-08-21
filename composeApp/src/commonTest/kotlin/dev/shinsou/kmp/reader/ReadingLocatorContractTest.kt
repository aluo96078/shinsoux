package dev.shinsou.kmp.reader

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReadingLocatorContractTest {
    @Test
    fun textQuoteRecoversAnAnchorWhenTheStoredOffsetIsStale() {
        val scope = scope()
        val locator = ReadingLocator.Text(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            scope = scope,
            resourceId = "body",
            blockId = "block-1",
            offset = 0,
            quote = TextQuote(
                exact = "beta",
                prefix = "alpha ",
                suffix = " gamma",
            ),
        )

        assertEquals(6, locator.resolveOffset("alpha beta gamma"))
        assertEquals(6, locator.resolveTextOffset("alpha beta gamma"))
    }

    @Test
    fun quoteOccurrenceAndUtf16OffsetsRemainDeterministic() {
        val scope = scope()
        val quote = TextQuote(exact = "猫", occurrence = 1)
        val locator = TextLocator(
            schemaVersion = 1,
            scope = scope,
            resourceId = "body",
            blockId = "block-1",
            // The second 猫 starts at UTF-16 code-unit offset 4.
            offset = 4,
            quote = quote,
        )

        assertEquals(4, locator.resolveOffset("犬猫😀猫"))
        assertNull(ImageLocator(1, scope, pageResourceId = "page").resolveTextOffset("ignored"))
    }

    @Test
    fun textOffsetsNeverResolveInsideASurrogatePair() {
        val scope = scope()
        val text = "前😀後"
        val emojiStart = text.indexOf("😀")
        val locator = TextLocator(
            schemaVersion = 1,
            scope = scope,
            resourceId = "body",
            blockId = "block-1",
            // Deliberately points between the two UTF-16 code units of 😀.
            offset = emojiStart + 1,
            quote = TextQuote(exact = "😀", occurrence = 0),
        )

        assertEquals(emojiStart, locator.resolveOffset(text))
        assertEquals(emojiStart, locator.resolveTextOffset(text))
    }

    @Test
    fun relativeHrefRejectsNestedEscapesAndMalformedAuthorities() {
        val scope = scope()
        EpubLocator(
            schemaVersion = 1,
            scope = scope,
            resourceId = "chapter",
            resourceHref = "Text/Chapter%201.xhtml",
            cfi = "epubcfi(/6/2[chapter]!/4/1:0)",
        )
        val unsafeHrefs = listOf(
            "%252e%252e/private.xhtml",
            "%2568ttp%253a%252f%252fevil.example/chapter.xhtml",
            "%255c%255cserver/chapter.xhtml",
            "%2500chapter.xhtml",
            "%2e%2e/chapter.xhtml",
            "safe%23/../../private.xhtml",
            "chapter%2.xhtml",
        )
        unsafeHrefs.forEach { href ->
            assertFailsWith<IllegalArgumentException>(href) {
                EpubLocator(
                    schemaVersion = 1,
                    scope = scope,
                    resourceId = "chapter",
                    resourceHref = href,
                    cfi = "epubcfi(/6/2[chapter]!/4/1:0)",
                )
            }
        }
    }

    @Test
    fun cfiValidationAcceptsPointCfiAndRejectsWhitespaceEscapesAndMalformedSteps() {
        val scope = scope()
        EpubLocator(
            schemaVersion = 1,
            scope = scope,
            resourceId = "chapter",
            resourceHref = "text/chapter.xhtml",
            cfi = "epubcfi(/6/2[chapter]!/4/1:0)",
        )
        listOf(
            "epubcfi(/6//2! /4/1:0)",
            "epubcfi(/6/2[chapter! /4/1:0)",
            "epubcfi(/6/0[chapter]!/4/1:0)",
            "epubcfi(/6/2[chapter]!/4/1:)",
            "epubcfi(/6/2[chapter]!/4/1:%30)",
            "epubcfi(/6/2[chapter]!/4/1:0)\u0000",
        ).forEach { cfi ->
            assertFailsWith<IllegalArgumentException>(cfi) {
                EpubLocator(
                    schemaVersion = 1,
                    scope = scope,
                    resourceId = "chapter",
                    resourceHref = "text/chapter.xhtml",
                    cfi = cfi,
                )
            }
        }
    }

    @Test
    fun rangeRequiresTheSamePublicationAcquisitionUnitRevisionAndKind() {
        val scope = scope(contentRevision = 3)
        val start = TextLocator(1, scope, "body", "block-1", offset = 2)
        val end = TextLocator(1, scope, "body", "block-1", offset = 5)
        ReadingRange(start, end).validate()

        val differentRevision = TextLocator(
            1,
            scope(contentRevision = 4),
            "body",
            "block-1",
            offset = 5,
        )
        assertFailsWith<IllegalArgumentException> { ReadingRange(start, differentRevision) }

        val image = ImageLocator(1, scope, pageResourceId = "page")
        assertFailsWith<IllegalArgumentException> { ReadingRange(start, image) }

        assertFailsWith<IllegalArgumentException> {
            ReadingRange(
                TextLocator(1, scope, "body", "block-1", offset = 6),
                start,
            )
        }
    }

    @Test
    fun epubAndScopeValidationRejectUnsafeOrOutOfRangeValues() {
        val scope = scope()
        assertFailsWith<IllegalArgumentException> {
            EpubLocator(
                schemaVersion = 1,
                scope = scope,
                resourceId = "chapter",
                resourceHref = "../private.xhtml",
                cfi = "epubcfi(/6/2[chapter]!/4/1:0)",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            EpubLocator(
                schemaVersion = 1,
                scope = scope,
                resourceId = "chapter",
                resourceHref = "%68ttp%3A%2F%2Fevil.example/chapter.xhtml",
                cfi = "epubcfi(/6/2[chapter]!/4/1:0)",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            ImageLocator(1, scope, pageResourceId = "page", normalizedOffsetFraction = 1.1)
        }
        assertFailsWith<IllegalArgumentException> {
            TextQuote(exact = "\u0000")
        }
    }

    private fun scope(contentRevision: Long = 0): ReadingScope {
        val publication = PublicationKey("11111111-1111-4111-8111-111111111111")
        return ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = "33333333-3333-4333-8333-333333333333",
            unitId = UnitKey(publication, "22222222-2222-4222-8222-222222222222"),
            contentRevision = contentRevision,
        )
    }
}
