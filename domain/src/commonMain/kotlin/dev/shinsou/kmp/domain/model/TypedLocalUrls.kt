package dev.shinsou.kmp.domain.model

/*
 * The identities local-library rows are stored under: the legacy source id of the local library
 * and the `local://typed/v1/` compatibility URLs that join legacy rows (history, sync records,
 * migrations) to typed Publication identity.
 */

/** Legacy source id of the app-local library. */
public const val LOCAL_SOURCE_ID: Long = 0L

fun isTypedLocalCompatibilityUrl(value: String): Boolean =
    value.startsWith(TYPED_LOCAL_URL_PREFIX)

/** Strict local-only decoder used to join compatibility history to typed unit identity. */
fun typedLocalChapterUnitKey(value: String): UnitKey? {
    val parts = value.takeIf { it.startsWith(TYPED_LOCAL_URL_PREFIX) }
        ?.removePrefix(TYPED_LOCAL_URL_PREFIX)
        ?.split('/')
        ?.takeIf { it.size == 3 }
        ?: return null
    // A parse probe: a reference that does not validate answers null.
    return runCatching {
        val publicationKey = PublicationKey(parts[0])
        require(PublicationKey.isPortableUuid(parts[1]))
        UnitKey(publicationKey, parts[2]).also(UnitKey::validate)
    }.getOrNull()
}

/** Returns the publication identity only for the publication-scoped compatibility URL. */
fun decodeTypedLocalPublicationUrl(value: String): PublicationKey? {
    val encoded = value.takeIf { it.startsWith(TYPED_LOCAL_URL_PREFIX) }
        ?.removePrefix(TYPED_LOCAL_URL_PREFIX)
        ?.takeIf { '/' !in it }
        ?: return null
    // A parse probe: a key that does not validate answers null.
    return runCatching { PublicationKey(encoded) }.getOrNull()
}

/**
 * Stable compatibility identity for one authoritative typed unit. The scheme name remains
 * historical: the referenced acquisition may be local or extension-backed, and [load] resolves
 * it exclusively through the typed Publication graph.
 */
fun encodeTypedLocalChapterUrl(
    publicationKey: PublicationKey,
    acquisitionId: String,
    unitKey: UnitKey,
): String {
    publicationKey.validate()
    require(PublicationKey.isPortableUuid(acquisitionId)) {
        "Typed acquisition id must be a portable UUID"
    }
    unitKey.validate()
    require(unitKey.publicationKey == publicationKey) {
        "Typed unit must belong to its URL publication"
    }
    return "$TYPED_LOCAL_URL_PREFIX${publicationKey.value}/$acquisitionId/${unitKey.value}"
}

/** Publication-scoped identity used by multi-unit legacy compatibility projections. */
fun encodeTypedLocalPublicationUrl(publicationKey: PublicationKey): String {
    publicationKey.validate()
    return "$TYPED_LOCAL_URL_PREFIX${publicationKey.value}"
}

const val TYPED_LOCAL_URL_PREFIX: String = "local://typed/v1/"
