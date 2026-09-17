package dev.shinsou.kmp.ui.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceFailureLabelsTest {
    @Test fun quotaFailureUsesFixedLocalizedGuidance() {
        val error = IllegalStateException("Error: SHINSOU_SOURCE_QUOTA_EXCEEDED (plugin.js#280)")
        assertEquals("來源的每日圖片額度已用完。請等待額度重置後再試。", error.localizedSourceFailure(shinsouStringsFor("zh-TW")))
        assertEquals("来源的每日图片额度已用完。请等待额度重置后再试。", error.localizedSourceFailure(shinsouStringsFor("zh-CN")))
        assertEquals("The source's daily image quota has been reached. Try again after the quota resets.", error.localizedSourceFailure(shinsouStringsFor("en")))
        assertNull(IllegalStateException("SHINSOU_SOURCE_QUOTA_EXCEEDED private-ticket").localizedSourceFailure(shinsouStringsFor("en")))
        assertEquals("來源的每日圖片額度已用完。請等待額度重置後再試。", "SHINSOU_SOURCE_QUOTA_EXCEEDED".localizedSourceFailure(shinsouStringsFor("zh-TW")))
        assertNull("SHINSOU_SOURCE_QUOTA_EXCEEDED private-ticket".localizedSourceFailure(shinsouStringsFor("en")))
    }

    @Test fun exactNestedMarkerUsesLocalizedGuidance() {
        val error = IllegalStateException("wrapper", IllegalArgumentException("SHINSOU_SOURCE_HTTP_CHALLENGE"))
        assertEquals("網站要求驗證。若來源設定提供 Web 驗證，請完成後重試；若沒有此選項，請稍後再試。", error.localizedSourceFailure(shinsouStringsFor("zh-TW")))
        assertEquals("网站要求验证。若来源设置提供 Web 验证，请完成后重试；若没有此选项，请稍后再试。", error.localizedSourceFailure(shinsouStringsFor("zh-CN")))
    }

    @Test fun arbitraryMessagesAndHtmlAreNotPresented() {
        assertNull(IllegalStateException("prefix SHINSOU_SOURCE_HTTP_FORBIDDEN").localizedSourceFailure(shinsouStringsFor("en")))
        assertNull(IllegalStateException("<html>SHINSOU_SOURCE_HTTP_BLOCKED</html>").localizedSourceFailure(shinsouStringsFor("en")))
        assertNull(IllegalStateException("Error: SHINSOU_SOURCE_HTTP_BLOCKED (source.js#4) trailing").localizedSourceFailure(shinsouStringsFor("en")))
        assertNull(IllegalStateException("Error: SHINSOU_SOURCE_HTTP_BLOCKED (<script>#4)").localizedSourceFailure(shinsouStringsFor("en")))
    }

    @Test fun anchoredEngineWrappersAreRecognized() {
        val strings = shinsouStringsFor("en")
        val expected = "The website denied access (HTTP 403). Retry later or check whether the site is accessible."
        assertEquals(expected, IllegalStateException("Error: SHINSOU_SOURCE_HTTP_FORBIDDEN (plugin.js#37)").localizedSourceFailure(strings))
        assertEquals(expected, IllegalStateException("Error: SHINSOU_SOURCE_HTTP_FORBIDDEN").localizedSourceFailure(strings))
        assertEquals(expected, IllegalStateException("SHINSOU_SOURCE_HTTP_FORBIDDEN (source.js:37:9)").localizedSourceFailure(strings))
    }

    @Test fun causeTraversalIsBounded() {
        assertNull(IllegalStateException("SHINSOU_SOURCE_HTTP_FORBIDDEN" + " ".repeat(512)).localizedSourceFailure(shinsouStringsFor("en")))
        var error: Throwable = IllegalStateException("SHINSOU_SOURCE_HTTP_UNAVAILABLE")
        repeat(8) { error = IllegalStateException("wrapper", error) }
        assertNull(error.localizedSourceFailure(shinsouStringsFor("en")))
    }

    @Test fun englishLabelsDistinguishFailureKinds() {
        val strings = shinsouStringsFor("en")
        assertEquals("The website rejected the connection and returned no content. Try again later or contact the site administrator.", IllegalStateException("SHINSOU_SOURCE_HTTP_BLOCKED").localizedSourceFailure(strings))
        assertEquals("The website denied access (HTTP 403). Retry later or check whether the site is accessible.", IllegalStateException("SHINSOU_SOURCE_HTTP_FORBIDDEN").localizedSourceFailure(strings))
        assertEquals("The source website or network request is currently unavailable. Try again later.", IllegalStateException("SHINSOU_SOURCE_HTTP_UNAVAILABLE").localizedSourceFailure(strings))
    }
}
