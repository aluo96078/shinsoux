package dev.shinsou.kmp

import dev.shinsou.kmp.data.AppSnapshot
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDefaults
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding

/** Publishes a small, non-sensitive library projection to the Widget extension's App Group. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal object IosWidgetPublisher {
    const val DID_CHANGE_NOTIFICATION = "dev.aluo.shinsoux.widget-library.changed"

    private const val APP_GROUP = "group.dev.aluo.shinsoux"
    private const val LIBRARY_KEY = "widget.library"
    private const val MAX_WIDGET_ITEMS = 12

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
    }
    private val serializer = ListSerializer(IosWidgetManga.serializer())
    private var lastPayload: String? = null

    fun publish(snapshot: AppSnapshot) {
        val payload = json.encodeToString(serializer, snapshot.toWidgetManga())
        if (payload == lastPayload) return

        val data = NSString.create(string = payload).dataUsingEncoding(NSUTF8StringEncoding)
            ?: return
        val defaults = NSUserDefaults(suiteName = APP_GROUP)
        defaults.setObject(data, forKey = LIBRARY_KEY)
        defaults.synchronize()
        lastPayload = payload
        NSNotificationCenter.defaultCenter.postNotificationName(
            DID_CHANGE_NOTIFICATION,
            `object` = null,
        )
    }

    private fun AppSnapshot.toWidgetManga(): List<IosWidgetManga> {
        val chaptersByManga = chapters.groupBy { it.mangaId }
        val chapterById = chapters.associateBy { it.id }
        val latestHistoryByManga = mutableMapOf<Long, Pair<Long, Long>>()
        histories.forEach { history ->
            val mangaId = chapterById[history.chapterId]?.mangaId ?: return@forEach
            val previous = latestHistoryByManga[mangaId]
            if (previous == null || history.lastRead > previous.first) {
                latestHistoryByManga[mangaId] = history.lastRead to history.chapterId
            }
        }

        return mangas.asSequence()
            .filter { it.favorite }
            .map { manga ->
                val mangaChapters = chaptersByManga[manga.id].orEmpty()
                val chapterUpdate = mangaChapters.maxOfOrNull { maxOf(it.dateUpload, it.dateFetch) } ?: 0L
                IosWidgetManga(
                    id = manga.id,
                    title = manga.title,
                    coverURL = manga.thumbnailUrl,
                    unreadCount = mangaChapters.count { !it.read },
                    lastReadChapterID = latestHistoryByManga[manga.id]?.second,
                    updatedAt = maxOf(manga.lastUpdate, chapterUpdate),
                )
            }
            .sortedWith(compareByDescending<IosWidgetManga> { it.updatedAt }.thenBy { it.title.lowercase() })
            .take(MAX_WIDGET_ITEMS)
            .toList()
    }
}

@Serializable
private data class IosWidgetManga(
    val id: Long,
    val title: String,
    val coverURL: String?,
    val unreadCount: Int,
    val lastReadChapterID: Long?,
    val updatedAt: Long,
)
