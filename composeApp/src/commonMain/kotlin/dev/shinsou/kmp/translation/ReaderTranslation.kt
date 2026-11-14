package dev.shinsou.kmp.translation

import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What a platform contributes to offline translation: its engine and where models are kept. */
public class OfflineTranslationPlatform(
    public val engine: OfflineTranslationEngine,
    /** Absolute directory for downloaded models; they can always be downloaded again. */
    public val modelDirectory: String,
    /** Refuses to load a model the device has no room for; unrestricted where memory is not scarce. */
    public val memory: TranslationMemoryGuard = TranslationMemoryGuard.Unrestricted,
)

/** Where translations come from. */
public enum class TranslationProviderKind(public val id: String, internal val secretName: String?) {
    /** The bundled engine: free, on the device, nothing is sent anywhere. */
    OFFLINE(OfflineTranslationProvider.ID, null),

    /** DeepL with the user's API key. What is read is sent to DeepL. */
    DEEPL(DeepLTranslationProvider.ID, "translation.deepl.secret"),

    /** Google Cloud Translation with the user's API key. What is read is sent to Google. */
    GOOGLE(GoogleTranslationProvider.ID, "translation.google.secret"),

    /** A language model behind an address the user set up. What is read is sent there. */
    LLM(LlmTranslationProvider.ID, "translation.llm.secret"),
}

public data class TranslationPreferences(
    val provider: TranslationProviderKind,
    /** Characters a day that may go to paid providers; 0 means no limit. */
    val dailyCharacterLimit: Long,
    val charactersUsedToday: Long,
    val deepLKeySet: Boolean,
    val googleKeySet: Boolean,
    /** The language-model service as the user set it up; its key is reported only as set or not. */
    val llm: LlmSettings = LlmSettings(),
    val llmKeySet: Boolean = false,
    /** False where keys cannot be stored safely: only the offline engine is offered. */
    val cloudAvailable: Boolean,
)

/** A language the reader can offer, with its own name. */
public data class TranslationLanguage(val tag: String, val name: String)

/**
 * Translation as the reader uses it (ADR-0004 §4.4): per-work settings, the models a language
 * pair needs, the translation itself and the work's glossary. Everything here runs on the
 * device; nothing is sent anywhere except the model download from Mozilla's CDN.
 */
