package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.SyncKindSettings
import dev.shinsou.kmp.domain.model.Manga

internal object SyncKinds {
    const val LIBRARY = "library"
    const val UNIT = "unit"
    const val PROGRESS = "progress"
    const val HISTORY = "history"
    const val CATEGORY = "category"
    const val REPOSITORY = "repository"
    const val SETTING = "setting"
    const val PACKAGE = "package"

    /**
     * The kinds that follow one switch of [SyncKindSettings]. Reading progress and history travel
     * together; a history row without its progress (or the other way round) would be half a
     * bookmark.
     */
    fun enabled(kinds: SyncKindSettings): Set<String> = buildSet {
        if (kinds.library) add(LIBRARY)
        if (kinds.readingProgress) { add(UNIT); add(PROGRESS); add(HISTORY) }
        if (kinds.categories) add(CATEGORY)
        if (kinds.repositories) add(REPOSITORY)
        if (kinds.settings) add(SETTING)
        if (kinds.packages) add(PACKAGE)
    }

    /** Every kind, in the order records are applied. */
    val ALL: List<String> = listOf(CATEGORY, LIBRARY, UNIT, HISTORY, PROGRESS, REPOSITORY, SETTING, PACKAGE)

    /** Records of a sourced title are keyed by its work hash and need the work's descriptor. */
    fun needsWork(kind: String, key: String): Boolean = when (kind) {
        CATEGORY, REPOSITORY, SETTING, PACKAGE -> false
        else -> !key.startsWith("local:")
    }
}

internal fun mangaRef(id: Long): String = "m:$id"
internal fun chapterRef(id: Long): String = "c:$id"
internal fun categoryRef(id: Long): String = "k:$id"
/**
 * The ref of a removal that carries its own record key. Local ids are reused (the next id is the
 * highest one plus one), so a removal marked under the plain ref would be overwritten by whatever
 * takes that id before the next sync, and the removed record would stay live on the account.
 */
internal fun removedRef(ref: String, recordKey: String): String = "$ref$REMOVED_REF_SEPARATOR$recordKey"
internal const val REMOVED_REF_SEPARATOR: Char = '#'
internal fun repositoryRef(baseUrl: String): String = "r:$baseUrl"
internal fun settingRef(path: String): String = "s:$path"
internal fun packageRef(packageId: String): String = "p:$packageId"

/** What the diff needs from the outside world; kept small so it can be tested without a database. */
internal interface SyncDiffEnv {
    /** A fresh, strictly increasing HLC string. */
    fun nextClock(): String

    /** `recordKey to workHash` of a title that was removed, from the previous snapshot. */
    fun tombstoneWork(manga: Manga): Pair<String, String?>?

    fun categoryUuid(localId: Long): String?

    fun forgetCategory(localId: Long)
}

/**
 * Finds the syncable fields that changed between two snapshots. It compares by reference first
 * (the repository copies lists but shares unchanged elements), so an edit costs one pass over
 * the affected lists with no allocation and no serialisation.
 */
