package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.contract.AccountPackage
import dev.shinsou.kmp.contract.AccountRepository
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_DEVICE_NAME
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_ERROR
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_INSECURE_LAN
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_LAST_SYNC
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_REVIEWS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SERVER_URL
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SHARING
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SIGNED_IN
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_USERNAME
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** The one owner of what [SyncV3Service] publishes: the Settings state and the account's lists. */
internal class SyncStatePublisher(private val context: SyncV3Context) {
    private val store = context.store
    private val repository = context.repository
    private val identity = context.identity

    private val mutableState = MutableStateFlow(SyncUiState())
    val state: StateFlow<SyncUiState> = mutableState.asStateFlow()

    private val mutableAccountRepositories = MutableStateFlow<List<AccountRepository>>(emptyList())
    val accountRepositories: StateFlow<List<AccountRepository>> = mutableAccountRepositories.asStateFlow()

    private val mutableAccountPackages = MutableStateFlow<List<AccountPackage>>(emptyList())
    val accountPackages: StateFlow<List<AccountPackage>> = mutableAccountPackages.asStateFlow()

    fun update(transform: (SyncUiState) -> SyncUiState) {
        mutableState.update(transform)
    }

    private fun publishAccountPackages(signedIn: Boolean) {
        val packages = if (!signedIn || SyncKinds.PACKAGE !in context.enabledKinds()) {
            emptyList()
        } else {
            store.liveRecords(SyncKinds.PACKAGE).mapNotNull { record ->
                fun field(name: String) = (record.state.fields[name]?.value as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.trim().orEmpty()
                val repositoryUrl = field("repositoryUrl")
                // Without a repository there is nothing to install it from; with one that is not a
                // portable URL the device must not even be offered it.
                if (repositoryUrl.isNotEmpty() && !isPortableRepositoryUrl(repositoryUrl)) return@mapNotNull null
                AccountPackage(
                    packageId = record.key,
                    repositoryUrl = repositoryUrl,
                    name = field("name").ifEmpty { record.key },
                    version = field("version"),
                    versionCode = (record.state.fields["versionCode"]?.value as? JsonPrimitive)?.longOrNull,
                    signingKeyFingerprint = field("signingKeyFingerprint"),
                )
            }.sortedBy { it.packageId }
        }
        if (mutableAccountPackages.value != packages) mutableAccountPackages.value = packages
    }

    private fun publishAccountRepositories(signedIn: Boolean) {
        val repositories = if (!signedIn || SyncKinds.REPOSITORY !in context.enabledKinds()) {
            emptyList()
        } else {
            store.liveRecords(SyncKinds.REPOSITORY).mapNotNull { record ->
                if (!isPortableRepositoryUrl(record.key)) return@mapNotNull null
                val name = (record.state.fields["name"]?.value as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                val fingerprint = (record.state.fields["trustedPublishers"]?.value as? JsonArray)
                    ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }?.trim().orEmpty()
                AccountRepository(
                    url = record.key,
                    name = name.ifEmpty { record.key.substringAfter("://").substringBefore('/') },
                    signingKeyFingerprint = fingerprint,
                )
            }.sortedBy { it.url }
        }
        mutableAccountRepositories.value = repositories
    }

    private fun missingSources(): List<SyncMissingSource> =
        store.worksWaitingForSource()
            // A stored work that does not decode cannot name its source; it is left out of the list.
            .mapNotNull { json -> runCatching { SyncWireJson.decodeFromString(WireWork.serializer(), json) }.getOrNull() }
            .groupBy { it.packageId }
            .map { (packageId, works) ->
                SyncMissingSource(
                    packageId = packageId,
                    name = works.firstNotNullOfOrNull { it.sourceName?.trim()?.takeIf(String::isNotEmpty) } ?: packageId,
                    language = works.firstNotNullOfOrNull { it.language?.trim()?.takeIf(String::isNotEmpty) },
                    repositoryUrl = works.firstNotNullOfOrNull { it.repositoryUrl?.trim()?.takeIf(String::isNotEmpty) },
                    titleCount = works.distinctBy { it.hash }.size,
                    titles = works.distinctBy { it.hash }.map { it.title.trim() }.filter(String::isNotEmpty).sorted(),
                )
            }
            .sortedWith(compareByDescending<SyncMissingSource> { it.titleCount }.thenBy { it.name })

    fun publishState(syncing: Boolean = mutableState.value.syncing) {
        val signedIn = store.meta(META_SIGNED_IN) == "1"
        publishAccountRepositories(signedIn)
        publishAccountPackages(signedIn)
        val off = context.disabledKinds()
        val snapshot = repository.currentSnapshot
        // Counting titles without a portable identity: a failed lookup is exactly that case.
        val unsyncable = if (signedIn) snapshot.mangas.count { it.favorite && runCatching { identity.workOf(it) }.getOrNull() == null } else 0
        mutableState.update { previous ->
            // The session can end (expireSession) after `signedIn` was read above. Publishing that
            // stale view would mark the device signed in again and clear "session expired", and
            // the next publish would then report signed out with the flag gone for good.
            if (signedIn && store.meta(META_SIGNED_IN) != "1") return@update previous
            previous.copy(
                signedIn = signedIn,
                sessionExpired = if (signedIn) false else previous.sessionExpired,
                serverUrl = store.meta(META_SERVER_URL),
                insecureLan = store.meta(META_INSECURE_LAN) == "1",
                username = store.meta(META_USERNAME),
                deviceName = store.meta(META_DEVICE_NAME),
                syncing = syncing,
                lastSyncAt = store.meta(META_LAST_SYNC)?.toLongOrNull(),
                pendingCount = if (signedIn) store.dirtyRefCount(off) + store.dirtyRecordCount(off) else 0,
                pendingRemoteCount = if (signedIn) store.pendingRemoteCount(off) else 0,
                missingSources = if (signedIn) missingSources() else emptyList(),
                unsyncableCount = unsyncable,
                rejectedCount = if (signedIn) store.rejectedCount(off) else 0,
                lastError = store.meta(META_ERROR),
                sharingEnabled = signedIn && store.meta(META_SHARING) == "1",
                reviewsEnabled = signedIn && store.meta(META_REVIEWS) == "1",
            )
        }
    }
}
