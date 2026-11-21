package dev.shinsou.kmp.ui

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.ExtensionManagement
import dev.shinsou.kmp.plugin.ExtensionTrust
import dev.shinsou.kmp.plugin.SourceCatalog
import dev.shinsou.kmp.plugin.BrowseExtension
import dev.shinsou.kmp.plugin.PluginPermissionDetails
import dev.shinsou.kmp.plugin.RepositoryTrustPrompt
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.PluginDeveloperModeState
import dev.shinsou.kmp.plugin.admits
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.ui.i18n.StringKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Installed from the account but not yet reviewed: it has no runtime until it is. */
private fun BrowseExtension.awaitsFirstAccessReview(): Boolean = installed && !trusted && !inDevelopment

/** An installed package the index does not list yet, as far as its pending [review] tells. */
private fun unindexedExtension(review: PluginEventGrantReview): BrowseExtension = BrowseExtension(
    id = review.artifact.packageId,
    name = review.artifact.packageId,
    version = review.artifact.version,
    language = "",
    installed = true,
)

/** A title of a package waiting for its permission review was opened; [proceed] opens it once approved. */
internal class SourceReviewGate(
    val extension: BrowseExtension,
    val review: PluginEventGrantReview,
    val proceed: () -> Unit,
    /** The review ended without proceeding: dismissed, failed, or replaced by another. */
    val abandon: () -> Unit = {},
    /** Whether the open that asked for it still stands ([PluginReviewCoordinator.dropStaleReview]). */
    val stillWanted: () -> Boolean = { true },
)

/** The extension dialogs the shell may show; at most one of each. */
internal data class PluginReviewState(
    /** The permission review shown when a title of a package still waiting for approval is opened. */
    val sourceReviewGate: SourceReviewGate? = null,
    /** ADR-0002 §8.1: a repository opened from an add-repository link, waiting for the user. */
    val linkedRepositoryTrust: RepositoryTrustPrompt? = null,
    /** An add-repository link to a repository without a signing key: added only once confirmed. */
    val linkedUnsignedRepository: String? = null,
    /** The add-repository link behind those dialogs: stays pending until added or declined. */
    val pendingRepositoryLink: ShinsouDeepLink? = null,
)

/**
 * R4-3 (docs/architecture/0006): the extension trust decisions the shell asks the user for: the
 * first-access permission review of a package pre-installed from the account, and repositories
 * added by a link. A link never adds anything by itself; the user confirms every one.
 */
