package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.plugin.BrowseExtension
import dev.shinsou.kmp.plugin.ExtensionManagement
import dev.shinsou.kmp.plugin.ExtensionTrust
import dev.shinsou.kmp.plugin.InstalledPluginPermissions
import dev.shinsou.kmp.plugin.PackageSecuritySnapshot
import dev.shinsou.kmp.plugin.PluginPermissionDetails
import dev.shinsou.kmp.plugin.RepositoryTrustPrompt
import dev.shinsou.kmp.plugin.UpdatePermissionPreview
import dev.shinsou.kmp.plugin.events.PluginEventGrantReview
import dev.shinsou.kmp.plugin.events.PluginGrantSelection
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A permission review waiting for the user's answer. */
internal data class PendingPluginEventGrant(
    val extension: BrowseExtension,
    val review: PluginEventGrantReview,
)

/** ADR-0002 §6.3: an update asking for something new, reviewed before it is installed. */
internal data class PendingUpdateReview(
    val extension: BrowseExtension,
    val preview: UpdatePermissionPreview,
)

/** Source settings → permissions (ADR-0002 §6.5): the granted plugin whose items are being edited. */
internal data class EditedPluginPermissions(
    val extension: BrowseExtension,
    val permissions: InstalledPluginPermissions,
)

/**
 * R4-3 (docs/architecture/0006): the Extensions page's work and dialogs — repositories, installing,
 * updating and removing packages, their trust and permission reviews, and package security. One
 * operation on an extension runs at a time ([operations]); other actions report their failure in
 * the page's snackbar through [showMessage].
 */
