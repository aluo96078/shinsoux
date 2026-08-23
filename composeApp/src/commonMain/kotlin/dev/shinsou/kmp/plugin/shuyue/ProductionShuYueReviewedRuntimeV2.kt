@file:OptIn(dev.shinsou.kmp.plugin.v2.ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.concurrent.SynchronousLock
import dev.shinsou.kmp.concurrent.withLock
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.PluginCookie
import dev.shinsou.kmp.plugin.PluginCredential
import dev.shinsou.kmp.plugin.PluginLoginRequester
import dev.shinsou.kmp.plugin.submitLegacyLoginCompatibility
import dev.shinsou.kmp.plugin.events.PluginArtifactIdentity
import dev.shinsou.kmp.plugin.PluginManifest
import dev.shinsou.kmp.plugin.PluginStorage
import dev.shinsou.kmp.plugin.SChapter
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.ScriptPluginEnvironment
import dev.shinsou.kmp.plugin.ScriptPluginRuntime
import dev.shinsou.kmp.plugin.ScriptPluginRuntimeFactory
import dev.shinsou.kmp.plugin.SourceIndexEntry
import dev.shinsou.kmp.plugin.events.BoundPluginScope
import dev.shinsou.kmp.plugin.events.BoundPluginScopeFactory
import dev.shinsou.kmp.plugin.events.PluginEventRuntimeStatus
import dev.shinsou.kmp.plugin.events.PluginHostPermission
import dev.shinsou.kmp.plugin.events.PluginRuntimeLifecycle
import dev.shinsou.kmp.plugin.events.PluginSystemEventGateway
import dev.shinsou.kmp.plugin.resolveSourceHttpUrl
import dev.shinsou.kmp.plugin.toBrowseFilterV2
import dev.shinsou.kmp.plugin.toPluginFilter
import dev.shinsou.kmp.plugin.v2.BrowseOptionsSchemaV2
import dev.shinsou.kmp.plugin.v2.BrowseOptionsV2
import dev.shinsou.kmp.plugin.v2.BrowseFilterListV2
import dev.shinsou.kmp.plugin.v2.CloseableExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.ExtensionPackageV2
import dev.shinsou.kmp.plugin.v2.ExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.UserInteractionScopedExtensionSourceV2
import dev.shinsou.kmp.plugin.v2.ArtifactBoundExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.ImmutableExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.LegacyLoginCredentialsResolverV2
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.plugin.v2.LoginResultV2
import dev.shinsou.kmp.plugin.v2.PagedResultV2
import dev.shinsou.kmp.plugin.v2.PreferenceV2
import dev.shinsou.kmp.plugin.v2.RemotePublicationV2
import dev.shinsou.kmp.plugin.v2.RemoteUnitV2
import dev.shinsou.kmp.plugin.v2.SourceDescriptorV2
import dev.shinsou.kmp.plugin.v2.TextChunkStreamV2
import dev.shinsou.kmp.plugin.v2.TextPayloadSourceV2
import dev.shinsou.kmp.plugin.v2.UnitContentPayload
import dev.shinsou.kmp.plugin.v2.UnitContentResultV2
import dev.shinsou.kmp.plugin.v2.SourceLifecycleControlledExtensionPackageRuntimeV2
import dev.shinsou.kmp.plugin.v2.toBoundedRemoteMetadata
import dev.shinsou.kmp.plugin.v2.toOptionalBoundedRemoteMetadata
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Resolves a process-local v1 engine/storage scope for one reviewed opaque source identity.
 * The resulting Long is deliberately absent from [SourceKey] and is never portable authority.
 */
public fun interface ShuYueExecutionScopeResolverV2 {
    public fun resolve(identity: ShuYueArtifactIdentityV2, sourceKey: SourceKey): Long
}

/** Fixed, non-authoritative engine scopes for the reviewed built-in ShuYue packages. */
public object BuiltInShuYueExecutionScopesV2 : ShuYueExecutionScopeResolverV2 {
    override fun resolve(identity: ShuYueArtifactIdentityV2, sourceKey: SourceKey): Long {
        require(identity.packageId == sourceKey.packageId) { "ShuYue execution scope package mismatch" }
        return requireNotNull(SCOPES[sourceKey.packageId to sourceKey.sourceId]) {
            "No host-local execution scope is assigned to ${sourceKey.canonicalId}"
        }
    }

    private val SCOPES: Map<Pair<String, String>, Long> = mapOf(
        ("zh.wenku8" to "zh.wenku8") to -9_110_000_000_000_001L,
        ("zh.wenku8.api" to "zh.wenku8.api") to -9_110_000_000_000_002L,
        ("zh.biquge.tw" to "zh.biquge.tw") to -9_110_000_000_000_003L,
    )
}

/** Host construction path pairing admission gates with the concrete platform runtime factory. */
public fun productionShuYueReviewedAdmissionV2(
    quarantineStore: ShuYueScriptQuarantineStoreV2,
    trustStore: ShuYueScriptTrustStoreV2,
    permissionStore: ShuYueScriptPermissionStoreV2,
    runtimeFactory: ScriptPluginRuntimeFactory,
    environment: ScriptPluginEnvironment,
    credentialsResolver: LegacyLoginCredentialsResolverV2? = null,
    executionScopes: ShuYueExecutionScopeResolverV2 = BuiltInShuYueExecutionScopesV2,
    reviewedProfiles: List<ShuYueReviewedPluginProfileV2> = ShuYueReviewedPluginCatalogV2.profiles,
): ShuYueReviewedPluginAdmissionV2 = ShuYueReviewedPluginAdmissionV2(
    quarantineStore = quarantineStore,
    trustStore = trustStore,
    permissionStore = permissionStore,
    runtimeFactory = ProductionShuYueReviewedRuntimeFactoryV2(
        runtimeFactory = runtimeFactory,
        environment = environment,
        credentialsResolver = credentialsResolver,
        executionScopes = executionScopes,
    ),
    reviewedProfiles = reviewedProfiles,
)

/**
 * Production factory for the exact reviewed ShuYue scripts.
 *
 * Original bytes are decoded and evaluated only after admission. A constant host shim adapts the
 * reviewed `search/latest/browse/chapters/chapterText` contract to the existing platform engines;
 * the returned v2 runtime still exposes only the reviewed opaque [SourceKey].
 */
public class ProductionShuYueReviewedRuntimeFactoryV2(
    private val runtimeFactory: ScriptPluginRuntimeFactory,
    private val environment: ScriptPluginEnvironment,
    private val credentialsResolver: LegacyLoginCredentialsResolverV2? = null,
    private val executionScopes: ShuYueExecutionScopeResolverV2 = BuiltInShuYueExecutionScopesV2,
) : ShuYueReviewedRuntimeFactoryV2 {
    private var nextEventRuntimeGeneration: Long = 0

    override suspend fun create(artifact: ShuYueAdmittedScriptV2): CloseableExtensionPackageRuntimeV2 {
        require(ShuYueExecutionPermissionV2.EXECUTE_SCRIPT in artifact.grantedPermissions) {
            "Reviewed ShuYue runtime lacks script execution permission"
        }
        require(ShuYueExecutionPermissionV2.NETWORK in artifact.grantedPermissions) {
            "Reviewed ShuYue runtime lacks network permission"
        }
        val descriptor = artifact.descriptor
        descriptor.validate()
        val sourceDescriptor = descriptor.sources.singleOrNull()
            ?: throw IllegalArgumentException("A reviewed ShuYue script must declare exactly one source")
        require(sourceDescriptor.sourceKey.packageId == artifact.identity.packageId) {
            "Reviewed ShuYue descriptor package mismatch"
        }

        val eventGateway = environment.systemEventSink as? PluginSystemEventGateway
        val eventContextRegistry = environment.systemEventContextRegistry ?: eventGateway?.contextRegistry
        val eventRuntimeGeneration = ++nextEventRuntimeGeneration
        val eventBinding = artifact.systemEvents?.let { declaration ->
            eventGateway?.let { gateway ->
                val scope = BoundPluginScopeFactory().bind(
                    artifactIdentity = PluginArtifactIdentity(
                        packageId = artifact.identity.packageId,
                        version = artifact.identity.version,
                        versionCode = artifact.identity.versionCode,
                        sha256 = artifact.identity.sha256,
                    ),
                    sourceKey = sourceDescriptor.sourceKey,
                    runtimeInstanceId = "shuyue-${artifact.identity.packageId}-${artifact.identity.sha256.take(16)}",
                    runtimeGeneration = eventRuntimeGeneration,
                )
                val requestedHostPermissions = if (
                    ShuYueExecutionPermissionV2.LOGIN_PROMPT in artifact.grantedPermissions
                ) {
                    setOf(PluginHostPermission.REQUEST_LOGIN_UI)
                } else {
                    emptySet()
                }
                gateway.grantRuntimePermissions(scope, requestedHostPermissions)
                val negotiation = gateway.negotiate(scope, declaration)
                if (!negotiation.enabled) {
                    gateway.revokeRuntimePermissions(scope)
                    null
                } else {
                    gateway.openRuntime(
                        scope,
                        PluginEventRuntimeStatus(
                            lifecycle = PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED,
                            sourceCapabilities = buildSet {
                                add("CATALOGUE")
                                if (ExtensionCapability.LOGIN in sourceDescriptor.capabilities) add("LOGIN")
                                if (ExtensionCapability.LATEST in sourceDescriptor.capabilities) add("LATEST")
                            },
                        ),
                    )
                    ReviewedEventBinding(
                        gateway = gateway,
                        scope = scope,
                        negotiation = negotiation,
                        contextRegistry = eventContextRegistry,
                    )
                }
            }
        }

        val executionScope = executionScopes.resolve(artifact.identity, sourceDescriptor.sourceKey)
        val sourceEntry = SourceIndexEntry(
            name = sourceDescriptor.displayName,
            lang = sourceDescriptor.languageTag,
            id = executionScope,
            baseUrl = sourceDescriptor.baseUrl,
        )
        val manifest = PluginManifest(
            id = artifact.identity.packageId,
            name = descriptor.displayName,
            version = artifact.identity.version,
            versionCode = artifact.identity.versionCode,
            lang = sourceDescriptor.languageTag,
            script = "${artifact.identity.packageId}.reviewed.js",
            signature = artifact.identity.sha256,
            sources = listOf(sourceEntry),
            systemEvents = artifact.systemEvents,
            requestedHostPermissions = if (ShuYueExecutionPermissionV2.LOGIN_PROMPT in artifact.grantedPermissions) {
                setOf(PluginHostPermission.REQUEST_LOGIN_UI)
            } else {
                emptySet()
            },
        )
        val filteredStorage = CapabilityFilteredShuYueStorage(
            delegate = environment.storage,
            permissions = artifact.grantedPermissions,
        )
        val scopedEnvironment = environment.copy(
            network = environment.network.scopedToStorage(filteredStorage),
            storage = filteredStorage,
            loginRequester = if (ShuYueExecutionPermissionV2.LOGIN_PROMPT in artifact.grantedPermissions) {
                environment.loginRequester
            } else {
                PluginLoginRequester.None
            },
            systemEventSink = eventBinding?.gateway,
            boundPluginScope = eventBinding?.scope,
            systemEventNegotiation = eventBinding?.negotiation,
            systemEventDeclaration = artifact.systemEvents,
            systemEventContextRegistry = eventContextRegistry,
        )
        val originalScript = artifact.copyBytes().decodeToString(throwOnInvalidSequence = true)
        val script = originalScript + compatibilityShim(sourceDescriptor.sourceKey.sourceId)
        var runtime: ScriptPluginRuntime? = null
        try {
            runtime = runtimeFactory.createForSource(script, manifest, sourceEntry, scopedEnvironment)
            require(runtime.pluginId == artifact.identity.packageId && runtime.id == executionScope) {
                "Reviewed ShuYue platform runtime escaped its assigned execution scope"
            }
            val source = ProductionShuYueSourceV2(
                descriptor = sourceDescriptor,
                runtime = runtime,
                credentialsResolver = credentialsResolver,
                eventBinding = eventBinding,
                hasStoredCredentials = { filteredStorage.getCredential(executionScope) != null },
                requestLogin = { reason ->
                    if (eventBinding != null) {
                        submitLegacyLoginCompatibility(scopedEnvironment, supportsLogin = true, reason)
                    } else {
                        environment.loginRequester.request(
                            executionScope,
                            sourceDescriptor.displayName,
                            reason,
                        )
                    }
                },
            )
            return CloseableShuYuePackageRuntimeV2(
                PluginArtifactIdentity(
                    artifact.identity.packageId,
                    artifact.identity.version,
                    artifact.identity.versionCode,
                    artifact.identity.sha256,
                ),
                ImmutableExtensionPackageRuntimeV2(descriptor, listOf(source)),
                runtime,
                source::closeTextStreams,
                source,
                closeEventBinding = {
                    eventBinding?.let {
                        it.gateway.closeRuntime(it.scope)
                        it.gateway.revokeRuntimePermissions(it.scope)
                    }
                },
            )
        } catch (error: Throwable) {
            eventBinding?.let {
                it.gateway.closeRuntime(it.scope)
                it.gateway.revokeRuntimePermissions(it.scope)
            }
            runtime?.let { failedRuntime -> runCatching { failedRuntime.close() } }
            throw error
        }
    }
}

private class CloseableShuYuePackageRuntimeV2(
    override val artifactIdentity: PluginArtifactIdentity,
    private val delegate: ImmutableExtensionPackageRuntimeV2,
    private val runtime: ScriptPluginRuntime,
    private val closeTextStreams: () -> Unit,
    private val eventSource: ProductionShuYueSourceV2,
    private val closeEventBinding: () -> Unit = {},
) : CloseableExtensionPackageRuntimeV2,
    ArtifactBoundExtensionPackageRuntimeV2,
    SourceLifecycleControlledExtensionPackageRuntimeV2 {
    override val descriptor: ExtensionPackageV2 get() = delegate.descriptor
    override fun source(sourceKey: SourceKey): ExtensionSourceV2? = delegate.source(sourceKey)
    override suspend fun setSourceEnabled(sourceKey: SourceKey, enabled: Boolean): Boolean {
        return eventSource.setSourceEnabled(sourceKey, enabled)
    }
    override suspend fun close() {
        closeTextStreams()
        closeEventBinding()
        runtime.close()
    }
}

private data class ReviewedEventBinding(
    val gateway: PluginSystemEventGateway,
    val scope: BoundPluginScope,
    val negotiation: dev.shinsou.kmp.plugin.events.PluginSystemEventNegotiation,
    val contextRegistry: dev.shinsou.kmp.plugin.events.PluginEventContextRegistry?,
)

private class ProductionShuYueSourceV2(
    override val descriptor: SourceDescriptorV2,
    private val runtime: ScriptPluginRuntime,
    private val credentialsResolver: LegacyLoginCredentialsResolverV2?,
    private val eventBinding: ReviewedEventBinding?,
    private val hasStoredCredentials: suspend () -> Boolean,
    private val requestLogin: (String?) -> Boolean,
) : ExtensionSourceV2, UserInteractionScopedExtensionSourceV2 {
    private val interactionMutex = Mutex()
    /** Serializes context-bearing engine calls so a later invocation cannot replace its handle. */
    private val invocationMutex = Mutex()
    private var interactionCount = 0
    private val sourceStateLock = SynchronousLock()
    private var sourceEnabled: Boolean = true

    override suspend fun <T> withUserInteractionContext(block: suspend () -> T): T {
        interactionMutex.withLock {
            interactionCount += 1
            if (interactionCount == 1) eventBinding?.gateway?.setUserInteractionContext(eventBinding.scope, true)
        }
        return try {
            block()
        } finally {
            interactionMutex.withLock {
                interactionCount = (interactionCount - 1).coerceAtLeast(0)
                if (interactionCount == 0) eventBinding?.gateway?.setUserInteractionContext(eventBinding.scope, false)
            }
        }
    }

    override fun setHostUiAvailable(available: Boolean) {
        eventBinding?.gateway?.setRuntimeLifecycle(
            eventBinding.scope,
            if (available) PluginRuntimeLifecycle.OPEN_FOREGROUND_UNLOCKED
            else PluginRuntimeLifecycle.OPEN_BACKGROUND,
        )
        if (!available) eventBinding?.gateway?.setUserInteractionContext(eventBinding.scope, false)
    }
    private val textStreamLock = SynchronousLock()
    private val pendingTextStreams = linkedMapOf<String, PendingShuYueTextStream>()
    private val activeTextStreams = linkedMapOf<String, ReviewedShuYueTextChunkStream>()
    private var reservedTextStreamCount: Int = 0
    private var reservedTextBytes: Long = 0
    private var nextTextStreamOrdinal: Long = 1
    private var textStreamsClosed: Boolean = false

    override suspend fun getFilterList(): BrowseFilterListV2 = ensureSourceEnabled().let {
        runtime.getFilterList().map { it.toBrowseFilterV2() }
    }

    override suspend fun browseOptions(): BrowseOptionsSchemaV2 = ensureSourceEnabled().let {
        BrowseOptionsSchemaV2(
            keys = listOf(BROWSE_OPTION_KEY),
            filters = getFilterList(),
        )
    }

    override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
        ensureSourceEnabled().let {
            runtime.getSearchManga(scriptPage(page), SEARCH_COMMAND_PREFIX + query, emptyList()).toV2Page()
        }

    override suspend fun search(
        query: String,
        page: Int,
        options: BrowseOptionsV2,
    ): PagedResultV2<RemotePublicationV2> = ensureSourceEnabled().let {
        runtime.getSearchManga(
            scriptPage(page),
            SEARCH_COMMAND_PREFIX + query,
            options.filters.map { it.toPluginFilter() },
        ).toV2Page()
    }

    override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> =
        ensureSourceEnabled().let { runtime.getLatestUpdates(scriptPage(page)).toV2Page() }

    override suspend fun browse(
        options: BrowseOptionsV2,
        page: Int,
    ): PagedResultV2<RemotePublicationV2> {
        ensureSourceEnabled()
        require(options.values.keys.all { it == BROWSE_OPTION_KEY } && options.values.size <= 1) {
            "Reviewed ShuYue browse accepts only '$BROWSE_OPTION_KEY'"
        }
        val option = options.values[BROWSE_OPTION_KEY]
        val result = if (option != null) {
            if (option == BOOKCASE_OPTION_VALUE && !hasStoredCredentials()) {
                // In a fully wired app this queues the exact v2 login dialog and avoids a
                // needless unauthenticated request. Test/embedded hosts without a presenter
                // return false, in which case the source retains its historical network path.
                if (requestLogin("收藏庫需要登入")) return PagedResultV2(emptyList(), false)
            }
            runtime.getSearchManga(scriptPage(page), BROWSE_COMMAND_PREFIX + option, emptyList())
        } else if (options.filters.isNotEmpty()) {
            runtime.getSearchManga(
                scriptPage(page),
                "",
                options.filters.map { it.toPluginFilter() },
            )
        } else {
            runtime.getPopularManga(scriptPage(page))
        }
        return result.toV2Page()
    }

    override suspend fun details(remotePublicationId: String): RemotePublicationV2 {
        ensureSourceEnabled()
        return withActiveInvocationContext(remotePublicationId) {
            val details = runtime.getMangaDetails(
                SManga(url = remotePublicationId, title = remotePublicationId),
            )
            details.toRemotePublication(remotePublicationId)
        }
    }

    override suspend fun units(
        remotePublicationId: String,
        page: Int,
    ): PagedResultV2<RemoteUnitV2> {
        ensureSourceEnabled()
        return withActiveInvocationContext(remotePublicationId) {
            val chapters = runtime.getChapterList(
                SManga(url = remotePublicationId, title = remotePublicationId),
            )
            require(chapters.size <= MAX_SHUYUE_UNITS) { "Reviewed ShuYue source returned too many units" }
            val fromLong = page.toLong() * PAGE_SIZE.toLong()
            if (fromLong >= chapters.size) return@withActiveInvocationContext PagedResultV2(emptyList(), false)
            val from = fromLong.toInt()
            val to = minOf(chapters.size, from + PAGE_SIZE)
            PagedResultV2(
                chapters.subList(from, to).map { chapter -> chapter.toRemoteUnit() },
                hasNextPage = to < chapters.size,
            )
        }
    }

    override suspend fun content(
        remotePublicationId: String,
        remoteUnitId: String,
    ): UnitContentResultV2 {
        ensureSourceEnabled()
        return withActiveInvocationContext(remotePublicationId, remoteUnitId) {
            val carrier = runtime.getMangaDetails(
                SManga(
                    url = CONTENT_COMMAND_PREFIX + remoteUnitId,
                    title = remotePublicationId,
                ),
            )
            val text = carrier.description.orEmpty()
            val textByteSize = text.utf8ByteSizeBounded(MAX_SHUYUE_TEXT_STREAM_BYTES)
            val representation = if (textByteSize <= MAX_SHUYUE_INLINE_TEXT_BYTES) {
                UnitContentPayload.InlineTextPayload(
                    schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
                    representationId = SHUYUE_TEXT_REPRESENTATION_ID,
                    sourceKey = descriptor.sourceKey,
                    remoteUnitId = remoteUnitId,
                    source = TextPayloadSourceV2.InlineTextPayload(text),
                    blocks = listOf(TextBlock("body", 0, text.length)),
                )
            } else {
                UnitContentPayload.ChunkedTextPayload(
                    schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
                    representationId = SHUYUE_TEXT_REPRESENTATION_ID,
                    sourceKey = descriptor.sourceKey,
                    remoteUnitId = remoteUnitId,
                    source = registerTextStream(text, textByteSize),
                )
            }
            UnitContentResultV2(
                schemaVersion = ExtensionPackageV2.CURRENT_CONTRACT_VERSION,
                sourceKey = descriptor.sourceKey,
                remotePublicationId = remotePublicationId,
                remoteUnitId = remoteUnitId,
                representations = listOf(representation),
            )
        }
    }

    private suspend fun <T> withActiveInvocationContext(
        publicationId: String,
        unitId: String? = null,
        block: suspend () -> T,
    ): T {
        val registry = eventBinding?.contextRegistry
        val scope = eventBinding?.scope
        if (registry == null || scope == null) return block()
        return invocationMutex.withLock {
            registry.withInvocation(
                scope,
                dev.shinsou.kmp.plugin.events.PluginEventContextRegistry.VisibleContext(
                    publicationId,
                    unitId,
                ),
            ) { block() }
        }
    }

    override suspend fun openTextStream(streamId: String): TextChunkStreamV2 {
        ensureSourceEnabled()
        return textStreamLock.withLock {
            check(!textStreamsClosed) { "Reviewed ShuYue text streams are closed" }
            val selected = requireNotNull(pendingTextStreams.remove(streamId)) {
                "Reviewed ShuYue text stream is missing, expired, or already opened"
            }
            ReviewedShuYueTextChunkStream(selected.text) {
                releaseTextStream(streamId, selected.byteSize)
            }.also { activeTextStreams[streamId] = it }
        }
    }

    private fun registerTextStream(
        text: String,
        byteSize: Long,
    ): TextPayloadSourceV2.ChunkedTextPayload = textStreamLock.withLock {
        check(!textStreamsClosed) { "Reviewed ShuYue text streams are closed" }
        require(byteSize > MAX_SHUYUE_INLINE_TEXT_BYTES && byteSize <= MAX_SHUYUE_TEXT_STREAM_BYTES)
        require(reservedTextStreamCount < MAX_RESERVED_SHUYUE_TEXT_STREAMS &&
            reservedTextBytes <= MAX_RESERVED_SHUYUE_TEXT_BYTES - byteSize) {
            "Too many reviewed ShuYue text bodies are pending or active"
        }
        check(nextTextStreamOrdinal < Long.MAX_VALUE) { "Reviewed ShuYue text stream id space is exhausted" }
        val streamId = "shuyue-text-${nextTextStreamOrdinal++}"
        pendingTextStreams[streamId] = PendingShuYueTextStream(text, byteSize)
        reservedTextStreamCount++
        reservedTextBytes += byteSize
        TextPayloadSourceV2.ChunkedTextPayload(
            streamId = streamId,
            firstCursor = "0",
            maxChunkBytes = SHUYUE_TEXT_CHUNK_BYTES,
            maxTotalBytes = byteSize,
            maxChunks = ((byteSize + SHUYUE_TEXT_CHUNK_BYTES - 4) /
                (SHUYUE_TEXT_CHUNK_BYTES - 3)).toInt(),
            cancellationReference = "$streamId-cancel",
        )
    }

    private fun releaseTextStream(streamId: String, byteSize: Long) {
        textStreamLock.withLock {
            if (activeTextStreams.remove(streamId) == null) return@withLock
            reservedTextStreamCount--
            reservedTextBytes -= byteSize
            check(reservedTextStreamCount >= 0 && reservedTextBytes >= 0) {
                "Reviewed ShuYue text stream reservation underflow"
            }
        }
    }

    internal fun closeTextStreams() {
        val active = textStreamLock.withLock {
            if (textStreamsClosed) return
            textStreamsClosed = true
            pendingTextStreams.clear()
            val streams = activeTextStreams.values.toList()
            activeTextStreams.clear()
            reservedTextStreamCount = 0
            reservedTextBytes = 0
            streams
        }
        active.forEach(TextChunkStreamV2::cancel)
    }

    override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 {
        ensureSourceEnabled()
        val resolved = credentialsResolver?.resolve(credentials) ?: return LoginResultV2(false)
        return LoginResultV2(runtime.login(resolved.username, resolved.password))
    }

    override suspend fun logout(): Unit = ensureSourceEnabled().let { runtime.logout() }
    override suspend fun preferences(): List<PreferenceV2> = ensureSourceEnabled().let { emptyList() }

    override suspend fun favorite(remotePublicationId: String, favorite: Boolean) {
        ensureSourceEnabled()
        require(favorite) { "Reviewed ShuYue sources do not expose an unfavorite mutation" }
        // The favorite hook is an ordinary script invocation, so it must receive the same
        // short-lived visible publication context as details/chapters/content.  Without this
        // wrapper a script-triggered ACTIVE_CONTEXT refresh is rejected and the UI only sees a
        // no-op/failed mutation.
        withActiveInvocationContext(remotePublicationId) {
            val result = runtime.getMangaDetails(
                SManga(url = FAVORITE_COMMAND_PREFIX + remotePublicationId, title = remotePublicationId),
            )
            check(result.initialized) { "Reviewed ShuYue favorite mutation failed" }
        }
    }

    private fun dev.shinsou.kmp.plugin.MangasPage.toV2Page(): PagedResultV2<RemotePublicationV2> {
        val bounded = mangas.take(PAGE_SIZE)
        return PagedResultV2(
            items = bounded.map { it.toRemotePublication(it.url) },
            hasNextPage = hasNextPage || mangas.size > bounded.size,
        )
    }

    private fun SManga.toRemotePublication(remoteId: String): RemotePublicationV2 = RemotePublicationV2(
        remoteId = remoteId,
        title = title.toBoundedRemoteMetadata().ifBlank { remoteId.toBoundedRemoteMetadata() },
        url = resolveSourceHttpUrl(descriptor.baseUrl, url.ifBlank { remoteId }),
        thumbnailUrl = thumbnailUrl?.let { resolveSourceHttpUrl(descriptor.baseUrl, it) },
        author = author.toOptionalBoundedRemoteMetadata(),
        artist = artist.toOptionalBoundedRemoteMetadata(),
        description = description.toOptionalBoundedRemoteMetadata(),
        genre = genre?.mapNotNull { it.toOptionalBoundedRemoteMetadata() }?.takeIf { it.isNotEmpty() },
        status = status.takeIf { it != dev.shinsou.kmp.plugin.MangaStatus.UNKNOWN }?.name,
    )

    private fun SChapter.toRemoteUnit(): RemoteUnitV2 = RemoteUnitV2(
        remoteId = url,
        title = name.ifBlank { url },
        url = resolveSourceHttpUrl(descriptor.baseUrl, url),
    )

    private fun scriptPage(page: Int): Int = page + 1

    /** Disabling is terminal for this engine instance; re-enable must reload a new generation. */
    internal suspend fun setSourceEnabled(sourceKey: SourceKey, enabled: Boolean): Boolean {
        require(sourceKey == descriptor.sourceKey) { "Source lifecycle target does not match runtime" }
        if (enabled) return false
        val shouldDisable = sourceStateLock.withLock {
            if (!sourceEnabled) {
                false
            } else {
                sourceEnabled = false
                true
            }
        }
        if (!shouldDisable) return true
        closeTextStreams()
        eventBinding?.let {
            it.gateway.closeRuntime(it.scope)
            it.gateway.revokeRuntimePermissions(it.scope)
        }
        return true
    }

    private fun ensureSourceEnabled() {
        sourceStateLock.withLock {
            check(sourceEnabled) { "Reviewed ShuYue source '${descriptor.sourceKey.canonicalId}' is disabled" }
        }
    }
}

private data class PendingShuYueTextStream(
    val text: String,
    val byteSize: Long,
)

/** One-use, cursor-exact UTF-8 view over a legacy chapter String. */
private class ReviewedShuYueTextChunkStream(
    initialText: String,
    private val releaseReservation: () -> Unit,
) : TextChunkStreamV2 {
    private var text: String? = initialText
    private var expectedOffset: Int = 0
    private var terminal: Boolean = false
    private var cancelled: Boolean = false
    private var reservationReleased: Boolean = false

    override val maxChunkBytes: Int = SHUYUE_TEXT_CHUNK_BYTES

    override suspend fun next(cursor: String?): dev.shinsou.kmp.plugin.v2.TextChunkResultV2 {
        check(!cancelled) { "Reviewed ShuYue text stream is cancelled" }
        check(!terminal) { "Reviewed ShuYue text stream is complete" }
        require(cursor == expectedOffset.toString()) { "Reviewed ShuYue text cursor changed" }
        val source = requireNotNull(text)
        val end = source.utf8ChunkEnd(expectedOffset, maxChunkBytes)
        val chunk = source.substring(expectedOffset, end)
        expectedOffset = end
        terminal = end == source.length
        if (terminal) {
            text = null
            releaseOnce()
        }
        return dev.shinsou.kmp.plugin.v2.TextChunkResultV2(
            utf8Text = chunk,
            nextCursor = end.toString().takeUnless { terminal },
            done = terminal,
        )
    }

    override fun cancel() {
        if (cancelled) return
        cancelled = true
        text = null
        releaseOnce()
    }

    private fun releaseOnce() {
        if (reservationReleased) return
        reservationReleased = true
        releaseReservation()
    }
}

private fun String.utf8ByteSizeBounded(maximum: Long): Long {
    var total = 0L
    var index = 0
    while (index < length) {
        val value = this[index]
        val width = when {
            value.code <= 0x7f -> 1
            value.code <= 0x7ff -> 2
            value.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                index++
                4
            }
            else -> 3
        }
        total += width
        require(total <= maximum) { "Reviewed ShuYue chapter exceeds the text stream hard limit" }
        index++
    }
    return total
}

