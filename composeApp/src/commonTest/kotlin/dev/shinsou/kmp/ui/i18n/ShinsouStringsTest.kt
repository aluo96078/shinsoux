package dev.shinsou.kmp.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ShinsouStringsTest {
    @Test
    fun localeTagsKeepTraditionalAndSimplifiedChineseSeparate() {
        assertEquals("書庫", shinsouStringsFor("zh-TW").library)
        assertEquals("書庫", shinsouStringsFor("zh-Hant").library)
        assertEquals("書庫", shinsouStringsFor("zh-HK").library)
        assertEquals("书库", shinsouStringsFor("zh-CN").library)
        assertEquals("书库", shinsouStringsFor("zh-Hans").library)
        assertEquals("书库", shinsouStringsFor("zh-SG").library)
    }

    @Test
    fun bareChineseTagUsesTheStableTraditionalFallback() {
        assertEquals("書庫", shinsouStringsFor("zh").library)
        assertEquals("書庫", shinsouStringsFor("ZH").library)
    }

    @Test
    fun nonChineseLocalesHaveAStableEnglishFallback() {
        assertEquals("Library", shinsouStringsFor("en-US").library)
        assertEquals("Library", shinsouStringsFor("xx-YY").library)
    }

    @Test
    fun longTailStringsAreLocalizedAndInterpolated() {
        val traditional = shinsouStringsFor("zh-TW")
        val simplified = shinsouStringsFor("zh-CN")

        assertEquals("沒有章節", traditional.text("No chapters"))
        assertEquals("没有章节", simplified.text("No chapters"))
        assertEquals("已閱讀 3/12 章 · 25%", traditional.text("{0} of {1} chapters read · {2}%", 3, 12, 25))
        assertEquals("已阅读 3/12 章 · 25%", simplified.text("{0} of {1} chapters read · {2}%", 3, 12, 25))
    }

    @Test
    fun coreLabelsResolveThroughTextForEverySupportedEastAsianLocale() {
        assertEquals("設定", shinsouStringsFor("ja-JP").text("Settings"))
        assertEquals("설정", shinsouStringsFor("ko-KR").text("Settings"))
        assertEquals("设置", shinsouStringsFor("zh-CN").text("Settings"))
        assertEquals("設定", shinsouStringsFor("zh-TW").text("Settings"))
    }

    @Test
    fun platformSecurityMessagesFollowTheSelectedChineseLocale() {
        val key = "Set up a device passcode, PIN, password, or biometric authentication to use app lock."
        assertEquals(
            "請設定裝置密碼、PIN、密碼或生物辨識驗證，才能使用應用程式鎖。",
            shinsouStringsFor("zh-TW").text(key),
        )
        assertEquals(
            "请设置设备密码、PIN、密码或生物识别验证，才能使用应用锁。",
            shinsouStringsFor("zh-CN").text(key),
        )
    }

    @Test
    fun simplifiedLongTailDoesNotLeakCommonTraditionalTerms() {
        val simplified = shinsouStringsFor("zh-CN")

        assertEquals("启用 Shinsou X 应用程序锁", simplified.text("Enable Shinsou X app lock"))
        assertEquals("网络与扩展", simplified.text("Network and extensions"))
        assertEquals("来源设置", simplified.text("Source settings"))
        assertEquals("选择本机漫画", simplified.text("Choose local manga"))
    }

    @Test
    fun readerDirectionsAndPageAnimationAreCompleteInEverySupportedLanguage() {
        val expected = mapOf(
            "en-US" to ReaderCopy(
                "Paged · left to right", "Paged · right to left", "Vertical paging", "Webtoon",
                "Continuous vertical", "Page turn animation", "Animate transitions when changing pages",
            ),
            "zh-TW" to ReaderCopy(
                "翻頁（左至右）", "翻頁（右至左）", "垂直翻頁", "條漫",
                "連續垂直", "翻頁動畫", "切換頁面時顯示動畫",
            ),
            "zh-CN" to ReaderCopy(
                "翻页（从左到右）", "翻页（从右到左）", "垂直翻页", "条漫",
                "连续垂直", "翻页动画", "切换页面时显示动画",
            ),
            "ja-JP" to ReaderCopy(
                "ページ送り（左から右）", "ページ送り（右から左）", "縦方向のページ送り", "ウェブトゥーン",
                "縦スクロール", "ページ切り替えアニメーション", "ページを切り替えるときにアニメーションを表示",
            ),
            "ko-KR" to ReaderCopy(
                "페이지 넘김(왼쪽에서 오른쪽)", "페이지 넘김(오른쪽에서 왼쪽)", "세로 페이지 넘김", "웹툰",
                "연속 세로 스크롤", "페이지 전환 애니메이션", "페이지를 전환할 때 애니메이션 표시",
            ),
            "fr-FR" to ReaderCopy(
                "Pages · de gauche à droite", "Pages · de droite à gauche", "Pagination verticale", "Webtoon",
                "Défilement vertical continu", "Animation de changement de page", "Animer la transition entre les pages",
            ),
            "de-DE" to ReaderCopy(
                "Seiten · von links nach rechts", "Seiten · von rechts nach links", "Vertikales Blättern", "Webtoon",
                "Fortlaufend vertikal", "Seitenwechsel animieren", "Übergänge zwischen Seiten animieren",
            ),
            "es-ES" to ReaderCopy(
                "Páginas · de izquierda a derecha", "Páginas · de derecha a izquierda", "Paginación vertical", "Webtoon",
                "Desplazamiento vertical continuo", "Animación al pasar página", "Animar la transición entre páginas",
            ),
            "pt-BR" to ReaderCopy(
                "Páginas · da esquerda para a direita", "Páginas · da direita para a esquerda", "Paginação vertical", "Webtoon",
                "Rolagem vertical contínua", "Animação ao virar página", "Animar a transição entre páginas",
            ),
        )

        expected.forEach { (locale, copy) ->
            val strings = shinsouStringsFor(locale)
            assertEquals(copy.leftToRight, strings.text("Left to right"), locale)
            assertEquals(copy.rightToLeft, strings.text("Right to left"), locale)
            assertEquals(copy.vertical, strings.text("Vertical paging"), locale)
            assertEquals(copy.webtoon, strings.text("Webtoon"), locale)
            assertEquals(copy.continuousVertical, strings.text("Continuous vertical"), locale)
            assertEquals(copy.pageTurnAnimation, strings.text("Page turn animation"), locale)
            assertEquals(copy.pageTurnAnimationDescription, strings.text("Animate transitions when changing pages"), locale)
            assertEquals(copy.leftToRight, strings.text("Pager ltr"), "$locale legacy LTR key")
            assertEquals(copy.rightToLeft, strings.text("Pager rtl"), "$locale legacy RTL key")
            assertFalse(strings.text("Left to right").contains("LTR", ignoreCase = true), locale)
            assertFalse(strings.text("Right to left").contains("RTL", ignoreCase = true), locale)
        }
    }

    private data class ReaderCopy(
        val leftToRight: String,
        val rightToLeft: String,
        val vertical: String,
        val webtoon: String,
        val continuousVertical: String,
        val pageTurnAnimation: String,
        val pageTurnAnimationDescription: String,
    )
}
