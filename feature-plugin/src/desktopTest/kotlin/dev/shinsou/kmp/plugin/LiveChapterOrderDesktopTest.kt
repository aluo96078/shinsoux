package dev.shinsou.kmp.plugin

import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.test.Test

/**
 * Opt-in live check of the chapter-order contract: a source's chapter list is newest first
 * (`sourceOrder == 0` is the latest chapter; see `buildReaderChapterNavigation`). Runs every
 * installable package of the plugin repositories in `SHINSOU_LIVE_CHAPTER_ORDER_REPOS`
 * (colon-separated repository roots, each with `src/index.json` and `src/plugins/`) against the
 * real sites and prints one verdict per package. `SHINSOU_LIVE_CHAPTER_ORDER_ONLY` limits it to a
 * comma-separated list of package ids.
 */
class LiveChapterOrderDesktopTest {
    @Test
    fun everyPackageListsChaptersNewestFirst() {
        val repos = System.getenv("SHINSOU_LIVE_CHAPTER_ORDER_REPOS")?.takeIf(String::isNotBlank) ?: return
        val only = System.getenv("SHINSOU_LIVE_CHAPTER_ORDER_ONLY")?.split(',')?.map(String::trim)?.toSet()
        val transport = requireNotNull(createPlatformPinnedPluginHttpTransport())
        val resolver = createPlatformPluginHostResolver()
        repos.split(':').filter(String::isNotBlank).forEach { root ->
            val index = PluginJson.parseToJsonElement(File(root, "src/index.json").readText()).jsonObject
            index.getValue("packages").jsonArray.map { it.jsonObject }.forEachIndexed { offset, entry ->
                val id = entry.string("id") ?: return@forEachIndexed
                if (only != null && id !in only) return@forEachIndexed
                if (entry["installable"]?.jsonPrimitive?.booleanOrNull == false) return@forEachIndexed
                if (entry["referenceOnly"]?.jsonPrimitive?.booleanOrNull == true) return@forEachIndexed
                val line = runCatching { runBlocking { withTimeout(150_000) { probe(root, entry, offset, transport, resolver) } } }
                    .getOrElse { "ERROR ${it::class.simpleName}: ${it.message?.take(160)}" }
                println("CHAPTER-ORDER $id | $line")
            }
        }
    }

