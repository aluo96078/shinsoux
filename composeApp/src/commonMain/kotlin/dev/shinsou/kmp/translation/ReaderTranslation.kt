package dev.shinsou.kmp.translation

import dev.shinsou.kmp.concurrent.runCatchingCancellable
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
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

    /** The operating system's translation (Apple's on iOS): free, on the device, languages kept by the system. */
    SYSTEM(SystemTranslationProvider.ID, null),

    /** DeepL with the user's API key. What is read is sent to DeepL. */
    DEEPL(DeepLTranslationProvider.ID, "translation.deepl.secret"),

    /** Google Cloud Translation with the user's API key. What is read is sent to Google. */
    GOOGLE(GoogleTranslationProvider.ID, "translation.google.secret"),

    /** Azure AI Translator with the user's resource key. What is read is sent to Microsoft. */
    AZURE(AzureTranslationProvider.ID, "translation.azure.secret"),

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
    val azureKeySet: Boolean = false,
    /** The Azure resource's region; empty for a global resource. */
    val azureRegion: String = "",
    /** False where keys cannot be stored safely: only the offline engine is offered. */
    val cloudAvailable: Boolean,
    /** The platform has its own translation to offer. */
    val systemAvailable: Boolean = false,
    /** A work in another language than the reader's is translated as soon as it is opened (until the user turns it off for that work). */
    val autoTranslate: Boolean = true,
    /** ... with a paid service too; otherwise a paid service only offers to translate. */
    val autoTranslatePaid: Boolean = false,
    /** Work languages (engine languages) left as they are even with [autoTranslate] on; every other one is translated. */
    val autoTranslateSkipped: Set<String> = emptySet(),
    /**
     * A reader of Chinese also has works in the other script converted as they open; off until
     * turned on, whatever [autoTranslateSkipped] says: they can read both.
     */
    val autoTranslateOtherScript: Boolean = false,
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
    /** The operating system's own translation, where the platform has one (iOS). */
    private val system: SystemTranslationEngine? = null,
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
            azureKeySet = !key(TranslationProviderKind.AZURE).isNullOrBlank(),
            azureRegion = memory.preference(PREF_AZURE_REGION).orEmpty(),
            cloudAvailable = secrets != null,
            systemAvailable = system?.available == true,
            autoTranslate = memory.preference(PREF_AUTO) != "0",
            autoTranslatePaid = memory.preference(PREF_AUTO_PAID) == "1",
            autoTranslateSkipped = memory.preference(PREF_AUTO_SKIPPED).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
            autoTranslateOtherScript = memory.preference(PREF_AUTO_OTHER_SCRIPT) == "1",
        )
    }

    /**
     * The settings whether a work starts translating by itself turns on, read without the keys:
     * a keychain that refuses or times out does not stop a chapter opening.
     */
    private suspend fun autoPreferences(): TranslationPreferences = withContext(Dispatchers.Default) {
        TranslationPreferences(
            provider = providerKind(),
            dailyCharacterLimit = 0,
            charactersUsedToday = 0,
            deepLKeySet = false,
            googleKeySet = false,
            cloudAvailable = secrets != null,
            autoTranslate = memory.preference(PREF_AUTO) != "0",
            autoTranslatePaid = memory.preference(PREF_AUTO_PAID) == "1",
            autoTranslateSkipped = memory.preference(PREF_AUTO_SKIPPED).orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
            autoTranslateOtherScript = memory.preference(PREF_AUTO_OTHER_SCRIPT) == "1",
        )
    }

    /** [supports], false when the service could not be asked (its key unreadable): nothing then starts by itself. */
    private suspend fun supportsForAuto(from: String, to: String): Boolean = try {
        supports(from, to)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /** Whether works in another language are translated as they are opened, and whether with a paid service too. */
    public suspend fun setAutoTranslate(on: Boolean, paid: Boolean): Unit = withContext(Dispatchers.Default) {
        memory.transaction {
            memory.setPreference(PREF_AUTO, if (on) "1" else "0")
            memory.setPreference(PREF_AUTO_PAID, if (paid) "1" else "0")
        }
    }

    /** Whether works in [language] are among those translated automatically (all are, until one is turned off). */
    public suspend fun setAutoTranslateLanguage(language: String, on: Boolean): Unit = withContext(Dispatchers.Default) {
        memory.transaction {
            val skipped = memory.preference(PREF_AUTO_SKIPPED).orEmpty().split(',').filter { it.isNotBlank() }.toSet()
            val next = if (on) skipped - language else skipped + language
            memory.setPreference(PREF_AUTO_SKIPPED, next.sorted().joinToString(","))
        }
    }

    /** Whether a reader of Chinese has works in the other script converted as they open. */
    public suspend fun setAutoTranslateOtherScript(on: Boolean): Unit = withContext(Dispatchers.Default) {
        memory.setPreference(PREF_AUTO_OTHER_SCRIPT, if (on) "1" else "0")
    }

    public suspend fun setProvider(kind: TranslationProviderKind): Unit =
        withContext(Dispatchers.Default) { memory.setPreference(PREF_PROVIDER, kind.id) }

    /** Stores [value] as the API key of [kind] in protected storage; blank removes it. */
    public suspend fun setKey(kind: TranslationProviderKind, value: String) {
        val store = secrets ?: return
        val name = kind.secretName ?: return
        if (value.isBlank()) store.remove(name) else store.putString(name, value.trim())
    }

    /** The region of the Azure Translator resource (for example `eastasia`); empty for a global resource. */
    public suspend fun setAzureRegion(region: String): Unit = withContext(Dispatchers.Default) {
        memory.setPreference(PREF_AZURE_REGION, region.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }.take(40))
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
            ?.takeIf {
                when (it) {
                    TranslationProviderKind.OFFLINE -> true
                    TranslationProviderKind.SYSTEM -> system?.available == true
                    else -> secrets != null
                }
            }
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
        TranslationProviderKind.SYSTEM -> SystemTranslationProvider(requireNotNull(system))
        TranslationProviderKind.DEEPL -> DeepLTranslationProvider(http, key(kind).orEmpty(), usage)
        TranslationProviderKind.GOOGLE -> GoogleTranslationProvider(http, key(kind).orEmpty(), usage)
        TranslationProviderKind.AZURE -> AzureTranslationProvider(
            http, key(kind).orEmpty(), withContext(Dispatchers.Default) { memory.preference(PREF_AZURE_REGION).orEmpty() }, usage,
        )
        TranslationProviderKind.LLM ->
            LlmTranslationProvider(http, withContext(Dispatchers.Default) { llmSettings() }, key(kind).orEmpty(), usage)
    }

    /** Simplified and Traditional Chinese are written one as the other here, whatever service is chosen. */
    private val chineseScript = ChineseScriptProvider()

    /**
     * The pair each work started translating in by itself while the app runs (its language read
     * from its pages, say): not saved with the work, which stays started by itself, but chapters
     * queued for it are translated the same way.
     */
    private val startedPairs = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())

    public fun startedByItself(workKey: String, from: String, to: String) {
        startedPairs.update { it + (workKey to (from to to)) }
    }

    public fun startedByItself(workKey: String): Pair<String, String>? = startedPairs.value[workKey]

    /** The provider a [from] to [to] chapter goes through: the device's own conversion between the two scripts of Chinese. */
    private suspend fun provider(from: String, to: String): TranslationProvider =
        if (ChineseScript.converts(from, to)) chineseScript else provider()

    /** The provider in use: the reader says where the text goes. */
    public suspend fun activeProvider(): TranslationProviderKind = withContext(Dispatchers.Default) { providerKind() }

    /**
     * The active provider and, where it can change under the same provider (another LLM model,
     * the system's other models), the engine: what drawn manga pages are keyed by.
     */
    public suspend fun engineIdentity(): String = withContext(Dispatchers.Default) {
        val kind = providerKind().name.lowercase()
        provider().currentEngine?.let { "$kind:" + TranslationText.hash(it).take(12) } ?: kind
    }

    /** [engineIdentity] for the pair: a conversion between the scripts of Chinese is the same whatever service is chosen. */
    public suspend fun engineIdentity(from: String, to: String): String =
        if (ChineseScript.converts(from, to)) "${ChineseScriptProvider.ID}:${ChineseScriptProvider.ENGINE}" else engineIdentity()

    public suspend fun supports(from: String, to: String): Boolean = provider(from, to).route(from, to) != null

    /** [autoTranslationPair] with this device's settings, when the chosen service can translate the pair. */
    public suspend fun autoTranslation(workLanguage: String?, appLanguage: String): Pair<String, String>? =
        autoTranslationPair(workLanguage, appLanguage, autoPreferences())?.takeIf { (from, to) -> supportsForAuto(from, to) }

    /**
     * Where a work starting by itself with [auto] goes: a language [picked] for it while it was off
     * is kept, but only when that pair too may start by itself, so a saved target cannot send a work
     * to a paid service the reader did not allow to start by itself.
     */
    public suspend fun autoTarget(auto: Pair<String, String>, picked: String?): String {
        val to = picked?.takeIf { it != auto.first && it != auto.second } ?: return auto.second
        val allowed = autoTranslationPair(auto.first, to, autoPreferences()) != null && supportsForAuto(auto.first, to)
        return if (allowed) to else auto.second
    }

    /** Models the offline engine still needs for this pair; empty for a cloud provider. */
    public suspend fun missingModels(from: String, to: String): List<OfflineModel> =
        if (!ChineseScript.converts(from, to) && activeProvider() == TranslationProviderKind.OFFLINE) offline.missingModels(from, to) else emptyList()

    /** True when the system's translation is chosen and still needs the pair's languages downloaded. */
    public suspend fun systemLanguagesMissing(from: String, to: String): Boolean {
        val engine = system?.takeIf { it.available } ?: return false
        if (activeProvider() != TranslationProviderKind.SYSTEM || ChineseScript.converts(from, to)) return false
        val source = OfflineModelCatalog.language(from) ?: return false
        val target = OfflineModelCatalog.language(to) ?: return false
        return runCatchingCancellable { engine.status(source, target) }.getOrNull() == SystemLanguageStatus.DOWNLOADABLE
    }

    /** Asks the system to download the pair's languages (its own prompt); true once installed. */
    public suspend fun downloadSystemLanguages(from: String, to: String): Boolean {
        val engine = system?.takeIf { it.available } ?: return false
        val source = OfflineModelCatalog.language(from) ?: return false
        val target = OfflineModelCatalog.language(to) ?: return false
        return engine.download(source, target)
    }

    // ---- offline models and stored translations, for the settings page ------------------------

    public suspend fun installedModels(): List<OfflineModel> =
        withContext(Dispatchers.Default) { OfflineModelCatalog.models.filter { files.installed(it) != null } }

    public suspend fun removeModel(model: OfflineModel): Unit = withContext(Dispatchers.Default) { files.remove(model) }

    /** Bytes of stored translations: free ones first, paid ones second. */
    public suspend fun storedTranslationBytes(): Pair<Long, Long> =
        withContext(Dispatchers.Default) { memory.freeBytes() to memory.paidBytes() }

    /** Translating chapters ahead of reading; shared by every screen, for the app's run. */
    public val prefetch: TranslationPrefetchQueue get() = prefetchQueue.value
    private val prefetchQueue = lazy { TranslationPrefetchQueue(this) }

    /** Stops the work that outlives screens; called when the app shuts down. */
    public fun close() {
        if (prefetchQueue.isInitialized()) prefetchQueue.value.close()
        background.cancel()
    }

    /** Chapter keys of [workKey] that have a complete stored translation, whatever made it. */
    public suspend fun translatedChapterKeys(workKey: String): Set<String> =
        withContext(Dispatchers.Default) { memory.completeChapterKeys(workKey, to = null, provider = null) }

    /**
     * Those translated into [to] by the service the pair goes through now: the ones translating
     * ahead can skip. From the other script of Chinese that is the conversion, from any other
     * language the service in use; with [from] not known, either.
     */
    public suspend fun translatedChapterKeys(workKey: String, to: String, from: String? = null): Set<String> {
        val target = OfflineModelCatalog.language(to) ?: to
        val source = from?.let { OfflineModelCatalog.language(it) ?: it }
        val conversion = source != null && ChineseScript.converts(source, target)
        // a conversion never asks for the chosen service (nor its key)
        val current = if (conversion) null else provider()
        return withContext(Dispatchers.Default) {
            val converted = if (target.startsWith("zh") && (source == null || conversion)) {
                // from the other script of Chinese, converted on the device whatever service is chosen
                memory.completeChapterKeys(workKey, target, chineseScript.id, chineseScript.currentEngine) +
                    memory.completeChapterKeys(workKey, to, chineseScript.id, chineseScript.currentEngine)
            } else {
                emptySet()
            }
            val translated = current?.let { provider ->
                // a chapter translated from another language (one since corrected) is not this pair's
                val hopsFrom = from?.let { provider.route(it, to)?.map { hop -> hop.from }?.distinct() }
                if (from != null && hopsFrom == null) return@let emptySet()
                memory.completeChapterKeys(workKey, target, provider.id, provider.currentEngine, hopsFrom) +
                    memory.completeChapterKeys(workKey, to, provider.id, provider.currentEngine, hopsFrom)
            }.orEmpty()
            translated + converted
        }
    }

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
        val provider = provider(request.from, request.to)
        return withContext(Dispatchers.Default) {
            // Also for chapters translated before names were recorded: opening one names it.
            request.labels?.let { labels -> runCatching { memory.saveLabels(request.workKey, request.chapterKey, labels) } }
            service.stored(request, provider)
        }
    }

    public suspend fun translate(request: ChapterTranslationRequest, onProgress: (done: Int, total: Int) -> Unit): ChapterTranslation {
        // the other script of Chinese is converted on the device: no key or service to check
        val kind = if (ChineseScript.converts(request.from, request.to)) null else activeProvider()
        // Decided by the project owner (2026-11-14): a cloud provider the user chose and keyed
        // may translate content whose grant has no EXPORT right, as extension content does by
        // default. The reader says where the text goes; `exportable` only governs backups.
        if (kind == TranslationProviderKind.LLM) {
            // A model server on this device or the home network may need no key at all.
            if (!withContext(Dispatchers.Default) { llmSettings() }.configured) {
                throw TranslationException(TranslationFailure.NOT_CONFIGURED, "The language-model service is not set up")
            }
        } else if (kind != null && kind != TranslationProviderKind.OFFLINE && kind != TranslationProviderKind.SYSTEM && key(kind).isNullOrBlank()) {
            throw TranslationException(TranslationFailure.INVALID_KEY, "No API key is set for ${kind.id}")
        }
        val provider = provider(request.from, request.to)
        // One chapter at a time, whoever asks (the reader or the queue that translates ahead):
        // two at once would each find the same lines missing and send them twice, and one's
        // "done, release the model" would take the model from under the other. The second
        // finds in the memory what the first stored.
        return translating.withLock { withContext(Dispatchers.Default) {
            try {
                service.translate(request, provider, onProgress)
            } finally {
                // A model is only held while a chapter is being translated: loading it again
                // takes a tenth of a second, keeping it takes about 350 MB of the reader's memory.
                withContext(kotlinx.coroutines.NonCancellable) { runCatchingCancellable { offline.unload() } }
                // Free translations can be made again: keep the newest and let the oldest go.
                runCatching { memory.evictFree(FREE_MEMORY_LIMIT_BYTES) }
            }
        } }
    }

    private val translating = kotlinx.coroutines.sync.Mutex()

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
        background.launch { runCatchingCancellable { offline.unload() } }
    }

    public companion object {
        private const val STORED_CHAPTER_LIST_LIMIT = 2_000
        private const val PREF_PROVIDER = "provider"
        private const val PREF_AZURE_REGION = "azure.region"
        private const val PREF_LLM_PROTOCOL = "llm.protocol"
        private const val PREF_LLM_BASE_URL = "llm.base"
        private const val PREF_LLM_MODEL = "llm.model"
        private const val MAX_LLM_MODEL_LENGTH = 200
        private const val PREF_DAILY_LIMIT = "daily.limit"
        private const val PREF_AUTO = "auto"
        private const val PREF_AUTO_PAID = "auto.paid"
        private const val PREF_AUTO_SKIPPED = "auto.skipped"
        private const val PREF_AUTO_OTHER_SCRIPT = "auto.otherScript"
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
         * models for. Chinese is Simplified or Traditional by the characters written only one way
         * ([ChineseScript.detect]); Simplified when it has none of them. The reader lets the user correct it.
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
                han > latin / 4 && han > 0 -> ChineseScript.detect(text) ?: ChineseScript.SIMPLIFIED
                else -> "en"
            }
        }

        /**
         * [detectLanguage], or null when [text] has too little writing to tell: an illustration
         * page is all markup and addresses, which would read as English.
         */
        public fun detectLanguageOrNull(text: String, minimumLetters: Int = MIN_DETECTED_LETTERS): String? {
            val written = text.take(DETECTED_CHARACTERS).replace(NOT_WRITING, " ")
            return if (written.count { it.isLetter() } >= minimumLetters) detectLanguage(written) else null
        }

        /**
         * Letters enough to take a chapter's language over the one a work is already being
         * translated from: a title page of a few kanji reads as Chinese, a heading as English.
         */
        public const val SETTLED_LETTERS: Int = 60

        private const val DETECTED_CHARACTERS = 4_000
        private const val MIN_DETECTED_LETTERS = 8
        private val NOT_WRITING = Regex("""!?\[[^\]]*]\([^)]*\)|<[^>]*>|https?://\S+""")

        /** The services that charge for what they translate. */
        public val PAID_PROVIDERS: Set<TranslationProviderKind> = setOf(
            TranslationProviderKind.DEEPL, TranslationProviderKind.GOOGLE, TranslationProviderKind.AZURE, TranslationProviderKind.LLM,
        )

        /**
         * The pair a work in [workLanguage] is translated with when it is opened with no settings of
         * its own, for someone reading the app in [appLanguage]; null leaves it as it is: automatic
         * translation is off, the work's language is unknown or already the reader's, or the service
         * charges and may not start by itself, or the user left works in that language untranslated.
         * Simplified and Traditional Chinese are converted on the device: free, so never held back as paid.
         */
        public fun autoTranslationPair(workLanguage: String?, appLanguage: String, preferences: TranslationPreferences): Pair<String, String>? {
            if (!preferences.autoTranslate || workLanguage.isNullOrBlank()) return null
            val from = OfflineModelCatalog.language(workLanguage) ?: return null
            val to = OfflineModelCatalog.language(appLanguage) ?: return null
            if (from == to) return null
            // the other script of Chinese is converted on the device, whatever the chosen service
            // charges, and only once the reader asked for it
            if (ChineseScript.converts(from, to)) return (from to to).takeIf { preferences.autoTranslateOtherScript }
            if (from in preferences.autoTranslateSkipped) return null
            if (preferences.provider in PAID_PROVIDERS && !preferences.autoTranslatePaid) return null
            return from to to
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
