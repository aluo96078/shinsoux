package dev.shinsou.kmp.navigation

import dev.shinsou.kmp.ui.DeepLinkSection
import dev.shinsou.kmp.ui.ShinsouDeepLink
import dev.shinsou.kmp.ui.SyncLinkAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepLinkParserTest {

    @Test
    fun parsesPercentEncodedOneTimeSyncLink() {
        val parsed = DeepLinkParser.parse(
            "shinsou://sync/pair?endpoint=https%3A%2F%2Fsync.example.com%2F&session=pair_12345678&secret=secret_12345678",
        ) as ShinsouDeepLink.OpenSyncLink

        assertEquals(SyncLinkAction.PAIR, parsed.payload.action)
        assertEquals("https://sync.example.com", parsed.payload.endpoint)
        assertEquals("pair_12345678", parsed.payload.sessionId)
        assertEquals("secret_12345678", parsed.payload.oneTimeSecret?.use { it })
        assertTrue("secret_12345678" !in parsed.payload.toString())
    }

    @Test
    fun acceptsSecretlessSetupMetadataAndRedactsOptionalSecret() {
        val parsed = DeepLinkParser.parse(
            "shinsou://sync/setup?endpoint=https%3A%2F%2Fsync.example.com&instance=00000000-0000-4000-8000-000000000001",
        ) as ShinsouDeepLink.OpenSyncLink

        assertEquals(SyncLinkAction.SETUP, parsed.payload.action)
        assertNull(parsed.payload.oneTimeSecret)
        assertEquals("00000000-0000-4000-8000-000000000001", parsed.payload.instanceId)
        assertTrue("secret=" !in parsed.payload.toString())
    }

    @Test
    fun acceptsHttpOnlyForLoopbackLocalTesting() {
        val local = DeepLinkParser.parse(
            "shinsou://sync/setup?endpoint=http%3A%2F%2Flocalhost%3A8787&secret=bootstrap_12345678",
        )
        val remote = DeepLinkParser.parse(
            "shinsou://sync/setup?endpoint=http%3A%2F%2Fexample.com&secret=bootstrap_12345678",
        )

        assertTrue(local is ShinsouDeepLink.OpenSyncLink)
        assertNull(remote)
    }

    @Test
    fun parsesExactEmergencyResetHandoffBinding() {
        val parsed = DeepLinkParser.parse(
            "shinsou://sync/emergency-reset?endpoint=https%3A%2F%2Fsync.example.com" +
                "&instance=00000000-0000-4000-8000-000000000001" +
                "&session=00000000-0000-4000-8000-000000000020" +
                "&user=00000000-0000-4000-8000-000000000002" +
                "&workspace=00000000-0000-4000-8000-000000000007" +
                "&secret=cGFydGljdWxhcmx5LXNlY3VyZS1oYW5kb2ZmLXNlY3JldA",
        ) as ShinsouDeepLink.OpenSyncLink

        assertEquals(SyncLinkAction.EMERGENCY_RESET, parsed.payload.action)
        assertEquals("00000000-0000-4000-8000-000000000020", parsed.payload.sessionId)
        assertEquals("00000000-0000-4000-8000-000000000002", parsed.payload.userId)
        assertEquals("00000000-0000-4000-8000-000000000007", parsed.payload.workspaceId)
        assertTrue("cGFydGljdWxhcmx5" !in parsed.payload.toString())
    }

    @Test
    fun rejectsEmergencyResetWithoutEveryOperatorBinding() {
        assertNull(
            DeepLinkParser.parse(
                "shinsou://sync/emergency-reset?endpoint=https%3A%2F%2Fsync.example.com" +
                    "&instance=00000000-0000-4000-8000-000000000001" +
                    "&session=00000000-0000-4000-8000-000000000020" +
                    "&workspace=00000000-0000-4000-8000-000000000007" +
                    "&secret=cGFydGljdWxhcmx5LXNlY3VyZS1oYW5kb2ZmLXNlY3JldA",
            ),
        )
    }

    @Test
    fun rejectsSyncQuerySmugglingAndMalformedEscapes() {
        assertNull(
            DeepLinkParser.parse(
                "shinsou://sync/invite?endpoint=https%3A%2F%2Fsync.example.com&secret=first_123&secret=second_123",
            ),
        )
        assertNull(
            DeepLinkParser.parse(
                "shinsou://sync/invite?endpoint=https%ZZ&secret=secret_12345678",
            ),
        )
        assertNull(
            DeepLinkParser.parse(
                "shinsou://sync/setup?endpoint=https%3A%2F%2Fuser%40sync.example.com&secret=secret_12345678",
            ),
        )
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
}
