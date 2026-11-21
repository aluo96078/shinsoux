package dev.shinsou.kmp.sync.v3

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewWireTest {
    private val review = """
        {"id":"r1","author":{"id":"u1","username":"alice"},"rating":null,"body":"hi","bodyHtml":"<p>hi</p>",
         "spoiler":true,"status":"hidden","hiddenReason":"reported: spam","helpful":2,"votedHelpful":true,
         "replyCount":3,"createdAt":10,"updatedAt":20,"edited":true,"mine":true,"future":"ignored"}
    """.trimIndent()

    @Test
    fun decodesAReviewWithNullRatingAndHiddenReason() {
        val decoded = SyncWireJson.decodeFromString(WireReview.serializer(), review)
        assertNull(decoded.rating)
        assertEquals("reported: spam", decoded.hiddenReason)
        assertTrue(decoded.spoiler && decoded.edited && decoded.mine && decoded.votedHelpful)
        assertEquals(3, decoded.replyCount)
    }

    @Test
    fun decodesAWorkPageWithoutRatingsOrOwnReview() {
        val json = """
            {"work":{"keyVersion":1,"packageId":"p","sourceId":"s","remoteId":"x","hash":"${"a".repeat(64)}","title":"T"},
             "rating":{"average":null,"count":0},"reviewCount":0,"myReview":null,"inLibrary":false}
        """.trimIndent()
        val page = SyncWireJson.decodeFromString(WireWorkPage.serializer(), json)
        assertNull(page.rating.average)
        assertNull(page.myReview)
        assertFalse(page.inLibrary)
    }

    @Test
    fun reviewInputOmitsANullRating() {
        val text = SyncWireJson.encodeToString(WireReviewInput.serializer(), WireReviewInput(null, "text", false))
        assertEquals("""{"body":"text","spoiler":false}""", text)
    }
}
