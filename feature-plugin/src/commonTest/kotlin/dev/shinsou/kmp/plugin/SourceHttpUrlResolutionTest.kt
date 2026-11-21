package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** R5-1: from RepositoryPluginCoordinatorTest, which stays in the app (it also needs :feature-library). */
class SourceHttpUrlResolutionTest {
    @Test
    fun externalWebUrlResolutionHandlesReferencesAndRejectsUnsafeSchemes() {
        assertEquals(
            "https://reader.example/catalog/chapter/1?token=a#page-2",
            resolveSourceHttpUrl(
                baseUrl = "https://reader.example/catalog",
                value = "chapter/1?token=a#page-2",
            ),
        )
        assertEquals(
            "https://reader.example/chapter/2",
            resolveSourceHttpUrl("https://reader.example/catalog", "/chapter/2"),
        )
        assertEquals(
            "https://cdn.example/chapter/3",
            resolveSourceHttpUrl("https://reader.example", "//cdn.example/chapter/3"),
        )
        assertEquals(
            "http://absolute.example/chapter/4",
            resolveSourceHttpUrl(null, "http://absolute.example/chapter/4"),
        )

        assertNull(resolveSourceHttpUrl("https://reader.example", "javascript:alert(1)"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "file:///private/chapter"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "local://chapter/1"))
        assertNull(resolveSourceHttpUrl("ftp://reader.example", "/chapter/1"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "chapter\\redirect"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "chapter/1\nhttps://evil.example"))
        assertNull(resolveSourceHttpUrl("https://reader.example", "   "))
    }
}
