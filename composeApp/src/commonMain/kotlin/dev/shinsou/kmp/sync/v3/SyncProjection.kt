package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.AppSnapshotJson
import dev.shinsou.kmp.domain.model.Category
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.History
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.MangaCategory
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.LOCAL_SOURCE_ID
import dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.validate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** A [ReadingLocator] without its device-local scope (records.md §3). */
internal object PortableLocator {
    private val json get() = AppSnapshotJson.format

    fun toWire(locator: ReadingLocator): JsonObject {
        val encoded = json.encodeToJsonElement(ReadingLocator.serializer(), locator) as JsonObject
        return JsonObject(encoded.filterKeys { it != "scope" })
    }

    fun fromWire(wire: JsonObject, scope: ReadingScope): ReadingLocator? = runCatching {
        val withScope = JsonObject(wire + ("scope" to json.encodeToJsonElement(ReadingScope.serializer(), scope)))
        json.decodeFromJsonElement(ReadingLocator.serializer(), withScope).also { it.validate() }
    }.getOrNull()
}

/** Reads the current local value of a syncable field from a snapshot (push side). */
internal class SyncOutgoing(
    private val snapshot: AppSnapshot,
    private val identity: SyncIdentityResolver,
    private val categoryKey: (Long) -> String?,
) {
    private val linksByManga: Map<Long, List<Long>> by lazy {
        val map = HashMap<Long, MutableList<Long>>()
        snapshot.mangaCategories.forEach { map.getOrPut(it.mangaId) { ArrayList() } += it.categoryId }
        map
    }
    private val latestHistory: Map<Long, Pair<History, Chapter>> by lazy {
        val chapters = HashMap<Long, Chapter>(snapshot.chapters.size * 4 / 3 + 1)
        snapshot.chapters.forEach { chapters[it.id] = it }
        val latest = HashMap<Long, Pair<History, Chapter>>()
        for (h in snapshot.histories) {
            val chapter = chapters[h.chapterId] ?: continue
            val current = latest[chapter.mangaId]
            if (current == null || h.lastRead > current.first.lastRead) latest[chapter.mangaId] = h to chapter
        }
        latest
    }
    val mangaById: Map<Long, Manga> by lazy { snapshot.mangas.associateBy { it.id } }
    val chapterById: Map<Long, Chapter> by lazy { snapshot.chapters.associateBy { it.id } }
    val categoryById: Map<Long, Category> by lazy { snapshot.categories.associateBy { it.id } }
    val repositoryByUrl: Map<String, ExtensionRepo> by lazy { snapshot.extensionRepositories.associateBy { it.baseUrl } }
    private val settingsJson: JsonObject by lazy { PortableSettings.encode(snapshot.settings) }

    fun libraryValue(manga: Manga, field: String): JsonElement? = when (field) {
        "addedAt" -> JsonPrimitive(manga.dateAdded)
        "categoryIds" -> JsonArray(
            (linksByManga[manga.id].orEmpty())
                .filter { it != Category.Default.id }
                .mapNotNull(categoryKey)
                .sorted()
                .map(::JsonPrimitive),
        )
        else -> null
    }

    fun unitValue(chapter: Chapter, field: String): JsonElement? = when (field) {
        "read" -> JsonPrimitive(chapter.read)
        "bookmarked" -> JsonPrimitive(chapter.bookmark)
        "lastPageIndex" -> JsonPrimitive(chapter.lastPageRead)
        else -> null
    }

    fun historyValue(manga: Manga, work: SyncedWork, field: String): JsonElement? {
        val (history, chapter) = latestHistory[manga.id] ?: return null
        return when (field) {
            "lastReadAt" -> JsonPrimitive(history.lastRead)
            "unitRemoteId" -> identity.unitRemoteId(manga, work, chapter)?.let(::JsonPrimitive)
            else -> null
        }
    }

    fun progressValue(manga: Manga, work: SyncedWork, field: String): JsonElement? {
        val (history, chapter) = latestHistory[manga.id] ?: return null
        return when (field) {
            "unitRemoteId" -> identity.unitRemoteId(manga, work, chapter)?.let(::JsonPrimitive)
            "locator" -> history.lastLocator?.let(PortableLocator::toWire)
            "progression" -> history.lastLocator?.progression?.takeIf { it.isFinite() }?.let(::JsonPrimitive)
            else -> null
        }
    }

    fun categoryValue(category: Category, field: String): JsonElement? = when (field) {
        "name" -> JsonPrimitive(category.name)
        "order" -> JsonPrimitive(category.sort)
        "flags" -> JsonPrimitive(category.flags)
        else -> null
    }

    /**
     * `trustedPublishers` only says which key the repository was signed with when it was added.
     * Trust itself is granted per device and never travels (see [SyncApplier.applyRepository]).
     */
    fun repositoryValue(repo: ExtensionRepo, field: String): JsonElement? = when (field) {
        "url" -> JsonPrimitive(repo.baseUrl)
        "name" -> JsonPrimitive(repo.name.truncated(MAX_REPOSITORY_NAME_CHARS))
        "trustedPublishers" -> JsonArray(listOfNotNull(repo.signingKeyFingerprint.takeIf { it.isNotBlank() }?.let(::JsonPrimitive)))
        else -> null
    }

    fun settingValue(path: String, field: String): JsonElement? =
        if (field == SyncDiff.SETTING_FIELD && PortableSettings.isPortable(path)) PortableSettings.valueAt(settingsJson, path) else null
}

