package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.reader.JmImageDescrambler
import dev.shinsou.kmp.reader.ReaderImageTransform

public interface Source {
    public val id: Long
    public val name: String
    public val lang: String

    public suspend fun getMangaDetails(manga: SManga): SManga
    public suspend fun getChapterList(manga: SManga): List<SChapter>
    public suspend fun getPageList(chapter: SChapter): List<Page>
}

public interface CatalogueSource : Source {
    public val supportsLatest: Boolean
    /** Whether the source exposes an account-owned remote collection. */
    public val supportsFavorites: Boolean get() = false
    public val baseUrl: String
    public val headers: Map<String, String> get() = emptyMap()

    public suspend fun getPopularManga(page: Int): MangasPage
    public suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage
    public suspend fun getLatestUpdates(page: Int): MangasPage
    /** Loads the source-owned collection without mixing it with the host's local library. */
    public suspend fun getFavoriteManga(page: Int): MangasPage = MangasPage(emptyList(), false)
    public suspend fun getFilterList(): FilterList
}

public interface LoginSource {
    public val supportsLogin: Boolean
    public suspend fun login(username: String, password: String): Boolean
    public suspend fun logout()
}

/** A source that describes settings rendered and persisted by the host application. */
public interface ConfigurableSource : Source {
    public val preferenceKey: String get() = id.toString()
    public suspend fun getPreferenceDefinitions(): List<SourcePreference> = emptyList()
}

public data class SManga(
    val url: String = "",
    val title: String = "",
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val thumbnailUrl: String? = null,
    val updateStrategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE,
    val initialized: Boolean = false,
)

public enum class MangaStatus(public val value: Int) {
    UNKNOWN(0),
    ONGOING(1),
    COMPLETED(2),
    LICENSED(3),
    PUBLISHING_FINISHED(4),
    CANCELLED(5),
    ON_HIATUS(6);

    public companion object {
        public fun fromValue(value: Int): MangaStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

public enum class UpdateStrategy(public val value: Int) {
    ALWAYS_UPDATE(0),
    ONLY_FETCH_ONCE(1),
}

public data class SChapter(
    val url: String = "",
    val name: String = "",
    val scanlator: String? = null,
    val dateUpload: Long = 0,
    val chapterNumber: Double = -1.0,
)

public data class Page(
    val index: Int,
    val url: String = "",
    val imageUrl: String? = null,
)

public data class MangasPage(
    val mangas: List<SManga>,
    val hasNextPage: Boolean,
)

public typealias FilterList = List<Filter>

public sealed interface Filter {
    public val name: String

    public data class Header(override val name: String) : Filter
    public data object Separator : Filter { override val name: String = "" }
    public data class Select(override val name: String, val values: List<String>, val state: Int) : Filter
    public data class Text(override val name: String, val state: String) : Filter
    public data class CheckBox(override val name: String, val state: Boolean) : Filter
    public data class TriState(override val name: String, val state: TriStateValue) : Filter
    public data class Group(override val name: String, val filters: FilterList) : Filter
    public data class Sort(override val name: String, val values: List<String>, val selection: SortSelection?) : Filter
}

public enum class TriStateValue(public val value: Int) {
    IGNORE(0), INCLUDE(1), EXCLUDE(2);

    public companion object {
        public fun fromValue(value: Int): TriStateValue = entries.firstOrNull { it.value == value } ?: IGNORE
    }
}

public data class SortSelection(val index: Int, val ascending: Boolean)

public sealed interface SourcePreference {
    public val key: String
    public val title: String

    public data class TextField(
        override val key: String,
        override val title: String,
        val summary: String,
        val defaultValue: String,
    ) : SourcePreference

    public data class Toggle(
        override val key: String,
        override val title: String,
        val summary: String,
        val defaultValue: Boolean,
    ) : SourcePreference

    public data class Select(
        override val key: String,
        override val title: String,
        val entries: List<String>,
        val entryValues: List<String>,
        val defaultValue: String,
    ) : SourcePreference

    public data class MultiSelect(
        override val key: String,
        override val title: String,
        val entries: List<String>,
        val entryValues: List<String>,
        val defaultValues: Set<String>,
    ) : SourcePreference
}

/** Parses Shinsou's URL-fragment convention used for image headers and JM metadata. */
public data class PageRequestMetadata(
    val cleanUrl: String,
    val headers: Map<String, String>,
    val metadata: Map<String, String>,
) {
    public fun imageTransform(sourceId: Long): ReaderImageTransform? =
        JmImageDescrambler.transform(sourceId, metadata)

    public companion object {
        private val metadataKeys = setOf(
            JmImageDescrambler.SCRAMBLE_ID_KEY,
            JmImageDescrambler.PHOTO_ID_KEY,
            JmImageDescrambler.FILENAME_KEY,
        )

        public fun parse(value: String): PageRequestMetadata {
            val hash = value.indexOf('#')
            if (hash < 0) return PageRequestMetadata(value, emptyMap(), emptyMap())
            val pairs = value.substring(hash + 1).split('&').mapNotNull { pair ->
                val separator = pair.indexOf('=')
                if (separator <= 0) null else decodeComponent(pair.substring(0, separator)) to
                    decodeComponent(pair.substring(separator + 1))
            }.toMap()
            return PageRequestMetadata(
                cleanUrl = value.substring(0, hash),
                headers = pairs.filterKeys { it !in metadataKeys },
                metadata = pairs.filterKeys { it in metadataKeys },
            )
        }

        private fun decodeComponent(value: String): String {
            val bytes = ArrayList<Byte>(value.length)
            val result = StringBuilder(value.length)
            var index = 0
            fun flush() {
                if (bytes.isNotEmpty()) {
                    result.append(bytes.toByteArray().decodeToString())
                    bytes.clear()
                }
            }
            while (index < value.length) {
                if (value[index] == '%' && index + 2 < value.length) {
                    val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
                    if (byte != null) {
                        bytes += byte.toByte()
                        index += 3
                        continue
                    }
                }
                flush()
                result.append(if (value[index] == '+') ' ' else value[index])
                index++
            }
            flush()
            return result.toString()
        }
    }
}
