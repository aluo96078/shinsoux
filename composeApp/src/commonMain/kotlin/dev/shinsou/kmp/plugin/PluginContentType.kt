package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.content.ContentKind

/**
 * The kind of catalogue a source is expected to expose.
 *
 * `BOTH` is intentionally the compatibility fallback. Older Shinsou and ShuYue manifests did
 * not carry a type field, and treating an unknown value as a novel-only or manga-only source
 * would hide otherwise usable sources from the catalogue.
 */
public enum class PluginContentType {
    MANGA,
    NOVEL,
    BOTH,
    ;

    public companion object {
        public fun parse(value: String?): PluginContentType =
            parseOrNull(value) ?: BOTH

        public fun parseOrNull(value: String?): PluginContentType? = when (
            value?.trim()?.lowercase()?.replace('-', '_')
        ) {
            "manga", "comic", "comics" -> MANGA
            "novel", "light_novel", "lightnovel", "book", "books" -> NOVEL
            "both", "all", "mixed", "any" -> BOTH
            null, "" -> null
            else -> null
        }

        /** Returns the least restrictive type that can represent both inputs. */
        public fun merge(first: PluginContentType, second: PluginContentType): PluginContentType =
            if (first == second) first else BOTH

        public fun resolve(
            packageType: String?,
            sourceTypes: Iterable<String?>,
        ): PluginContentType {
            val explicitSourceTypes = sourceTypes.mapNotNull(::parseOrNull).distinct()
            val explicitPackageType = parseOrNull(packageType)
            return when {
                explicitPackageType != null && explicitSourceTypes.isEmpty() -> explicitPackageType
                explicitPackageType == null && explicitSourceTypes.isEmpty() -> BOTH
                explicitPackageType == null -> explicitSourceTypes.reduce(::merge)
                else -> explicitSourceTypes.fold(explicitPackageType, ::merge)
            }
        }
    }
}

/** The wire-level repository family detected from the fetched document, never from its URL. */
public enum class PluginRepositoryFormat {
    SHINSOU,
    SHUYUE,
    UNIFIED,
}

public fun SourceIndexEntry.contentType(): PluginContentType =
    PluginContentType.parse(contentType ?: type)

internal fun resolveEntryContentType(
    packageType: String?,
    packageContentType: String?,
    sources: Iterable<SourceIndexEntry>,
): PluginContentType = PluginContentType.resolve(
    packageType = packageContentType ?: packageType,
    sourceTypes = sources.map { it.contentType ?: it.type },
)

public fun Set<ContentKind>.toPluginContentType(): PluginContentType {
    val manga = ContentKind.IMAGE_SEQUENCE in this
    val novel = ContentKind.PLAIN_TEXT in this || ContentKind.EPUB_SPINE in this
    return when {
        manga && novel -> PluginContentType.BOTH
        manga -> PluginContentType.MANGA
        novel -> PluginContentType.NOVEL
        else -> PluginContentType.BOTH
    }
}
