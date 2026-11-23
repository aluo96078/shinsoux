package dev.shinsou.kmp.content.acquisition

import dev.shinsou.kmp.content.blob.InMemoryContentBlobStore
import dev.shinsou.kmp.domain.content.ContentRepresentation
import dev.shinsou.kmp.domain.model.PublicationKey
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LegacyTextAcquisitionTest {
    private val simplified = "第一章 开始\n\n他说：“我们这就出发了。”\n\n第二章 结束\n\n你是不是还没有到？"
    private val traditional = "第一章 開始\n\n他說：「我們這就出發了。」\n\n第二章 結束\n\n你是不是還沒有到？"

    @Test
    fun gbkTextIsDecodedAndStoredAsCanonicalUtf8() {
        val store = InMemoryContentBlobStore()
        val acquired = TextAcquisitionService(store)
            .acquire(TextAcquisitionRequest(target(), simplified.toByteArray(charset("GBK"))))
        val representation = acquired.representation as ContentRepresentation.PlainText

        assertEquals(simplified, acquired.metadata.canonicalText)
        assertEquals(SourceTextEncoding.GB18030, acquired.metadata.sourceEncoding)
        assertEquals("GB18030", representation.sourceEncoding)
        assertEquals(listOf("第一章 开始", "第二章 结束"), acquired.metadata.chapters.map(TextChapter::title))
        assertContentEquals(simplified.encodeToByteArray(), store.read(representation.resource.blob))
    }

    @Test
    fun big5TextIsNotMistakenForGb18030() {
        val acquired = TextAcquisitionService(InMemoryContentBlobStore())
            .acquire(TextAcquisitionRequest(target(), traditional.replace("\n", "\r\n").toByteArray(charset("Big5"))))

        assertEquals(traditional, acquired.metadata.canonicalText)
        assertEquals(SourceTextEncoding.BIG5, acquired.metadata.sourceEncoding)
    }

    @Test
    fun shortTextValidInBothEncodingsGoesToTheOneWithCommonCharacters() {
        // GBK 天龙八部 is also valid Big5 (毞韓匐窒), and Big5 天龍八部 is also valid GB18030.
        listOf(
            Triple("天龙八部", "GBK", SourceTextEncoding.GB18030),
            Triple("天龍八部", "Big5", SourceTextEncoding.BIG5),
        ).forEach { (text, charset, expected) ->
            val acquired = TextAcquisitionService(InMemoryContentBlobStore())
                .acquire(TextAcquisitionRequest(target(), text.toByteArray(charset(charset))))
            assertEquals(text, acquired.metadata.canonicalText)
            assertEquals(expected, acquired.metadata.sourceEncoding)
        }
    }

    @Test
    fun anUnclearShortReadingIsLeftToTheUserAndAnExplicitChoiceDecodesThatEncoding() {
        val big5 = "鳳凰".toByteArray(charset("Big5"))
        val outcome = assertIs<TextDecodingOutcome.Legacy>(decodeTextSource(big5))
        assertFalse(outcome.confident)
        assertEquals(
            setOf(SourceTextEncoding.GB18030, SourceTextEncoding.BIG5),
            outcome.candidates.map(LegacyTextCandidate::encoding).toSet(),
        )
        assertEquals("鳳凰", decodeTextSource(big5, TextEncodingHint.BIG5).decoded.text)
        assertEquals("获澳", decodeTextSource(big5, TextEncodingHint.GB18030).decoded.text)

        val novel = (traditional + "\n").repeat(40).toByteArray(charset("Big5"))
        val trusted = assertIs<TextDecodingOutcome.Legacy>(decodeTextSource(novel))
        assertTrue(trusted.confident, "a novel's right reading has a clear lead")
        assertEquals(SourceTextEncoding.BIG5, trusted.decoded.sourceEncoding)
        assertIs<TextDecodingOutcome.Unicode>(decodeTextSource(traditional.encodeToByteArray()))
    }

    @Test
    fun legacyTextThatIsAlsoValidUtf8OffersBothReadings() {
        // GBK 小说 is valid UTF-8 too (С˵); its GB18030 reading is far likelier.
        val gbk = "小说".toByteArray(charset("GBK"))
        val outcome = assertIs<TextDecodingOutcome.Legacy>(decodeTextSource(gbk))
        assertEquals(SourceTextEncoding.GB18030, outcome.recommended)
        // Nobody chose yet: the automatic reading of valid UTF-8 stays UTF-8.
        assertEquals(SourceTextEncoding.UTF_8, outcome.decoded.sourceEncoding)
        assertEquals("С˵", outcome.decoded.text)
        assertTrue(SourceTextEncoding.UTF_8 in outcome.candidates.map { it.encoding })
        assertFalse(outcome.confident, "too short to be trusted without asking")
        assertEquals("С˵", decodeTextSource(gbk, TextEncodingHint.UTF_8).decoded.text)

        // Valid multilingual UTF-8 that scores like a long legacy text (位 = c么te) is never read
        // as one without asking, however long it is.
        val multilingual = List(40) { "λ = côte" }.joinToString("\n").encodeToByteArray()
        when (val read = decodeTextSource(multilingual)) {
            is TextDecodingOutcome.Unicode -> assertEquals(SourceTextEncoding.UTF_8, read.decoded.sourceEncoding)
            is TextDecodingOutcome.Legacy -> {
                assertFalse(read.confident, "a legacy reading of valid UTF-8 needs the user's choice")
                assertEquals(SourceTextEncoding.UTF_8, read.decoded.sourceEncoding)
                assertTrue(SourceTextEncoding.UTF_8 in read.candidates.map { it.encoding })
            }
        }

        // Plain UTF-8 text keeps its one reading: Chinese, Japanese, accented Latin and ASCII.
        listOf("这是一本小说，我们一起读吧。", "これは日本語の小説です。", "Crème brûlée", "Chapter 1").forEach { text ->
            assertIs<TextDecodingOutcome.Unicode>(decodeTextSource(text.encodeToByteArray()), text)
        }
    }

    @Test
    fun legacyTextThatReadsAsOneScriptOfUtf8IsOfferedOnlyWhenTheUserAsksToChoose() {
        // GBK 小学 is valid UTF-8 in one script (Cyrillic Сѧ): nothing tells it from Cyrillic text.
        val bytes = "小学".toByteArray(charset("GBK"))
        val automatic = assertIs<TextDecodingOutcome.Unicode>(decodeTextSource(bytes))
        assertEquals("Сѧ", automatic.decoded.text)

        val offered = assertIs<TextDecodingOutcome.Legacy>(decodeTextSourceOfferingEveryReading(bytes))
        assertFalse(offered.confident)
        assertEquals("Сѧ", offered.decoded.text, "the reading used when nobody chooses stays the automatic one")
        assertEquals(SourceTextEncoding.UTF_8, offered.recommended)
        assertEquals(SourceTextEncoding.UTF_8, offered.candidates.first().encoding)
        assertEquals("小学", offered.candidates.single { it.encoding == SourceTextEncoding.GB18030 }.preview)
    }

    @Test
    fun choosingTheEncodingOffersEveryReadingOfOrdinaryTextAndNoneWhereThereIsOne() {
        // Ordinary UTF-8 Chinese is silent by default; asked to choose, a legacy misreading it has is listed too.
        val chinese = "中文".encodeToByteArray()
        assertIs<TextDecodingOutcome.Unicode>(decodeTextSource(chinese))
        val offered = assertIs<TextDecodingOutcome.Legacy>(decodeTextSourceOfferingEveryReading(chinese))
        assertEquals("中文", offered.decoded.text)
        assertEquals(SourceTextEncoding.UTF_8, offered.recommended)
        assertTrue(offered.candidates.single { it.encoding == SourceTextEncoding.GB18030 }.preview.startsWith("涓"))
        // UTF-8 whose bytes no legacy encoding accepts has one reading.
        assertIs<TextDecodingOutcome.Unicode>(
            decodeTextSourceOfferingEveryReading("第一章 开始\n这是一个普通的中文小说。".encodeToByteArray()),
        )

        // A confident legacy reading stays the decoded and recommended one, but is not confident any more.
        val gbk = List(40) { "这是一个普通的中文小说，我们都知道。" }.joinToString("\n").toByteArray(charset("GBK"))
        val automatic = assertIs<TextDecodingOutcome.Legacy>(decodeTextSource(gbk))
        val chosen = assertIs<TextDecodingOutcome.Legacy>(decodeTextSourceOfferingEveryReading(gbk))
        assertEquals(automatic.decoded.sourceEncoding, chosen.decoded.sourceEncoding)
        assertEquals(automatic.candidates, chosen.candidates)
        assertEquals(automatic.candidates.size < 2, chosen.confident)

        // One reading: plain ASCII (every encoding gives the same text) and a byte order mark.
        assertIs<TextDecodingOutcome.Unicode>(decodeTextSourceOfferingEveryReading("Chapter 1\n\nText".encodeToByteArray()))
        val withBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "小学".toByteArray(charset("GBK"))
        assertIs<TextDecodingOutcome.Unicode>(decodeTextSourceOfferingEveryReading(withBom))
    }

    @Test
    fun validUtf8AcquiredWithoutAHintIsNeverStoredInALegacyReading() {
        // Scores like a legacy novel (位 = c么te) and nobody can be asked here: it stays UTF-8.
        val text = "λ = côte\n".repeat(40)
        val store = InMemoryContentBlobStore()
        val acquired = TextAcquisitionService(store)
            .acquire(TextAcquisitionRequest(target(), text.encodeToByteArray()))
        val representation = acquired.representation as ContentRepresentation.PlainText

        assertEquals(text, acquired.metadata.canonicalText)
        assertEquals(SourceTextEncoding.UTF_8, acquired.metadata.sourceEncoding)
        assertContentEquals(text.encodeToByteArray(), store.read(representation.resource.blob))
        // The same holds for short legacy-looking bytes that are valid UTF-8 (GBK 小说).
        val short = TextAcquisitionService(InMemoryContentBlobStore())
            .acquire(TextAcquisitionRequest(target(), "小说".toByteArray(charset("GBK"))))
        assertEquals("С˵", short.metadata.canonicalText)
    }

    @Test
    fun anExplicitUnicodeHintOrAByteOrderMarkStillRejectsLegacyBytes() {
        val gbk = simplified.toByteArray(charset("GBK"))
        assertFailsWith<IllegalArgumentException> {
            TextAcquisitionService(InMemoryContentBlobStore())
                .acquire(TextAcquisitionRequest(target(), gbk, TextEncodingHint.UTF_8))
        }
        val utf8Bom = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte())
        assertFailsWith<IllegalArgumentException> {
            TextAcquisitionService(InMemoryContentBlobStore())
                .acquire(TextAcquisitionRequest(target(), utf8Bom + gbk))
        }
    }

    @Test
    fun chapterBlockMapsMatchTheOverlapDefinitionForManyChapters() {
        val source = buildString {
            append("前言\n\n序文")
            repeat(300) { chapter ->
                append("\n\n第${chapter + 1}章 標題\n")
                repeat(chapter % 4) { paragraph -> append("段落$chapter-$paragraph\n\n") }
                append("尾$chapter")
            }
        }
        val acquired = TextAcquisitionService(InMemoryContentBlobStore())
            .acquire(TextAcquisitionRequest(target(), source.encodeToByteArray()))
        val blocks = (acquired.representation as ContentRepresentation.PlainText).blocks

        assertEquals(301, acquired.metadata.chapters.size)
        acquired.metadata.chapters.forEach { chapter ->
            val expected = blocks.filter { block ->
                if (block.startUtf16 == block.endUtf16) {
                    block.startUtf16 in chapter.startUtf16..chapter.endUtf16
                } else {
                    block.startUtf16 < chapter.endUtf16 && block.endUtf16 > chapter.startUtf16
                }
            }.map { it.blockId }
            assertEquals(expected, chapter.blockIds, chapter.title)
        }
    }

    private fun target(): LocalAcquisitionTarget = LocalAcquisitionTarget(
        publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111"),
        publicationTitle = "Book",
        stableImportId = "fixtures/text/legacy",
        unitTitle = "Unit",
        contentRevision = 1,
        acquiredAtEpochMillis = 100,
    )
}
