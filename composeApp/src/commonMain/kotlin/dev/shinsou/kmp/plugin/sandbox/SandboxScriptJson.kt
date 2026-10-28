package dev.shinsou.kmp.plugin.sandbox

import dev.shinsou.kmp.plugin.Filter
import dev.shinsou.kmp.plugin.LoginAttemptResult
import dev.shinsou.kmp.plugin.MangaStatus
import dev.shinsou.kmp.plugin.MangasPage
import dev.shinsou.kmp.plugin.Page
import dev.shinsou.kmp.plugin.PluginExecutionLimits
import dev.shinsou.kmp.plugin.PluginJson
import dev.shinsou.kmp.plugin.PluginResourceLimitException
import dev.shinsou.kmp.plugin.SChapter
import dev.shinsou.kmp.plugin.SManga
import dev.shinsou.kmp.plugin.SortSelection
import dev.shinsou.kmp.plugin.SourcePreference
import dev.shinsou.kmp.plugin.TriStateValue
import dev.shinsou.kmp.plugin.pluginJsonShapeWithin
import dev.shinsou.kmp.plugin.pluginUtf8ByteCountAtMost
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON shapes exchanged with sandboxed scripts. They are deliberately identical to the
 * JavaScriptCore engine's (`JavaScriptCoreScriptPluginRuntime.kt`), because the sandbox runs the
 * same bootstrap and the same scripts. (Consolidating both copies is S4 work, ADR-0002 §17.)
 */
internal object SandboxScriptJson {
    /**
     * Parses a sandbox result with the host's result budgets: shape first (before materializing),
     * then string sizes. A violation is a [PluginResourceLimitException], as in the other engines.
     */
    fun parseBoundedResult(json: String, limits: PluginExecutionLimits, pluginId: String): JsonElement {
        if (pluginUtf8ByteCountAtMost(json, limits.maxResultBytes) == null) {
            throw PluginResourceLimitException("Plugin '$pluginId' exceeded the ${limits.maxResultBytes}-byte result limit")
        }
        val shapeOk = pluginJsonShapeWithin(
            json,
            maxTotalValues = limits.maxResultTotalElements,
            maxArrayElements = limits.maxResultArrayElements,
            maxObjectEntries = limits.maxResultObjectEntries,
            maxDepth = limits.maxResultDepth,
        )
        if (!shapeOk) throw PluginResourceLimitException("Plugin '$pluginId' exceeded the result shape limits")
        val value = runCatching { PluginJson.parseToJsonElement(json) }.getOrElse {
            throw IllegalStateException("Plugin '$pluginId' returned malformed JSON")
        }
        requireStrings(value, limits, pluginId)
        return value
    }

    private fun requireStrings(value: JsonElement, limits: PluginExecutionLimits, pluginId: String) {
        fun check(s: String) {
            if (pluginUtf8ByteCountAtMost(s, limits.maxResultStringBytes) == null) {
                throw PluginResourceLimitException("Plugin '$pluginId' exceeded the ${limits.maxResultStringBytes}-byte result string limit")
            }
        }
        when (value) {
            is JsonArray -> value.forEach { requireStrings(it, limits, pluginId) }
            is JsonObject -> value.forEach { (k, v) -> check(k); requireStrings(v, limits, pluginId) }
            is JsonPrimitive -> if (value.isString) check(value.content)
        }
    }

    fun mangasPage(value: JsonElement): MangasPage {
        val obj = value as? JsonObject ?: return MangasPage(emptyList(), false)
        return MangasPage(obj["mangas"].arrayOrEmpty().mapNotNull { (it as? JsonObject)?.let(::manga) }, obj["hasNextPage"].bool())
    }

    fun mangaJson(m: SManga): JsonObject = buildJsonObject {
        put("url", m.url); put("title", m.title); put("artist", m.artist); put("author", m.author)
        put("description", m.description)
        m.genre?.let { values -> put("genre", JsonArray(values.map(::JsonPrimitive))) }
        put("status", m.status.value); put("thumbnailUrl", m.thumbnailUrl); put("initialized", m.initialized)
    }

    fun chapterJson(c: SChapter): JsonObject = buildJsonObject {
        put("url", c.url); put("name", c.name); put("scanlator", c.scanlator)
        put("dateUpload", c.dateUpload); put("chapterNumber", c.chapterNumber)
    }

    fun manga(o: JsonObject): SManga = SManga(
        url = o.str("url").orEmpty(),
        title = o.str("title").orEmpty(),
        artist = o.str("artist"),
        author = o.str("author"),
        description = o.str("description"),
        genre = o["genre"].arrayOrEmpty().mapNotNull { it.primitiveContent() }.takeIf { it.isNotEmpty() },
        status = MangaStatus.fromValue((o["status"] as? JsonPrimitive)?.intOrNull ?: 0),
        thumbnailUrl = o.str("thumbnailUrl") ?: o.str("thumbnail_url"),
        initialized = o["initialized"].bool(),
    )

    fun chapters(value: JsonElement): List<SChapter> = value.arrayOrEmpty().mapNotNull { (it as? JsonObject)?.let(::chapter) }

    private fun chapter(o: JsonObject): SChapter = SChapter(
        url = o.str("url").orEmpty(),
        name = o.str("name").orEmpty(),
        scanlator = o.str("scanlator"),
        dateUpload = (o["dateUpload"] as? JsonPrimitive)?.longOrNull ?: 0L,
        chapterNumber = (o["chapterNumber"] as? JsonPrimitive)?.doubleOrNull ?: -1.0,
    )