internal object SyncDiff {
    fun diff(previous: AppSnapshot, next: AppSnapshot, env: SyncDiffEnv): List<DirtyEntry> {
        if (previous === next) return emptyList()
        val out = ArrayList<DirtyEntry>(8)
        fun add(
            kind: String,
            ref: String,
            field: String,
            aux: String? = null,
            tombKey: String? = null,
            tombWork: String? = null,
        ) {
            out += DirtyEntry(kind, ref, field, env.nextClock(), aux, tombKey, tombWork)
        }

        fun libraryFields(m: Manga) {
            add(SyncKinds.LIBRARY, mangaRef(m.id), DirtyEntry.DELETED_FIELD, aux = "false")
            add(SyncKinds.LIBRARY, mangaRef(m.id), "addedAt")
            add(SyncKinds.LIBRARY, mangaRef(m.id), "categoryIds")
        }

        if (previous.mangas !== next.mangas) {
            diffList(
                previous.mangas, next.mangas, { it.id },
                added = { m -> if (m.favorite) libraryFields(m) },
                removed = { m ->
                    if (m.favorite) {
                        env.tombstoneWork(m)?.let { (key, hash) ->
                            add(SyncKinds.LIBRARY, removedRef(mangaRef(m.id), key), DirtyEntry.DELETED_FIELD, "true", key, hash)
                        }
                    }
                },
                changed = { p, n ->
                    if (p.favorite != n.favorite) {
                        if (n.favorite) libraryFields(n) else add(SyncKinds.LIBRARY, mangaRef(n.id), DirtyEntry.DELETED_FIELD, "true")
                    } else if (n.favorite && p.dateAdded != n.dateAdded) {
                        add(SyncKinds.LIBRARY, mangaRef(n.id), "addedAt")
                    }
                },
            )
        }

        if (previous.mangaCategories !== next.mangaCategories) {
            val before = previous.mangaCategories.toHashSet()
            val after = next.mangaCategories.toHashSet()
            val affected = LinkedHashSet<Long>()
            for (link in after) if (link !in before) affected += link.mangaId
            for (link in before) if (link !in after) affected += link.mangaId
            if (affected.isNotEmpty()) {
                val favorites = if (affected.size > 8) {
                    next.mangas.asSequence().filter { it.favorite }.mapTo(HashSet()) { it.id }
                } else {
                    null
                }
                for (id in affected) {
                    val favorite = favorites?.contains(id) ?: next.mangas.any { it.id == id && it.favorite }
                    if (favorite) add(SyncKinds.LIBRARY, mangaRef(id), "categoryIds")
                }
            }
        }

        if (previous.categories !== next.categories) {
            fun categoryFields(c: Category) {
                val ref = categoryRef(c.id)
                if (c.id != Category.Default.id) {
                    add(SyncKinds.CATEGORY, ref, "name")
                    add(SyncKinds.CATEGORY, ref, "order")
                }
                add(SyncKinds.CATEGORY, ref, "flags")
            }
            diffList(
                previous.categories, next.categories, { it.id },
                added = { c ->
                    add(SyncKinds.CATEGORY, categoryRef(c.id), DirtyEntry.DELETED_FIELD, aux = "false")
                    categoryFields(c)
                },
                removed = { c ->
                    if (c.id != Category.Default.id) {
                        env.categoryUuid(c.id)?.let { uuid ->
                            add(SyncKinds.CATEGORY, removedRef(categoryRef(c.id), uuid), DirtyEntry.DELETED_FIELD, "true", uuid)
                        }
                        env.forgetCategory(c.id)
                    }
                },
                changed = { p, n ->
                    val ref = categoryRef(n.id)
                    if (n.id != Category.Default.id) {
                        if (p.name != n.name) add(SyncKinds.CATEGORY, ref, "name")
                        if (p.sort != n.sort) add(SyncKinds.CATEGORY, ref, "order")
                    }
                    if (p.flags != n.flags) add(SyncKinds.CATEGORY, ref, "flags")
                },
            )
        }

        if (previous.chapters !== next.chapters) {
            fun chapterFields(p: Chapter?, n: Chapter) {
                val ref = chapterRef(n.id)
                if (n.read != (p?.read ?: false)) add(SyncKinds.UNIT, ref, "read")
                if (n.bookmark != (p?.bookmark ?: false)) add(SyncKinds.UNIT, ref, "bookmarked")
                if (n.lastPageRead != (p?.lastPageRead ?: 0)) add(SyncKinds.UNIT, ref, "lastPageIndex")
            }
            diffList(
                previous.chapters, next.chapters, { it.id },
                added = { c -> chapterFields(null, c) },
                removed = { },
                changed = { p, n -> chapterFields(p, n) },
            )
        }

        if (previous.histories !== next.histories) {
            val touched = LinkedHashMap<Long, Boolean>() // mangaId -> locator changed
            val removedChapterIds = ArrayList<Long>()
            diffList(
                previous.histories, next.histories, { it.id },
                added = { h -> historyTouched(next.chapters, h, h.lastLocator != null, touched) },
                removed = { h -> removedChapterIds += h.chapterId },
                changed = { p, n ->
                    if (p.chapterId != n.chapterId) {
                        removedChapterIds += p.chapterId
                        historyTouched(next.chapters, n, n.lastLocator != null, touched)
                    } else if (p.lastRead != n.lastRead || p.lastLocator != n.lastLocator) {
                        historyTouched(next.chapters, n, p.lastLocator != n.lastLocator && n.lastLocator != null, touched)
                    }
                },
            )
            for ((mangaId, locatorChanged) in touched) {
                val ref = mangaRef(mangaId)
                add(SyncKinds.HISTORY, ref, DirtyEntry.DELETED_FIELD, aux = "false")
                add(SyncKinds.HISTORY, ref, "lastReadAt")
                add(SyncKinds.HISTORY, ref, "unitRemoteId")
                add(SyncKinds.PROGRESS, ref, DirtyEntry.DELETED_FIELD, aux = "false")
                add(SyncKinds.PROGRESS, ref, "unitRemoteId")
                if (locatorChanged) {
                    add(SyncKinds.PROGRESS, ref, "locator")
                    add(SyncKinds.PROGRESS, ref, "progression")
                }
            }
            if (removedChapterIds.isNotEmpty()) tombstoneEmptiedHistories(previous, next, removedChapterIds, env, ::add)
        }
        if (previous.extensionRepositories !== next.extensionRepositories) {
            val before = HashMap<String, ExtensionRepo>()
            previous.extensionRepositories.forEach { before[it.baseUrl] = it }
            val after = HashSet<String>()
            for (repo in next.extensionRepositories) {
                if (repo.baseUrl.isBlank() || !after.add(repo.baseUrl)) continue
                val ref = repositoryRef(repo.baseUrl)
                val old = before[repo.baseUrl]
                if (old == null) {
                    add(SyncKinds.REPOSITORY, ref, DirtyEntry.DELETED_FIELD, aux = "false")
                    REPOSITORY_FIELDS.forEach { add(SyncKinds.REPOSITORY, ref, it) }
                } else {
                    if (old.name != repo.name) add(SyncKinds.REPOSITORY, ref, "name")
                    if (old.signingKeyFingerprint != repo.signingKeyFingerprint) add(SyncKinds.REPOSITORY, ref, "trustedPublishers")
                }
            }
            // A repository removed here is deliberately not tombstoned: the account's list is a set of
            // suggestions for other devices, and deleting locally must not take the suggestion away.
            // Removing it from the account is an explicit act (SyncV3Service.forgetRepository).
        }

        if (previous.settings !== next.settings) {
            for (path in PortableSettings.changed(previous.settings, next.settings)) {
                add(SyncKinds.SETTING, settingRef(path), SETTING_FIELD)
            }
        }
        return out
    }

