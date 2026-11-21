package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.SnapshotMutationObserver
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.contract.AccountPackage
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.MARK_CHUNK
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_ERROR
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_INITIAL_DONE
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_SEEDED_KINDS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.META_USER_ID
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.PACKAGE_FIELDS
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.PACKAGE_KEY
import dev.shinsou.kmp.sync.v3.SyncV3Service.Companion.SEEDED_KINDS_VERSION
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The one owner of dirty tracking: the library observer that marks local edits, the once-per-
 * session seeding and the installed packages this device last reported. [signalDirty] asks for an
 * automatic cycle.
 */
internal class SyncDirtyTracker(
    context: SyncV3Context,
    private val clocks: SyncClockKeeper,
    private val signalDirty: () -> Unit,
) {
    private val repository = context.repository
    private val store = context.store
    private val identity = context.identity

    /** What this device has installed, as last reported by the composition; null until reported. */
    @Volatile var installedPackages: List<AccountPackage>? = null

    private val observerMutex = Mutex()
    private val dirtyObserver = DirtyObserver()
    private val observerInstalled = MutableStateFlow(false)

    /**
     * Idempotent, and serialised with [removeObserver]: restoring a session while it is being
     * signed out cannot leave a tracker behind, because the account is re-checked under the lock
     * that sign-out holds while it forgets the account.
     */
    suspend fun installObserver() = observerMutex.withLock {
        if (observerInstalled.value || store.meta(META_USER_ID) == null) return@withLock
        repository.setMutationObserver(dirtyObserver)
        observerInstalled.value = true
    }

    /** Removes the tracker, then runs [afterRemoval] before another install can observe the state. */
    suspend fun removeObserver(afterRemoval: () -> Unit = {}) = observerMutex.withLock {
        if (observerInstalled.value) {
            repository.setMutationObserver(null)
            observerInstalled.value = false
        }
        afterRemoval()
    }

    /** Marks every installed package whose record on this device differs (or is missing); see [SyncV3Service.packagesChanged]. */
    fun markPackages(): Unit = clocks.marking {
        val installed = installedPackages ?: return@marking
        val entries = ArrayList<DirtyEntry>()
        for (pkg in installed) {
            if (!PACKAGE_KEY.matches(pkg.packageId)) continue // the server would refuse it for good
            val stored = store.loadRecord(SyncKinds.PACKAGE, pkg.packageId)
            val live = stored != null && stored.state.deleted?.value != true
            for (field in PACKAGE_FIELDS) {
                val value = packageValue(pkg, field)
                if (value == null || (live && stored?.state?.fields?.get(field)?.value == value)) continue
                entries += DirtyEntry(SyncKinds.PACKAGE, packageRef(pkg.packageId), field, clocks.nextClock())
            }
        }
        if (entries.isEmpty()) return@marking
        store.mark(entries)
        clocks.persistClock()
    }

    /** Marks [url] removed from the account; see [SyncV3Service.forgetRepository]. */
    fun markRepositoryForgotten(url: String) {
        clocks.marking {
            store.mark(listOf(DirtyEntry(SyncKinds.REPOSITORY, repositoryRef(url), DirtyEntry.DELETED_FIELD, clocks.nextClock(), "true", url)))
            clocks.persistClock()
        }
    }

    private fun markEverythingDirty() {
        val snapshot = repository.currentSnapshot
        val entries = SyncDiff.markAll(snapshot, clocks::clockAt)
        entries.chunked(MARK_CHUNK).forEach { store.mark(it) }
        markPackages()
        clocks.persistClock()
        store.setMeta(META_INITIAL_DONE, "1")
        store.setMeta(META_SEEDED_KINDS, SEEDED_KINDS_VERSION)
    }

    /**
     * Everything is marked once per session. A session that began before a kind was synced
     * (repositories and settings came later) gets that kind marked once here, without touching
     * edits still waiting to be sent.
     */
    fun ensureSeeded() {
        if (store.meta(META_INITIAL_DONE) != "1") return markEverythingDirty()
        // Reported packages are compared with their stored records every time, so a report that
        // arrived before the clock existed (or while signed out) is not lost.
        markPackages()
        val seeded = store.meta(META_SEEDED_KINDS)
        if (seeded == SEEDED_KINDS_VERSION) return
        // Version 3 already seeded settings; version 4 added packages (marked above).
        val seedSettings = seeded != "3"
        val pending = HashMap<Pair<String, String>, Set<String>>()
        val entries = SyncDiff.markAll(repository.currentSnapshot, clocks::clockAt).filter { e ->
            seedSettings && e.kind == SyncKinds.SETTING &&
                e.field !in pending.getOrPut(e.kind to e.ref) { store.dirtyFor(e.kind, e.ref).mapTo(HashSet()) { it.field } }
        }
        entries.chunked(MARK_CHUNK).forEach { store.mark(it) }
        if (seedSettings) reseedRepositories()
        clocks.persistClock()
        store.setMeta(META_SEEDED_KINDS, SEEDED_KINDS_VERSION)
    }

    /**
     * Repositories used to be deleted from the account whenever they were deleted here, so an
     * account can hold tombstones for repositories some device still has. Marking each local
     * repository live with the current clock (not the clock 0 of [SyncDiff.markAll]) lets such a
     * device bring them back, and a queued removal from the old behaviour is dropped rather than
     * pushed.
     */
    private fun reseedRepositories(): Unit = clocks.marking {
        val queued = store.allDirty().filter { it.kind == SyncKinds.REPOSITORY }
        val repositories = LinkedHashSet<String>()
        val entries = ArrayList<DirtyEntry>()
        for (repo in repository.currentSnapshot.extensionRepositories) {
            if (repo.baseUrl.isBlank() || !repositories.add(repo.baseUrl)) continue
            val ref = repositoryRef(repo.baseUrl)
            entries += DirtyEntry(SyncKinds.REPOSITORY, ref, DirtyEntry.DELETED_FIELD, clocks.nextClock(), "false")
            SyncDiff.REPOSITORY_FIELDS.forEach { entries += DirtyEntry(SyncKinds.REPOSITORY, ref, it, clocks.nextClock()) }
        }
        store.deleteDirty(queued.filter { it.field == DirtyEntry.DELETED_FIELD && it.aux == "true" })
        store.mark(entries)
    }

    private inner class DirtyObserver : SnapshotMutationObserver {
        private val env = object : SyncDiffEnv {
            override fun nextClock(): String = clocks.nextClock()

            override fun tombstoneWork(manga: Manga): Pair<String, String?>? {
                val work = identity.workOf(manga)
                if (work != null) return work.recordKey to (work as? SyncedWork.Sourced)?.key?.hash
                return store.rememberedWork(manga.id)
            }

            override fun categoryUuid(localId: Long): String? = store.categoryUuid(localId)

            override fun forgetCategory(localId: Long) = store.unbindCategory(localId)
        }

        override suspend fun beforeCommit(previous: AppSnapshot, next: AppSnapshot) {
            try {
                val marked = clocks.marking {
                    val entries = SyncDiff.diff(previous, next, env)
                    if (entries.isNotEmpty()) {
                        store.mark(entries)
                        clocks.persistClock()
                    }
                    entries.isNotEmpty()
                }
                if (marked) signalDirty()
                if (next.chapters.size > previous.chapters.size) signalDirty()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Never fail the user's edit because sync bookkeeping broke; the next full
                // sign-in style resweep covers it.
                store.setMeta(META_ERROR, "dirty tracking failed: ${error.message}")
                store.setMeta(META_INITIAL_DONE, "0")
            }
        }
    }
}

/** The value a package record carries for [field]; null for a field it does not have. */
internal fun packageValue(pkg: AccountPackage, field: String): JsonElement? = when (field) {
    "repositoryUrl" -> JsonPrimitive(pkg.repositoryUrl)
    "name" -> JsonPrimitive(pkg.name.trim().truncated(SyncIdentityResolver.MAX_TITLE_CHARS))
    "version" -> JsonPrimitive(pkg.version)
    "versionCode" -> pkg.versionCode?.let(::JsonPrimitive)
    "signingKeyFingerprint" -> JsonPrimitive(pkg.signingKeyFingerprint)
    else -> null
}
