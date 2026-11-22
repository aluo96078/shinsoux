package dev.shinsou.kmp.translation

import dev.shinsou.kmp.domain.model.TranslationBackup
import io.ktor.client.HttpClient

/**
 * Translation as the reader uses it (ADR-0004 §4.4), one narrow service per concern: which
 * service and when it starts by itself ([setup]), the models a pair needs ([models]), what is
 * stored ([storage]), translating a work's chapters ([chapters]) and its glossary ([glossaries]).
 * Everything runs on the device; nothing is sent anywhere except the model download from
 * Mozilla's CDN, or the text to the cloud service the reader chose and keyed.
 */
public interface ReaderTranslation {
    public val setup: TranslationSetup
    public val models: TranslationModels
    public val storage: StoredTranslations
    public val chapters: ChapterTranslator
    public val glossaries: WorkGlossary

    /** Translating chapters ahead of reading; shared by every screen, for the app's run. */
    public val prefetch: TranslationPrefetchQueue

    /** Stops the work that outlives screens; called when the app shuts down. */
    public fun close()

    public companion object {
        /** About a dozen long chapters a day until the user sets their own limit. */
        public const val DEFAULT_DAILY_CHARACTER_LIMIT: Long = 100_000

        /** Stored free translations beyond this are dropped, least recently read first (about 30 long novels). */
        public const val FREE_MEMORY_LIMIT_BYTES: Long = 96L * 1024 * 1024

        /** A backup is one document held in memory: stored translations beyond this stay behind, oldest first. */
        public const val BACKUP_SEGMENT_BUDGET_BYTES: Long = 24L * 1024 * 1024

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

/** Reader translation on [platform], keeping translations, settings and glossaries in [memory]. */
@Suppress("LongParameterList") // The former constructor's parameters; the last three have defaults.
public fun ReaderTranslation(
    memory: TranslationMemoryStore,
    platform: OfflineTranslationPlatform,
    http: HttpClient,
    /** Where API keys are kept (the platform's protected storage); null leaves only the offline engine. */
    secrets: dev.shinsou.kmp.files.KeyValueStore? = null,
    now: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
    /** The operating system's own translation, where the platform has one (iOS). */
    system: SystemTranslationEngine? = null,
): ReaderTranslation = DefaultReaderTranslation(memory, platform, http, secrets, system, ReaderTranslationEnvironment(now))

/** Which service translates, with which keys, and whether works start translating by themselves. */
public interface TranslationSetup {
    public val languages: List<TranslationLanguage>

    public suspend fun preferences(): TranslationPreferences

    /** The provider in use: the reader says where the text goes. */
    public suspend fun activeProvider(): TranslationProviderKind

    /** Whether works in another language are translated as they are opened, and whether with a paid service too. */
    public suspend fun setAutoTranslate(on: Boolean, paid: Boolean)

    /** Whether works in [language] are among those translated automatically (all are, until one is turned off). */
    public suspend fun setAutoTranslateLanguage(language: String, on: Boolean)

    /** Whether a reader of Chinese has works in the other script converted as they open. */
    public suspend fun setAutoTranslateOtherScript(on: Boolean)

    public suspend fun setProvider(kind: TranslationProviderKind)

    /** Stores [value] as the API key of [kind] in protected storage; blank removes it. */
    public suspend fun setKey(kind: TranslationProviderKind, value: String)

    /** The region of the Azure Translator resource (for example `eastasia`); empty for a global resource. */
    public suspend fun setAzureRegion(region: String)

    /** The language-model service: wire format, address and model. Refused when the address must not be used. */
    public suspend fun setLlmSettings(settings: LlmSettings)

    /** Characters a day that may be sent to paid providers; 0 removes the limit. */
    public suspend fun setDailyCharacterLimit(characters: Long)
}

/** The models a language pair needs on this device: the offline engine's and the system's own. */
public interface TranslationModels {
    /** False on a device that can never hold a model: nothing should be downloaded for it. */
    public val deviceSupported: Boolean

    /** Models the offline engine still needs for this pair; empty for a cloud provider. */
    public suspend fun missingModels(from: String, to: String): List<OfflineModel>

    public suspend fun downloadModels(from: String, to: String, onProgress: (received: Long, total: Long) -> Unit)

    /** True when the system's translation is chosen and still needs the pair's languages downloaded. */
    public suspend fun systemLanguagesMissing(from: String, to: String): Boolean

    /** Asks the system to download the pair's languages (its own prompt); true once installed. */
    public suspend fun downloadSystemLanguages(from: String, to: String): Boolean

    public suspend fun installedModels(): List<OfflineModel>

    public suspend fun removeModel(model: OfflineModel)

    /** Releases the loaded model's memory once the reader that used it is gone. */
    public fun release()
}

/** Stored chapter translations: what they take, which chapters have one, removing them, backups. */
public interface StoredTranslations {
    /** Bytes of stored translations: free ones first, paid ones second. */
    public suspend fun storedTranslationBytes(): Pair<Long, Long>

    /** Chapters with a stored translation, most recent first, for the settings page. */
    public suspend fun storedChapters(): List<StoredChapterTranslation>

    /** Removes one chapter's stored translation; returns the bytes removed. */
    public suspend fun deleteChapterTranslation(chapter: StoredChapterTranslation): Long

    /** Removes every stored chapter translation of a work; its glossary and settings stay. */
    public suspend fun deleteWorkTranslations(workKey: String): Long

    public suspend fun clearFreeTranslations()

    /** Chapter keys of [workKey] that have a complete stored translation, whatever made it. */
    public suspend fun translatedChapterKeys(workKey: String): Set<String>

    /**
     * Those translated into [to] by the service the pair goes through now: the ones translating
     * ahead can skip. From the other script of Chinese that is the conversion, from any other
     * language the service in use; with [from] not known, either.
     */
    public suspend fun translatedChapterKeys(workKey: String, to: String, from: String? = null): Set<String>

    /** Translation data for a backup; stored translations are included by the user's choice. */
    public suspend fun exportBackup(includePaid: Boolean, includeFree: Boolean): TranslationBackup

    public suspend fun importBackup(backup: TranslationBackup)
}

/** Translating one work's chapters as it is read: its settings, the pair it starts with, the text. */
public interface ChapterTranslator {
    public suspend fun settings(workKey: String): WorkTranslationSettings?

    /** Writes are queued in the order they were asked for, so the last change is the one that stays. */
    public suspend fun saveSettings(workKey: String, settings: WorkTranslationSettings)

    public suspend fun supports(from: String, to: String): Boolean

    /** [ReaderTranslation.autoTranslationPair] with this device's settings, when the chosen service can translate the pair. */
    public suspend fun autoTranslation(workLanguage: String?, appLanguage: String): Pair<String, String>?

    /**
     * Where a work starting by itself with [auto] goes: a language [picked] for it while it was off
     * is kept, but only when that pair too may start by itself, so a saved target cannot send a work
     * to a paid service the reader did not allow to start by itself.
     */
    public suspend fun autoTarget(auto: Pair<String, String>, picked: String?): String

    /** The work started translating from [from] into [to] by itself; kept for the app's run, not saved. */
    public fun startedByItself(workKey: String, from: String, to: String)

    public fun startedByItself(workKey: String): Pair<String, String>?

    /**
     * The active provider and, where it can change under the same provider (another LLM model,
     * the system's other models), the engine: what drawn manga pages are keyed by.
     */
    public suspend fun engineIdentity(): String

    /** [engineIdentity] for the pair: a conversion between the scripts of Chinese is the same whatever service is chosen. */
    public suspend fun engineIdentity(from: String, to: String): String

    public suspend fun stored(request: ChapterTranslationRequest): ChapterTranslation?

    public suspend fun translate(request: ChapterTranslationRequest, onProgress: (done: Int, total: Int) -> Unit): ChapterTranslation
}

/** The terms a work's translation keeps, per target language. */
public interface WorkGlossary {
    public suspend fun glossary(workKey: String, to: String): List<GlossaryEntry>

    public suspend fun putGlossaryTerm(workKey: String, to: String, source: String, target: String): GlossaryEntry

    public suspend fun removeGlossaryTerm(workKey: String, to: String, id: Int)
}