    val REPOSITORY_FIELDS = listOf("url", "name", "trustedPublishers")
    const val SETTING_FIELD = "value"

    private fun historyTouched(chapters: List<Chapter>, history: History, locatorChanged: Boolean, touched: MutableMap<Long, Boolean>) {
        val chapter = chapters.firstOrNull { it.id == history.chapterId } ?: return
        touched[chapter.mangaId] = (touched[chapter.mangaId] ?: false) || locatorChanged
    }

    private fun tombstoneEmptiedHistories(
        previous: AppSnapshot,
        next: AppSnapshot,
        removedChapterIds: List<Long>,
        env: SyncDiffEnv,
        add: (String, String, String, String?, String?, String?) -> Unit,
    ) {
        val removed = removedChapterIds.toHashSet()
        val mangaIds = LinkedHashSet<Long>()
        for (chapter in previous.chapters) if (chapter.id in removed) mangaIds += chapter.mangaId
        if (mangaIds.isEmpty()) return
        val chaptersOfAffected = HashMap<Long, MutableSet<Long>>()
        for (chapter in previous.chapters) if (chapter.mangaId in mangaIds) {
            chaptersOfAffected.getOrPut(chapter.mangaId) { HashSet() } += chapter.id
        }
        val stillHasHistory = HashSet<Long>()
        for (history in next.histories) {
            for ((mangaId, ids) in chaptersOfAffected) if (history.chapterId in ids) stillHasHistory += mangaId
        }
        for (mangaId in mangaIds) {
            if (mangaId in stillHasHistory) continue
            val manga = previous.mangas.firstOrNull { it.id == mangaId } ?: continue
            val (key, hash) = env.tombstoneWork(manga) ?: continue
            add(SyncKinds.HISTORY, mangaRef(mangaId), DirtyEntry.DELETED_FIELD, "true", key, hash)
            add(SyncKinds.PROGRESS, mangaRef(mangaId), DirtyEntry.DELETED_FIELD, "true", key, hash)
        }
    }