private fun String.utf8ChunkEnd(start: Int, maximumBytes: Int): Int {
    require(start in 0 until length)
    var used = 0
    var index = start
    while (index < length) {
        val value = this[index]
        val units: Int
        val width: Int
        if (value.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
            units = 2
            width = 4
        } else {
            units = 1
            width = when {
                value.code <= 0x7f -> 1
                value.code <= 0x7ff -> 2
                else -> 3
            }
        }
        if (used + width > maximumBytes) break
        used += width
        index += units
    }
    check(index > start) { "Reviewed ShuYue text chunk limit cannot fit one scalar" }
    return index
}

/** Direct bridge calls and automatic network cookies see the same admitted permission filter. */
private class CapabilityFilteredShuYueStorage(
    private val delegate: PluginStorage,
    permissions: Set<ShuYueExecutionPermissionV2>,
) : PluginStorage {
    private val credentialsAllowed = ShuYueExecutionPermissionV2.CREDENTIAL_ACCESS in permissions
    private val cookiesAllowed = ShuYueExecutionPermissionV2.COOKIE_STORAGE in permissions

    override suspend fun getPreference(sourceId: Long, key: String): String? =
        delegate.getPreference(sourceId, key)

    override suspend fun setPreference(sourceId: Long, key: String, value: String): Unit =
        delegate.setPreference(sourceId, key, value)

    override suspend fun getCredential(sourceId: Long): PluginCredential? =
        if (credentialsAllowed) delegate.getCredential(sourceId) else null

    override suspend fun setCredential(sourceId: Long, credential: PluginCredential) {
        if (credentialsAllowed) delegate.setCredential(sourceId, credential)
    }

    override suspend fun clearCredential(sourceId: Long) {
        if (credentialsAllowed) delegate.clearCredential(sourceId)
    }

    override suspend fun getCookies(sourceId: Long): List<PluginCookie> =
        if (cookiesAllowed) delegate.getCookies(sourceId) else emptyList()

    override suspend fun setCookie(sourceId: Long, cookie: PluginCookie) {
        if (cookiesAllowed) delegate.setCookie(sourceId, cookie)
    }

    override suspend fun deleteCookie(sourceId: Long, name: String, domain: String) {
        if (cookiesAllowed) delegate.deleteCookie(sourceId, name, domain)
    }

    override suspend fun deleteCookieExact(sourceId: Long, name: String, domain: String, path: String) {
        if (cookiesAllowed) delegate.deleteCookieExact(sourceId, name, domain, path)
    }

    override suspend fun clearCookies(sourceId: Long) {
        if (cookiesAllowed) delegate.clearCookies(sourceId)
    }
}

