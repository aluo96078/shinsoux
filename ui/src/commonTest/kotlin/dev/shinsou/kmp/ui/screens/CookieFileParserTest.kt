package dev.shinsou.kmp.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CookieFileParserTest {
    @Test
    fun parsesNetscapeAndHttpOnlyRows() {
        val parsed = CookieFileParser.parse(
            content = listOf(
                "# Netscape HTTP Cookie File",
                ".example.com\tTRUE\t/\tTRUE\t2000000000\tcf_clearance\ttoken==",
                "#HttpOnly_reader.example.com\tFALSE\t/reader\tFALSE\t0\tsession\tabc",
            ).joinToString("\n"),
            sourceBaseUrl = "https://reader.example.com/",
            nowEpochMillis = 1_000L,
        )

        assertEquals(listOf("cf_clearance", "session"), parsed.map { it.name })
        assertEquals(".example.com", parsed[0].domain)
        assertFalse(parsed[0].hostOnly)
        assertTrue(parsed[0].secure)
        assertTrue(parsed[1].httpOnly)
        assertEquals("reader.example.com", parsed[1].domain)
    }

    @Test
    fun parsesCookieEditorJsonAndWrappedJson() {
        val parsed = CookieFileParser.parse(
            content = """
                {"cookies":[
                  {"name":"one","value":"1","domain":".example.com","path":"/","secure":true,"httpOnly":true,"expirationDate":2000000000},
                  {"name":"two","value":"2","path":"/reader","hostOnly":true,"expiry":"2000000001"}
                ]}
            """.trimIndent(),
            sourceBaseUrl = "https://reader.example.com/",
            nowEpochMillis = 1_000L,
        )

        assertEquals(2, parsed.size)
        assertEquals(2_000_000_000_000L, parsed[0].expiresAtEpochMillis)
        assertTrue(parsed[0].httpOnly)
        assertEquals("reader.example.com", parsed[1].domain)
        assertTrue(parsed[1].hostOnly)
    }

    @Test
    fun rejectsExpiredForeignAndMalformedCookies() {
        val parsed = CookieFileParser.parse(
            content = """
                [
                  {"name":"expired","value":"x","domain":"example.com","expirationDate":1},
                  {"name":"foreign","value":"x","domain":"evil.example"},
                  {"name":"bad name","value":"x","domain":"example.com"},
                  {"name":"bad-value","value":"x;y","domain":"example.com"},
                  {"name":"ok","value":"x","domain":"example.com"}
                ]
            """.trimIndent(),
            sourceBaseUrl = "https://example.com/",
            nowEpochMillis = 2_000L,
        )

        assertEquals(listOf("ok"), parsed.map { it.name })
    }

    @Test
    fun lastDuplicateWinsAndMillisecondExpiryIsPreserved() {
        val parsed = CookieFileParser.parse(
            content = """
                [
                  {"name":"session","value":"old","domain":"example.com","expires":2000000000000},
                  {"name":"session","value":"new","domain":"example.com","expires":2000000000000}
                ]
            """.trimIndent(),
            sourceBaseUrl = "https://example.com/",
            nowEpochMillis = 1_000L,
        )

        assertEquals(1, parsed.size)
        assertEquals("new", parsed.single().value)
        assertEquals(2_000_000_000_000L, parsed.single().expiresAtEpochMillis)
    }

    @Test
    fun invalidSourceOrMalformedJsonReturnsNoCookies() {
        assertTrue(CookieFileParser.parse("[]", "file:///tmp/source").isEmpty())
        assertTrue(CookieFileParser.parse("{not-json", "https://example.com").isEmpty())
    }
}
