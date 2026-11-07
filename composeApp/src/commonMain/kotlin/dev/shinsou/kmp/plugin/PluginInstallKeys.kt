package dev.shinsou.kmp.plugin


/**
 * ADR-0002 D6: the local identity of an installed package, so the same `packageId` from two
 * publishers can be installed side by side.
 *
 * The install key replaces the bare package id everywhere this app keys a package locally —
 * package store, runtimes, grants, trust tokens, sandboxes, and the `packageId` of its
 * [dev.shinsou.kmp.domain.model.SourceKey]s — so persisted formats do not change:
 *
 * - a package without a publisher key, or installed from the original community repository URL,
 *   keeps its bare `packageId` (existing library rows, progress and downloads stay bound);
 * - any other package becomes `packageId~<first 16 hex digits of the publisher key's digest>`.
 *
 * The rule depends only on the package id, the publisher key and the repository URL, so every
 * device derives the same key and synced library rows stay bound to the same package.
 */
internal object PluginInstallKeys {
    const val SEPARATOR: Char = '~'
    private const val PUBLISHER_HEX_DIGITS = 16

    /** The longest published package id that still fits an install key (artifact ids are capped at 128). */
    const val MAX_PUBLISHED_PACKAGE_ID_LENGTH: Int = 128 - 1 - PUBLISHER_HEX_DIGITS

    fun installKey(packageId: String, publisherId: String?, fromLegacyCommunityRepository: Boolean): String {
        require(SEPARATOR !in packageId) { "Plugin package ids cannot contain '$SEPARATOR'" }
        if (publisherId == null || fromLegacyCommunityRepository) return packageId
        require(packageId.length <= MAX_PUBLISHED_PACKAGE_ID_LENGTH) { "Plugin package id is too long" }
        return "$packageId$SEPARATOR${publisherDigits(publisherId)}"
    }

    /** ADR-0002 §8.1: what an unsigned package from a developer-mode repository is scoped by. */
    const val DEVELOPER_SCOPE: String = "dev"

    /**
     * The key of an unsigned package loaded in developer mode: its own identity, so it never
     * replaces (or is mistaken for) a signed install of the same package id.
     */
    fun developerInstallKey(packageId: String): String {
        require(SEPARATOR !in packageId) { "Plugin package ids cannot contain '$SEPARATOR'" }
        require(packageId.length <= MAX_PUBLISHED_PACKAGE_ID_LENGTH) { "Plugin package id is too long" }
        return "$packageId$SEPARATOR$DEVELOPER_SCOPE"
    }

    /** True for a package installed from a developer-mode repository. */
    fun isDeveloperKey(installKey: String): Boolean = installKey.substringAfter(SEPARATOR, "") == DEVELOPER_SCOPE

    /** True for a key that is scoped to a publisher (its sources get projected numeric ids). */
    fun isPublisherScoped(installKey: String): Boolean = SEPARATOR in installKey

    /** The package id the repository published, which the install key is derived from. */
    fun publishedPackageId(installKey: String): String = installKey.substringBefore(SEPARATOR)

    /**
     * The numeric source id of a publisher-scoped source: a deterministic function of its exact
     * identity, the same on every device (sync keys library rows by it). Kept in the upper half of
     * the positive range, away from small hand-picked ids; collisions are still refused by the
     * source reservation check.
     */
    fun projectedSourceId(installKey: String, sourceId: String): Long {
        val digest = Sha256.hex("shinsou-source-projection-v1\u0000$installKey\u0000$sourceId".encodeToByteArray())
        val value = digest.take(16).toULong(16).toLong()
        return (value and 0x3FFF_FFFF_FFFF_FFFFL) or 0x4000_0000_0000_0000L
    }

    private fun publisherDigits(publisherId: String): String =
        Sha256.hex(publisherId.encodeToByteArray()).take(PUBLISHER_HEX_DIGITS)
}
