package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.ShinsouDeepLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeepLinkParserTest {

    @Test
    fun oldSyncLinksAreNoLongerRecognized() {
        assertNull(
            DeepLinkParser.parse(
                "shinsou://sync/pair?endpoint=https%3A%2F%2Fsync.example.com%2F&session=pair_12345678&secret=secret_12345678",
            ),
        )
        assertNull(DeepLinkParser.parse("shinsou://sync/setup?endpoint=https%3A%2F%2Fsync.example.com"))
    }

    @Test
    fun parsesPublishedRoutes() {
        assertEquals(ShinsouDeepLink.OpenManga(42), DeepLinkParser.parse("shinsou://manga/42"))
        assertEquals(
            ShinsouDeepLink.OpenChapter(mangaId = -1, chapterId = 9),
            DeepLinkParser.parse("shinsou://chapter/9"),
        )
        assertEquals(
            ShinsouDeepLink.OpenSection(DeepLinkSection.Updates),
            DeepLinkParser.parse("shinsou://updates"),
        )
    }

    @Test
    fun rejectsForeignAndMalformedLinks() {
        assertNull(DeepLinkParser.parse("https://example.com/manga/42"))
        assertNull(DeepLinkParser.parse("shinsou://manga/not-a-number"))
    }

    @Test
    fun addRepositoryLinksCarryAnHttpsRepositoryAndAFullFingerprintOnly() = kotlinx.coroutines.test.runTest {
        dev.shinsou.kmp.crypto.SodiumPrimitives.initialize()
        val hex = "ab".repeat(32)
        val link = DeepLinkParser.parse("shinsou://add-repository?url=https%3A%2F%2Frepo.example%2Fx&fp=$hex")
        val parsed = link as ShinsouDeepLink.AddRepository
        assertEquals("https://repo.example/x", parsed.repositoryUrl)
        assertEquals(dev.shinsou.kmp.plugin.PublisherFingerprint.parse(hex), parsed.fingerprint)
        assertEquals(
            ShinsouDeepLink.AddRepository("https://repo.example/x", null),
            DeepLinkParser.parse("shinsou://add-repository?url=https://repo.example/x"),
        )
        assertNull(DeepLinkParser.parse("shinsou://add-repository?url=http://repo.example/x"))
        assertNull(DeepLinkParser.parse("shinsou://add-repository?url=https://repo.example/x&fp=1234"))
        assertNull(DeepLinkParser.parse("shinsou://add-repository"))
    }

    @Test
    fun addDevRepositoryLinksFromTheCliCarryTheServerUrl() {
        assertEquals(
            ShinsouDeepLink.AddDevRepository("http://127.0.0.1:8787"),
            DeepLinkParser.parse("shinsou://add-dev-repository?url=http%3A%2F%2F127.0.0.1%3A8787"),
        )
        assertNull(DeepLinkParser.parse("shinsou://add-dev-repository?url=ftp://127.0.0.1"))
        assertNull(DeepLinkParser.parse("shinsou://add-dev-repository"))
    }
}
