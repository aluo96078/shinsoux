package dev.shinsou.kmp.ui.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.concurrent.runCatchingCancellable
import dev.shinsou.kmp.logging.Log
import dev.shinsou.kmp.translation.LlmProtocol
import dev.shinsou.kmp.translation.LlmSettings
import dev.shinsou.kmp.translation.OfflineModel
import dev.shinsou.kmp.translation.normalizedLlmBaseUrl
import dev.shinsou.kmp.translation.ReaderTranslation
import dev.shinsou.kmp.translation.StoredChapterTranslation
import dev.shinsou.kmp.translation.TranslationPreferences
import dev.shinsou.kmp.translation.TranslationProviderKind
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch
import dev.shinsou.kmp.ui.components.ChoiceSetting
import dev.shinsou.kmp.ui.components.SettingDescription
import dev.shinsou.kmp.ui.components.SettingsList

private class TranslationSettingsView(
    val preferences: TranslationPreferences,
    val models: List<OfflineModel>,
    val freeBytes: Long,
    val paidBytes: Long,
    val works: List<StoredWork>,
)

/** The stored chapters of one work, most recently translated first. */
internal class StoredWork(val key: String, val chapters: List<StoredChapterTranslation>) {
    val title: String? get() = chapters.firstNotNullOfOrNull { it.workTitle }
    val sourceName: String? get() = chapters.firstNotNullOfOrNull { it.sourceName }
}

internal fun storedWorks(chapters: List<StoredChapterTranslation>): List<StoredWork> =
    chapters.groupBy { it.workKey }.map { (key, rows) -> StoredWork(key, rows) }

private val DAILY_LIMITS = listOf(20_000L, 50_000L, 100_000L, 500_000L, 1_000_000L, 0L)

/**
 * One page of More → Translate ([TranslationScreen]): where translations come from and the chosen
 * service's key, the offline models, manga translation, or what is kept on the device.
 */
