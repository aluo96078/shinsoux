package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ExtensionLibraryRecoveryV2Test {
    private val sourceKey = SourceKey(
        contractVersion = 2,
        packageId = "zh.bilimanga",
        sourceId = "zh.bilimanga.novel",
    )

    @Test
    fun recoveryAcceptsOnlyTheExactDeterministicPublicationIdentity() {
        val expectedRemoteId = "https://tw.linovelib.com/novel/42.html"
        val publicationKey = extensionPublicationKey(sourceKey, expectedRemoteId)

        val recovered = exactExtensionLibraryRecoveryMatchV2(
            publicationKey = publicationKey,
            sourceKey = sourceKey,
            publications = listOf(
                RemotePublicationV2("https://tw.linovelib.com/novel/99.html", "Same title"),
                RemotePublicationV2(expectedRemoteId, "Same title"),
            ),
        )

        assertEquals(expectedRemoteId, recovered?.remotePublicationId)
        assertEquals(publicationKey, recovered?.publicationKey)
    }

    @Test
    fun titleOnlyMatchCannotRebindALegacyFavorite() {
        val publicationKey = extensionPublicationKey(
            sourceKey,
            "https://tw.linovelib.com/novel/42.html",
        )

        assertNull(
            exactExtensionLibraryRecoveryMatchV2(
                publicationKey = publicationKey,
                sourceKey = sourceKey,
                publications = listOf(
                    RemotePublicationV2(
                        "https://tw.linovelib.com/novel/99.html",
                        "Same title",
                    ),
                ),
            ),
        )
    }

    @Test
    fun duplicateExactRowsFailClosed() {
        val remoteId = "https://tw.linovelib.com/novel/42.html"
        val publicationKey = extensionPublicationKey(sourceKey, remoteId)

        assertNull(
            exactExtensionLibraryRecoveryMatchV2(
                publicationKey = publicationKey,
                sourceKey = sourceKey,
                publications = listOf(
                    RemotePublicationV2(remoteId, "First"),
                    RemotePublicationV2(remoteId, "Duplicate"),
                ),
            ),
        )
    }
}
