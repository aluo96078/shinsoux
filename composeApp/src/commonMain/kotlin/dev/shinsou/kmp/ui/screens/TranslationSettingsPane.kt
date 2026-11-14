package dev.shinsou.kmp.ui.screens

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
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.launch

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

/** Settings → Translation: where translations come from, the API keys, and what is kept on the device. */
@Composable
internal fun TranslationSettingsPane(translation: ReaderTranslation?) {
    val strings = LocalShinsouStrings.current
    if (translation == null) {
        SettingsList { item { SettingDescription(strings.text("Translation is not available in this version of the app.")) } }
        return
    }
    val scope = rememberCoroutineScope()
    var revision by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf<TranslationSettingsView?>(null) }
    var expandedWork by remember { mutableStateOf<String?>(null) }
    var removal by remember { mutableStateOf<StoredRemoval?>(null) }
    LaunchedEffect(translation, revision) {
        val stored = translation.storedTranslationBytes()
        view = TranslationSettingsView(
            translation.preferences(), translation.installedModels(), stored.first, stored.second,
            storedWorks(translation.storedChapters()),
        )
    }
    val current = view ?: return
    val preferences = current.preferences
    fun change(action: suspend () -> Unit) {
        scope.launch {
            runCatching { action() }
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
                    if (chapter == null) translation.deleteWorkTranslations(pending.work.key) else translation.deleteChapterTranslation(chapter)
                }
            },
            onDismiss = { removal = null },
        )
    }

    SettingsList {
        item {
            SettingDescription(
                strings.text("Translate novel chapters while reading: open a chapter and press the translation button in the reader's top bar. Translated chapters are kept, so nothing is translated twice."),
            )
        }
        item {
            val labels = TranslationProviderKind.entries
                .filter { it == TranslationProviderKind.OFFLINE || preferences.cloudAvailable }
                .associateWith { it.label(strings) }
            ChoiceSetting(
                title = strings.text("Translation service"),
                summary = when (preferences.provider) {
                    TranslationProviderKind.OFFLINE ->
                        strings.text("Free and on this device. Nothing you read is sent anywhere. Quality is below the paid services.")
                    TranslationProviderKind.DEEPL ->
                        strings.text("Uses your {0} API key. The text of the chapters you translate is sent to {0}, and {0} may charge for it.", "DeepL")
                    TranslationProviderKind.GOOGLE ->
                        strings.text("Uses your {0} API key. The text of the chapters you translate is sent to {0}, and {0} may charge for it.", "Google")
                    TranslationProviderKind.AZURE ->
                        strings.text("Uses your {0} API key. The text of the chapters you translate is sent to {0}, and {0} may charge for it.", "Microsoft Azure")
                    TranslationProviderKind.LLM ->
                        strings.text("A language model at the address you enter below. The text of the chapters you translate is sent there; its provider may charge for it.")
                },
                selectedLabel = labels.getValue(preferences.provider),
                choices = labels.values.toList(),
            ) { label ->
                labels.entries.firstOrNull { it.value == label }?.let { choice -> change { translation.setProvider(choice.key) } }
            }
        }
        if (preferences.cloudAvailable) {
            item {
                ApiKeySetting(
                    title = strings.text("{0} API key", "DeepL"),
                    help = strings.text("From your DeepL account, under API keys. The free plan covers 500,000 characters a month."),
                    stored = preferences.deepLKeySet,
                    tag = "translation-key-deepl",
                ) { key -> change { translation.setKey(TranslationProviderKind.DEEPL, key) } }
            }
            item {
                ApiKeySetting(
                    title = strings.text("{0} API key", "Google Cloud Translation"),
                    help = strings.text("An API key from a Google Cloud project with the Cloud Translation API enabled."),
                    stored = preferences.googleKeySet,
                    tag = "translation-key-google",
                ) { key -> change { translation.setKey(TranslationProviderKind.GOOGLE, key) } }
            }
            item {
                ApiKeySetting(
                    title = strings.text("{0} API key", "Azure Translator"),
                    help = strings.text("A key of a Translator resource in the Azure portal (Keys and Endpoint)."),
                    stored = preferences.azureKeySet,
                    tag = "translation-key-azure",
                ) { key -> change { translation.setKey(TranslationProviderKind.AZURE, key) } }
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
                            label = { Text(strings.text("Azure region")) },
                            supportingText = { Text(strings.text("The resource's region, for example eastasia. Leave empty for a global resource.")) },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("translation-azure-region"),
                        )
                        Button(
                            onClick = { change { translation.setAzureRegion(region) } },
                            enabled = region.trim() != preferences.azureRegion,
                        ) { Text(strings.text("Save")) }
                    }
                }
            }
            item {
                LlmServiceSetting(
                    settings = preferences.llm,
                    keyStored = preferences.llmKeySet,
                    onSave = { settings, key ->
                        change {
                            translation.setLlmSettings(settings)
                            // An empty field keeps the stored key; the Remove button clears it.
                            if (key.isNotBlank()) translation.setKey(TranslationProviderKind.LLM, key)
                        }
                    },
                    onRemoveKey = { change { translation.setKey(TranslationProviderKind.LLM, "") } },
                )
            }
            item {
                val labels = DAILY_LIMITS.associateWith { limit ->
                    if (limit == 0L) strings.text("No limit") else strings.text("{0} characters", grouped(limit))
                }
                ChoiceSetting(
                    title = strings.text("Daily limit for paid translation"),
                    summary = strings.text("Used today: {0} characters. Translation stops at the limit so a long read cannot run up a bill.", grouped(preferences.charactersUsedToday)),
                    selectedLabel = labels[preferences.dailyCharacterLimit]
                        ?: strings.text("{0} characters", grouped(preferences.dailyCharacterLimit)),
                    choices = labels.values.toList(),
                ) { label ->
                    labels.entries.firstOrNull { it.value == label }?.let { choice -> change { translation.setDailyCharacterLimit(choice.key) } }
                }
            }
        }
        item { SettingDescription(strings.text("Offline translation models")) }
        if (current.models.isEmpty()) {
            item {
                SettingRow(
                    title = strings.text("No models downloaded"),
                    summary = strings.text("A model is downloaded the first time a language pair is translated offline."),
                )
            }
        }
        items(current.models, key = { it.id }) { model ->
            SettingRow(
                title = "${languageName(translation, model.from)} → ${languageName(translation, model.to)}",
                summary = strings.text("{0} MB", megabytes(model.downloadBytes)),
            ) {
                TextButton(onClick = { change { translation.removeModel(model) } }) { Text(strings.text("Remove")) }
            }
        }
        item { SettingDescription(strings.text("Stored translations")) }
        item {
            SettingRow(
                title = strings.text("Free machine translations"),
                summary = strings.text("{0}. The oldest are removed automatically when they grow too large.", storedSize(current.freeBytes)),
            ) {
                TextButton(
                    onClick = { change { translation.clearFreeTranslations() } },
                    enabled = current.freeBytes > 0,
                ) { Text(strings.text("Clear")) }
            }
        }
        item {
            SettingRow(
                title = strings.text("Paid translations"),
                summary = strings.text("{0}. Kept until you remove them, because translating again would cost again.", storedSize(current.paidBytes)),
            )
        }
        item { SettingDescription(strings.text("Translated chapters")) }
        if (current.works.isEmpty()) {
            item { SettingRow(title = strings.text("No translated chapters yet"), summary = strings.text("Chapters you translate while reading are listed here.")) }
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
                            Text(work.title ?: strings.text("Untitled work (open it again to show its name)"), style = MaterialTheme.typography.titleMedium)
                            Text(
                                listOfNotNull(
                                    work.sourceName,
                                    strings.text("{0} chapters", work.chapters.size),
                                    storedTime(work.chapters.first().updatedAt, strings),
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { removal = StoredRemoval(work, null) },
                            modifier = Modifier.testTag("delete-stored-work"),
                        ) { Icon(Icons.Outlined.Delete, strings.text("Delete this work's translations")) }
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null)
                    }
                }
            }
            if (expanded) {
                items(work.chapters, key = { "chapter:" + it.workKey + "/" + it.chapterKey + "/" + it.to + "/" + it.provider }) { chapter ->
                    Row(Modifier.fillMaxWidth().padding(start = 26.dp, end = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                      Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                        Text(chapter.chapterTitle ?: strings.text("Untitled chapter"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            listOfNotNull(
                                providerLabel(chapter.provider, strings),
                                "→ " + languageName(translation, chapter.to),
                                strings.text("{0} lines", chapter.lineCount),
                                strings.text("Incomplete").takeIf { !chapter.complete },
                                storedTime(chapter.updatedAt, strings),
                            ).joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                      }
                      IconButton(
                          onClick = { removal = StoredRemoval(work, chapter) },
                          modifier = Modifier.testTag("delete-stored-chapter"),
                      ) { Icon(Icons.Outlined.Delete, strings.text("Delete this chapter's translation")) }
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
                if (chapter == null) strings.text("Delete this work's translations?") else strings.text("Delete this chapter's translation?"),
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (chapter == null) {
                        strings.text(
                            "{0}: the stored translations of {1} chapters are removed. The work's names and terms are kept.",
                            removal.work.title ?: strings.text("Untitled work (open it again to show its name)"),
                            removal.work.chapters.size,
                        )
                    } else {
                        chapter.chapterTitle ?: strings.text("Untitled chapter")
                    },
                )
                Text(
                    if (paid) {
                        strings.text("These were translated by a paid service: reading them translated again is charged again.")
                    } else {
                        strings.text("They are translated again the next time the chapters are read with translation on.")
                    },
                    color = if (paid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("confirm-delete-stored")) {
                Text(strings.text("Delete"), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.text("Cancel")) } },
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
            Text(strings.text("AI model (your own API)"), style = MaterialTheme.typography.titleMedium)
            Text(
                strings.text("Any service that speaks one of these three formats: the vendors' own APIs, gateways that resell them, or a model server on this device or your network."),
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
                label = { Text(strings.text("API address")) },
                supportingText = {
                    Text(
                        if (addressUsable) {
                            strings.text("The base address; the app adds the rest of the path for the chosen format.")
                        } else {
                            strings.text("Use https, or http only for this device or a private network address.")
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
                label = { Text(strings.text("Model")) },
                supportingText = { Text(strings.text("The model's identifier exactly as the service names it.")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("llm-model"),
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it.take(512) },
                label = { Text(if (keyStored) strings.text("Replace the key") else strings.text("API key")) },
                supportingText = {
                    Text(
                        if (keyStored) strings.text("A key is saved in this device's secure storage.")
                        else strings.text("Leave empty for a local server that needs no key."),
                    )
                },
                singleLine = true,
                visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { keyVisible = !keyVisible }) {
                        Icon(
                            if (keyVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (keyVisible) strings.text("Hide token") else strings.text("Show token"),
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
                ) { Text(strings.text("Save")) }
                if (keyStored) TextButton(onClick = onRemoveKey) { Text(strings.text("Remove the key")) }
            }
        }
    }
}

private fun LlmProtocol.label(strings: ShinsouStrings): String = when (this) {
    LlmProtocol.OPENAI -> strings.text("OpenAI-compatible")
    LlmProtocol.ANTHROPIC -> strings.text("Anthropic-compatible")
    LlmProtocol.GOOGLE -> strings.text("Google Gemini-compatible")
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
                if (stored) strings.text("A key is saved in this device's secure storage.") else help,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(256) },
                label = { Text(if (stored) strings.text("Replace the key") else strings.text("API key")) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            if (visible) strings.text("Hide token") else strings.text("Show token"),
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
                ) { Text(strings.text("Save")) }
                if (stored) {
                    TextButton(onClick = { onSave("") }) { Text(strings.text("Remove")) }
                }
            }
        }
    }
}

internal fun TranslationProviderKind.label(strings: ShinsouStrings): String = when (this) {
    TranslationProviderKind.OFFLINE -> strings.text("Offline (free)")
    TranslationProviderKind.DEEPL -> "DeepL"
    TranslationProviderKind.GOOGLE -> strings.text("Google Translate")
    TranslationProviderKind.AZURE -> "Azure Translator"
    TranslationProviderKind.LLM -> strings.text("AI model (your own API)")
}

private fun providerLabel(id: String, strings: ShinsouStrings): String =
    TranslationProviderKind.entries.firstOrNull { it.id == id }?.label(strings) ?: id

private fun storedTime(timestamp: Long, strings: ShinsouStrings): String {
    val delta = (kotlin.time.Clock.System.now().toEpochMilliseconds() - timestamp).coerceAtLeast(0)
    return when {
        delta < 60_000 -> strings.text("Just now")
        delta < 3_600_000 -> strings.text("{0}m ago", delta / 60_000)
        delta < 86_400_000 -> strings.text("{0}h ago", delta / 3_600_000)
        else -> strings.text("{0}d ago", delta / 86_400_000)
    }
}

private fun languageName(translation: ReaderTranslation, tag: String): String =
    translation.languages.firstOrNull { it.tag == tag || it.tag.substringBefore('-') == tag }?.name ?: tag

private fun megabytes(bytes: Long): String = ((bytes + 524_288L) / 1_048_576L).toString()

/** A chapter is a few kilobytes: megabytes alone would read "0 MB" for a whole evening's reading. */
internal fun storedSize(bytes: Long): String = when {
    bytes <= 0L -> "0 KB"
    bytes < 1_048_576L -> "${maxOf(1L, (bytes + 512L) / 1024L)} KB"
    else -> "${bytes / 1_048_576L}.${(bytes % 1_048_576L) * 10L / 1_048_576L} MB"
}

private fun grouped(value: Long): String = value.toString().reversed().chunked(3).joinToString(",").reversed()
