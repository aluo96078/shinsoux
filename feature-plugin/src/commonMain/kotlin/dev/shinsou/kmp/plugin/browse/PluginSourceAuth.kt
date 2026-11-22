package dev.shinsou.kmp.plugin.browse

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.plugin.v2.ExtensionCapability
import dev.shinsou.kmp.plugin.v2.LoginCredentialsV2
import dev.shinsou.kmp.contract.SourceLoginRequest
import dev.shinsou.kmp.contract.SourceLoginFailureStage
import dev.shinsou.kmp.contract.SourceLoginResult
import dev.shinsou.kmp.contract.SourceSecrets
import dev.shinsou.kmp.contract.SourceSecretsResult
import dev.shinsou.kmp.contract.SourceCredential as BrowseSourceCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.TimeoutCancellationException
import dev.shinsou.kmp.plugin.contracts.LoginSource
import dev.shinsou.kmp.plugin.contracts.Source
import dev.shinsou.kmp.plugin.contracts.SourceAuth
import dev.shinsou.kmp.plugin.manager.ExactPluginSessionOwnership
import dev.shinsou.kmp.plugin.manager.PluginLogoutConfirmation
import dev.shinsou.kmp.plugin.network.PluginNetworkLimits
import dev.shinsou.kmp.plugin.storage.PluginCredential
import dev.shinsou.kmp.plugin.trust.PluginVerifier
/** Cooperative bound layered over the desktop protected-store caller timeout. */
private const val SOURCE_SECRET_ACCESS_TIMEOUT_MILLIS: Long = 12_000L
private const val V2_USERNAME_REFERENCE_PREFIX: String = "shinsou-v2-username-"
private const val V2_PASSWORD_REFERENCE_PREFIX: String = "shinsou-v2-password-"

