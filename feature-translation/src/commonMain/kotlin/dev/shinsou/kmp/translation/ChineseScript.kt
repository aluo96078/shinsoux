package dev.shinsou.kmp.translation

import dev.aluo.shinsoux.translation.generated.resources.Res
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * Simplified and Traditional Chinese: telling them apart, and writing one as the other on the
 * device. The two are one language in two scripts (with some words of their own), so a work in
 * one is converted for a reader of the other, never sent to a translation service.
 */
public object ChineseScript {
    public const val SIMPLIFIED: String = "zh-Hans"
    public const val TRADITIONAL: String = "zh-Hant"

    private val simplifiedOnly: Set<Char> by lazy { SIMPLIFIED_ONLY.toSet() }
    private val traditionalOnly: Set<Char> by lazy { TRADITIONAL_ONLY.toSet() }

    /** True for a pair that is one script of Chinese to the other: converted on the device. */
    public fun converts(from: String, to: String): Boolean {
        val source = OfflineModelCatalog.language(from) ?: return false
        val target = OfflineModelCatalog.language(to) ?: return false
        return source != target && source.startsWith("zh") && target.startsWith("zh")
    }

    /** True for a tag that says Chinese but not which script (`zh`, as many sources give it). */
    public fun unspecified(tag: String): Boolean = tag.trim().replace('_', '-').lowercase() == "zh"

    /**
     * The script [text] is written in, from the characters written only one way (这/這, 们/們);
     * null when it has fewer than [minimum] of them. A few of the other script's characters, as a
     * name or a misread may bring, do not decide it: the larger count does.
     */
    public fun detect(text: String, limit: Int = 4_000, minimum: Int = 1): String? {
        var simplified = 0
        var traditional = 0
        for (char in text.take(limit)) {
            if (char in simplifiedOnly) simplified++ else if (char in traditionalOnly) traditional++
        }
        if (simplified + traditional < maxOf(minimum, 1)) return null
        return if (traditional > simplified) TRADITIONAL else SIMPLIFIED
    }
}

/**
 * The conversion tables (OpenCC's s2twp and tw2sp: Taiwan's characters and words one way,
 * mainland ones the other), as `tools/chinese-script/build_dictionary.py` writes them.
 */
internal class ChineseScriptTable(text: String) {
    private val stages: Map<String, Stage>

    init {
        val tables = HashMap<String, HashMap<String, String>>()
        var current: HashMap<String, String>? = null
        for (line in text.lineSequence()) {
            when {
                line.isEmpty() || line.startsWith("#") -> Unit
                line.startsWith("@") -> current = tables.getOrPut(line.substring(1)) { HashMap() }
                else -> {
                    val tab = line.indexOf('\t')
                    if (tab > 0) current?.put(line.substring(0, tab), line.substring(tab + 1))
                }
            }
        }
        stages = tables.mapValues { (_, entries) -> Stage(entries) }
        require(STEPS.values.flatten().all { it in stages }) { "Incomplete Chinese script table" }
    }

    /** [text] in the [to] script; [from] and [to] are `zh-Hans` and `zh-Hant`, either way round. */
    fun convert(text: String, from: String, to: String): String {
        val steps = STEPS[from to to] ?: return text
        return steps.fold(text) { out, step -> stages.getValue(step).convert(out) }
    }

    /** Longest match first, as OpenCC does. */
    private class Stage(private val entries: Map<String, String>) {
        // the longest key starting with each character: most characters start none
        private val longest: Map<Char, Int> = HashMap<Char, Int>().also { map ->
            for (key in entries.keys) if (key.length > (map[key[0]] ?: 0)) map[key[0]] = key.length
        }

        fun convert(text: String): String {
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                var n = minOf(longest[text[i]] ?: 0, text.length - i)
                var hit: String? = null
                while (n > 0) {
                    hit = entries[text.substring(i, i + n)]
                    if (hit != null) break
                    n--
                }
                if (hit != null) {
                    out.append(hit); i += n
                } else {
                    out.append(text[i]); i++
                }
            }
            return out.toString()
        }
    }

    private companion object {
        val STEPS = mapOf(
            (ChineseScript.SIMPLIFIED to ChineseScript.TRADITIONAL) to listOf("s2t", "t2tw"),
            (ChineseScript.TRADITIONAL to ChineseScript.SIMPLIFIED) to listOf("tw2t", "t2s"),
        )
    }
}

/**
 * Simplified to Traditional Chinese and back, on the device from the bundled tables: free, and
 * with no models to download.
 */
public class ChineseScriptProvider(
    private val load: suspend () -> String = ::bundledTable,
    /** Where the tables are parsed and the text converted. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TranslationProvider {
    override val id: String = ID
    override val paid: Boolean = false
    override val currentEngine: String = ENGINE

    override fun route(from: String, to: String): List<TranslationHop>? {
        if (!ChineseScript.converts(from, to)) return null
        val source = OfflineModelCatalog.language(from) ?: return null
        val target = OfflineModelCatalog.language(to) ?: return null
        return listOf(TranslationHop(source, target, ENGINE))
    }

    override suspend fun translate(hop: TranslationHop, segments: List<String>): List<String> {
        val table = table()
        return withContext(dispatcher) { segments.map { table.convert(it, hop.from, hop.to) } }
    }

    private val loading = Mutex()
    private var loaded: ChineseScriptTable? = null

    private suspend fun table(): ChineseScriptTable = loading.withLock {
        loaded ?: try {
            val text = load()
            withContext(dispatcher) { ChineseScriptTable(text) }.also { loaded = it }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            throw TranslationException(TranslationFailure.ENGINE_FAILED, "The Chinese script table could not be read", e)
        }
    }

    public companion object {
        public const val ID: String = "chinese-script"

        /** Changes with the tables: chapters converted with older ones are converted again. */
        public const val ENGINE: String = "opencc-fae2a0a9"

        @OptIn(ExperimentalResourceApi::class)
        private suspend fun bundledTable(): String = Res.readBytes("files/chinese-script.txt").decodeToString()
    }
}