internal class ExtensionManagementState(
    private val management: ExtensionManagement,
    private val trust: ExtensionTrust,
    private val work: ScreenWork,
    private val showMessage: suspend (String) -> Unit,
) {
    val operations = ExtensionOperationRunner(work.scope, work.workDispatcher, beforeWork = work.awaitFrame)

    var pendingGrant by mutableStateOf<PendingPluginEventGrant?>(null)
        private set
    var pendingUpdate by mutableStateOf<PendingUpdateReview?>(null)
        private set
    var editedPermissions by mutableStateOf<EditedPluginPermissions?>(null)
        private set
    var repositoryTrust by mutableStateOf<RepositoryTrustPrompt?>(null)
        private set
    var packageSecurity by mutableStateOf<PackageSecuritySnapshot?>(null)
        private set

    /**
     * Repository actions (add, trust, remove, switch) started and not finished yet. They queue
     * behind any other extension work, so the page says so instead of looking unresponsive.
     */
    var repositoryWork by mutableStateOf(0)
        private set

    /** [launch] for a repository action, counted in [repositoryWork] until its work ends. */
    private fun launchRepositoryWork(block: suspend () -> Unit) {
        work.scope.launch {
            repositoryWork++
            val failure = try {
                work.awaitFrame()
                runCatchingCancellable { withContext(work.workDispatcher) { block() } }.exceptionOrNull()
            } finally {
                repositoryWork--
            }
            // A snackbar may suspend until dismissed; it must not keep the page marked busy.
            failure?.let { showMessage(work.describe(it)) }
        }
    }

    /** Runs [block] off the UI thread once the press has been drawn; a failure goes to the snackbar. */
    fun launch(block: suspend () -> Unit) {
        work.scope.launch {
            // Let the pressed/ripple state reach the display before dispatching plugin work.
            work.awaitFrame()
            runCatchingCancellable { withContext(work.workDispatcher) { block() } }
                .onFailure { showMessage(work.describe(it)) }
        }
    }

    private fun <T> launchOperation(
        extension: BrowseExtension,
        labelKey: StringKey,
        onSuccess: (T) -> Unit = {},
        block: suspend () -> T,
    ): Boolean = operations.start(
        operation = ExtensionOperation(extension.id, labelKey),
        work = block,
        onSuccess = onSuccess,
        onError = { showMessage(work.describe(it)) },
    )

    /**
     * Looks for an installed package still waiting for its permission review, unless one is shown
     * or an operation is running. A package pre-installed from the account is reviewed on first
     * access (opening one of its titles, or switching it on here), not the moment this page appears.
     */
    suspend fun findPendingGrant(extensions: List<BrowseExtension>) {
        if (pendingGrant != null || operations.active != null) return
        runCatchingCancellable {
            withContext(work.workDispatcher) {
                extensions.filter { it.installed && !it.preinstalledFromAccount }
                    .firstNotNullOfOrNull { extension ->
                        trust.pendingPluginEventGrantReview(extension.id)?.let { PendingPluginEventGrant(extension, it) }
                    }
            }
        }.onSuccess { pending ->
            // A click can claim an operation before recomposition cancels this lookup.
            if (operations.active == null && pendingGrant == null) pendingGrant = pending
        }.onFailure { showMessage(work.describe(it)) }
    }

    /** ADR-0002 §8.2: a signed third-party repository shows its publisher key first. */
    fun addRepository(url: String) = launchRepositoryWork {
        val prompt = management.inspectRepositoryTrust(url)
        when {
            prompt == null -> management.addRepository(url) // unsigned: the URL the user typed
            prompt.alreadyTrusted -> management.trustAndAddRepository(prompt, null)
            else -> repositoryTrust = prompt
        }
    }

    fun trustRepository(prompt: RepositoryTrustPrompt, fingerprint: String?) {
        repositoryTrust = null
        launchRepositoryWork { management.trustAndAddRepository(prompt, fingerprint) }
    }

    fun dismissRepositoryTrust() {
        repositoryTrust = null
    }

    fun removeRepository(id: String, fromAccount: Boolean) = launchRepositoryWork { management.removeRepository(id, fromAccount) }

    fun dismissRepositorySuggestion(url: String) = launch { management.dismissRepositorySuggestion(url) }

    fun selectRepository(id: String?) = launchRepositoryWork { management.selectRepository(id) }

    fun openPackageSecurity() = launch { packageSecurity = trust.packageSecurity() }

    /** A change made in the package security dialog; the dialog then shows the new state. */
    fun changePackageSecurity(action: suspend ExtensionTrust.() -> Unit) = launch {
        trust.action()
        packageSecurity = trust.packageSecurity()
    }

    fun dismissPackageSecurity() {
        packageSecurity = null
    }

    fun unblock(extension: BrowseExtension) {
        extension.blockEntryKey?.let { key -> launch { trust.unblockPackageEntry(key) } }
    }

    fun block(extension: BrowseExtension) = launch { trust.setExtensionBlocked(extension.id, blocked = true) }

    /** Installs [extension], or updates it (an update asking for something new is reviewed first). */
    fun install(extension: BrowseExtension) {
        val update = extension.installed && extension.updateAvailable
        launchOperation(
            extension,
            if (extension.installed) StringKey.UPDATING else StringKey.INSTALLING,
            onSuccess = { review: PluginEventGrantReview? -> afterInstall(extension, review) },
        ) {
            // Asks for something new: review it first; the installed version keeps running.
            val preview = if (update) management.updatePermissionPreview(extension.id) else null
            if (preview != null) {
                pendingUpdate = PendingUpdateReview(extension, preview)
                null
            } else {
                management.installExtension(extension.id)
                trust.pendingPluginEventGrantReview(extension.id)
            }
        }
    }

    private fun afterInstall(extension: BrowseExtension, review: PluginEventGrantReview?) {
        pendingGrant = review?.let { PendingPluginEventGrant(extension, it) }
        // An update that asked for nothing new kept the previous grant (D4): say so.
        if (review == null && extension.installed) {
            work.scope.launch {
                showMessage(work.strings().text(StringKey.X_WAS_UPDATED_AND_KEPT_THE_PERMISSIONS, extension.name))
            }
        }
    }

    fun uninstall(extension: BrowseExtension) {
        launchOperation(extension, StringKey.REMOVING) { management.uninstallExtension(extension.id) }
    }

    fun editPermissions(extension: BrowseExtension) {
        launchOperation(
            extension,
            StringKey.LOADING_PERMISSIONS,
            onSuccess = { permissions: InstalledPluginPermissions? ->
                editedPermissions = permissions?.let { EditedPluginPermissions(extension, it) }
            },
        ) { trust.installedPluginPermissions(extension.id) }
    }

    fun setTrusted(extension: BrowseExtension, trusted: Boolean) {
        launchOperation(
            extension,
            StringKey.UPDATING_TRUST,
            onSuccess = { review: PluginEventGrantReview? -> pendingGrant = review?.let { PendingPluginEventGrant(extension, it) } },
        ) {
            management.setExtensionTrusted(extension.id, trusted)
            if (trusted) trust.pendingPluginEventGrantReview(extension.id) else null
        }
    }

    /** Who published the reviewed artifact and where it connects; null when it cannot be read. */
    suspend fun permissionDetails(pending: PendingPluginEventGrant): PluginPermissionDetails? =
        runCatchingCancellable { trust.pluginPermissionDetails(pending.extension.id, pending.review) }
            .onFailure { Log.warn("Extension", it) { "Loading extension permission details failed" } }
            .getOrNull()

    fun approveGrant(pending: PendingPluginEventGrant, selection: PluginGrantSelection) {
        val started = launchOperation(pending.extension, StringKey.APPLYING_PERMISSIONS) {
            trust.approvePluginEventGrantReview(pending.extension.id, pending.review, selection.hostPermissions, selection)
        }
        if (started) pendingGrant = null
    }

    fun dismissGrant() {
        pendingGrant = null
    }

    fun approveUpdate(pending: PendingUpdateReview, selection: PluginGrantSelection) {
        pendingUpdate = null
        launchOperation(
            pending.extension,
            StringKey.UPDATING,
            onSuccess = { remaining: PluginEventGrantReview? ->
                // The artifact asked for something else than shown: review that instead.
                pendingGrant = remaining?.let { PendingPluginEventGrant(pending.extension, it) }
            },
        ) { management.installReviewedUpdate(pending.extension.id, pending.preview, selection) }
    }

    fun dismissUpdate() {
        pendingUpdate = null
    }

    fun narrowPermissions(edited: EditedPluginPermissions, selection: PluginGrantSelection) {
        val done = selection == edited.permissions.granted ||
            launchOperation(edited.extension, StringKey.APPLYING_PERMISSIONS) {
                trust.narrowPluginPermissions(edited.extension.id, selection)
            }
        if (done) editedPermissions = null
    }

    fun dismissPermissions() {
        editedPermissions = null
    }
}
