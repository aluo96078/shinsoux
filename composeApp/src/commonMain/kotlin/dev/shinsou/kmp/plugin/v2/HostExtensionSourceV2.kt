package dev.shinsou.kmp.plugin.v2

// R4-4 (docs/architecture/0006): moved out of ExtensionV2Contracts.kt without changing it.

@OptIn(ExtensionImplementationApi::class)
public class HostExtensionSourceV2 internal constructor(
    public val descriptor: SourceDescriptorV2,
    private val implementation: ExtensionSourceV2,
) {
    public suspend fun <T> withUserInteractionContext(block: suspend () -> T): T =
        (implementation as? UserInteractionScopedExtensionSourceV2)?.withUserInteractionContext(block) ?: block()
    public fun setHostUiAvailable(available: Boolean) {
        (implementation as? UserInteractionScopedExtensionSourceV2)?.setHostUiAvailable(available)
    }
    public fun webChallengeUserAgent(): String? {
        val userAgent = (implementation as? WebChallengeUserAgentSourceV2)
            ?.webChallengeUserAgent
            ?: return null
        require(userAgent.isNotBlank()) { "Web challenge User-Agent must not be blank" }
        requireHeaderHint("User-Agent", userAgent)
        return userAgent
    }
    public fun webChallengeUrl(): String? {
        val url = (implementation as? WebChallengeUserAgentSourceV2)
            ?.webChallengeUrl
            ?: return null
        require(url.isNotBlank()) { "Web challenge URL must not be blank" }
        requireRemoteUri(url, "Web challenge URL")
        return url
    }
    public fun webChallengeLocalStorageKeys(): Set<String> =
        (implementation as? WebChallengeUserAgentSourceV2)
            ?.webChallengeLocalStorageKeys
            .orEmpty()
    public fun requiredWebChallengeLocalStorageKeys(): Set<String> =
        (implementation as? WebChallengeUserAgentSourceV2)
            ?.requiredWebChallengeLocalStorageKeys
            .orEmpty()
    public fun webChallengeBrowserSessionOrigins(): Set<String> =
        (implementation as? WebChallengeUserAgentSourceV2)
            ?.browserSessionOrigins
            .orEmpty()

    public suspend fun browseOptions(): BrowseOptionsSchemaV2 {
        requireCapability(ExtensionCapability.BROWSE)
        return implementation.browseOptions().also(::validateBrowseSchema)
    }

    public suspend fun getFilterList(): BrowseFilterListV2 {
        requireCapability(ExtensionCapability.BROWSE)
        return implementation.getFilterList().also { filters ->
            validateBrowseFilters(filters)
        }
    }

    public suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.SEARCH)
        requireQuery(query)
        requirePage(page)
        return implementation.search(query, page).also(::validatePublicationPage)
    }

    public suspend fun search(
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.SEARCH)
        requireQuery(query)
        validateBrowseOptions(options)
        requirePage(page)
        return implementation.search(query, page, options).also(::validatePublicationPage)
    }

    public suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.LATEST)
        requirePage(page)
        return implementation.latest(page).also(::validatePublicationPage)
    }

    public suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> {
        requireCapability(ExtensionCapability.BROWSE)
        validateBrowseOptions(options)
        requirePage(page)
        return implementation.browse(options, page).also(::validatePublicationPage)
    }

    public suspend fun details(remotePublicationId: String): RemotePublicationV2 {
        requireCapability(ExtensionCapability.METADATA)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        return implementation.details(remotePublicationId).also { result ->
            validateRemotePublication(result)
            require(result.remoteId == remotePublicationId) { "Extension details changed remote identity" }
        }
    }

    public suspend fun units(remotePublicationId: String, page: Int): PagedResultV2<RemoteUnitV2> {
        requireCapability(ExtensionCapability.UNITS)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        requirePage(page)
        return implementation.units(remotePublicationId, page).also(::validateUnitPage)
    }

    public suspend fun content(remotePublicationId: String, remoteUnitId: String): UnitContentResultV2 {
        requireCapability(ExtensionCapability.CONTENT)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        requireSafeIdentifier(remoteUnitId, "Remote unit id")
        return implementation.content(remotePublicationId, remoteUnitId).also { result ->
            result.validate()
            require(result.sourceKey == descriptor.sourceKey &&
                result.remotePublicationId == remotePublicationId && result.remoteUnitId == remoteUnitId) {
                "Extension content result escaped the requested source/publication/unit scope"
            }
            result.representations.forEach { payload ->
                require(payload.kind in descriptor.supportedContentKinds) {
                    "Extension returned an undeclared content kind"
                }
            }
        }
    }

    public suspend fun openTextStream(plan: TextPayloadSourceV2.ChunkedTextPayload): HostTextChunkStreamV2 {
        requireCapability(ExtensionCapability.CONTENT)
        plan.validateChunked()
        val stream = implementation.openTextStream(plan.streamId)
        return HostTextChunkStreamV2(plan, stream)
    }

    public suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 {
        requireCapability(ExtensionCapability.LOGIN)
        validateCredentials(credentials)
        return implementation.login(credentials)
    }

    public suspend fun logout(): Unit {
        requireCapability(ExtensionCapability.LOGIN)
        implementation.logout()
    }

    public suspend fun preferences(): List<PreferenceV2> {
        requireCapability(ExtensionCapability.PREFERENCES)
        return implementation.preferences().also { preferences ->
            require(preferences.size <= MAX_PREFERENCES) { "Extension returned too many preferences" }
            require(preferences.map(PreferenceV2::key).distinct().size == preferences.size) {
                "Extension returned duplicate preference keys"
            }
            preferences.forEach(::validatePreference)
        }
    }

    public suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit {
        requireCapability(ExtensionCapability.FAVORITE)
        requireSafeIdentifier(remotePublicationId, "Remote publication id")
        implementation.favorite(remotePublicationId, favorite)
    }

    private fun requireCapability(capability: ExtensionCapability) {
        descriptor.requireCapability(capability)
    }
}