private fun compatibilityShim(opaqueSourceId: String): String {
    val encoded = Json.encodeToString(String.serializer(), opaqueSourceId)
    return SHUYUE_COMPATIBILITY_SHIM_PREFIX + encoded + SHUYUE_COMPATIBILITY_SHIM_SUFFIX
}

private const val SHUYUE_COMPATIBILITY_SHIM_PREFIX: String = """
;(function(expectedOpaqueId){
  'use strict';
  if(typeof source!=='object'||!source)throw new Error('Reviewed ShuYue script does not export source');
  if(String(source.id)!==String(expectedOpaqueId))throw new Error('Reviewed ShuYue source id changed');
  var target=source,opaqueId=String(expectedOpaqueId);
  var searchPrefix='__shinsou_shuyue_search__:';
  var browsePrefix='__shinsou_shuyue_browse__:';
  var contentPrefix='__shinsou_shuyue_content__:';
  var favoritePrefix='__shinsou_shuyue_favorite__:';
  var original={
    search:target.search,latest:target.latest,browseOptions:target.browseOptions,browse:target.browse,
    chapters:target.chapters,chapterText:target.chapterText,bookFromAid:target.bookFromAid,
    aidFromText:target.aidFromText,favorite:target.favorite,
    legacyPopular:target.getPopularManga,legacySearch:target.getSearchManga,
    legacyLatest:target.getLatestUpdates,legacyDetails:target.getMangaDetails,
    legacyChapters:target.getChapterList,legacyPages:target.getPageList,
    legacyFilters:target.getFilterList
  };
  if(typeof target.parseBookLinks==='function'){
    var originalParseBookLinks=target.parseBookLinks;
    target.parseBookLinks=function(html,pageUrl){
      return originalParseBookLinks.call(target,String(html==null?'':html),pageUrl);
    };
  }
  function call(fn,args){
    if(typeof fn!=='function')return null;
    target.id=opaqueId;
    return fn.apply(target,args||[]);
  }
  function book(value){
    if(!value||typeof value!=='object')return null;
    var url=String(value.url||'');
    var title=String(value.title||value.name||url||'Untitled');
    if(!url)return null;
    return {url:url,title:title,artist:value.artist||null,author:value.author||null,
      description:value.description||null,genre:value.genre||null,status:Number(value.status||0),
      thumbnailUrl:value.thumbnailUrl||value.thumbnail_url||value.coverImage||value.cover||null,initialized:true};
  }
  function books(values){
    if(!Array.isArray(values))return [];
    var out=[];for(var i=0;i<values.length;i++){var mapped=book(values[i]);if(mapped)out.push(mapped);}return out;
  }
  function page(values,hasNext){var mapped=books(values);return {mangas:mapped,hasNextPage:hasNext===undefined?mapped.length>0:!!hasNext};}
  function legacyPage(value){
    if(value&&typeof value==='object'&&Array.isArray(value.mangas))return value;
    return page(value);
  }
  function selectedFilter(filters){
    if(!Array.isArray(filters))return 0;
    for(var i=0;i<filters.length;i++){
      var filter=filters[i]||{};
      if(Array.isArray(filter.values)){
        var state=Number(filter.state);
        if(isFinite(state))return Math.max(0,Math.floor(state));
      }
      var nested=selectedFilter(filter.filters);
      if(nested>0)return nested;
    }
    return 0;
  }
  target.getSearchManga=function(pageNumber,query,filters){
    var command=String(query||'');
    if(command.indexOf(browsePrefix)===0){
      var browseId=command.substring(browsePrefix.length);
      var browseValues=call(original.browse,[browseId,Number(pageNumber||1)]);
      // Account-owned collections are complete snapshots in both reviewed Wenku8 scripts;
      // advertising another page makes the UI issue a needless second network request.
      return page(browseValues,browseId==='bookcase'?false:undefined);
    }
    var selected=selectedFilter(filters);
    if(selected>0&&typeof original.browse==='function'){
      var browseOptions=call(original.browseOptions,[])||[];
      if(browseOptions[selected-1]){
        var filteredValues=call(original.browse,[String(browseOptions[selected-1].id||''),Number(pageNumber||1)]);
        return page(filteredValues);
      }
    }
    if(command.indexOf(searchPrefix)===0)command=command.substring(searchPrefix.length);
    if(typeof original.legacySearch==='function'){
      return legacyPage(call(original.legacySearch,[Math.max(0,Number(pageNumber||1)-1),command,filters||[]]));
    }
    return page(call(original.search,[command,Number(pageNumber||1)]));
  };
  target.getLatestUpdates=function(pageNumber){
    if(typeof original.legacyLatest==='function')return legacyPage(call(original.legacyLatest,[Math.max(0,Number(pageNumber||1)-1)]));
    return page(call(original.latest,[Number(pageNumber||1)]));
  };
  target.getPopularManga=function(pageNumber){
    if(typeof original.legacyPopular==='function')return legacyPage(call(original.legacyPopular,[Math.max(0,Number(pageNumber||1)-1)]));
    var options=call(original.browseOptions,[])||[];
    var option=options.length&&options[0]?String(options[0].id||''):'';
    if(option&&typeof original.browse==='function')return page(call(original.browse,[option,Number(pageNumber||1)]));
    return page(call(original.latest,[Number(pageNumber||1)]));
  };
  target.getMangaDetails=function(manga){
    manga=manga||{};var url=String(manga.url||'');
    if(url.indexOf(contentPrefix)===0){
      var chapterUrl=url.substring(contentPrefix.length);
      var text=call(original.chapterText,[{url:chapterUrl,bookUrl:String(manga.title||'')}]);
      return {url:url,title:String(manga.title||chapterUrl),description:String(text==null?'':text),initialized:true};
    }
    if(url.indexOf(favoritePrefix)===0){
      var publicationUrl=url.substring(favoritePrefix.length);
      var success=!!call(original.favorite,[{url:publicationUrl,title:String(manga.title||publicationUrl)}]);
      return {url:url,title:String(manga.title||publicationUrl),initialized:success};
    }
    if(typeof original.legacyDetails==='function')return call(original.legacyDetails,[manga]);
    var aid=typeof original.aidFromText==='function'?call(original.aidFromText,[url]):url;
    var details=aid&&typeof original.bookFromAid==='function'?call(original.bookFromAid,[aid]):manga;
    return book(details)||book(manga)||{url:url,title:String(manga.title||url),initialized:true};
  };
  target.getChapterList=function(manga){
    if(typeof original.legacyChapters==='function')return call(original.legacyChapters,[manga||{}]);
    var values=call(original.chapters,[{url:String(manga&&manga.url||''),title:String(manga&&manga.title||'')}])||[];
    var out=[];for(var i=0;i<values.length;i++){
      var value=values[i]||{},url=String(value.url||'');if(!url)continue;
      out.push({url:url,name:String(value.title||value.name||url),scanlator:null,dateUpload:0,chapterNumber:Number(value.index||i)});
    }return out;
  };
  target.getPageList=function(chapter){
    if(typeof original.legacyPages==='function')return call(original.legacyPages,[chapter||{}]);
    return [];
  };
  target.getFilterList=function(){
    if(typeof original.legacyFilters==='function')return call(original.legacyFilters,[]);
    return [];
  };
})(
"""

