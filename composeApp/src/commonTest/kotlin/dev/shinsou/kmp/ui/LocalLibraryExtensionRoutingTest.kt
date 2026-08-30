package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2
import dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.plugin.v2.extensionPublicationKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class LocalLibraryExtensionRoutingTest {
    private val sourceKey = SourceKey(
        contractVersion = 2,
        packageId = "zh.bilimanga",
        sourceId = "zh.bilimanga.novel",
    )
    private val remotePublicationId = "https://tw.linovelib.com/novel/42.html"
    private val publicationKey = extensionPublicationKey(sourceKey, remotePublicationId)
    private val binding = ExtensionLibraryBindingV2(publicationKey, sourceKey, remotePublicationId)

    @Test
    fun reversibleFavoriteOpensItsExtensionWithoutRecovery() {
        val route = localLibraryExtensionRoute(
            manga = Manga(
                id = 7,
                source = LOCAL_SOURCE_ID,
                url = encodeExtensionLibraryPublicationUrl(sourceKey, remotePublicationId),
            ),
            hasLocalChapters = false,
            legacyBinding = { error("Reversible rows must not inspect the typed graph") },
        )

        assertEquals(
            LocalLibraryExtensionRoute.Open(binding, migrateLegacyUrl = false),
            route,
        )
    }

    @Test
    fun materializedLegacyFavoriteUsesItsTypedSourceBinding() {
        val route = localLibraryExtensionRoute(
            manga = legacyTypedManga(),
            hasLocalChapters = true,
            legacyBinding = { key -> binding.takeIf { key == publicationKey } },
        )

        assertEquals(
            LocalLibraryExtensionRoute.Open(binding, migrateLegacyUrl = true),
            route,
        )
    }

    @Test
    fun typedLocalTxtOrEpubWithAChapterStaysOnLocalDetail() {
        val route = localLibraryExtensionRoute(
            manga = legacyTypedManga(),
            hasLocalChapters = true,
            legacyBinding = { null },
        )

        assertEquals(LocalLibraryExtensionRoute.LocalDetail, route)
    }

    @Test
    fun onlyAZeroChapterUuidRowRequestsLegacySearchRecovery() {
        val route = localLibraryExtensionRoute(
            manga = legacyTypedManga(),
            hasLocalChapters = false,
            legacyBinding = { null },
        )

        assertEquals(publicationKey, assertIs<LocalLibraryExtensionRoute.RecoverLegacy>(route).publicationKey)
    }

    @Test
    fun ordinaryLocalAndRemoteRowsKeepTheirExistingDetailRoutes() {
        listOf(
            Manga(source = LOCAL_SOURCE_ID, url = "local://manga/7"),
            Manga(source = 123, url = encodeTypedLocalPublicationUrl(publicationKey)),
        ).forEach { manga ->
            assertEquals(
                LocalLibraryExtensionRoute.LocalDetail,
                localLibraryExtensionRoute(manga, hasLocalChapters = false, legacyBinding = { binding }),
            )
        }
    }

    @Test
    fun sourceWithoutRemoteFavoritesUsesOnlyTheAppOwnedLibrary() = runTest {
        var localMutations = 0
        var sourceMutations = 0

        mutateExtensionFavorite(
            destination = extensionFavoriteDestination(sourceSupportsFavorites = false),
            localMutation = { localMutations++ },
            sourceMutation = { sourceMutations++ },
        )

        assertEquals(1, localMutations)
        assertEquals(0, sourceMutations)
    }

    @Test
    fun explicitRemoteFavoriteCapabilityKeepsItsSeparateSourceMutation() = runTest {
        var localMutations = 0
        var sourceMutations = 0

        mutateExtensionFavorite(
            destination = extensionFavoriteDestination(sourceSupportsFavorites = true),
            localMutation = { localMutations++ },
            sourceMutation = { sourceMutations++ },
        )

        assertEquals(0, localMutations)
        assertEquals(1, sourceMutations)
    }

    private fun legacyTypedManga(): Manga = Manga(
        id = 7,
        source = LOCAL_SOURCE_ID,
        url = encodeTypedLocalPublicationUrl(publicationKey),
        title = "Fixture novel",
    )
}