@Composable
internal fun TranslationSettingsPane(translation: ReaderTranslation?, page: TranslationPage) {
    val strings = LocalShinsouStrings.current
    if (translation == null) {
        SettingsList { item { SettingDescription(strings.text(StringKey.TRANSLATION_IS_NOT_AVAILABLE_IN_THIS_VERSION)) } }
        return
    }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<TranslationSettingsView?>(null) }
    var expandedWork by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<StoredRemoval?>(null) }
    LaunchedEffect(translation, revision) {
        val stored = translation.storage.storedTranslationBytes()
        view = TranslationSettingsView(
            translation.setup.preferences(), translation.models.installedModels(), stored.first, stored.second,
            storedWorks(translation.storage.storedChapters()),
        )
    }
    val current = view ?: return
    val manga = LocalMangaTranslation.current
    val preferences = current.preferences
    fun change(action: suspend () -> Unit) {
        scope.launch {
            runCatchingCancellable { action() }
                .onFailure { Log.warn("Translation", it) { "Changing a translation setting failed" } }
            revision++
        }
    }

    removal?.let { pending ->
        StoredRemovalDialog(
            removal = pending,
            onConfirm = {
                removal = null
                change {
                    val chapter = pending.chapter
                    if (chapter == null) translation.storage.deleteWorkTranslations(pending.work.key) else translation.storage.deleteChapterTranslation(chapter)
                }
            },
            onDismiss = { removal = null },
        )
    }

    SettingsList {
      if (page == TranslationPage.Service) {
        item {
            val labels = TranslationProviderKind.entries
                .filter {
                    when (it) {
                        TranslationProviderKind.OFFLINE -> true
                        TranslationProviderKind.SYSTEM -> preferences.systemAvailable
                        else -> preferences.cloudAvailable
                    }
                }
                .associateWith { it.label(strings) }
            ChoiceSetting(
                title = strings.text(StringKey.TRANSLATION_SERVICE),
                summary = when (preferences.provider) {
                    TranslationProviderKind.OFFLINE ->
                        strings.text(StringKey.FREE_AND_ON_THIS_DEVICE_NOTHING_YOU)
                    TranslationProviderKind.SYSTEM ->
                        strings.text(StringKey.THE_TRANSLATION_BUILT_INTO_THE_SYSTEM_FREE)
                    TranslationProviderKind.DEEPL ->
                        strings.text(StringKey.USES_YOUR_X_API_KEY_THE_TEXT, "DeepL")
                    TranslationProviderKind.GOOGLE ->
                        strings.text(StringKey.USES_YOUR_X_API_KEY_THE_TEXT, "Google")
                    TranslationProviderKind.AZURE ->
                        strings.text(StringKey.USES_YOUR_X_API_KEY_THE_TEXT, "Microsoft Azure")
                    TranslationProviderKind.LLM ->
                        strings.text(StringKey.A_LANGUAGE_MODEL_AT_THE_ADDRESS_YOU)
                } + "\n" + strings.text(StringKey.SIMPLIFIED_AND_TRADITIONAL_CHINESE_ARE_ALWAYS_CONVERTED),
                selectedLabel = labels.getValue(preferences.provider),
                choices = labels.values.toList(),
            ) { label ->
                labels.entries.firstOrNull { it.value == label }?.let { choice -> change { translation.setup.setProvider(choice.key) } }
            }
        }
        // Only the chosen service's settings: the others are filled in after switching to them.
        if (preferences.cloudAvailable && preferences.provider == TranslationProviderKind.DEEPL) {
            item {
                ApiKeySetting(
                    title = strings.text(StringKey.X_API_KEY, "DeepL"),
                    help = strings.text(StringKey.FROM_YOUR_DEEPL_ACCOUNT_UNDER_API_KEYS),
                    stored = preferences.deepLKeySet,
                    tag = "translation-key-deepl",
                ) { key -> change { translation.setup.setKey(TranslationProviderKind.DEEPL, key) } }
            }
        }
        if (preferences.cloudAvailable && preferences.provider == TranslationProviderKind.GOOGLE) {
            item {
                ApiKeySetting(
                    title = strings.text(StringKey.X_API_KEY, "Google Cloud Translation"),
                    help = strings.text(StringKey.AN_API_KEY_FROM_A_GOOGLE_CLOUD),
                    stored = preferences.googleKeySet,
                    tag = "translation-key-google",
                ) { key -> change { translation.setup.setKey(TranslationProviderKind.GOOGLE, key) } }
            }
        }
        if (preferences.cloudAvailable && preferences.provider == TranslationProviderKind.AZURE) {
            item {
                ApiKeySetting(
                    title = strings.text(StringKey.X_API_KEY, "Azure Translator"),
                    help = strings.text(StringKey.A_KEY_OF_A_TRANSLATOR_RESOURCE_IN),
                    stored = preferences.azureKeySet,
                    tag = "translation-key-azure",
                ) { key -> change { translation.setup.setKey(TranslationProviderKind.AZURE, key) } }
            }
            item {
                var region by remember(preferences.azureRegion) { mutableStateOf(preferences.azureRegion) }
                Surface(
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = region,
                            onValueChange = { region = it.take(40) },
                            label = { Text(strings.text(StringKey.AZURE_REGION)) },
                            supportingText = { Text(strings.text(StringKey.THE_RESOURCE_S_REGION_FOR_EXAMPLE_EASTASIA)) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("translation-azure-region"),
                        )
                        Button(
                            onClick = { change { translation.setup.setAzureRegion(region) } },
                            enabled = region.trim() != preferences.azureRegion,
                        ) { Text(strings.text(StringKey.SAVE)) }
                    }
                }
            }
        }
        if (preferences.cloudAvailable && preferences.provider == TranslationProviderKind.LLM) {
            item {
                LlmServiceSetting(
                    settings = preferences.llm,
                    keyStored = preferences.llmKeySet,
                    onSave = { settings, key ->
                        change {
                            translation.setup.setLlmSettings(settings)
                            // An empty field keeps the stored key; the Remove button clears it.
                            if (key.isNotBlank()) translation.setup.setKey(TranslationProviderKind.LLM, key)
                        }
                    },
                    onRemoveKey = { change { translation.setup.setKey(TranslationProviderKind.LLM, "") } },
                )
            }
        }
        if (preferences.cloudAvailable && preferences.provider in ReaderTranslation.PAID_PROVIDERS) {
            item {
                val labels = DAILY_LIMITS.associateWith { limit ->
                    if (limit == 0L) strings.text(StringKey.NO_LIMIT) else strings.text(StringKey.X_CHARACTERS, grouped(limit))
                }
                ChoiceSetting(
                    title = strings.text(StringKey.DAILY_LIMIT_FOR_PAID_TRANSLATION),
                    summary = strings.text(StringKey.USED_TODAY_X_CHARACTERS_TRANSLATION_STOPS_AT, grouped(preferences.charactersUsedToday)),
                    selectedLabel = labels[preferences.dailyCharacterLimit]
                        ?: strings.text(StringKey.X_CHARACTERS, grouped(preferences.dailyCharacterLimit)),
                    choices = labels.values.toList(),
                ) { label ->
                    labels.entries.firstOrNull { it.value == label }?.let { choice -> change { translation.setup.setDailyCharacterLimit(choice.key) } }
                }
            }
        }
      }
      if (page == TranslationPage.Downloads) {
        item { SettingDescription(strings.text(StringKey.OFFLINE_TRANSLATION_MODELS)) }
        if (current.preferences.systemAvailable) {
            item { SettingDescription(strings.text(StringKey.APPLE_S_TRANSLATION_LANGUAGES_ARE_MANAGED_IN)) }
        }
        if (current.models.isEmpty()) {
            item {
                SettingRow(
                    title = strings.text(StringKey.NO_MODELS_DOWNLOADED),
                    summary = strings.text(StringKey.A_MODEL_IS_DOWNLOADED_THE_FIRST_TIME),
                )
            }
        }
        items(current.models, key = { it.id }) { model ->
            SettingRow(
                title = "${languageName(translation, model.from)} → ${languageName(translation, model.to)}",
                summary = strings.text(StringKey.X_MB, megabytes(model.downloadBytes)),
            ) {
                TextButton(onClick = { change { translation.models.removeModel(model) } }) { Text(strings.text(StringKey.REMOVE)) }
            }
        }
        if (manga != null) item { MangaResourceSettings(manga) }
      }
      if (page == TranslationPage.Manga && manga != null) {
        item { MangaAcceleratorSettings(manga) }
      }
      if (page == TranslationPage.Stored) {
        item {
            SettingRow(
                title = strings.text(StringKey.FREE_MACHINE_TRANSLATIONS),
                summary = strings.text(StringKey.X_THE_OLDEST_ARE_REMOVED_AUTOMATICALLY_WHEN, storedSize(current.freeBytes)),
            ) {
                TextButton(
                    onClick = { change { translation.storage.clearFreeTranslations() } },
                    enabled = current.freeBytes > 0,
                ) { Text(strings.text(StringKey.CLEAR)) }
            }
        }
        item {
            SettingRow(
                title = strings.text(StringKey.PAID_TRANSLATIONS),
                summary = strings.text(StringKey.X_KEPT_UNTIL_YOU_REMOVE_THEM_BECAUSE, storedSize(current.paidBytes)),
            )
        }
        item { SettingDescription(strings.text(StringKey.TRANSLATED_CHAPTERS)) }
        if (current.works.isEmpty()) {
            item { SettingRow(title = strings.text(StringKey.NO_TRANSLATED_CHAPTERS_YET), summary = strings.text(StringKey.CHAPTERS_YOU_TRANSLATE_WHILE_READING_ARE_LISTED)) }
        }
        current.works.forEach { work ->
            val expanded = expandedWork == work.key
            item(key = "work:" + work.key) {
                Surface(
                    onClick = { expandedWork = if (expanded) null else work.key },
                    shape = RoundedCornerShape(11.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth().testTag("stored-work"),
                ) {
                    Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(work.title ?: strings.text(StringKey.UNTITLED_WORK_OPEN_IT_AGAIN_TO_SHOW), style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    work.sourceName,
                                    strings.text(StringKey.X_CHAPTERS, work.chapters.size),
                                    storedTime(work.chapters.first().updatedAt, strings),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { removal = StoredRemoval(work, null) },
                            modifier = Modifier.testTag("delete-stored-work"),
                        ) { Icon(Icons.Outlined.Delete, strings.text(StringKey.DELETE_THIS_WORK_S_TRANSLATIONS)) }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                }
            }
            if (expanded) {
                items(work.chapters, key = { "chapter:" + it.workKey + "/" + it.chapterKey + "/" + it.to + "/" + it.provider }) { chapter ->
                    Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                      Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(chapter.chapterTitle ?: strings.text(StringKey.UNTITLED_CHAPTER), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                providerLabel(chapter.provider, strings),
                                "→ " + languageName(translation, chapter.to),
                                strings.text(StringKey.X_LINES, chapter.lineCount),
                                strings.text(StringKey.INCOMPLETE).takeIf { !chapter.complete },
                                storedTime(chapter.updatedAt, strings),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      IconButton(
                          onClick = { removal = StoredRemoval(work, chapter) },
                          modifier = Modifier.testTag("delete-stored-chapter"),
                      ) { Icon(Icons.Outlined.Delete, strings.text(StringKey.DELETE_THIS_CHAPTER_S_TRANSLATION)) }
                    }
                }
            }
        }
      }
    }
}