private const val SHUYUE_COMPATIBILITY_SHIM_SUFFIX: String = ");\n"

private const val SEARCH_COMMAND_PREFIX: String = "__shinsou_shuyue_search__:"
private const val BROWSE_COMMAND_PREFIX: String = "__shinsou_shuyue_browse__:"
private const val CONTENT_COMMAND_PREFIX: String = "__shinsou_shuyue_content__:"
private const val FAVORITE_COMMAND_PREFIX: String = "__shinsou_shuyue_favorite__:"
private const val BROWSE_OPTION_KEY: String = "option"
private const val BOOKCASE_OPTION_VALUE: String = "bookcase"
private const val SHUYUE_TEXT_REPRESENTATION_ID: String = "shuyue-inline-text"
private const val PAGE_SIZE: Int = 100
private const val MAX_SHUYUE_UNITS: Int = 100_000
private const val MAX_SHUYUE_INLINE_TEXT_BYTES: Long = 4L * 1024L * 1024L
private const val SHUYUE_TEXT_CHUNK_BYTES: Int = 64 * 1024
private const val MAX_SHUYUE_TEXT_STREAM_BYTES: Long = 512L * 1024L * 1024L
private const val MAX_RESERVED_SHUYUE_TEXT_STREAMS: Int = 8
private const val MAX_RESERVED_SHUYUE_TEXT_BYTES: Long = MAX_SHUYUE_TEXT_STREAM_BYTES