/** [SourceAuth] of [PluginBrowseAdapter]: source sign-in, stored credentials and logout. */
internal class PluginSourceAuth(
    private val shared: PluginBrowseState,
    deps: PluginBrowseDependencies,
    private val core: PluginBrowseCore,
) : SourceAuth {
    private val manager = deps.manager
    private val keyValueStore = deps.keyValueStore
    private val loginRequestCoordinator = deps.loginRequestCoordinator
    private val logoutRequestCoordinator = deps.logoutRequestCoordinator
    private val exactSourceRefreshInvalidations = deps.exactSourceRefreshInvalidations
    private val defaultDispatcher = deps.defaultDispatcher

    override val logoutConfirmations: StateFlow<List<PluginLogoutConfirmation>> =
        logoutRequestCoordinator?.requests
            ?: kotlinx.coroutines.flow.MutableStateFlow(emptyList())

    override fun dismissPluginLogout(eventId: String) {
        logoutRequestCoordinator?.dismiss(eventId)
    }

    override fun dismissAllPluginLogouts() {
        logoutRequestCoordinator?.clear()
    }

    override suspend fun setPluginUiAvailable(available: Boolean) {
        manager.setPluginUiAvailable(available)
        if (!available) {
            loginRequestCoordinator.retainPresentedRequest()
            logoutRequestCoordinator?.clear()
        }
    }

    override suspend fun confirmPluginLogout(eventId: String): Boolean = withContext(defaultDispatcher) {
        val request = logoutRequestCoordinator?.take(eventId) ?: return@withContext false
        val sourceKey = request.target.sourceKey
        val storageId = exactSessionStorageId(request) ?: return@withContext false
        val exact = manager.exactExtensionSource(request.target)
        val legacy = manager.exactLegacySource(request.target) as? LoginSource
        if (exact == null && legacy == null) return@withContext false
        // Remote logout is bounded. Host policy is fail-closed: timeout/failure preserves the
        // local session namespace so the user can retry and no unrelated credentials are erased.
        val remoteSucceeded = withTimeoutOrNull(PluginNetworkLimits.LOGOUT_WAIT_MILLIS) {
            runCatchingCancellable {
                if (exact != null) exact.logout() else legacy?.logout()
            }.isSuccess
        } == true
        if (!remoteSucceeded) return@withContext false
        // Credentials and cookies are the complete host-owned session namespace for this exact
        // execution/storage scope. Preferences remain source configuration, not session state.
        val ownerKey = ExactPluginSessionOwnership.ownerKey(request.target)
        if (!ExactPluginSessionOwnership.authorizesCleanup(keyValueStore.getString(ownerKey), request.target)) {
            return@withContext false
        }
        val storage = core.storageForSourceUi(request.target.sourceKey.legacyLongId ?: storageId)
        storage.clearCredential(storageId)
        storage.clearCookies(storageId)
        keyValueStore.remove(ownerKey)
        true
    }

    override suspend fun saveSourceEventCredentials(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceEventCredentialsResult(eventId, sourceId, username, password).succeeded

    override suspend fun saveSourceEventCredentialsResult(
        eventId: String,
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        val request = loginRequestCoordinator.event(eventId) ?: return SourceLoginResult(false)
        val target = request.exactTarget ?: return SourceLoginResult(false)
        if (request.sourceId != sourceId) return SourceLoginResult(false)
        val result = saveSourceCredentialsInternal(sourceId, username, password, dismissLegacy = false)
        if (result.succeeded) {
            keyValueStore.putString(
                ExactPluginSessionOwnership.ownerKey(target),
                ExactPluginSessionOwnership.targetKey(target),
            )
            exactSourceRefreshInvalidations.invalidate(target)
            loginRequestCoordinator.dismissEvent(eventId)
        }
        return result
    } catch (failure: SourceLoginStageException) {
        SourceLoginResult(false, failureStage = failure.stage)
    }

    private suspend fun exactSessionStorageId(request: PluginLogoutConfirmation): Long? {
        val target = request.target
        val sourceKey = target.sourceKey
        val artifact = target.artifactIdentity
        val installed = manager.installedPlugins().singleOrNull { stored ->
            stored.manifest.id == artifact.packageId &&
                stored.manifest.version == artifact.version &&
                (stored.manifest.versionCode ?: PluginVerifier.versionInt(stored.manifest.version)) == artifact.versionCode &&
                stored.metadata.installedSha256 == artifact.sha256 &&
                stored.manifest.sources.orEmpty().any { it.id == sourceKey.legacyLongId }
        }
        if (installed != null && manager.extensionSourceV2(sourceKey) != null) {
            return sourceKey.legacyLongId
        }
        return null
    }

    override val loginRequests: StateFlow<List<SourceLoginRequest>> = loginRequestCoordinator.loginRequests

    override fun dismissSourceLoginRequest(sourceId: Long) {
        loginRequestCoordinator.dismiss(sourceId)
    }

    override fun dismissSourceLoginEvent(eventId: String) {
        loginRequestCoordinator.dismissEvent(eventId)
    }

    override suspend fun saveSourceCredentials(
        sourceId: Long,
        username: String,
        password: String,
    ): Boolean = saveSourceCredentialsResult(sourceId, username, password).succeeded

    override suspend fun loadSourceSecrets(sourceId: Long): SourceSecretsResult = try {
        val v2Scope = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
            shared.requireV2SourceScope(sourceId)
        }
        val storageId = v2Scope?.second ?: sourceId
        val supportsLogin = if (v2Scope != null) {
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                requireNotNull(manager.extensionSourceV2(v2Scope.first)) {
                    "Unknown extension v2 source: ${v2Scope.first.canonicalId}"
                }
            }
            ExtensionCapability.LOGIN in source.descriptor.capabilities
        } else {
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
            }
            (source as? LoginSource)?.supportsLogin == true
        }
        val credential = if (supportsLogin) {
            secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
                core.storageForSourceUi(sourceId).getCredential(storageId)
            }
        } else {
            null
        }
        val cookies = secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
            core.storageForSourceUi(sourceId).getCookies(storageId)
        }
        // An account saved before sign-ins were marked: note it now that the secrets are open anyway.
        if (credential != null && keyValueStore.getString(core.sourceSignedInKey(storageId)) == null) {
            runCatchingCancellable { core.markSourceSignedIn(storageId, true) }
        }
        SourceSecretsResult(
            secrets = SourceSecrets(
                credential = credential?.let { BrowseSourceCredential(it.username, it.password) },
                cookies = cookies.map(::toBrowseCookie),
            ),
        )
    } catch (failure: SourceLoginStageException) {
        SourceSecretsResult(failureStage = failure.stage)
    }

    override suspend fun saveSourceCredentialsResult(
        sourceId: Long,
        username: String,
        password: String,
    ): SourceLoginResult = try {
        saveSourceCredentialsInternal(sourceId, username, password, dismissLegacy = true)
    } catch (failure: SourceLoginStageException) {
        SourceLoginResult(false, failureStage = failure.stage)
    }

    private suspend fun saveSourceCredentialsInternal(
        sourceId: Long,
        username: String,
        password: String,
        dismissLegacy: Boolean,
    ): SourceLoginResult = shared.operationMutex.withLock {
        require(username.isNotBlank()) { "Username cannot be blank" }
        val v2Scope = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
            shared.requireV2SourceScope(sourceId)
        }
        val result = if (v2Scope != null) {
            val (sourceKey, storageId) = v2Scope
            val source = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                requireNotNull(manager.extensionSourceV2(sourceKey)) {
                    "Unknown extension v2 source: ${sourceKey.canonicalId}"
                }
            }
            loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                check(ExtensionCapability.LOGIN in source.descriptor.capabilities) {
                    "Source does not implement the login contract: ${sourceKey.canonicalId}"
                }
            }
            val previous = secureStorageStage(SourceLoginFailureStage.READ_CREDENTIALS) {
                core.storageForSourceUi(sourceId).getCredential(storageId)
            }
            secureStorageStage(SourceLoginFailureStage.WRITE_CREDENTIALS) {
                core.storageForSourceUi(sourceId).setCredential(storageId, PluginCredential(username, password))
            }
            val loginResult = try {
                loginStage(SourceLoginFailureStage.AUTHENTICATE) {
                    manager.withUserInteractionContext(sourceKey) {
                        source.login(v2LoginCredentials(storageId))
                    }
                }
            } catch (failure: SourceLoginStageException) {
                restoreCredential(storageId, previous, failure)
                throw failure
            }
            if (!loginResult.loggedIn) {
                restoreCredential(storageId, previous)
            }
            SourceLoginResult(loginResult.loggedIn, loginResult.errorMessage)
        } else {
            val loginSource = loginStage(SourceLoginFailureStage.PREPARE_SOURCE) {
                val source = manager.source(sourceId)
                    ?: throw IllegalArgumentException("Unknown source: $sourceId")
                (source as? LoginSource)?.takeIf { it.supportsLogin }
                    ?: error("Source does not implement the login contract: $sourceId")
            }
            val loginResult = loginStage(SourceLoginFailureStage.AUTHENTICATE) {
                manager.withUserInteractionContext(sourceId) {
                    loginSource.loginResult(username, password)
                }
            }
            SourceLoginResult(loginResult.loggedIn, loginResult.errorMessage?.boundedLoginError())
        }
        if (result.succeeded) {
            val storageId = v2Scope?.second ?: sourceId
            // v2 login needs the credential in storage before the call; v1 stores it only after
            // success. Both paths converge here so the settings projection stays consistent.
            if (v2Scope == null) secureStorageStage(SourceLoginFailureStage.WRITE_CREDENTIALS) {
                core.storageForSourceUi(sourceId).setCredential(storageId, PluginCredential(username, password))
            }
            if (dismissLegacy) loginRequestCoordinator.dismiss(sourceId)
            runCatchingCancellable { core.markSourceSignedIn(storageId, true) }
        }
        loginStage(SourceLoginFailureStage.REFRESH_SOURCE_STATE) {
            core.rebuildSnapshot(errorMessage = null)
        }
        result
    }

    private suspend fun restoreCredential(
        storageId: Long,
        previous: PluginCredential?,
        originalFailure: SourceLoginStageException? = null,
    ) {
        try {
            secureStorageStage(SourceLoginFailureStage.RESTORE_CREDENTIALS) {
                val storage = core.storageForSourceUi(storageId)
                if (previous == null) storage.clearCredential(storageId)
                else storage.setCredential(storageId, previous)
            }
        } catch (restoreFailure: SourceLoginStageException) {
            originalFailure?.let(restoreFailure::addSuppressed)
            throw restoreFailure
        }
    }

    private suspend inline fun <T> loginStage(
        stage: SourceLoginFailureStage,
        crossinline block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SourceLoginStageException) {
        throw failure
    } catch (failure: Throwable) {
        throw SourceLoginStageException(stage, failure)
    }

    private suspend inline fun <T> secureStorageStage(
        stage: SourceLoginFailureStage,
        crossinline block: suspend () -> T,
    ): T = try {
        withTimeout(SOURCE_SECRET_ACCESS_TIMEOUT_MILLIS) { block() }
    } catch (timeout: TimeoutCancellationException) {
        throw SourceLoginStageException(stage, timeout)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: SourceLoginStageException) {
        throw failure
    } catch (failure: Throwable) {
        throw SourceLoginStageException(stage, failure)
    }

    private fun String.boundedLoginError(): String? =
        filterNot(Char::isISOControl).trim().take(512).takeIf(String::isNotBlank)

    override suspend fun logoutSource(sourceId: Long): Unit = shared.operationMutex.withLock {
        val v2Scope = shared.requireV2SourceScope(sourceId)
        val result = runCatchingCancellable {
            if (v2Scope != null) {
                val source = requireNotNull(manager.extensionSourceV2(v2Scope.first)) {
                    "Unknown extension v2 source: ${v2Scope.first.canonicalId}"
                }
                if (ExtensionCapability.LOGIN in source.descriptor.capabilities) source.logout()
            } else {
                val source = manager.source(sourceId) ?: throw IllegalArgumentException("Unknown source: $sourceId")
                (source as? LoginSource)?.takeIf { it.supportsLogin }?.logout()
            }
        }
        val storageId = v2Scope?.second ?: sourceId
        core.storageForSourceUi(sourceId).clearCredential(storageId)
        core.markSourceSignedIn(storageId, false)
        core.rebuildSnapshot(errorMessage = null)
        result.getOrThrow()
    }

    private fun v2LoginCredentials(scopeId: Long): LoginCredentialsV2 = LoginCredentialsV2(
        usernameReference = "$V2_USERNAME_REFERENCE_PREFIX$scopeId",
        passwordReference = "$V2_PASSWORD_REFERENCE_PREFIX$scopeId",
    )
}
