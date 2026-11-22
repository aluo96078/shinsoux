package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.plugin.v2.ExtensionContentMaterializationV2
import dev.shinsou.kmp.domain.model.ExtensionDownloadEntryV2
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadQueueFileStoreV2
import dev.shinsou.kmp.plugin.v2.ExtensionDownloadQueueV2
import dev.shinsou.kmp.plugin.v2.ExtensionUnitSelectionV2
import dev.shinsou.kmp.contract.TypedReaderContentSession
import dev.shinsou.kmp.domain.model.encodeTypedLocalChapterUrl
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import dev.shinsou.kmp.plugin.contracts.ContentAcquisition
/** [ContentAcquisition] of [PluginBrowseAdapter]: the v2 download queue and opening stored content. */
internal class PluginContentAcquisition(
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
) : ContentAcquisition {
    private val manager = deps.manager
    private val portableRepository = deps.portableRepository
    private val extensionContentConsumerV2 = deps.extensionContentConsumerV2
    private val fileSystem = deps.fileSystem
    private val defaultDispatcher = deps.defaultDispatcher

    private val extensionDownloadQueueV2 get() = extensionDownloadQueueV2Lazy.value

    /** Saves and stops the extension download queue when the app shuts down (R4-8). */
    suspend fun close() {
        if (extensionDownloadQueueV2Lazy.isInitialized()) extensionDownloadQueueV2Lazy.value.close()
    }

    private val extensionDownloadQueueV2Lazy = lazy {
        ExtensionDownloadQueueV2(
            materialize = { selection ->
                try {
                    materializeExtensionContentV2(selection, null)
                } catch (choice: dev.shinsou.kmp.plugin.v2.ExtensionContentConsumerException.RepresentationSelectionRequired) {
                    // A background download has nobody to ask; keep the source's first offering,
                    // which is also what offline reading opens by default.
                    materializeExtensionContentV2(selection, choice.availableIds.firstOrNull() ?: throw choice)
                }
            },
            storedUnitIds = { selection ->
                extensionContentConsumerV2
                    ?.downloadedRemoteUnitIds(
                        dev.shinsou.kmp.domain.model.extensionPublicationKey(
                            selection.sourceKey,
                            selection.remotePublicationId,
                        ),
                    )
                    .orEmpty()
            },
            store = fileSystem?.let(::ExtensionDownloadQueueFileStoreV2),
            // Sources load during startup; downloads begin once [openExtensionDownloadsV2] runs.
            startOpen = false,
        )
    }

    /**
     * Brings back novel downloads left by the previous run. Called early in startup so the
     * queue (and the Android download service) is visible at once; the work itself waits for
     * [openExtensionDownloadsV2].
     */
    suspend fun restoreExtensionDownloadsV2() {
        val consumer = extensionContentConsumerV2 ?: return
        extensionDownloadQueueV2.restore(consumer::restoredSelection)
    }

    /** Sources are loaded: queued novel downloads may run. */
    fun openExtensionDownloadsV2() {
        extensionDownloadQueueV2.open()
    }

    /** The app is back on screen: retry downloads that stopped on repeated (network) failures. */
    suspend fun resumeExtensionDownloadsAfterFailuresV2() {
        extensionDownloadQueueV2.resumeAfterFailures()
    }

    override val extensionDownloadsV2: StateFlow<List<ExtensionDownloadEntryV2>>
        get() = extensionDownloadQueueV2.entries

    override suspend fun enqueueExtensionDownloadsV2(selections: List<ExtensionUnitSelectionV2>) {
        requireNotNull(extensionContentConsumerV2) { "Extension v2 content storage is unavailable" }
        extensionDownloadQueueV2.enqueue(selections)
    }

    /** System pause of exactly [ids] in the novel queue. */
    suspend fun pauseExtensionDownloadsForSystemV2(ids: Set<String>): Unit =
        extensionDownloadQueueV2.pauseForSystem(ids)

    /** Resumes only [ids] (null: all) after a system pause. */
    suspend fun resumeExtensionDownloadsAfterSystemV2(ids: Set<String>?): Unit =
        extensionDownloadQueueV2.resumeAfterSystem(ids)

    /** Writes the novel queue to disk now; throws if it could not be saved. */
    suspend fun flushExtensionDownloadsV2(): Unit = extensionDownloadQueueV2.flush()

    override suspend fun retryExtensionDownloadV2(id: String) = extensionDownloadQueueV2.retry(id)

    override suspend fun removeExtensionDownloadV2(id: String) = extensionDownloadQueueV2.remove(id)

    override suspend fun clearCompletedExtensionDownloadsV2() = extensionDownloadQueueV2.clearCompleted()

    override suspend fun removeExtensionPublicationDownloadsV2(sourceKey: SourceKey, remotePublicationId: String) =
        extensionDownloadQueueV2.removePublication(sourceKey, remotePublicationId)

    override suspend fun purgeExtensionPublicationContentV2(sourceKey: SourceKey, remotePublicationId: String) {
        val consumer = extensionContentConsumerV2 ?: return
        withContext(defaultDispatcher) { consumer.purgeStoredContent(sourceKey, remotePublicationId) }
    }

    override suspend fun pauseExtensionDownloadsV2(paused: Boolean) = extensionDownloadQueueV2.setPaused(paused)

    override suspend fun materializeExtensionContentV2(
        selection: ExtensionUnitSelectionV2,
        representationId: String?,
    ): ExtensionContentMaterializationV2 = withContext(defaultDispatcher) {
        core.ensureInstalledLoaded()
        requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }.let { consumer ->
            // A stored chapter opens from disk, so downloads read offline and skip the network.
            consumer.storedMaterialization(selection, representationId)
                ?: manager.withUserInteractionContext(selection.sourceKey) {
                    consumer.materialize(selection, representationId)
                }
        }
    }

    override suspend fun openMaterializedExtensionContentV2(
        materialization: ExtensionContentMaterializationV2,
    ): TypedReaderContentSession = withContext(defaultDispatcher) {
        val consumer = requireNotNull(extensionContentConsumerV2) {
            "Extension v2 content storage is unavailable"
        }
        val readable = consumer.open(materialization)
        val localChapterUrl = encodeTypedLocalChapterUrl(
            publicationKey = materialization.publicationKey,
            acquisitionId = materialization.acquisitionId,
            unitKey = materialization.unitKey,
        )
        val localSnapshot = portableRepository?.currentSnapshot
        val localChapter = localSnapshot?.chapters?.firstOrNull { it.url == localChapterUrl }
        val localLocator = localChapter?.let { chapter ->
            localSnapshot.histories.firstOrNull { it.chapterId == chapter.id }?.lastLocator
        }?.takeIf { locator -> readable.content.navigation.indexOf(locator) != null }
        // Opened here the first time: it starts where another device left it, so the first
        // progress saved here does not send that device's place back to the chapter's start.
        val synced = if (localLocator == null && localChapter?.lastPageRead.let { it == null || it == 0 }) {
            consumer.syncedStart(materialization, readable.content.initialLocator.scope)
        } else {
            null
        }
        val syncedLocator = synced?.locator?.takeIf { locator -> readable.content.navigation.indexOf(locator) != null }
        TypedReaderContentSession(
            content = readable.content.copy(
                initialLocator = localLocator ?: syncedLocator ?: readable.content.initialLocator,
            ),
            canonicalText = readable.canonicalText,
            access = readable.access,
            initialVisualPageIndex = localChapter?.lastPageRead?.takeIf { synced?.pageIndex == null } ?: synced?.pageIndex,
            initialVisualPageCount = localChapter?.let { chapter ->
                localSnapshot.histories.firstOrNull { it.chapterId == chapter.id }?.lastPageCount
            },
            remoteAssetScope = manager.contentNetworkScopeForSource(materialization.sourceKey),
        )
    }
}
