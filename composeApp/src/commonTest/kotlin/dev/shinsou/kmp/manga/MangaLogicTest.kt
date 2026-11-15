package dev.shinsou.kmp.manga

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MangaLayoutTest {
    /** Latin is half an em, everything else a full em. */
    private val layout = MangaLayout(TextMeasure { text, size -> text.sumOf { if (it.code < 0x2E80) (size * 0.5).toDouble() else size.toDouble() }.toFloat() })

    private fun oval(w: Int, h: Int): Region {
        val cx = w / 2f; val cy = h / 2f
        return Region(0, 0, w, h, BooleanArray(w * h) { i ->
            val dx = (i % w - cx) / cx; val dy = (i / w - cy) / cy
            dx * dx + dy * dy <= 1f
        })
    }

    private fun columns(l: TextLayout): List<String> =
        l.glyphs.groupBy { it.x }.entries.sortedByDescending { it.key }.map { (_, col) -> col.sortedBy { it.y }.joinToString("") { it.text } }

    @Test
    fun verticalTextUsesVerticalFormsAndStaysWithinTheSourceSize() {
        val l = assertNotNull(layout.layout("等一下，你到底在說什麼啊？", oval(220, 420), Box(40f, 60f, 180f, 360f), sourceSize = 30, inBubble = true, horizontalSource = false, orientation = MangaOrientation.VERTICAL))
        assertTrue(l.vertical)
        assertTrue(l.size <= 31, "size ${l.size}")
        val text = columns(l).joinToString("")
        assertTrue("︐" in text, text)   // the vertical comma
        assertTrue(columns(l).none { it.first().toString() in MangaLayout.NO_START }, "kinsoku: ${columns(l)}")
    }

    @Test
    fun noColumnIsLeftWithALoneCharacter() {
        val l = assertNotNull(layout.layout("這次絕對不會再讓你逃走！「沒關係」……我一個人也可以的", oval(200, 520), Box(30f, 40f, 170f, 480f), 32, true, false, MangaOrientation.VERTICAL))
        assertTrue(columns(l).last().length > 1, "${columns(l)}")
    }

    @Test
    fun shortNumbersAndTwoCapitalsAreUprightLongWordsLieSideways() {
        val toks = layout.tokens("第12話OK2026USB Thank you", 30, vertical = true)
        assertEquals(GlyphKind.TATE_CHU_YOKO, toks.first { it.text == "12" }.kind)
        // "OK2026USB" is one Latin run of mixed caps and digits: it lies on its side
        assertEquals(GlyphKind.SIDEWAYS, toks.first { it.text.startsWith("OK") }.kind)
        val caps = layout.tokens("借我USB線", 30, vertical = true)
        assertEquals(listOf("U", "S", "B"), caps.filter { it.kind == GlyphKind.TATE_CHU_YOKO }.map { it.text })
        val pair = layout.tokens("啊!?", 30, vertical = true)
        assertEquals("!?", pair.last().text)
        assertEquals(GlyphKind.TATE_CHU_YOKO, pair.last().kind)
    }

    @Test
    fun ellipsisIsNotSplitAcrossLines() {
        val s = 30
        val toks = layout.tokens("我一個人……也可以", s, vertical = true)
        val lines = assertNotNull(layout.breakLines(toks, listOf(s * 5f, s * 5f), s))
        lines.zipWithNext().forEach { (a, b) -> assertTrue(!(a.last().text == "︙" && b.first().text == "︙"), "split: $lines") }
    }

    @Test
    fun preferHorizontalFallsBackToVerticalInANarrowBubble() {
        val narrow = oval(90, 520)
        val l = assertNotNull(layout.layout("「沒關係」……我一個人也可以的。真的嗎？", narrow, Box(10f, 20f, 80f, 500f), 28, true, false, MangaOrientation.PREFER_HORIZONTAL))
        assertTrue(l.vertical)
        val wide = oval(420, 240)
        val h = assertNotNull(layout.layout("等一下，你到底在說什麼啊？", wide, Box(40f, 40f, 380f, 200f), 30, true, false, MangaOrientation.PREFER_HORIZONTAL))
        assertTrue(!h.vertical)
    }

    @Test
    fun nothingFitsInAnEmptyArea() {
        assertNull(layout.layout("文字", Region(0, 0, 4, 4, BooleanArray(16)), Box(0f, 0f, 4f, 4f), 30, true, false, MangaOrientation.VERTICAL))
    }
}