public class ReaderTranslation(
    private val memory: TranslationMemoryStore,
    platform: OfflineTranslationPlatform,
    private val http: HttpClient,
    /** Where API keys are kept (the platform's protected storage); null leaves only the offline engine. */
    private val secrets: dev.shinsou.kmp.plugin.PluginKeyValueStore? = null,
    private val now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
) {
    private val engine = platform.engine
    private val files = OfflineModelStore(platform.modelDirectory, http)
    private val offline = OfflineTranslationProvider(files, engine, platform.memory)

    /** False on a device that can never hold a model: nothing should be downloaded for it. */
    public val deviceSupported: Boolean get() = offline.deviceSupported
    private val service = TranslationService(memory)

    public val languages: List<TranslationLanguage> = LANGUAGES

    // ---- which provider ---------------------------------------------------------------------

    public suspend fun preferences(): TranslationPreferences = withContext(Dispatchers.Default) {
        TranslationPreferences(
            provider = providerKind(),
            dailyCharacterLimit = dailyLimit(),
            charactersUsedToday = usedToday(),
            deepLKeySet = !key(TranslationProviderKind.DEEPL).isNullOrBlank(),
            googleKeySet = !key(TranslationProviderKind.GOOGLE).isNullOrBlank(),
            llm = llmSettings(),
            llmKeySet = !key(TranslationProviderKind.LLM).isNullOrBlank(),
            cloudAvailable = secrets != null,
        )
    }

    public suspend fun setProvider(kind: TranslationProviderKind): Unit =
        withContext(Dispatchers.Default) { memory.setPreference(PREF_PROVIDER, kind.id) }

    /** Stores [value] as the API key of [kind] in protected storage; blank removes it. */
    public suspend fun setKey(kind: TranslationProviderKind, value: String) {
        val store = secrets ?: return
        val name = kind.secretName ?: return
        if (value.isBlank()) store.remove(name) else store.putString(name, value.trim())
    }

    /** The language-model service: wire format, address and model. Refused when the address must not be used. */
    public suspend fun setLlmSettings(settings: LlmSettings): Unit = withContext(Dispatchers.Default) {
        val base = requireNotNull(normalizedLlmBaseUrl(settings.baseUrl)) {
            "The address must use https, or http for this device or a private network"
        }
        memory.transaction {
            memory.setPreference(PREF_LLM_PROTOCOL, settings.protocol.id)
            memory.setPreference(PREF_LLM_BASE_URL, base)
            memory.setPreference(PREF_LLM_MODEL, settings.model.trim().take(MAX_LLM_MODEL_LENGTH))
        }
    }

    private fun llmSettings(): LlmSettings {
        val protocol = LlmProtocol.entries.firstOrNull { it.id == memory.preference(PREF_LLM_PROTOCOL) } ?: LlmProtocol.OPENAI
        return LlmSettings(
            protocol = protocol,
            baseUrl = memory.preference(PREF_LLM_BASE_URL) ?: protocol.defaultBaseUrl,
            model = memory.preference(PREF_LLM_MODEL) ?: protocol.suggestedModel,
        )
    }

    /** Characters a day that may be sent to paid providers; 0 removes the limit. */
    public suspend fun setDailyCharacterLimit(characters: Long): Unit =
        withContext(Dispatchers.Default) { memory.setPreference(PREF_DAILY_LIMIT, characters.coerceAtLeast(0).toString()) }

    private fun providerKind(): TranslationProviderKind =
        TranslationProviderKind.entries.firstOrNull { it.id == memory.preference(PREF_PROVIDER) }
            ?.takeIf { it == TranslationProviderKind.OFFLINE || secrets != null }
            ?: TranslationProviderKind.OFFLINE

    private suspend fun key(kind: TranslationProviderKind): String? = kind.secretName?.let { secrets?.getString(it) }

    private fun dailyLimit(): Long = memory.preference(PREF_DAILY_LIMIT)?.toLongOrNull() ?: DEFAULT_DAILY_CHARACTER_LIMIT

    private fun today(): Long = now() / 86_400_000L

    private fun usedToday(): Long =
        if (memory.preference(PREF_USAGE_DAY)?.toLongOrNull() == today()) memory.preference(PREF_USAGE_CHARACTERS)?.toLongOrNull() ?: 0L else 0L

    private val usage = object : TranslationUsage {
        override fun reserve(characters: Long): Boolean = memory.transaction {
            val limit = dailyLimit()
            val used = usedToday()
            if (limit > 0 && used + characters > limit) return@transaction false
            memory.setPreference(PREF_USAGE_DAY, today().toString())
            memory.setPreference(PREF_USAGE_CHARACTERS, (used + characters).toString())
            true
        }

        override fun release(characters: Long): Unit = memory.transaction {
            memory.setPreference(PREF_USAGE_CHARACTERS, (usedToday() - characters).coerceAtLeast(0).toString())
        }
    }

    /** The provider chapters go through right now. A cloud provider without a key refuses when asked to translate. */
    private suspend fun provider(): TranslationProvider = when (val kind = withContext(Dispatchers.Default) { providerKind() }) {
        TranslationProviderKind.OFFLINE -> offline
        TranslationProviderKind.DEEPL -> DeepLTranslationProvider(http, key(kind).orEmpty(), usage)
        TranslationProviderKind.GOOGLE -> GoogleTranslationProvider(http, key(kind).orEmpty(), usage)
        TranslationProviderKind.LLM ->
            LlmTranslationProvider(http, withContext(Dispatchers.Default) { llmSettings() }, key(kind).orEmpty(), usage)
    }

    /** The provider in use: the reader says where the text goes. */
    public suspend fun activeProvider(): TranslationProviderKind = withContext(Dispatchers.Default) { providerKind() }

    public suspend fun supports(from: String, to: String): Boolean = provider().route(from, to) != null

    /** Models the offline engine still needs for this pair; empty for a cloud provider. */
    public suspend fun missingModels(from: String, to: String): List<OfflineModel> =
        if (activeProvider() == TranslationProviderKind.OFFLINE) offline.missingModels(from, to) else emptyList()

    // ---- offline models and stored translations, for the settings page ------------------------

    public suspend fun installedModels(): List<OfflineModel> =
        withContext(Dispatchers.Default) { OfflineModelCatalog.models.filter { files.installed(it) != null } }

    public suspend fun removeModel(model: OfflineModel): Unit = withContext(Dispatchers.Default) { files.remove(model) }

    /** Bytes of stored translations: free ones first, paid ones second. */
    public suspend fun storedTranslationBytes(): Pair<Long, Long> =
        withContext(Dispatchers.Default) { memory.freeBytes() to memory.paidBytes() }

    /** Chapters with a stored translation, most recent first, for the settings page. */
    public suspend fun storedChapters(): List<StoredChapterTranslation> =
        withContext(Dispatchers.Default) { memory.storedChapters(STORED_CHAPTER_LIST_LIMIT) }

    /** Removes one chapter's stored translation; returns the bytes removed. */
    public suspend fun deleteChapterTranslation(chapter: StoredChapterTranslation): Long =
        withContext(Dispatchers.Default) { memory.deleteChapterTranslation(chapter.workKey, chapter.chapterKey, chapter.to, chapter.provider) }

    /** Removes every stored chapter translation of a work; its glossary and settings stay. */
    public suspend fun deleteWorkTranslations(workKey: String): Long =
        withContext(Dispatchers.Default) { memory.deleteWorkTranslations(workKey) }

    public suspend fun clearFreeTranslations(): Unit = withContext(Dispatchers.Default) { memory.evictFree(0); Unit }

    public suspend fun downloadModels(from: String, to: String, onProgress: (received: Long, total: Long) -> Unit) {
        val missing = offline.missingModels(from, to)
        val total = missing.sumOf { it.downloadBytes }
        var before = 0L
        for (model in missing) {
            files.download(model) { received, _ -> onProgress(before + received, total) }
            before += model.downloadBytes
        }
    }

    public suspend fun settings(workKey: String): WorkTranslationSettings? = withContext(Dispatchers.Default) { memory.workSettings(workKey) }

    private val settingsWrites = kotlinx.coroutines.sync.Mutex()

    /** Writes are queued in the order they were asked for, so the last change is the one that stays. */
    public suspend fun saveSettings(workKey: String, settings: WorkTranslationSettings): Unit =
        settingsWrites.withLock { withContext(Dispatchers.Default) { memory.saveWorkSettings(workKey, settings) } }

    public suspend fun stored(request: ChapterTranslationRequest): ChapterTranslation? {
        val provider = provider()
        return withContext(Dispatchers.Default) {
            // Also for chapters translated before names were recorded: opening one names it.
            request.labels?.let { labels -> runCatching { memory.saveLabels(request.workKey, request.chapterKey, labels) } }
            service.stored(request, provider)
        }
    }

    public suspend fun translate(request: ChapterTranslationRequest, onProgress: (done: Int, total: Int) -> Unit): ChapterTranslation {
        val kind = activeProvider()
        // Decided by the project owner (2026-11-14): a cloud provider the user chose and keyed
        // may translate content whose grant has no EXPORT right, as extension content does by
        // default. The reader says where the text goes; `exportable` only governs backups.
        if (kind == TranslationProviderKind.LLM) {
            // A model server on this device or the home network may need no key at all.
            if (!withContext(Dispatchers.Default) { llmSettings() }.configured) {
                throw TranslationException(TranslationFailure.NOT_CONFIGURED, "The language-model service is not set up")
            }
        } else if (kind != TranslationProviderKind.OFFLINE && key(kind).isNullOrBlank()) {
            throw TranslationException(TranslationFailure.INVALID_KEY, "No API key is set for ${kind.id}")
        }
        val provider = provider()
        return withContext(Dispatchers.Default) {
            try {
                service.translate(request, provider, onProgress)
            } finally {
                // A model is only held while a chapter is being translated: loading it again
                // takes a tenth of a second, keeping it takes about 350 MB of the reader's memory.
                withContext(kotlinx.coroutines.NonCancellable) { runCatching { offline.unload() } }
                // Free translations can be made again: keep the newest and let the oldest go.
                runCatching { memory.evictFree(FREE_MEMORY_LIMIT_BYTES) }
            }
        }
    }

    public suspend fun glossary(workKey: String, to: String): List<GlossaryEntry> = withContext(Dispatchers.Default) { memory.glossary(workKey, to) }

    public suspend fun putGlossaryTerm(workKey: String, to: String, source: String, target: String): GlossaryEntry =
        withContext(Dispatchers.Default) { memory.putGlossaryTerm(workKey, to, source, target) }

    public suspend fun removeGlossaryTerm(workKey: String, to: String, id: Int): Unit =
        withContext(Dispatchers.Default) { memory.removeGlossaryTerm(workKey, to, id) }

    /** Translation data for a backup; stored translations are included by the user's choice. */
    public suspend fun exportBackup(includePaid: Boolean, includeFree: Boolean): TranslationBackup =
        withContext(Dispatchers.Default) { memory.exportBackup(includePaid, includeFree, BACKUP_SEGMENT_BUDGET_BYTES) }

    public suspend fun importBackup(backup: TranslationBackup): Unit =
        withContext(Dispatchers.Default) { memory.importBackup(backup, kotlin.time.Clock.System.now().toEpochMilliseconds()) }

    private val background = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)

    /** Releases the loaded model's memory once the reader that used it is gone. */
    public fun release() {
        background.launch { runCatching { offline.unload() } }
    }

    public companion object {
        private const val STORED_CHAPTER_LIST_LIMIT = 2_000
        private const val PREF_PROVIDER = "provider"
        private const val PREF_LLM_PROTOCOL = "llm.protocol"
        private const val PREF_LLM_BASE_URL = "llm.base"
        private const val PREF_LLM_MODEL = "llm.model"
        private const val MAX_LLM_MODEL_LENGTH = 200
        private const val PREF_DAILY_LIMIT = "daily.limit"
        private const val PREF_USAGE_DAY = "usage.day"
        private const val PREF_USAGE_CHARACTERS = "usage.characters"

        /** About a dozen long chapters a day until the user sets their own limit. */
        public const val DEFAULT_DAILY_CHARACTER_LIMIT: Long = 100_000

        /** Stored free translations beyond this are dropped, least recently read first (about 30 long novels). */
        public const val FREE_MEMORY_LIMIT_BYTES: Long = 96L * 1024 * 1024

        /** A backup is one document held in memory: stored translations beyond this stay behind, oldest first. */
        public const val BACKUP_SEGMENT_BUDGET_BYTES: Long = 24L * 1024 * 1024

        private val LANGUAGES = listOf(
            TranslationLanguage("zh-Hant", "繁體中文"),
            TranslationLanguage("zh-Hans", "简体中文"),
            TranslationLanguage("ja", "日本語"),
            TranslationLanguage("ko", "한국어"),
            TranslationLanguage("en", "English"),
        )

        /**
         * A guess at the language of [text] from its script, among the languages the engine has
         * models for. Chinese is reported as Simplified: the two cannot be told apart reliably
         * from a sample, and the reader lets the user correct it.
         */
        public fun detectLanguage(text: String): String {
            var kana = 0; var hangul = 0; var han = 0; var latin = 0
            for (char in text.take(4_000)) {
                when {
                    char in '぀'..'ヿ' -> kana++
                    char in '가'..'힯' -> hangul++
                    char in '一'..'鿿' -> han++
                    char in 'a'..'z' || char in 'A'..'Z' -> latin++
                }
            }
            return when {
                kana > 0 && kana * 20 >= han -> "ja"
                hangul > 0 && hangul >= han -> "ko"
                han > latin / 4 && han > 0 -> "zh-Hans"
                else -> "en"
            }
        }

        /** The engine language for an app or system language tag; Traditional Chinese when it has no model. */
        public fun targetLanguage(tag: String): String = OfflineModelCatalog.language(tag) ?: "zh-Hant"
    }
}

