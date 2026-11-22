package dev.shinsou.kmp.ui

import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.LOCAL_SOURCE_ID
import dev.shinsou.kmp.domain.model.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.domain.model.ExtensionLibraryBindingV2
import dev.shinsou.kmp.domain.model.decodeExtensionLibraryPublicationUrl

/** Navigation decision for one row selected from the app-owned local library. */
internal sealed interface LocalLibraryExtensionRoute {
    /** The row belongs to the normal local/legacy detail flow. */
    data object LocalDetail : LocalLibraryExtensionRoute

    /** The exact extension authority is available and can load its live chapter list. */
    data class Open(
        val binding: ExtensionLibraryBindingV2,
        val migrateLegacyUrl: Boolean,
    ) : LocalLibraryExtensionRoute

    /** A beta-era UUID-only favorite needs a bounded exact-identity search. */
    data class RecoverLegacy(
        val publicationKey: PublicationKey,
    ) : LocalLibraryExtensionRoute
}

/**
 * Classifies local-library rows without network access.
 *
 * Imported TXT/EPUB and legacy projected books already have local chapters and must stay on the
 * local detail path. Only a UUID-only, zero-chapter row is eligible for the beta favorite repair.
 */
internal fun localLibraryExtensionRoute(
    manga: Manga,
    hasLocalChapters: Boolean,
    legacyBinding: (PublicationKey) -> ExtensionLibraryBindingV2?,
): LocalLibraryExtensionRoute {
    if (manga.source != LOCAL_SOURCE_ID) return LocalLibraryExtensionRoute.LocalDetail

    decodeExtensionLibraryPublicationUrl(manga.url)?.let { binding ->
        return LocalLibraryExtensionRoute.Open(binding, migrateLegacyUrl = false)
    }

    val publicationKey = decodeTypedLocalPublicationUrl(manga.url)
        ?: return LocalLibraryExtensionRoute.LocalDetail
    legacyBinding(publicationKey)?.let { binding ->
        return LocalLibraryExtensionRoute.Open(binding, migrateLegacyUrl = true)
    }
    if (hasLocalChapters) return LocalLibraryExtensionRoute.LocalDetail

    return LocalLibraryExtensionRoute.RecoverLegacy(publicationKey)
}