class MangaDetectorSelectTest {
    @Test
    fun thresholdsDifferByKindAndOverlappingTextKeepsTheSurerBox() {
        val labels = longArrayOf(1, 2, 2, 0, 2)
        val scores = floatArrayOf(0.44f, 0.36f, 0.34f, 0.9f, 0.80f)
        val boxes = floatArrayOf(
            0f, 0f, 10f, 10f,       // bubble text under its 0.45 threshold: dropped
            20f, 0f, 30f, 10f,      // free text over its 0.35 threshold: kept
            40f, 0f, 50f, 10f,      // free text under 0.35: dropped
            0f, 0f, 100f, 100f,     // bubble: kept
            19f, 0f, 30f, 10f,      // overlaps the kept free text, but surer: wins
        )
        val found = MangaDetector.select(labels, boxes, scores)
        assertEquals(listOf(RegionKind.BUBBLE, RegionKind.FREE_TEXT), found.map { it.kind })
        assertEquals(0.80f, found.last().score)
    }
}

class MangaScriptTest {
    private fun page(vararg regions: TextRegion) = PageAnalysis(1000, 1200, regions.toList(), emptyList())

    @Test
    fun oneLinePerRegionInMangaReadingOrderWithPagesAndPunctuationKept() {
        val p1 = page(
            TextRegion(RegionKind.BUBBLE_TEXT, 100, 100, 200, 300, "左の\nセリフ"),
            TextRegion(RegionKind.BUBBLE_TEXT, 700, 120, 800, 300, "右のセリフ"),
            TextRegion(RegionKind.BUBBLE_TEXT, 400, 800, 500, 900, "……"),
        )
        val p2 = page(TextRegion(RegionKind.FREE_TEXT, 10, 10, 50, 50, "次"))
        val script = MangaScript.build(listOf(p1, p2))
        val lines = script.text.split("\n")
        assertEquals(listOf("右のセリフ", "左の セリフ", "……", "次"), lines.filterNot { script.keep(it) && it.startsWith("⁣") })
        assertTrue(script.keep(lines[0]))                      // page marker
        assertTrue(script.keep("……"))
        val translated = lines.map { if (script.keep(it)) null else "T:$it" }
        val map = script.translations(translated)
        assertEquals("T:右のセリフ", map[0]!![1])
        assertEquals("T:左の セリフ", map[0]!![0])
        assertNull(map[0]!![2])
        assertEquals("T:次", map[1]!![0])
    }

    @Test
    fun webtoonsReadLeftToRight() {
        val p = page(
            TextRegion(RegionKind.BUBBLE_TEXT, 700, 100, 800, 200, "B"),
            TextRegion(RegionKind.BUBBLE_TEXT, 100, 100, 200, 200, "A"),
        )
        assertEquals(listOf(1, 0), MangaScript.readingOrder(p, ReadingDirection.LEFT_TO_RIGHT))
        assertEquals(listOf(0, 1), MangaScript.readingOrder(p, ReadingDirection.RIGHT_TO_LEFT))
    }
}

class MangaTidyTest {
    @Test
    fun endlessRepeatsAreCutAndRunawayTranslationsKeepTheSource() {
        kotlin.test.assertEquals("喔喔喔啊啊啊", tidyTranslation("喔喔喔喔喔喔喔喔啊啊啊啊啊", "おおおおあああ"))
        kotlin.test.assertEquals("等等等一下", tidyTranslation("等等等等等等一下", "待って"))
        kotlin.test.assertEquals("哈哈哈", tidyTranslation("哈哈哈哈哈哈", "はは"))
        kotlin.test.assertEquals("你好嗎？你好嗎？你好嗎？", tidyTranslation("你好嗎？".repeat(9), "元気？"))
        kotlin.test.assertEquals("……好吧", tidyTranslation("  ……好吧 ", "……いいよ"))
        kotlin.test.assertNull(tidyTranslation("我不知道你到底在說什麼但是我覺得這件事情非常非常重要所以請你一定要認真聽我說完", "えっ"))
        kotlin.test.assertNull(tidyTranslation("", "あ"))
        kotlin.test.assertNull(tidyTranslation(null, "あ"))
    }
}