    private suspend fun probe(
        root: String,
        entry: JsonObject,
        offset: Int,
        transport: PluginHttpTransport,
        resolver: PluginHostResolver,
    ): String {
        val id = entry.string("id")!!
        val fileName = entry.string("scriptUrl")!!.substringBefore('?').substringAfterLast('/')
        val source = (entry["sources"] as JsonArray).first().jsonObject
        val sourceEntry = SourceIndexEntry(
            name = source.string("name") ?: id,
            lang = source.string("lang") ?: "all",
            id = source["legacyLongId"]?.jsonPrimitive?.longOrNull ?: (90_000L + offset),
            baseUrl = source.string("baseUrl"),
            requestOrigins = source.strings("requestOrigins"),
            credentialOrigins = source.strings("credentialOrigins"),
            contentOrigins = source.strings("contentOrigins"),
            browserSessionOrigins = source.strings("browserSessionOrigins"),
            contentHostSuffixes = source.strings("contentHostSuffixes"),
        )
        val storage = KeyValuePluginStorage(InMemoryPluginKeyValueStore())
        val network = PluginNetworkClient(transport = transport, storage = storage, hostResolver = resolver)
            .scopedToPolicy(sourceEntry.networkPolicy())
        val manifest = PluginManifest(
            id = id,
            name = entry.string("name") ?: id,
            version = entry.string("version") ?: "0",
            versionCode = 0,
            lang = entry.string("lang") ?: "all",
            nsfw = entry["nsfw"]?.jsonPrimitive?.booleanOrNull == true,
            script = fileName,
            signature = "",
            sources = listOf(sourceEntry),
        )
        val runtime = RhinoScriptPluginRuntimeFactory.unsafeForTests().create(
            File(root, "src/plugins/$fileName").readText(),
            manifest,
            ScriptPluginEnvironment(
                network = network,
                storage = storage,
                hostResolver = resolver,
                runtimePermissions = setOf(PluginRuntimePermission.EXECUTE_SCRIPT, PluginRuntimePermission.NETWORK),
            ),
        )
        try {
            val mangas = runtime.getPopularManga(1).mangas.ifEmpty { runtime.getLatestUpdates(1).mangas }
            if (mangas.isEmpty()) return "NO-TITLES"
            val reports = mutableListOf<String>()
            for (manga in mangas.take(6)) {
                val chapters = runCatching { runtime.getChapterList(manga) }.getOrElse {
                    reports += "\"${manga.title.take(20)}\" chapters failed: ${it.message?.take(80)}"
                    continue
                }
                if (chapters.size < 3) continue
                if (System.getenv("SHINSOU_LIVE_CHAPTER_ORDER_DUMP") == "1") {
                    println("CHAPTER-DUMP ${manga.title}: " + chapters.joinToString(" | ") { it.name })
                }
                reports += report(manga.title, chapters)
                if (System.getenv("SHINSOU_LIVE_CHAPTER_ORDER_PAGES") == "1") {
                    reports += runCatching { runtime.getPageList(chapters.first()).let { "pages(first)=${it.size} sample=${it.firstOrNull()?.imageUrl?.substringAfterLast('/')?.take(80)} cover=${manga.thumbnailUrl?.substringAfterLast('/')?.take(80)}" } }
                        .getOrElse { "pages(first) failed: ${it.message?.take(80)}" }
                }
                if (reports.count { !it.contains("failed") } >= 2) break
            }
            return reports.ifEmpty { listOf("ONLY-SINGLE-CHAPTER titles") }.joinToString(" || ")
        } finally {
            runtime.close()
        }
    }

    private fun report(title: String, chapters: List<SChapter>): String {
        // Story order is expected: the first chapter first (the reader's "next" is the next entry).
        val named = chapters.map { chapterNumberInName(it.name) }
        val numbered = named.filterNotNull()
        val nameVerdict = direction(numbered)
        val numberVerdict = direction(chapters.map { it.chapterNumber }.filter { it > 0 })
        val duplicates = chapters.size - chapters.distinctBy { it.url }.size
        fun show(c: SChapter) = "${c.name.take(16)}#${c.chapterNumber}"
        return "names=$nameVerdict numbers=$numberVerdict dupUrls=$duplicates n=${chapters.size} \"${title.take(14)}\" " +
            "first=[${chapters.take(2).joinToString(", ", transform = ::show)}] " +
            "last=[${chapters.takeLast(2).joinToString(", ", transform = ::show)}]"
    }

    /** OK = ascending story order; REVERSED = descending; the count is the steps against it. */
    private fun direction(values: List<Double>): String {
        if (values.size < 3) return "n/a"
        val up = values.zipWithNext().count { (a, b) -> b > a }
        val down = values.zipWithNext().count { (a, b) -> b < a }
        return when {
            up >= down -> if (down == 0) "OK" else "OK-ish(${down} back)"
            else -> if (up == 0) "REVERSED" else "REVERSED-ish(${up} fwd)"
        }
    }

    private fun chapterNumberInName(name: String): Double? =
        Regex("""第\s*([0-9]+(?:\.[0-9]+)?)\s*[話话回章集卷冊册]""").find(name)?.groupValues?.get(1)?.toDoubleOrNull()
            ?: Regex("""(?:Ch\.?|Chapter)\s*([0-9]+(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toDoubleOrNull()

    private fun JsonObject.string(key: String): String? = this[key]?.jsonPrimitive?.content
    private fun JsonObject.strings(key: String): Set<String> =
        (this[key] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
}