internal const val MAX_REPOSITORY_NAME_CHARS = 200
internal const val MAX_REPOSITORY_URL_CHARS = 2048

/** Pending reason of a title whose source is not installed on this device. */
internal const val PENDING_SOURCE_NOT_INSTALLED = "source_not_installed"
private const val MAX_FINGERPRINT_CHARS = 128

/** A repository URL another device may suggest: bounded, http(s), and free of whitespace or control characters. */
internal fun isPortableRepositoryUrl(url: String): Boolean =
    url.length <= MAX_REPOSITORY_URL_CHARS &&
        (url.startsWith("https://") || url.startsWith("http://")) &&
        url.substringAfter("://").isNotEmpty() &&
        url.none { it.isWhitespace() || it.isISOControl() }

internal sealed interface ApplyOutcome {
    data object Applied : ApplyOutcome
    data class Pending(val reason: String) : ApplyOutcome
}

internal class SyncApplyContext(
    val identity: SyncIdentityResolver,
    /** Category uuid to local id, as stored in the category map. */
    val categoryBindings: Map<String, Long>,
    /** Work hash to the installed v2 source that may materialise it (see SyncService). */
    val creatable: Map<String, SourceKey>,
    /** Fields of the entity that were edited since the records were merged; never overwritten. */
    val dirtyFields: (kind: String, ref: String) -> Set<String>,
    val nowMillis: Long,
)

internal class SyncApplyResult(
    val snapshot: AppSnapshot,
    val outcomes: Map<Pair<String, String>, ApplyOutcome>,
    val newBindings: Map<String, Long>,
    val removedBindings: Set<String>,
    val changed: Boolean,
)

/**
 * Projects merged server records onto an [AppSnapshot]. It is a pure function of its inputs so
 * the caller can retry it against a newer snapshot when a compare-and-swap loses. Fields with
 * unpushed local edits are skipped, and nothing here marks anything dirty: the result goes through
 * the repository's sync replacement path, which does not notify the mutation observer.
 */