/** What the user asked to remove: a whole work's translations, or one chapter's. */
private class StoredRemoval(val work: StoredWork, val chapter: StoredChapterTranslation?)

@Composable
private fun StoredRemovalDialog(removal: StoredRemoval, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val strings = LocalShinsouStrings.current
    val chapter = removal.chapter
    val paid = chapter?.paid ?: removal.work.chapters.any { it.paid }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (chapter == null) strings.text(StringKey.DELETE_THIS_WORK_S_TRANSLATIONS_2) else strings.text(StringKey.DELETE_THIS_CHAPTER_S_TRANSLATION_2),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (chapter == null) {
                        strings.text(
                            StringKey.X_THE_STORED_TRANSLATIONS_OF_X_CHAPTERS,
                            removal.work.title ?: strings.text(StringKey.UNTITLED_WORK_OPEN_IT_AGAIN_TO_SHOW),
                            removal.work.chapters.size,
                        )
                    } else {
                        chapter.chapterTitle ?: strings.text(StringKey.UNTITLED_CHAPTER)
                    },
                )
                Text(
                    if (paid) {
                        strings.text(StringKey.THESE_WERE_TRANSLATED_BY_A_PAID_SERVICE)
                    } else {
                        strings.text(StringKey.THEY_ARE_TRANSLATED_AGAIN_THE_NEXT_TIME)
                    },
                    color = if (paid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-delete-stored")) {
                Text(strings.text(StringKey.DELETE), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.text(StringKey.CANCEL)) } },
    )
}

