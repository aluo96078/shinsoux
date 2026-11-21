package dev.shinsou.kmp.manga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaTextRecognizerTest {
    @Test
    fun linesAreConnectedAreasGrownByTheirMargin() {
        val w = 40; val h = 20
        val prob = FloatArray(w * h)
        // a sure 21 × 5 line, a 2-pixel speck, and a 10 × 10 area too faint on average
        for (y in 2..6) for (x in 4..24) prob[y * w + x] = 0.9f
        prob[15 * w + 2] = 0.9f; prob[15 * w + 3] = 0.9f
        for (y in 8..17) for (x in 28..37) prob[y * w + x] = 0.35f
        val boxes = lineBoxes(prob, w, h)
        assertEquals(1, boxes.size)
        // 20 × 4 between centres: grown by 20·4·1.5 / (2·24) = 2.5
        assertEquals(Box(1.5f, -0.5f, 26.5f, 8.5f), boxes.single())
    }

    @Test
    fun diagonalNeighboursAreOneLine() {
        val w = 10; val h = 10
        // two squares meeting at a corner; the rest just below the threshold keeps the area sure on average
        val prob = FloatArray(w * h) { 0.3f }
        for (y in 0..4) for (x in 0..4) prob[y * w + x] = 1f
        for (y in 5..9) for (x in 5..9) prob[y * w + x] = 1f
        assertEquals(1, lineBoxes(prob, w, h).size)
    }

    @Test
    fun piecesOfOneRowAreJoinedAndRowsReadTopToBottom() {
        val lines = groupLines(listOf(Box(50f, 30f, 90f, 50f), Box(0f, 0f, 40f, 20f), Box(45f, 2f, 80f, 21f)), vertical = false)
        assertEquals(listOf(Box(0f, 0f, 80f, 21f), Box(50f, 30f, 90f, 50f)), lines)
    }

    @Test
    fun columnsAreReadRightToLeft() {
        val boxes = listOf(Box(0f, 0f, 20f, 100f), Box(30f, 0f, 50f, 60f), Box(31f, 65f, 50f, 100f))
        assertTrue(isVertical(boxes))
        assertEquals(listOf(Box(30f, 0f, 50f, 100f), Box(0f, 0f, 20f, 100f)), groupLines(boxes, vertical = true))
        assertFalse(isVertical(listOf(Box(0f, 0f, 100f, 20f), Box(0f, 30f, 20f, 60f))))
    }

    @Test
    fun ctcCollapsesRepeatsAndDropsTheBlank() {
        val chars = listOf("", "a", "b", " ")
        // steps: a a blank a b b space
        val best = intArrayOf(1, 1, 0, 1, 2, 2, 3)
        val scores = FloatArray(best.size * 4).also { s -> best.forEachIndexed { i, c -> s[i * 4 + c] = 1f } }
        assertEquals("aab ", ctcDecode(scores, 4, chars))
    }

    @Test
    fun linesJoinByScript() {
        assertEquals("你好世界", joinLines(listOf("你好", " 世界 "), OcrScript.CHINESE))
        assertEquals("I DON'T KNOW WHAT HAPPENED", joinLines(listOf("I DON'T", "", "KNOW WHAT HAP-", "PENED"), OcrScript.LATIN))
        assertEquals("WAIT - NO", joinLines(listOf("WAIT -", "NO"), OcrScript.LATIN))
    }

    @Test
    fun aColumnTurnedLeftReadsTopToBottomAsLeftToRight() {
        // 1 wide, 3 tall: pixels 1, 2, 3 from the top
        val column = RgbImage(1, 3, byteArrayOf(1, 1, 1, 2, 2, 2, 3, 3, 3))
        val row = column.rotatedLeft()
        assertEquals(3, row.width); assertEquals(1, row.height)
        assertEquals(listOf(1, 2, 3), (0 until 3).map { row.r(it) })
        // 2 × 2: the right column becomes the top row
        val square = RgbImage(2, 2, byteArrayOf(1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4)).rotatedLeft()
        assertEquals(listOf(2, 4, 1, 3), (0 until 4).map { square.r(it) })
    }

    @Test
    fun languagesChooseTheirRecogniserDirectionAndPacks() {
        assertNull(MangaLanguages.recognizer("ja"))
        assertEquals("zh", MangaLanguages.recognizer("zh-Hans"))
        assertEquals("zh", MangaLanguages.recognizer("zh-Hant"))
        assertEquals("en", MangaLanguages.recognizer("en"))
        assertEquals(ReadingDirection.RIGHT_TO_LEFT, MangaLanguages.direction("ja"))
        assertEquals(ReadingDirection.LEFT_TO_RIGHT, MangaLanguages.direction("zh-Hant"))
        assertEquals(emptyList(), MangaLanguages.packs("ja", "zh-Hant"))
        assertEquals(listOf(MangaModelCatalog.OCR_LINES, MangaModelCatalog.OCR_EN, MangaModelCatalog.FONT_SC), MangaLanguages.packs("en", "zh-Hans"))
        assertEquals(listOf(MangaModelCatalog.OCR_LINES, MangaModelCatalog.OCR_ZH), MangaLanguages.packs("zh-Hans", "zh-Hant"))
        assertFalse(MangaLanguages.readable("ko"))
    }
}