/**
 * The text a translated chapter is shown as, with positions mapped line by line to the original:
 * reading progress, bookmarks and sync keep using positions in the original text, whatever is on
 * screen.
 */
public class TranslationDisplayText private constructor(
    public val text: String,
    private val source: String,
    private val sourceStarts: IntArray,
    private val sourceLengths: IntArray,
    private val displayStarts: IntArray,
    private val displayLengths: IntArray,
) {
    /** The position in the original text for a position in [text]. */
    public fun toSource(displayOffset: Int): Int =
        map(displayOffset, text, displayStarts, displayLengths, source, sourceStarts, sourceLengths)

    /** The position in [text] for a position in the original text. */
    public fun toDisplay(sourceOffset: Int): Int =
        map(sourceOffset, source, sourceStarts, sourceLengths, text, displayStarts, displayLengths)

    private fun map(
        offset: Int, from: String, fromStarts: IntArray, fromLengths: IntArray,
        to: String, toStarts: IntArray, toLengths: IntArray,
    ): Int {
        if (offset <= 0) return 0
        if (offset >= from.length) return to.length
        var low = 0
        var high = fromStarts.lastIndex
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (fromStarts[middle] <= offset) low = middle else high = middle - 1
        }
        val within = (offset - fromStarts[low]).coerceAtMost(fromLengths[low])
        val scaled = if (fromLengths[low] == 0) 0 else (within.toLong() * toLengths[low] / fromLengths[low]).toInt()
        var result = toStarts[low] + scaled
        // Never land between the halves of a surrogate pair.
        if (result in 1 until to.length && to[result].isLowSurrogate() && to[result - 1].isHighSurrogate()) result--
        return result
    }

    public companion object {
        /**
         * Null when there is nothing to show differently: [mode] is the original, the lines do
         * not line up with the chapter, or no line has a translation yet.
         */
        public fun build(source: String, translation: ChapterTranslation, mode: TranslationDisplayMode): TranslationDisplayText? {
            if (mode == TranslationDisplayMode.ORIGINAL) return null
            val lines = source.split('\n')
            val segments = translation.segments
            if (segments.size != lines.size || segments.none { it.translation != null }) return null
            val sourceStarts = IntArray(lines.size)
            val sourceLengths = IntArray(lines.size)
            val displayStarts = IntArray(lines.size)
            val displayLengths = IntArray(lines.size)
            val out = StringBuilder(source.length * 2)
            var sourceOffset = 0
            lines.forEachIndexed { index, line ->
                if (index > 0) out.append('\n')
                sourceStarts[index] = sourceOffset
                sourceLengths[index] = line.length
                sourceOffset += line.length + 1
                displayStarts[index] = out.length
                val translated = segments[index].translation?.replace('\n', ' ')
                when {
                    translated == null -> out.append(line)
                    mode == TranslationDisplayMode.BILINGUAL -> out.append(line).append('\n').append(translated)
                    else -> out.append(translated)
                }
                displayLengths[index] = out.length - displayStarts[index]
            }
            return TranslationDisplayText(out.toString(), source, sourceStarts, sourceLengths, displayStarts, displayLengths)
        }
    }
}
