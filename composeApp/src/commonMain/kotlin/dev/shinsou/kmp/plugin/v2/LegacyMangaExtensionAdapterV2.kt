@file:OptIn(ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.ImageLayout
import dev.shinsou.kmp.content.ImageProgression
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.CatalogueSource
import dev.shinsou.kmp.plugin.ConfigurableSource
import dev.shinsou.kmp.plugin.FilterList
import dev.shinsou.kmp.plugin.LoginSource
import dev.shinsou.kmp.plugin.PageRequestMetadata
import dev.shinsou.kmp.plugin.SChapter
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.SourcePreference
import dev.shinsou.kmp.plugin.resolveSourceHttpUrl
import dev.shinsou.kmp.reader.ReaderImageTransform

/** Resolves host-owned secret references without exposing a secret store to a v1 plugin. */
public fun interface LegacyLoginCredentialsResolverV2 {
    public suspend fun resolve(credentials: LoginCredentialsV2): LegacyLoginCredentialsV2?
}

public data class LegacyLoginCredentialsV2(
    val username: String,
    val password: String,
) {
    init {
        require(username.isNotBlank() && username.length <= 4_096 && username.none(Char::isISOControl))
        require(password.length <= 16_384 && password.none(Char::isISOControl))
    }
}

/**
 * Executable v1 manga compatibility adapter. The numeric id is retained only as the lossless
 * legacy projection; v2 lookup always uses the complete opaque [SourceKey].
 */
public class LegacyMangaExtensionSourceV2(
    private val source: CatalogueSource,
    packageId: String,
    private val credentialsResolver: LegacyLoginCredentialsResolverV2? = null,
    additionalContentKinds: Set<ContentKind> = emptySet(),
    additionalRepresentations: Iterable<UnitContentRepresentationProviderV2> = emptyList(),
    private val imageProgression: ImageProgression = ImageProgression.RIGHT_TO_LEFT,
    private val imageLayout: ImageLayout = ImageLayout.PAGE,
) : ExtensionSourceV2 {
    private val extraProviders = additionalRepresentations.toList()
    private val contentResolver = MultiRepresentationContentResolverV2(
        listOf(LegacyImageRepresentationProvider()) + extraProviders,
    )

    override val descriptor: SourceDescriptorV2 = SourceDescriptorV2(
        sourceKey = SourceKey.fromLegacy(packageId, source.id),
        displayName = source.name,
        languageTag = source.lang,
        supportedContentKinds = setOf(ContentKind.IMAGE_SEQUENCE) + additionalContentKinds,
        capabilities = buildSet {
            add(ExtensionCapability.CATALOGUE)
            add(ExtensionCapability.BROWSE)
            add(ExtensionCapability.SEARCH)
            add(ExtensionCapability.METADATA)
            add(ExtensionCapability.UNITS)
            add(ExtensionCapability.CONTENT)
            if (source.supportsLatest) add(ExtensionCapability.LATEST)
            if (source is LoginSource && source.supportsLogin && credentialsResolver != null) {
                add(ExtensionCapability.LOGIN)
            }
            if (source is ConfigurableSource) add(ExtensionCapability.PREFERENCES)
        },
        baseUrl = source.baseUrl.takeIf(String::isNotBlank),
    )

    init {
        require(extraProviders.isEmpty() == additionalContentKinds.isEmpty()) {
            "Additional representations and their declared content kinds must be supplied together"
        }
    }

    override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()

    override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
        source.getSearchManga(page, query, emptyList()).toV2Page()

    override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> =
        source.getLatestUpdates(page).toV2Page()

    override suspend fun browse(
        options: BrowseOptionsV2,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> {
        require(options.values.isEmpty()) { "Legacy manga browse does not accept v2 option keys" }
        return source.getPopularManga(page).toV2Page()
    }

    override suspend fun details(remotePublicationId: String): RemotePublicationV2 {
        val details = source.getMangaDetails(SManga(url = remotePublicationId, title = remotePublicationId))
        return RemotePublicationV2(
            remoteId = remotePublicationId,
            title = details.title,
            url = resolveSourceHttpUrl(source.baseUrl, details.url.ifBlank { remotePublicationId }),
        )
    }

    override suspend fun units(
        remotePublicationId: String,
        page: Int,
    ): PagedResultV2<RemoteUnitV2> {
        val chapters = source.getChapterList(SManga(url = remotePublicationId, title = remotePublicationId))
        require(chapters.size <= MAX_LEGACY_UNITS_V2) { "Legacy manga source returned too many units" }
        val fromLong = page.toLong() * LEGACY_PAGE_SIZE.toLong()
        if (fromLong >= chapters.size) return PagedResultV2(emptyList(), false)
        val from = fromLong.toInt()
        val to = minOf(chapters.size, from + LEGACY_PAGE_SIZE)
        return PagedResultV2(
            items = chapters.subList(from, to).map { it.toRemoteUnitV2() },
            hasNextPage = to < chapters.size,
        )
    }

    override suspend fun content(
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 = contentResolver.resolve(
        UnitContentRequestV2(descriptor.sourceKey, remotePublicationId, remoteUnitId),
    ).also { result ->
        require(result.representations.all { it.kind in descriptor.supportedContentKinds }) {
            "Legacy adapter returned an undeclared content kind"
        }
    }

    override suspend fun openTextStream(streamId: String): TextChunkStreamV2 =
        throw UnsupportedOperationException("Legacy manga sources do not expose text streams")

    override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 {
        val loginSource = source as? LoginSource ?: return LoginResultV2(false)
        val resolved = credentialsResolver?.resolve(credentials) ?: return LoginResultV2(false)
        return LoginResultV2(loginSource.login(resolved.username, resolved.password))
    }

    override suspend fun logout() {
        (source as? LoginSource)?.logout()
    }

    override suspend fun preferences(): List<PreferenceV2> =
        (source as? ConfigurableSource)?.getPreferenceDefinitions().orEmpty().map(SourcePreference::toV2)

    override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit =
        throw UnsupportedOperationException("Legacy manga source does not implement favorite mutation")

    private inner class LegacyImageRepresentationProvider : UnitContentRepresentationProviderV2 {
        override val representationId: String = "legacy-image-sequence"

        override suspend fun load(request: UnitContentRequestV2): UnitContentPayload? {
            val pages = source.getPageList(SChapter(url = request.remoteUnitId, name = request.remoteUnitId))
            if (pages.isEmpty()) return null
            return UnitContentPayload.ImageSequence(
                schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
                representationId = representationId,
                sourceKey = request.sourceKey,
                remoteUnitId = request.remoteUnitId,
                pages = pages.mapIndexed { ordinal, page ->
                    val parsed = PageRequestMetadata.parse(page.imageUrl ?: page.url)
                    val resolvedUrl = requireNotNull(resolveSourceHttpUrl(source.baseUrl, parsed.cleanUrl)) {
                        "Legacy manga page URL is not a safe HTTP(S) resource"
                    }
                    ImagePageV2(
                        resourceId = "page-${page.index}-$ordinal",
                        request = RemoteRequestPlanV2(
                            method = HttpMethodV2.GET,
                            url = resolvedUrl,
                            headerHints = (source.headers + parsed.headers).safeV2HeaderHints(),
                        ),
                        mediaType = resolvedUrl.inferredImageMediaType(),
                        transform = parsed.imageTransform(source.id)?.toV2(),
                        layout = imageLayout,
                    )
                },
                progression = imageProgression,
                layout = imageLayout,
            )
        }
    }

    private fun dev.shinsou.kmp.plugin.MangasPage.toV2Page(): PagedResultV2<RemotePublicationV2> {
        val bounded = mangas.take(LEGACY_PAGE_SIZE)
        return PagedResultV2(
            items = bounded.map { it.toRemotePublicationV2() },
            hasNextPage = hasNextPage || mangas.size > bounded.size,
        )
    }

    private fun SManga.toRemotePublicationV2(): RemotePublicationV2 = RemotePublicationV2(
        remoteId = url,
        title = title,
        url = resolveSourceHttpUrl(source.baseUrl, url),
    )

    private fun SChapter.toRemoteUnitV2(): RemoteUnitV2 = RemoteUnitV2(
        remoteId = url,
        title = name,
        url = resolveSourceHttpUrl(source.baseUrl, url),
    )
}

/** Builds one v2 package over every live source of an existing Shinsou v1 plugin. */
public class LegacyMangaPackageRuntimeV2(
    packageId: String,
    version: String,
    displayName: String,
    sources: Iterable<CatalogueSource>,
    credentialsResolver: LegacyLoginCredentialsResolverV2? = null,
) : ExtensionPackageRuntimeV2 {
    private val delegate: ImmutableExtensionPackageRuntimeV2

    init {
        val adapters = sources.map { source ->
            LegacyMangaExtensionSourceV2(source, packageId, credentialsResolver)
        }
        require(adapters.isNotEmpty()) { "Legacy manga package needs at least one source" }
        val packageDescriptor = ExtensionPackageV2(
            contractVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
            packageId = packageId,
            version = version,
            displayName = displayName,
            sources = adapters.map(LegacyMangaExtensionSourceV2::descriptor),
            supportedContentKinds = setOf(ContentKind.IMAGE_SEQUENCE),
        )
        delegate = ImmutableExtensionPackageRuntimeV2(packageDescriptor, adapters)
    }

    override val descriptor: ExtensionPackageV2 get() = delegate.descriptor
    override fun source(sourceKey: SourceKey): ExtensionSourceV2? = delegate.source(sourceKey)
}

private fun SourcePreference.toV2(): PreferenceV2 = when (this) {
    is SourcePreference.TextField -> PreferenceV2(key, title, defaultValue)
    is SourcePreference.Toggle -> PreferenceV2(key, title, defaultValue.toString())
    is SourcePreference.Select -> PreferenceV2(key, title, defaultValue)
    is SourcePreference.MultiSelect -> PreferenceV2(key, title, defaultValues.sorted().joinToString(","))
}

private fun Map<String, String>.safeV2HeaderHints(): Map<String, String> = entries.mapNotNull { (name, value) ->
    val safeName = name.lowercase()
    if (safeName !in LEGACY_SAFE_HEADER_HINTS || value.length > 1_024 || value.any(Char::isISOControl)) null
    else name to value
}.toMap()

private fun ReaderImageTransform.toV2(): ImageTransformPlanV2 = when (this) {
    is ReaderImageTransform.ReverseVerticalSegments -> ImageTransformPlanV2(
        transformId = "reverse-vertical-segments",
        parameters = mapOf("segmentCount" to segmentCount.toString()),
    )
}

private fun String.inferredImageMediaType(): String = when (substringBefore('?').substringAfterLast('.').lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "avif" -> "image/avif"
    else -> "image/*"
}

private const val LEGACY_PAGE_SIZE: Int = 100
private const val MAX_LEGACY_UNITS_V2: Int = 100_000
private val LEGACY_SAFE_HEADER_HINTS: Set<String> = setOf(
    "accept",
    "accept-language",
    "content-type",
    "referer",
    "user-agent",
    "origin",
    "x-requested-with",
)