/**
 * Cursor stream guard owned by the host. It prevents cursor cycles and enforces both per-chunk
 * and aggregate limits even when an extension implementation keeps returning valid small chunks.
 */
@OptIn(ExtensionImplementationApi::class)
public class HostTextChunkStreamV2 internal constructor(
    public val plan: TextPayloadSourceV2.ChunkedTextPayload,
    private val implementation: TextChunkStreamV2,
) : TextChunkStreamV2 {
    private val seenCursors = HashSet<String?>()
    private var expectedCursor: String? = plan.firstCursor
    private var chunkCount: Int = 0
    private var totalBytes: Long = 0
    private var terminal: Boolean = false
    private var cancelled: Boolean = false

    override val maxChunkBytes: Int

    init {
        try {
            require(implementation.maxChunkBytes in 1..plan.maxChunkBytes) {
                "Extension text stream declared an invalid chunk bound"
            }
            maxChunkBytes = implementation.maxChunkBytes
        } catch (error: Throwable) {
            runCatching { implementation.cancel() }
            throw error
        }
    }

    override suspend fun next(cursor: String?): TextChunkResultV2 {
        check(!cancelled) { "Text stream is cancelled" }
        check(!terminal) { "Text stream is complete" }
        require(cursor == expectedCursor) { "Text stream cursor does not match the host continuation" }
        if (!seenCursors.add(cursor)) return fail("Text stream cursor cycle detected")
        return try {
            val result = implementation.next(cursor)
            result.validate(maxChunkBytes)
            val bytes = result.utf8Text.encodeToByteArray().size.toLong()
            if (bytes > Long.MAX_VALUE - totalBytes) return fail("Text stream byte count overflow")
            totalBytes += bytes
            chunkCount++
            if (totalBytes > plan.maxTotalBytes) return fail("Text stream exceeded its aggregate byte limit")
            if (chunkCount > plan.maxChunks || chunkCount == plan.maxChunks && !result.done) {
                return fail("Text stream exceeded its chunk limit")
            }
            if (result.nextCursor != null && result.nextCursor in seenCursors) {
                return fail("Text stream cursor cycle detected")
            }
            expectedCursor = result.nextCursor
            terminal = result.done
            result
        } catch (error: Throwable) {
            cancelSafely()
            throw error
        }
    }

    override fun cancel(): Unit {
        if (cancelled) return
        cancelled = true
        implementation.cancel()
    }

    private fun fail(message: String): Nothing {
        cancelSafely()
        throw IllegalArgumentException(message)
    }

    private fun cancelSafely() {
        runCatching { cancel() }
    }
}

private fun validateBrowseSchema(schema: BrowseOptionsSchemaV2) {
    schema.validate()
}

private fun validateBrowseOptions(options: BrowseOptionsV2) {
    options.validate()
}

private fun validatePublicationPage(page: PagedResultV2<RemotePublicationV2>) {
    require(page.items.size <= MAX_PAGE_ITEMS) { "Extension publication page is too large" }
    require(page.items.map(RemotePublicationV2::remoteId).distinct().size == page.items.size) {
        "Extension publication page contains duplicate identities"
    }
    page.items.forEach(::validateRemotePublication)
}

private fun validateUnitPage(page: PagedResultV2<RemoteUnitV2>) {
    require(page.items.size <= MAX_PAGE_ITEMS) { "Extension unit page is too large" }
    require(page.items.map(RemoteUnitV2::remoteId).distinct().size == page.items.size) {
        "Extension unit page contains duplicate identities"
    }
    page.items.forEach(RemoteUnitV2::validate)
}

private fun validateRemotePublication(publication: RemotePublicationV2) {
    publication.validate()
}

private fun validateCredentials(credentials: LoginCredentialsV2) {
    credentials.validate()
}

private fun validatePreference(preference: PreferenceV2) {
    preference.validate()
}

private fun requireQuery(query: String) {
    require(query.isNotBlank() && query.length <= MAX_QUERY_LENGTH && query.none(Char::isISOControl)) {
        "Search query must be bounded and printable"
    }
}

private const val MAX_QUERY_LENGTH = 4_096

private const val MAX_PREFERENCES = 256