class MangaDetectorQueriesTest {
    @Test
    fun queriesBecomePageBoxesOfTheirLikeliestKind() {
        // three queries: a bubble, text in it (both sure), and a doubtful free text
        val scores = floatArrayOf(0.9f, 0.1f, 0.0f, 0.2f, 0.8f, 0.1f, 0.1f, 0.0f, 0.3f)
        val boxes = floatArrayOf(0.5f, 0.5f, 0.4f, 0.2f, 0.5f, 0.5f, 0.2f, 0.1f, 0.1f, 0.1f, 0.1f, 0.1f)
        val found = MangaDetector.fromQueries(scores, boxes, width = 1000, height = 2000)
        kotlin.test.assertEquals(listOf(RegionKind.BUBBLE, RegionKind.BUBBLE_TEXT), found.map { it.kind })
        val text = found.single { it.kind == RegionKind.BUBBLE_TEXT }.box
        kotlin.test.assertEquals(400f, text.x0, 0.5f); kotlin.test.assertEquals(900f, text.y0, 0.5f)
        kotlin.test.assertEquals(600f, text.x1, 0.5f); kotlin.test.assertEquals(1100f, text.y1, 0.5f)
    }

    @Test
    fun aPackFileInADirectoryIsReleasedByItsName() {
        val file = MangaModelCatalog.COREML_DETECTOR.files.first { it.name.endsWith("weight.bin") }
        kotlin.test.assertEquals("weight.bin", file.assetName)
        kotlin.test.assertEquals(MangaModelCatalog.COREML_DETECTOR.files.size, MangaModelCatalog.COREML_DETECTOR.files.map { it.assetName }.toSet().size)
    }
}


class MangaAcceleratorsTest {
    @Test
    fun pagesAreSharedByWeightTheSameWayEveryTime() {
        val pages = assignPages(20, listOf(1, 4))
        assertEquals(4, pages.count { it == 0 }); assertEquals(16, pages.count { it == 1 })
        assertTrue(pages.contentEquals(assignPages(20, listOf(1, 4))))
        // the faster detector never waits more than one page between its own
        assertTrue((1 until pages.size).none { pages[it] == 0 && pages[it - 1] == 0 })
        assertTrue(assignPages(7, listOf(1)).all { it == 0 })
        assertEquals(listOf(0, 1, 2, 0, 1, 2), assignPages(6, listOf(1, 1, 1)).toList())
    }

    @Test
    fun snapdragonsFromThe8Gen2OnHaveTheirNpuLibraries() {
        assertEquals(75, QnnSoc.htpArch("SM8650"))
        assertEquals(79, QnnSoc.htpArch("sm8750-ab"))
        assertEquals(73, QnnSoc.htpArch("SM7675"))
        assertNull(QnnSoc.htpArch("SM8475")) // 8+ Gen 1: v69, too old
        assertNull(QnnSoc.htpArch("Tensor G4"))
        assertNull(QnnSoc.htpArch(null))
        val runtime = QnnSoc.runtime(75)
        assertEquals(5, runtime.entries.size)
        assertTrue("jni/arm64-v8a/libQnnHtpV75Skel.so" in runtime.entries && "jni/arm64-v8a/libQnnHtpV75Stub.so" in runtime.entries)
        assertTrue(runtime.entries.none { "V79" in it })
        assertTrue(runtime.installedBytes in 100_000_000L..110_000_000L)
        kotlin.test.assertNotEquals(runtime.id, QnnSoc.runtime(79).id, "each generation's libraries are kept apart")
    }
}

class MangaCpuModeTest {
    private val iphone = MangaDeviceProfile(performanceCores = 2, efficiencyCores = 4, memoryMb = 12_000)
    private val phone = MangaDeviceProfile(performanceCores = 6, efficiencyCores = 2, memoryMb = 12_000)
    private val mac = MangaDeviceProfile(performanceCores = 8, efficiencyCores = 4, memoryMb = 48_000)

    @Test
    fun theSaverIsOneThreadOnAnEfficiencyCore() {
        for (device in listOf(iphone, phone, mac)) {
            val saver = MangaTuning.of(device, MangaCpuMode.SAVER)
            assertEquals(listOf(1, 1, 1, 1), listOf(saver.detectorThreads, saver.ocrWorkers, saver.ocrThreads, saver.inpaintThreads))
            assertTrue(saver.efficiencyThread)
        }
    }

    @Test
    fun balancedUsesLessThanFullSpeedAndFullSpeedIsTheMeasuredBest() {
        for (device in listOf(iphone, phone, mac)) {
            val full = MangaTuning.of(device, MangaCpuMode.FULL)
            val balanced = MangaTuning.of(device, MangaCpuMode.BALANCED)
            assertEquals(MangaTuning.of(device), full)
            assertTrue(!full.efficiencyThread && !balanced.efficiencyThread)
            val threads = { t: MangaTuning -> t.detectorThreads + t.ocrWorkers * t.ocrThreads }
            assertTrue(threads(balanced) < threads(full), "$device: $balanced vs $full")
            assertTrue(balanced.inpaintThreads <= full.inpaintThreads)
        }
    }
}