@Composable
private fun SettingRow(title: String, summary: String, trailing: (@Composable () -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 13.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            trailing?.invoke()
        }
    }
}

/**
 * The user's own language-model service: which wire format it speaks, where it is, which model,
 * and its key. The three formats cover the vendors themselves and the many gateways and local
 * servers that copy one of them.
 */
@Composable
private fun LlmServiceSetting(
    settings: LlmSettings,
    keyStored: Boolean,
    onSave: (LlmSettings, key: String) -> Unit,
    onRemoveKey: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var protocol by remember(settings) { mutableStateOf(settings.protocol) }
    var baseUrl by remember(settings) { mutableStateOf(settings.baseUrl) }
    var model by remember(settings) { mutableStateOf(settings.model) }
    var key by remember(settings) { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }
    val addressUsable = normalizedLlmBaseUrl(baseUrl) != null
    val changed = protocol != settings.protocol || baseUrl.trim() != settings.baseUrl || model.trim() != settings.model || key.isNotBlank()
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(strings.text(StringKey.AI_MODEL_YOUR_OWN_API), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.text(StringKey.ANY_SERVICE_THAT_SPEAKS_ONE_OF_THESE),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                LlmProtocol.entries.forEach { choice ->
                    FilterChip(
                        selected = protocol == choice,
                        onClick = {
                            // An address or model left at the old format's default follows the new one.
                            if (baseUrl.trim() == protocol.defaultBaseUrl || baseUrl.isBlank()) baseUrl = choice.defaultBaseUrl
                            if (model.trim() == protocol.suggestedModel) model = choice.suggestedModel
                            protocol = choice
                        },
                        label = { Text(choice.label(strings)) },
                        modifier = Modifier.testTag("llm-protocol-" + choice.id),
                    )
                }
            }
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it.take(500) },
                label = { Text(strings.text(StringKey.API_ADDRESS)) },
                supportingText = {
                    Text(
                        if (addressUsable) {
                            strings.text(StringKey.THE_BASE_ADDRESS_THE_APP_ADDS_THE)
                        } else {
                            strings.text(StringKey.USE_HTTPS_OR_HTTP_ONLY_FOR_THIS)
                        },
                        color = if (addressUsable) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                },
                isError = !addressUsable,
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("llm-base-url"),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it.take(200) },
                label = { Text(strings.text(StringKey.MODEL)) },
                supportingText = { Text(strings.text(StringKey.THE_MODEL_S_IDENTIFIER_EXACTLY_AS_THE)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("llm-model"),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.take(512) },
                label = { Text(if (keyStored) strings.text(StringKey.REPLACE_THE_KEY) else strings.text(StringKey.API_KEY)) },
                supportingText = {
                    Text(
                        if (keyStored) strings.text(StringKey.A_KEY_IS_SAVED_IN_THIS_DEVICE)
                        else strings.text(StringKey.LEAVE_EMPTY_FOR_A_LOCAL_SERVER_THAT),
                    )
                },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (keyVisible) strings.text(StringKey.HIDE_TOKEN) else strings.text(StringKey.SHOW_TOKEN),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag("llm-key"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSave(LlmSettings(protocol, baseUrl.trim(), model.trim()), key) },
                    enabled = changed && addressUsable && model.isNotBlank(),
                    modifier = Modifier.testTag("llm-save"),
                ) { Text(strings.text(StringKey.SAVE)) }
                if (keyStored) TextButton(onClick = onRemoveKey) { Text(strings.text(StringKey.REMOVE_THE_KEY)) }
            }
        }
    }
}