internal class PluginReviewCoordinator<B>(
    /** The extension host: its catalog, repository management and trust decisions. */
    private val browse: B,
    private val scope: CoroutineScope,
    private val messages: AppMessages,
    /** Acknowledges a deep link to the platform, which then delivers the next one. */
    private val acknowledgeLink: (ShinsouDeepLink) -> Unit,
    /** The package set changed: titles and progress waiting for a package can apply. */
    private val onSourcesChanged: suspend () -> Unit = {},
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
) where B : SourceCatalog, B : ExtensionManagement, B : ExtensionTrust {
    private val mutableState = MutableStateFlow(PluginReviewState())

    val state: StateFlow<PluginReviewState> = mutableState.asStateFlow()

    /**
     * Runs [proceed] once the package [packageId] may run. A package pre-installed from the account
     * has no runtime until its permissions are reviewed, so opening its title first shows that
     * review (approving continues, dismissing stays where the user was). Anything else proceeds at
     * once, without waiting on the plugin manager. [abandon] runs instead when the review is
     * dismissed, fails or is replaced by another, or when [stillWanted] says the user moved on
     * while the review was being read (then no review is shown).
     */
    fun afterFirstAccessReview(
        packageId: String,
        abandon: () -> Unit = {},
        stillWanted: () -> Boolean = { true },
        proceed: () -> Unit,
    ) {
        val extension = browse.state.value.extensions.firstOrNull { it.id == packageId }
        // Not in the index yet (a title restored before Browse published it): the installed
        // package's pending review is still read, so it is reviewed rather than opened unusable.
        if (extension != null && !extension.awaitsFirstAccessReview()) {
            proceed()
            return
        }
        scope.launch {
            val review = try {
                withContext(workDispatcher) { browse.pendingPluginEventGrantReview(packageId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                Log.warn("App", logged) { "Reading the pending extension permission review failed; continuing" }
                null
            }
            if (!stillWanted()) {
                // The user moved on while the review was read: no prompt over the new page.
                abandon()
            } else if (review == null) {
                proceed()
            } else {
                val replaced = mutableState.value.sourceReviewGate
                val reviewed = extension ?: unindexedExtension(review)
                mutableState.update { it.copy(sourceReviewGate = SourceReviewGate(reviewed, review, proceed, abandon, stillWanted)) }
                replaced?.abandon?.invoke()
            }
        }
    }

    /** Who published the reviewed package and where it connects; null when that cannot be read. */
    suspend fun permissionDetails(gate: SourceReviewGate): PluginPermissionDetails? =
        runCatchingCancellable {
            withContext(workDispatcher) { browse.pluginPermissionDetails(gate.extension.id, gate.review) }
        }.onFailure { Log.warn("Extension", it) { "Loading extension permission details failed" } }
            .getOrNull()

    fun approveReview(gate: SourceReviewGate, selection: PluginGrantSelection) {
        // A prompt replaced, or whose title open no longer stands, grants nothing.
        if (mutableState.value.sourceReviewGate !== gate || !gate.stillWanted()) {
            if (mutableState.value.sourceReviewGate === gate) dismissReview()
            return
        }
        mutableState.update { it.copy(sourceReviewGate = null) }
        scope.launch {
            try {
                withContext(workDispatcher) {
                    browse.approvePluginEventGrantReview(
                        gate.extension.id,
                        gate.review,
                        selection.hostPermissions,
                        selection,
                    )
                    // The package runs now: titles and progress that waited for it can apply.
                    onSourcesChanged()
                }
                gate.proceed()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                messages.show(logged.message ?: logged::class.simpleName.orEmpty())
                gate.abandon()
            }
        }
    }

    /** Dismisses the review shown, if the open that asked for it no longer stands. */
    fun dropStaleReview() {
        val gate = mutableState.value.sourceReviewGate ?: return
        if (!gate.stillWanted()) dismissReview()
    }

    fun dismissReview() {
        val gate = mutableState.value.sourceReviewGate
        mutableState.update { it.copy(sourceReviewGate = null) }
        gate?.abandon?.invoke()
    }

    /**
     * An add-repository [link]. The repository's key is matched against the link's fingerprint;
     * [openBrowse] shows the extension list first.
     */
    /**
     * Closes the add-repository dialogs when their link was handled elsewhere ([stillPending]
     * false): another Android activity answered it, and this answer would act on a decided link.
     */
    fun dropPromptsHandledElsewhere(stillPending: (ShinsouDeepLink) -> Boolean) {
        val link = mutableState.value.pendingRepositoryLink ?: return
        if (stillPending(link)) return
        mutableState.update {
            it.copy(linkedRepositoryTrust = null, linkedUnsignedRepository = null, pendingRepositoryLink = null)
        }
    }

    /** Whether [link] is already being handled: its prompt is shown, or its confirmed add runs. */
    fun handlesRepositoryLink(link: ShinsouDeepLink): Boolean =
        link == mutableState.value.pendingRepositoryLink || link === addingLink

    suspend fun onRepositoryLink(link: ShinsouDeepLink.AddRepository, openBrowse: () -> Unit) {
        // The queue re-emits its head whenever another link arrives: this one is already waiting,
        // or the user confirmed it and it is being added (it is acknowledged once added).
        if (link == mutableState.value.pendingRepositoryLink || link === addingLink) return
        openBrowse()
        val prompt = withContext(workDispatcher) { browse.inspectRepositoryTrust(link.repositoryUrl) }
        when {
            prompt != null -> mutableState.update {
                it.copy(linkedRepositoryTrust = prompt.copy(linkedFingerprint = link.fingerprint))
            }
            link.fingerprint != null -> {
                // A definite refusal: report it and let the next link through.
                acknowledgeLink(link)
                messages.show(StringKey.THIS_REPOSITORY_PRESENTS_NO_SIGNING_KEY_TO)
                return
            }
            else -> mutableState.update { it.copy(linkedUnsignedRepository = link.repositoryUrl) }
        }
        // Acknowledged by the dialog: once added, or once the user declines.
        mutableState.update { it.copy(pendingRepositoryLink = link) }
    }

    /** A development-server link: only this device or an address the user entered is admitted. */
    suspend fun onDevRepositoryLink(link: ShinsouDeepLink.AddDevRepository, openBrowse: () -> Unit) {
        if (link == mutableState.value.pendingRepositoryLink || link === addingLink) return
        openBrowse()
        val developer = withContext(workDispatcher) { browse.packageSecurity().developerMode }
        val admitted = developer != null &&
            PluginDeveloperModeState(developer.enabled, developer.lanHosts).admits(link.repositoryUrl)
        if (!admitted) {
            acknowledgeLink(link)
            messages.show(StringKey.TO_ADD_THIS_DEVELOPMENT_SERVER_TURN_ON)
            return
        }
        // Confirmed like any unsigned repository; admission still checks the address.
        mutableState.update { it.copy(linkedUnsignedRepository = link.repositoryUrl, pendingRepositoryLink = link) }
    }

    /** The user trusted the linked repository's key ([fingerprint]). */
    fun trustLinkedRepository(fingerprint: String?) {
        val current = mutableState.value
        val prompt = current.linkedRepositoryTrust ?: return
        val forLink = current.pendingRepositoryLink
        mutableState.update { it.copy(linkedRepositoryTrust = null) }
        launchRepositoryAdd(forLink) { browse.trustAndAddRepository(prompt, fingerprint) }
    }

    fun declineLinkedRepository() {
        val forLink = mutableState.value.pendingRepositoryLink
        mutableState.update { it.copy(linkedRepositoryTrust = null) }
        repositoryLinkDone(forLink)
    }

    fun addLinkedUnsignedRepository() {
        val current = mutableState.value
        val url = current.linkedUnsignedRepository ?: return
        val forLink = current.pendingRepositoryLink
        mutableState.update { it.copy(linkedUnsignedRepository = null) }
        launchRepositoryAdd(forLink) { browse.addRepository(url) }
    }

    fun declineLinkedUnsignedRepository() {
        val forLink = mutableState.value.pendingRepositoryLink
        mutableState.update { it.copy(linkedUnsignedRepository = null) }
        repositoryLinkDone(forLink)
    }

    /**
     * The link whose repository the user confirmed and that is being added: not asked about again
     * (a shell coming back to the foreground meanwhile sees it still queued). UI thread.
     */
    private var addingLink: ShinsouDeepLink? = null

    private fun launchRepositoryAdd(forLink: ShinsouDeepLink?, add: suspend () -> Unit) {
        addingLink = forLink
        scope.launch {
            try {
                withContext(workDispatcher) { add() }
                repositoryLinkDone(forLink)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (logged: Throwable) {
                // Failed, it is still answered: the queue's head would otherwise hold every later
                // link back. Following the link again tries again.
                messages.showFailure(logged, StringKey.UNABLE_TO_OPEN_THIS_LINK)
                repositoryLinkDone(forLink)
            } finally {
                if (addingLink === forLink) addingLink = null
            }
        }
    }

    /** Acknowledges exactly [link] (the one a dialog was opened for), never whatever came later. */
    private fun repositoryLinkDone(link: ShinsouDeepLink?) {
        link?.let(acknowledgeLink)
        mutableState.update { if (it.pendingRepositoryLink == link) it.copy(pendingRepositoryLink = null) else it }
    }
}