internal class SyncApplier(
    snapshot: AppSnapshot,
    private val ctx: SyncApplyContext,
    private val workIndex: MutableMap<String, Long>,
) {
    private val original = snapshot
    private var mangas = snapshot.mangas
    private var chapters = snapshot.chapters
    private var categories = snapshot.categories
    private var links = snapshot.mangaCategories
    private var histories = snapshot.histories
    private var settings = snapshot.settings
    private var repositories = snapshot.extensionRepositories
    private var changed = false

    private val mangaIndex: HashMap<Long, Int> by lazy { indexOf(mangas) { it.id } }
    private val chapterIndex: HashMap<Long, Int> by lazy { indexOf(chapters) { it.id } }
    private val chaptersByManga: HashMap<Long, MutableList<Int>> by lazy {
        val map = HashMap<Long, MutableList<Int>>()
        chapters.forEachIndexed { i, c -> map.getOrPut(c.mangaId) { ArrayList() } += i }
        map
    }
    private val unitIndexCache = HashMap<Long, Map<String, Long>>()

    private val outcomes = HashMap<Pair<String, String>, ApplyOutcome>()
    private val newBindings = LinkedHashMap<String, Long>()
    private val removedBindings = LinkedHashSet<String>()

    private val bindings: Map<String, Long> get() = ctx.categoryBindings + newBindings - removedBindings

    fun apply(records: List<StoredRecord>): SyncApplyResult {
        for (record in records) {
            outcomes[record.kind to record.key] = try {
                when (record.kind) {
                    SyncKinds.CATEGORY -> applyCategory(record)
                    SyncKinds.LIBRARY -> applyLibrary(record)
                    SyncKinds.UNIT -> applyUnit(record)
                    SyncKinds.HISTORY -> applyHistory(record)
                    SyncKinds.PROGRESS -> applyProgress(record)
                    SyncKinds.REPOSITORY -> applyRepository(record)
                    SyncKinds.SETTING -> applySetting(record)
                    // Packages are read straight from the sync tables (SyncV3Service.accountPackages);
                    // they never change the snapshot. Kinds this version does not know are kept untouched.
                    SyncKinds.PACKAGE -> ApplyOutcome.Applied
                    else -> ApplyOutcome.Applied
                }
            } catch (error: IllegalArgumentException) {
                ApplyOutcome.Pending("invalid:${error.message.orEmpty().take(80)}")
            }
        }
        val next = if (!changed) original else original.copy(
            mangas = mangas,
            chapters = chapters,
            categories = categories,
            mangaCategories = links,
            histories = histories,
            settings = settings,
            extensionRepositories = repositories,
        )
        return SyncApplyResult(next, outcomes, newBindings, removedBindings, changed)
    }

    // ---- category ---------------------------------------------------------------------------

    private fun applyCategory(r: StoredRecord): ApplyOutcome {
        val st = r.state
        val isDefault = r.key == DEFAULT_CATEGORY_KEY
        val localId = if (isDefault) Category.Default.id else bindings[r.key]
        val ref = localId?.let(::categoryRef)
        val dirty = ref?.let { ctx.dirtyFields(SyncKinds.CATEGORY, it) }.orEmpty()
        if (!st.isLive) {
            if (!isDefault && localId != null && DirtyEntry.DELETED_FIELD !in dirty) removeCategory(localId)
            if (!isDefault) removedBindings += r.key
            return ApplyOutcome.Applied
        }
        fun field(name: String): JsonElement? =
            st.fields[name]?.takeIf { !it.dirty && name !in dirty }?.value
        if (localId == null) {
            val name = st.fields["name"]?.value?.str()?.trim()?.takeIf { it.isNotEmpty() }
                ?: return ApplyOutcome.Pending("category_incomplete")
            val adopted = categories.firstOrNull {
                it.id != Category.Default.id && it.name.trim().equals(name, ignoreCase = true) && it.id !in bindings.values
            }
            if (adopted != null) {
                newBindings[r.key] = adopted.id
                updateCategory(adopted.id, null, st.fields["order"]?.value?.long()?.toInt(), st.fields["flags"]?.value?.long())
            } else {
                val id = (categories.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
                val sort = st.fields["order"]?.value?.long()?.toInt() ?: ((categories.maxOfOrNull { it.sort } ?: -1) + 1)
                categories = categories + Category(id = id, name = name, sort = sort, flags = st.fields["flags"]?.value?.long() ?: 0L)
                newBindings[r.key] = id
                changed = true
            }
            return ApplyOutcome.Applied
        }
        updateCategory(
            localId,
            if (isDefault) null else field("name")?.str()?.trim()?.takeIf { it.isNotEmpty() },
            if (isDefault) null else field("order")?.long()?.toInt(),
            field("flags")?.long(),
        )
        return ApplyOutcome.Applied
    }

    private fun updateCategory(id: Long, name: String?, sort: Int?, flags: Long?) {
        val index = categories.indexOfFirst { it.id == id }
        if (index < 0) return
        val current = categories[index]
        val updated = current.copy(name = name ?: current.name, sort = sort ?: current.sort, flags = flags ?: current.flags)
        if (updated != current) {
            categories = categories.toMutableList().also { it[index] = updated }
            changed = true
        }
    }

    private fun removeCategory(id: Long) {
        if (id == Category.Default.id || categories.none { it.id == id }) return
        val affected = links.filter { it.categoryId == id }.mapTo(HashSet()) { it.mangaId }
        val kept = links.filterNot { it.categoryId == id }.toMutableList()
        affected.forEach { mangaId ->
            if (kept.none { it.mangaId == mangaId }) kept += MangaCategory(mangaId, Category.Default.id)
        }
        links = kept
        categories = categories.filterNot { it.id == id }
        if (settings.library.defaultCategoryId == id) {
            settings = settings.copy(library = settings.library.copy(defaultCategoryId = Category.Default.id))
        }
        changed = true
    }

    // ---- repository, setting ----------------------------------------------------------------

    /**
     * Keeps the name and signing key of a repository this device already has in step with the
     * account. It never adds or removes an entry: the account's repository records are suggestions
     * for other devices (surfaced through `SyncV3Service.accountRepositories`), and the list a
     * device actually uses is decided by its own user. Trust is likewise granted per device and
     * never travels.
     */
    private fun applyRepository(r: StoredRecord): ApplyOutcome {
        val url = r.key
        if (!r.state.isLive) return ApplyOutcome.Applied
        val index = repositories.indexOfFirst { it.baseUrl == url }
        if (index < 0) return ApplyOutcome.Applied
        val dirty = ctx.dirtyFields(SyncKinds.REPOSITORY, repositoryRef(url))
        fun field(name: String): JsonElement? =
            r.state.fields[name]?.takeIf { !it.dirty && name !in dirty }?.value
        val name = field("name")?.str()?.trim()?.takeIf { it.isNotEmpty() }?.truncated(MAX_REPOSITORY_NAME_CHARS)
        val fingerprint = (field("trustedPublishers") as? JsonArray)?.let { list ->
            list.firstOrNull()?.str()?.trim()?.takeIf { it.length <= MAX_FINGERPRINT_CHARS }.orEmpty()
        }
        val current = repositories[index]
        val updated = current.copy(
            name = name ?: current.name,
            signingKeyFingerprint = fingerprint ?: current.signingKeyFingerprint,
        )
        if (updated != current) {
            repositories = repositories.toMutableList().also { it[index] = updated }
            changed = true
        }
        return ApplyOutcome.Applied
    }

    private fun applySetting(r: StoredRecord): ApplyOutcome {
        // A setting this version does not carry, or a deletion (settings are never deleted).
        if (!PortableSettings.isPortable(r.key) || !r.state.isLive) return ApplyOutcome.Applied
        val dirty = ctx.dirtyFields(SyncKinds.SETTING, settingRef(r.key))
        val value = r.state.fields[SyncDiff.SETTING_FIELD]
            ?.takeIf { !it.dirty && SyncDiff.SETTING_FIELD !in dirty }?.value
            ?: return ApplyOutcome.Applied
        val updated = PortableSettings.patch(settings, r.key, value)
        if (updated != settings) {
            settings = updated
            changed = true
        }
        return ApplyOutcome.Applied
    }

    // ---- library ----------------------------------------------------------------------------

    private fun applyLibrary(r: StoredRecord): ApplyOutcome {
        val st = r.state
        val mangaId = mangaIdFor(r.key)
        val dirty = mangaId?.let { ctx.dirtyFields(SyncKinds.LIBRARY, mangaRef(it)) }.orEmpty()
        if (!st.isLive) {
            if (mangaId != null && DirtyEntry.DELETED_FIELD !in dirty) setFavorite(mangaId, false)
            return ApplyOutcome.Applied
        }
        fun field(name: String): JsonElement? = st.fields[name]?.takeIf { !it.dirty && name !in dirty }?.value
        val categoryIds = field("categoryIds")?.let { it as? JsonArray }?.mapNotNull { it.str() }

        if (mangaId == null) return createFromRecord(r, field("addedAt")?.long(), categoryIds)
        val index = mangaIndex.getValue(mangaId)
        var manga = mangas[index]
        if (!manga.favorite && DirtyEntry.DELETED_FIELD !in dirty) {
            setFavorite(mangaId, true)
            manga = mangas[index]
        }
        field("addedAt")?.long()?.let { added ->
            if (manga.dateAdded != added) {
                manga = manga.copy(dateAdded = added)
                replaceManga(index, manga)
            }
        }
        if (categoryIds != null && manga.favorite) setLinks(mangaId, categoryIds)
        return ApplyOutcome.Applied
    }

    private fun createFromRecord(r: StoredRecord, addedAt: Long?, categoryIds: List<String>?): ApplyOutcome {
        if (!r.key.matches(HASH)) return ApplyOutcome.Pending("local_content_missing")
        val work = r.workJson?.let { runCatching { SyncWireJson.decodeFromString(WireWork.serializer(), it) }.getOrNull() }
            ?: return ApplyOutcome.Pending("work_unknown")
        val sourceKey = ctx.creatable[r.key] ?: return ApplyOutcome.Pending(PENDING_SOURCE_NOT_INSTALLED)
        val url = encodeExtensionLibraryPublicationUrl(sourceKey, work.remoteId)
        val id = (mangas.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        val now = ctx.nowMillis
        val manga = Manga(
            id = id,
            source = LOCAL_SOURCE_ID,
            favorite = true,
            dateAdded = addedAt ?: now,
            url = url,
            title = work.title,
            author = work.author,
            genre = listOf("Extension"),
            thumbnailUrl = work.coverUrl,
            updateStrategy = 1,
            initialized = true,
            lastModifiedAt = now,
            favoriteModifiedAt = now,
            version = 1,
        )
        mangas = mangas + manga
        mangaIndex[id] = mangas.size - 1
        workIndex[r.key] = id
        changed = true
        setLinks(id, categoryIds.orEmpty())
        return ApplyOutcome.Applied
    }

    private fun setFavorite(mangaId: Long, favorite: Boolean) {
        val index = mangaIndex[mangaId] ?: return
        val current = mangas[index]
        if (current.favorite == favorite) return
        val stamp = maxOf(ctx.nowMillis, current.lastModifiedAt, current.favoriteModifiedAt ?: Long.MIN_VALUE)
        replaceManga(
            index,
            current.copy(favorite = favorite, lastModifiedAt = stamp, favoriteModifiedAt = stamp, version = current.version + 1),
        )
        if (!favorite) {
            links = links.filterNot { it.mangaId == mangaId }
        } else if (links.none { it.mangaId == mangaId }) {
            val configured = settings.library.defaultCategoryId
            val target = configured.takeIf { id -> categories.any { it.id == id } } ?: Category.Default.id
            links = links + MangaCategory(mangaId, target)
        }
        changed = true
    }

    private fun setLinks(mangaId: Long, categoryKeys: List<String>) {
        val ids = categoryKeys.mapNotNull { key -> if (key == DEFAULT_CATEGORY_KEY) Category.Default.id else bindings[key] }
            .filter { id -> categories.any { it.id == id } }
            .distinct()
            .ifEmpty { listOf(Category.Default.id) }
        val current = links.filter { it.mangaId == mangaId }.map { it.categoryId }.toSet()
        if (current == ids.toSet()) return
        links = links.filterNot { it.mangaId == mangaId } + ids.map { MangaCategory(mangaId, it) }
        changed = true
    }

    private fun replaceManga(index: Int, manga: Manga) {
        mangas = mangas.toMutableList().also { it[index] = manga }
        changed = true
    }

    // ---- unit -------------------------------------------------------------------------------

    private fun applyUnit(r: StoredRecord): ApplyOutcome {
        val workHash = r.workHash ?: return ApplyOutcome.Pending("work_unknown")
        val unitId = r.unitRemoteId ?: return ApplyOutcome.Pending("unit_unknown")
        val mangaId = mangaIdFor(workHash) ?: return ApplyOutcome.Pending("work_not_in_library")
        val chapterId = unitIndex(mangaId)[unitId] ?: return ApplyOutcome.Pending("unit_unknown")
        if (!r.state.isLive) return ApplyOutcome.Applied
        val dirty = ctx.dirtyFields(SyncKinds.UNIT, chapterRef(chapterId))
        fun field(name: String): JsonElement? = r.state.fields[name]?.takeIf { !it.dirty && name !in dirty }?.value
        val index = chapterIndex[chapterId] ?: return ApplyOutcome.Pending("unit_unknown")
        val current = chapters[index]
        val updated = current.copy(
            read = field("read")?.bool() ?: current.read,
            bookmark = field("bookmarked")?.bool() ?: current.bookmark,
            lastPageRead = field("lastPageIndex")?.long()?.toInt()?.coerceAtLeast(0) ?: current.lastPageRead,
        )
        if (updated != current) {
            chapters = chapters.toMutableList().also { it[index] = updated }
            if (updated.lastPageRead != current.lastPageRead) fixPageCount(chapterId, updated.lastPageRead)
            changed = true
        }
        return ApplyOutcome.Applied
    }

    /** A page index must stay inside the page count recorded with the history entry. */
    private fun fixPageCount(chapterId: Long, lastPageRead: Int) {
        val i = histories.indexOfFirst { it.chapterId == chapterId }
        if (i < 0) return
        val h = histories[i]
        if (h.lastPageCount != null && lastPageRead >= h.lastPageCount) {
            histories = histories.toMutableList().also { it[i] = h.copy(lastPageCount = null) }
        }
    }

    // ---- history / progress -----------------------------------------------------------------

    private fun applyHistory(r: StoredRecord): ApplyOutcome {
        val mangaId = mangaIdFor(r.key) ?: return ApplyOutcome.Pending("work_not_in_library")
        val dirty = ctx.dirtyFields(SyncKinds.HISTORY, mangaRef(mangaId))
        if (!r.state.isLive) {
            if (DirtyEntry.DELETED_FIELD !in dirty) {
                val ids = chapterIdsOf(mangaId)
                val kept = histories.filterNot { it.chapterId in ids }
                if (kept.size != histories.size) {
                    histories = kept
                    changed = true
                }
            }
            return ApplyOutcome.Applied
        }
        fun field(name: String): JsonElement? = r.state.fields[name]?.takeIf { !it.dirty && name !in dirty }?.value
        val unitId = field("unitRemoteId")?.str()
        val lastRead = field("lastReadAt")?.long()
        if (unitId == null || lastRead == null) return ApplyOutcome.Applied
        val chapterId = unitIndex(mangaId)[unitId] ?: return ApplyOutcome.Pending("unit_unknown")
        val index = histories.indexOfFirst { it.chapterId == chapterId }
        if (index >= 0) {
            val current = histories[index]
            if (current.lastRead != lastRead) {
                histories = histories.toMutableList().also { it[index] = current.copy(lastRead = lastRead.coerceAtLeast(0)) }
                changed = true
            }
        } else {
            addHistory(chapterId, lastRead.coerceAtLeast(0), null)
        }
        return ApplyOutcome.Applied
    }

    private fun applyProgress(r: StoredRecord): ApplyOutcome {
        val mangaId = mangaIdFor(r.key) ?: return ApplyOutcome.Pending("work_not_in_library")
        if (!r.state.isLive) return ApplyOutcome.Applied
        val dirty = ctx.dirtyFields(SyncKinds.PROGRESS, mangaRef(mangaId))
        fun field(name: String): JsonElement? = r.state.fields[name]?.takeIf { !it.dirty && name !in dirty }?.value
        val unitId = (field("unitRemoteId") ?: field("unitId"))?.str() ?: return ApplyOutcome.Applied
        val locatorJson = field("locator") as? JsonObject ?: return ApplyOutcome.Applied
        val chapterId = unitIndex(mangaId)[unitId] ?: return ApplyOutcome.Pending("unit_unknown")
        val chapter = chapters[chapterIndex.getValue(chapterId)]
        // Legacy chapters have no typed scope; their position travels in the unit record instead.
        val scope = ctx.identity.scopeFor(chapter) ?: return ApplyOutcome.Applied
        val locator = PortableLocator.fromWire(locatorJson, scope) ?: return ApplyOutcome.Applied
        val index = histories.indexOfFirst { it.chapterId == chapterId }
        if (index >= 0) {
            val current = histories[index]
            if (current.lastLocator != locator) {
                histories = histories.toMutableList().also { it[index] = current.copy(lastLocator = locator) }
                changed = true
            }
        } else {
            addHistory(chapterId, 0, locator)
        }
        return ApplyOutcome.Applied
    }

    private fun addHistory(chapterId: Long, lastRead: Long, locator: ReadingLocator?) {
        val id = (histories.maxOfOrNull { it.id } ?: 0L).coerceAtLeast(0L) + 1L
        histories = histories + History(id = id, chapterId = chapterId, lastRead = lastRead, lastLocator = locator)
        changed = true
    }

    // ---- helpers ----------------------------------------------------------------------------

    /** The local title for a record key; an index entry whose title has vanished counts as absent. */
    private fun mangaIdFor(recordKey: String): Long? = workIndex[recordKey]?.takeIf { it in mangaIndex }

    private fun chapterIdsOf(mangaId: Long): Set<Long> =
        chaptersByManga[mangaId].orEmpty().mapTo(HashSet()) { chapters[it].id }

    private fun unitIndex(mangaId: Long): Map<String, Long> = unitIndexCache.getOrPut(mangaId) {
        val manga = mangas.getOrNull(mangaIndex[mangaId] ?: return@getOrPut emptyMap()) ?: return@getOrPut emptyMap()
        val work = ctx.identity.workOf(manga) ?: return@getOrPut emptyMap()
        val map = HashMap<String, Long>()
        for (i in chaptersByManga[mangaId].orEmpty()) {
            val chapter = chapters[i]
            ctx.identity.unitRemoteId(manga, work, chapter)?.let { if (it !in map) map[it] = chapter.id }
        }
        map
    }

    private inline fun <T> indexOf(list: List<T>, id: (T) -> Long): HashMap<Long, Int> {
        val map = HashMap<Long, Int>(list.size * 4 / 3 + 1)
        list.forEachIndexed { i, item -> map[id(item)] = i }
        return map
    }

    private fun JsonElement.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement.long(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
    private fun JsonElement.bool(): Boolean? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

    companion object {
        const val DEFAULT_CATEGORY_KEY: String = "default"
        private val HASH = Regex("^[0-9a-f]{64}$")

        /** `recordKey -> mangaId` for every title that has a portable identity. */
        fun buildWorkIndex(snapshot: AppSnapshot, identity: SyncIdentityResolver): MutableMap<String, Long> {
            val map = HashMap<String, Long>(snapshot.mangas.size * 4 / 3 + 1)
            for (manga in snapshot.mangas) {
                identity.workOf(manga)?.let { if (it.recordKey !in map) map[it.recordKey] = manga.id }
            }
            return map
        }
    }
}