    fun pages(value: JsonElement): List<Page> = value.arrayOrEmpty().mapIndexedNotNull { index, v ->
        (v as? JsonObject)?.let { o ->
            Page(index = (o["index"] as? JsonPrimitive)?.intOrNull ?: index, url = o.str("url").orEmpty(), imageUrl = o.str("imageUrl"))
        }
    }

    fun filters(value: JsonElement): List<Filter> = value.arrayOrEmpty().mapNotNull { (it as? JsonObject)?.let(::filter) }

    fun filterJson(filter: Filter): JsonObject = buildJsonObject {
        when (filter) {
            is Filter.Header -> { put("type", "header"); put("name", filter.name) }
            Filter.Separator -> { put("type", "separator"); put("name", "") }
            is Filter.Select -> {
                put("type", "select"); put("name", filter.name)
                put("values", JsonArray(filter.values.map(::JsonPrimitive))); put("state", filter.state)
            }
            is Filter.Text -> { put("type", "text"); put("name", filter.name); put("state", filter.state) }
            is Filter.CheckBox -> { put("type", "checkBox"); put("name", filter.name); put("state", filter.state) }
            is Filter.TriState -> { put("type", "triState"); put("name", filter.name); put("state", filter.state.value) }
            is Filter.Group -> {
                put("type", "group"); put("name", filter.name)
                put("filters", JsonArray(filter.filters.map(::filterJson)))
            }
            is Filter.Sort -> {
                put("type", "sort"); put("name", filter.name)
                put("values", JsonArray(filter.values.map(::JsonPrimitive)))
                filter.selection?.let { selected ->
                    putJsonObject("selection") { put("index", selected.index); put("ascending", selected.ascending) }
                }
            }
        }
    }

    private fun filter(o: JsonObject): Filter? {
        val type = o.str("type") ?: return null
        val name = o.str("name").orEmpty()
        return when (type) {
            "header" -> Filter.Header(name)
            "separator" -> Filter.Separator
            "select" -> Filter.Select(name, o["values"].strings(), (o["state"] as? JsonPrimitive)?.intOrNull ?: 0)
            "text" -> Filter.Text(name, o.str("state").orEmpty())
            "checkBox" -> Filter.CheckBox(name, o["state"].bool())
            "triState" -> Filter.TriState(name, TriStateValue.fromValue((o["state"] as? JsonPrimitive)?.intOrNull ?: 0))
            "group" -> Filter.Group(name, o["filters"].arrayOrEmpty().mapNotNull { (it as? JsonObject)?.let(::filter) })
            "sort" -> {
                val selection = o["selection"] as? JsonObject
                Filter.Sort(
                    name,
                    o["values"].strings(),
                    selection?.let { SortSelection((it["index"] as? JsonPrimitive)?.intOrNull ?: 0, it["ascending"].bool()) },
                )
            }
            else -> null
        }
    }

    fun preferences(value: JsonElement): List<SourcePreference> = value.arrayOrEmpty().mapNotNull { (it as? JsonObject)?.let(::preference) }

    private fun preference(o: JsonObject): SourcePreference? {
        val type = o.str("type")?.lowercase() ?: return null
        val key = o.str("key")?.takeIf { it.isNotBlank() } ?: return null
        val title = o.str("title")?.takeIf { it.isNotBlank() } ?: key
        val summary = o.str("summary").orEmpty()
        return when (type) {
            "text", "textfield", "text_field" -> SourcePreference.TextField(key, title, summary, o.str("defaultValue").orEmpty())
            "toggle", "switch" -> SourcePreference.Toggle(key, title, summary, o["defaultValue"].bool())
            "select", "choice" -> {
                val entries = o["entries"].strings().ifEmpty { o["values"].strings() }
                val entryValues = o["entryValues"].strings().ifEmpty { entries }
                SourcePreference.Select(key, title, entries, entryValues, o.str("defaultValue").orEmpty())
            }
            "multiselect", "multi_select", "multichoice" -> {
                val entries = o["entries"].strings().ifEmpty { o["values"].strings() }
                val entryValues = o["entryValues"].strings().ifEmpty { entries }
                SourcePreference.MultiSelect(key, title, entries, entryValues, o["defaultValues"].strings().toSet())
            }
            else -> null
        }
    }

    fun loginResult(value: JsonElement): LoginAttemptResult {
        val obj = value as? JsonObject
        if (obj != null) {
            return LoginAttemptResult(
                loggedIn = obj["loggedIn"].bool(),
                errorMessage = obj.str("errorMessage")?.filterNot(Char::isISOControl)?.trim()?.take(512)?.takeIf(String::isNotBlank),
            )
        }
        // Older scripts return a bare Boolean.
        return LoginAttemptResult(loggedIn = value.bool())
    }

    fun stringMap(value: JsonElement?): Map<String, String> = (value as? JsonObject)?.mapNotNull { (k, v) ->
        v.primitiveContent()?.let { k to it }
    }?.toMap().orEmpty()

    fun stringList(value: JsonElement?): List<String> = value.strings()

    fun JsonObject.str(key: String): String? = this[key].primitiveContent()

    fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false

    private fun JsonElement?.primitiveContent(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull

    private fun JsonElement?.arrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())

    private fun JsonElement?.strings(): List<String> = arrayOrEmpty().mapNotNull { it.primitiveContent() }
}
