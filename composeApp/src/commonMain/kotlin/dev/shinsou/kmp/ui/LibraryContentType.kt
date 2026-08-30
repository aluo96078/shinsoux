package dev.shinsou.kmp.ui

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.local.decodeTypedLocalPublicationUrl
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.plugin.v2.ExtensionLibraryBindingV2
import dev.shinsou.kmp.plugin.v2.decodeExtensionLibraryPublicationUrl

/** User-facing distinction shown on every library title. */
public enum class LibraryContentType {
    MANGA,
    NOVEL,
    MIXED,
    UNKNOWN,
}

/**
 * Resolves a library row from authoritative metadata only.
 *
 * Extension favorites carry an exact source key, typed local imports carry a publication key,
 * and legacy image imports have their own URL namespace. No title/source-name heuristics are used.
 */
internal fun libraryContentType(
    manga: Manga,
    extensionSourceTypes: Map<SourceKey, PluginContentType>,
    legacySourceTypes: Map<Long, PluginContentType> = emptyMap(),
    legacyExtensionBinding: (PublicationKey) -> ExtensionLibraryBindingV2? = { null },
    typedPublicationKinds: (PublicationKey) -> Set<ContentKind>?,
): LibraryContentType {
    decodeExtensionLibraryPublicationUrl(manga.url)?.let { binding ->
        return extensionSourceTypes[binding.sourceKey].toLibraryContentType(bothIsMixed = true)
    }

    if (manga.source != LOCAL_SOURCE_ID) {
        return legacySourceTypes[manga.source].toLibraryContentType(bothIsMixed = false)
    }

    if (manga.source == LOCAL_SOURCE_ID && manga.url.startsWith(LOCAL_IMAGE_PUBLICATION_URL_PREFIX)) {
        return LibraryContentType.MANGA
    }

    if (manga.source == LOCAL_SOURCE_ID) {
        decodeTypedLocalPublicationUrl(manga.url)?.let { publicationKey ->
            legacyExtensionBinding(publicationKey)?.let { binding ->
                return extensionSourceTypes[binding.sourceKey].toLibraryContentType(bothIsMixed = true)
            }
            typedPublicationKinds(publicationKey)?.let { return it.toLibraryContentType() }
        }
    }

    return LibraryContentType.UNKNOWN
}

private fun PluginContentType?.toLibraryContentType(bothIsMixed: Boolean): LibraryContentType = when (this) {
    PluginContentType.MANGA -> LibraryContentType.MANGA
    PluginContentType.NOVEL -> LibraryContentType.NOVEL
    PluginContentType.BOTH -> if (bothIsMixed) LibraryContentType.MIXED else LibraryContentType.UNKNOWN
    null -> LibraryContentType.UNKNOWN
}

private fun Set<ContentKind>.toLibraryContentType(): LibraryContentType {
    val hasManga = ContentKind.IMAGE_SEQUENCE in this
    val hasNovel = ContentKind.PLAIN_TEXT in this || ContentKind.EPUB_SPINE in this
    return when {
        hasManga && hasNovel -> LibraryContentType.MIXED
        hasManga -> LibraryContentType.MANGA
        hasNovel -> LibraryContentType.NOVEL
        else -> LibraryContentType.UNKNOWN
    }
}

private const val LOCAL_IMAGE_PUBLICATION_URL_PREFIX: String = "local://manga/"
