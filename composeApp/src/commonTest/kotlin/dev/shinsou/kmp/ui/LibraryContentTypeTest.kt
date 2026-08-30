package dev.shinsou.kmp.ui

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2
import dev.shinsou.kmp.plugin.v2.extensionPublicationKey
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryContentTypeTest {
    private val novelSource = SourceKey(2, "zh.bilimanga", "zh.bilimanga.novel")
    private val mangaSource = SourceKey(2, "zh.bilimanga", "zh.bilimanga.manga")

    @Test
    fun extensionFavoriteUsesItsExactSourceDeclaration() {
        val sourceTypes = mapOf(
            novelSource to PluginContentType.NOVEL,
            mangaSource to PluginContentType.MANGA,
        )

        assertEquals(
            LibraryContentType.NOVEL,
            libraryContentType(extensionFavorite(novelSource), sourceTypes, typedPublicationKinds = { null }),
        )
        assertEquals(
            LibraryContentType.MANGA,
            libraryContentType(extensionFavorite(mangaSource), sourceTypes, typedPublicationKinds = { null }),
        )
    }

    @Test
    fun legacyDirectSourceUsesDeclaredSingleTypeButDoesNotCallFallbackBothMixed() {
        assertEquals(
            LibraryContentType.NOVEL,
            libraryContentType(
                Manga(source = 42),
                extensionSourceTypes = emptyMap(),
                legacySourceTypes = mapOf(42L to PluginContentType.NOVEL),
                typedPublicationKinds = { null },
            ),
        )
        assertEquals(
            LibraryContentType.UNKNOWN,
            libraryContentType(
                Manga(source = 42),
                extensionSourceTypes = emptyMap(),
                legacySourceTypes = mapOf(42L to PluginContentType.BOTH),
                typedPublicationKinds = { null },
            ),
        )
    }

    @Test
    fun betaTypedExtensionFavoriteUsesItsRecoveredExactBinding() {
        val remoteId = "https://example.test/title/42"
        val publicationKey = extensionPublicationKey(novelSource, remoteId)
        val binding = ExtensionLibraryBindingV2(publicationKey, novelSource, remoteId)

        assertEquals(
            LibraryContentType.NOVEL,
            libraryContentType(
                Manga(source = LOCAL_SOURCE_ID, url = encodeTypedLocalPublicationUrl(publicationKey)),
                extensionSourceTypes = mapOf(novelSource to PluginContentType.NOVEL),
                legacyExtensionBinding = { binding },
                typedPublicationKinds = { null },
            ),
        )
    }

    @Test
    fun typedLocalPublicationUsesItsManifestKinds() {
        val publicationKey = PublicationKey("25b77e54-2989-5a11-b91a-fdb4cf33d3df")
        val manga = Manga(
            source = LOCAL_SOURCE_ID,
            url = encodeTypedLocalPublicationUrl(publicationKey),
        )

        assertEquals(
            LibraryContentType.NOVEL,
            libraryContentType(
                manga,
                emptyMap(),
                typedPublicationKinds = { setOf(ContentKind.EPUB_SPINE) },
            ),
        )
        assertEquals(
            LibraryContentType.MANGA,
            libraryContentType(
                manga,
                emptyMap(),
                typedPublicationKinds = { setOf(ContentKind.IMAGE_SEQUENCE) },
            ),
        )
    }

    @Test
    fun legacyLocalImageImportIsMangaWithoutOpeningItsFiles() {
        assertEquals(
            LibraryContentType.MANGA,
            libraryContentType(
                Manga(source = LOCAL_SOURCE_ID, url = "local://manga/7"),
                extensionSourceTypes = emptyMap(),
                typedPublicationKinds = { error("Legacy image imports do not need the typed graph") },
            ),
        )
    }

    @Test
    fun missingAuthorityStaysUnknownInsteadOfGuessing() {
        assertEquals(
            LibraryContentType.UNKNOWN,
            libraryContentType(
                Manga(source = 42, title = "Definitely a novel"),
                emptyMap(),
                typedPublicationKinds = { null },
            ),
        )
    }

    private fun extensionFavorite(sourceKey: SourceKey): Manga = Manga(
        source = LOCAL_SOURCE_ID,
        url = encodeExtensionLibraryPublicationUrl(sourceKey, "https://example.test/title/42"),
    )
}