    private inline fun <T> diffList(
        previous: List<T>,
        next: List<T>,
        id: (T) -> Long,
        added: (T) -> Unit,
        removed: (T) -> Unit,
        changed: (T, T) -> Unit,
    ) {
        if (previous === next) return
        val common = minOf(previous.size, next.size)
        var aligned = true
        val changes = ArrayList<Int>(4)
        for (i in 0 until common) {
            val p = previous[i]
            val n = next[i]
            if (p === n) continue
            if (id(p) != id(n)) {
                aligned = false
                break
            }
            if (p != n) changes += i
        }
        if (aligned) {
            changes.forEach { changed(previous[it], next[it]) }
            for (i in common until next.size) added(next[i])
            for (i in common until previous.size) removed(previous[i])
            return
        }
        val before = HashMap<Long, T>(previous.size * 4 / 3 + 1)
        previous.forEach { before[id(it)] = it }
        val seen = HashSet<Long>(next.size * 4 / 3 + 1)
        for (n in next) {
            val key = id(n)
            seen += key
            val p = before[key]
            if (p == null) added(n) else if (p !== n && p != n) changed(p, n)
        }
        for (p in previous) if (id(p) !in seen) removed(p)
    }

    /**
     * First sign-in (§11): every syncable entity becomes dirty. Fields with a known modification
     * time use it as the HLC millis; others use 0 so a value already on the server wins.
     */
    fun markAll(snapshot: AppSnapshot, clockAt: (Long) -> String): List<DirtyEntry> {
        val out = ArrayList<DirtyEntry>(snapshot.mangas.size * 2 + snapshot.chapters.size)
        fun add(kind: String, ref: String, field: String, millis: Long, aux: String? = null) {
            out += DirtyEntry(kind, ref, field, clockAt(millis.coerceAtLeast(0)), aux)
        }
        val chapterById = HashMap<Long, Chapter>(snapshot.chapters.size * 4 / 3 + 1)
        snapshot.chapters.forEach { chapterById[it.id] = it }
        for (m in snapshot.mangas) {
            if (!m.favorite) continue
            val millis = m.favoriteModifiedAt ?: m.dateAdded
            // Live as of the time it was added: a removal the account already holds wins only
            // when it is newer than that.
            add(SyncKinds.LIBRARY, mangaRef(m.id), DirtyEntry.DELETED_FIELD, millis, aux = "false")
            add(SyncKinds.LIBRARY, mangaRef(m.id), "addedAt", millis)
            add(SyncKinds.LIBRARY, mangaRef(m.id), "categoryIds", millis)
        }
        for (c in snapshot.chapters) {
            val ref = chapterRef(c.id)
            if (c.read) add(SyncKinds.UNIT, ref, "read", c.lastModifiedAt)
            if (c.bookmark) add(SyncKinds.UNIT, ref, "bookmarked", c.lastModifiedAt)
            if (c.lastPageRead > 0) add(SyncKinds.UNIT, ref, "lastPageIndex", c.lastModifiedAt)
        }
        val latest = HashMap<Long, History>() // mangaId -> newest history row
        for (h in snapshot.histories) {
            val chapter = chapterById[h.chapterId] ?: continue
            val current = latest[chapter.mangaId]
            if (current == null || h.lastRead > current.lastRead) latest[chapter.mangaId] = h
        }
        for ((mangaId, h) in latest) {
            val ref = mangaRef(mangaId)
            add(SyncKinds.HISTORY, ref, "lastReadAt", h.lastRead)
            add(SyncKinds.HISTORY, ref, "unitRemoteId", h.lastRead)
            add(SyncKinds.PROGRESS, ref, "unitRemoteId", h.lastRead)
            if (h.lastLocator != null) {
                add(SyncKinds.PROGRESS, ref, "locator", h.lastRead)
                add(SyncKinds.PROGRESS, ref, "progression", h.lastRead)
            }
        }
        for (cat in snapshot.categories) {
            val ref = categoryRef(cat.id)
            if (cat.id != Category.Default.id) {
                add(SyncKinds.CATEGORY, ref, "name", 0)
                add(SyncKinds.CATEGORY, ref, "order", 0)
            }
            add(SyncKinds.CATEGORY, ref, "flags", 0)
        }
        val repositories = HashSet<String>()
        for (repo in snapshot.extensionRepositories) {
            if (repo.baseUrl.isBlank() || !repositories.add(repo.baseUrl)) continue
            REPOSITORY_FIELDS.forEach { add(SyncKinds.REPOSITORY, repositoryRef(repo.baseUrl), it, 0) }
        }
        // Only what this device customised: a fresh install must not replace the account's
        // settings with defaults, and at millis 0 a value already on the server wins anyway.
        for (path in PortableSettings.changed(AppSettings(), snapshot.settings)) {
            add(SyncKinds.SETTING, settingRef(path), SETTING_FIELD, 0)
        }
        return out
    }
}