private fun LlmProtocol.label(strings: ShinsouStrings): String = when (this) {
    LlmProtocol.OPENAI -> strings.text(StringKey.OPENAI_COMPATIBLE)
    LlmProtocol.ANTHROPIC -> strings.text(StringKey.ANTHROPIC_COMPATIBLE)
    LlmProtocol.GOOGLE -> strings.text(StringKey.GOOGLE_GEMINI_COMPATIBLE)
}

/** A key is write-only here: once saved it is never shown again, only replaced or removed. */
@Composable
private fun ApiKeySetting(title: String, help: String, stored: Boolean, tag: String, onSave: (String) -> Unit) {
    val strings = LocalShinsouStrings.current
    var value by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(11.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                if (stored) strings.text(StringKey.A_KEY_IS_SAVED_IN_THIS_DEVICE) else help,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(256) },
                label = { Text(if (stored) strings.text(StringKey.REPLACE_THE_KEY) else strings.text(StringKey.API_KEY)) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (visible) strings.text(StringKey.HIDE_TOKEN) else strings.text(StringKey.SHOW_TOKEN),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth().testTag(tag),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        onSave(value)
                        value = ""
                    },
                    enabled = value.isNotBlank(),
                ) { Text(strings.text(StringKey.SAVE)) }
                if (stored) {
                    TextButton(onClick = { onSave("") }) { Text(strings.text(StringKey.REMOVE)) }
                }
            }
        }
    }
}

private fun providerLabel(id: String, strings: ShinsouStrings): String =
    TranslationProviderKind.entries.firstOrNull { it.id == id }?.label(strings) ?: id

private fun storedTime(timestamp: Long, strings: ShinsouStrings): String {
    val delta = (kotlin.time.Clock.System.now().toEpochMilliseconds() - timestamp).coerceAtLeast(0)
    return when {
        delta < 60_000 -> strings.text(StringKey.JUST_NOW)
        delta < 3_600_000 -> strings.text(StringKey.X_M_AGO, delta / 60_000)
        delta < 86_400_000 -> strings.text(StringKey.X_H_AGO, delta / 3_600_000)
        else -> strings.text(StringKey.X_D_AGO, delta / 86_400_000)
    }
}

private fun languageName(translation: ReaderTranslation, tag: String): String =
    translation.setup.languages.firstOrNull { it.tag == tag || it.tag.substringBefore('-') == tag }?.name ?: tag

private fun megabytes(bytes: Long): String = ((bytes + 524_288L) / 1_048_576L).toString()

/** A chapter is a few kilobytes: megabytes alone would read "0 MB" for a whole evening's reading. */
internal fun storedSize(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1_048_576L -> "${maxOf(1L, (bytes + 512L) / 1024L)} KB"
    else -> "${bytes / 1_048_576L}.${(bytes % 1_048_576L) * 10L / 1_048_576L} MB"
}

private fun grouped(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
