package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.key.key
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.SourceAuth
import dev.shinsou.kmp.plugin.SourceSettings
import dev.shinsou.kmp.plugin.SourceWebSession
import dev.shinsou.kmp.plugin.BrowseRepository
import dev.shinsou.kmp.plugin.BrowseSource
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

@Composable
internal fun SourcesPane(
    sources: List<BrowseSource>,
    repositories: List<BrowseRepository>,
    onToggle: (BrowseSource, Boolean) -> Unit,
    pinnedSourceIds: Set<Long>,
    pinnedSourceKeys: Set<String>,
    onSourcePinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
    onSourcePinnedKeyChange: (sourceKey: String, pinned: Boolean) -> Unit,
    settings: SourceSettings,
    auth: SourceAuth,
    webSession: SourceWebSession,
    onImportDocument: suspend (acceptedExtensions: Set<String>) -> ImportedDocument?,
    onOpen: (BrowseSource) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    if (sources.isEmpty()) {
        EmptyState(
            title = strings.text(StringKey.NO_SOURCES),
            message = strings.text(StringKey.INSTALL_AN_EXTENSION_OR_ENABLE_ANOTHER_LANGUAGE),
            icon = { Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp)) },
        )
        return
    }
    var settingsSourceIdentity by remember { mutableStateOf<String?>(null) }
    // The sources page keeps its own repository choice; choosing one never refreshes repositories.
    var selectedRepositoryId by rememberSaveable { mutableStateOf<String?>(null) }
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val effectiveRepositoryId = selectedRepositoryId?.takeIf { id -> repositories.any { it.id == id } }
    val shown = if (effectiveRepositoryId == null) {
        sources
    } else {
        repositoryGroups(sources, repositories, effectiveRepositoryId) { it.repositoryId }.single().items
    }
    val sections = browseSourceSections(shown, pinnedSourceIds, pinnedSourceKeys)
    val groups = repositoryGroups(sections.regular, repositories, effectiveRepositoryId) { it.repositoryId }
    val groupKeys = buildList {
        if (sections.pinned.isNotEmpty()) add(PINNED_SOURCE_GROUP)
        groups.filter { it.items.isNotEmpty() }.forEach { add(it.repository?.id ?: OTHER_SOURCE_GROUP) }
    }
    val toggleGroup: (String) -> Unit = { key ->
        collapsedGroups = if (key in collapsedGroups) collapsedGroups - key else collapsedGroups + key
    }
    val openSettings: (BrowseSource) -> Unit = { settingsSourceIdentity = it.identityKey }
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (repositories.isNotEmpty()) {
            item("source-repositories") {
                RepositorySelector(
                    repositories = repositories,
                    selectedRepositoryId = effectiveRepositoryId,
                    enabled = true,
                    onSelect = { selectedRepositoryId = it },
                )
            }
        }
        item("sources-title") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                Text(strings.text(StringKey.SOURCES), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (groupKeys.size > 1) {
                    val allCollapsed = groupKeys.all { it in collapsedGroups }
                    TextButton(onClick = { collapsedGroups = if (allCollapsed) emptyList() else groupKeys }) {
                        Text(if (allCollapsed) strings.text(StringKey.EXPAND_ALL) else strings.text(StringKey.COLLAPSE_ALL))
                    }
                }
            }
        }
        if (sections.pinned.isEmpty() && sections.regular.isEmpty()) {
            item("sources-empty") {
                EmptyState(
                    title = strings.text(StringKey.NO_SOURCES),
                    message = strings.text(StringKey.INSTALL_AN_EXTENSION_OR_ENABLE_ANOTHER_LANGUAGE),
                    icon = { Icon(Icons.Outlined.TravelExplore, null, Modifier.size(30.dp)) },
                )
            }
        }
        if (sections.pinned.isNotEmpty()) {
            val collapsed = PINNED_SOURCE_GROUP in collapsedGroups
            item("pinned-header") {
                CollapsibleGroupHeader(strings.text(StringKey.PINNED), sections.pinned.size, collapsed, pinned = true) {
                    toggleGroup(PINNED_SOURCE_GROUP)
                }
            }
            if (!collapsed) {
                items(sections.pinned, key = { "pinned:${it.identityKey}" }) { source ->
                    SourceListRow(
                        source = source,
                        pinned = true,
                        onOpen = onOpen,
                        onToggle = onToggle,
                        onPinnedChange = onSourcePinnedChange,
                        onPinnedKeyChange = onSourcePinnedKeyChange,
                        onSettings = { openSettings(source) },
                    )
                }
            }
        }
        groups.filter { it.items.isNotEmpty() }.forEach { group ->
            val key = group.repository?.id ?: OTHER_SOURCE_GROUP
            val collapsed = key in collapsedGroups
            item("source-group-$key") {
                CollapsibleGroupHeader(
                    group.repository?.name ?: strings.text(StringKey.OTHER_SOURCES),
                    group.items.size,
                    collapsed,
                ) { toggleGroup(key) }
            }
            if (!collapsed) {
                items(group.items, key = { "source:$key:${it.identityKey}" }) { source ->
                    SourceListRow(
                        source = source,
                        pinned = false,
                        onOpen = onOpen,
                        onToggle = onToggle,
                        onPinnedChange = onSourcePinnedChange,
                        onPinnedKeyChange = onSourcePinnedKeyChange,
                        onSettings = { openSettings(source) },
                    )
                }
            }
        }
    }
    sources.firstOrNull { it.identityKey == settingsSourceIdentity }?.let { source ->
        SourceSettingsDialog(
            source = source,
            settings = settings,
            auth = auth,
            webSession = webSession,
            onImportDocument = onImportDocument,
            onDismiss = { settingsSourceIdentity = null },
        )
    }
}

private const val PINNED_SOURCE_GROUP: String = "pinned"
private const val OTHER_SOURCE_GROUP: String = "other"

internal data class BrowseSourceSections(
    val pinned: List<BrowseSource>,
    val regular: List<BrowseSource>,
)

/** Partitions unique sources and applies a platform-independent order for stable list keys/UI. */
internal fun browseSourceSections(
    sources: List<BrowseSource>,
    pinnedSourceIds: Set<Long>,
    pinnedSourceKeys: Set<String> = emptySet(),
): BrowseSourceSections {
    val unique = sources.distinctBy(BrowseSource::identityKey)
    val pinnedOrder = compareBy<BrowseSource>(
        { it.name.trim().lowercase() },
        { it.language.trim().lowercase() },
        BrowseSource::identityKey,
    )
    val regularOrder = compareBy<BrowseSource>(
        { it.language.trim().lowercase() },
        { it.name.trim().lowercase() },
        BrowseSource::identityKey,
    )
    return BrowseSourceSections(
        pinned = unique.filter { source ->
            source.isPinned(pinnedSourceIds, pinnedSourceKeys)
        }.sortedWith(pinnedOrder),
        regular = unique.filterNot { source ->
            source.isPinned(pinnedSourceIds, pinnedSourceKeys)
        }.sortedWith(regularOrder),
    )
}

private fun BrowseSource.isPinned(
    pinnedSourceIds: Set<Long>,
    pinnedSourceKeys: Set<String>,
): Boolean = if (sourceKey == null) {
    id in pinnedSourceIds
} else {
    identityKey in pinnedSourceKeys
}

@Composable
private fun SourceSectionHeader(title: String, count: Int, pinned: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 9.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (pinned) Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

internal data class SourceSettingsSections(
    val credentials: Boolean,
    val preferences: Boolean,
    val browserSessionLogin: Boolean,
)

/**
 * Login UI is capability-driven. A stale credential from an older plugin version must not make
 * username/password controls reappear after that source stops implementing the login contract.
 */
internal fun sourceSettingsSections(source: BrowseSource): SourceSettingsSections =
    SourceSettingsSections(
        credentials = source.supportsLogin,
        preferences = source.preferences.isNotEmpty(),
        browserSessionLogin = source.supportsLogin && source.requiresBrowserSessionLogin,
    )

/** The account entry of a source: filled and tinted once the user is signed in there. */
@Composable
internal fun SourceAccountIcon(signedIn: Boolean) {
    val strings = LocalShinsouStrings.current
    Icon(
        if (signedIn) Icons.Filled.AccountCircle else Icons.Outlined.AccountCircle,
        if (signedIn) strings.text(StringKey.SIGNED_IN) else strings.text(StringKey.LOG_IN),
        tint = if (signedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SourceListRow(
    source: BrowseSource,
    pinned: Boolean,
    onOpen: (BrowseSource) -> Unit,
    onToggle: (BrowseSource, Boolean) -> Unit,
    onPinnedChange: (sourceId: Long, pinned: Boolean) -> Unit,
    onPinnedKeyChange: (sourceKey: String, pinned: Boolean) -> Unit,
    onSettings: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val legacyActions = source.sourceKey == null
    val settingsSections = sourceSettingsSections(source)
    val settingsAvailable = legacyActions || settingsSections.credentials ||
        settingsSections.preferences || source.baseUrl.isNotBlank()
    var menuVisible by remember(source.identityKey) { mutableStateOf(false) }
    Surface(
        onClick = { if (source.enabled) onOpen(source) },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SourceIcon(source)
            Column(Modifier.weight(1f)) {
                Text(source.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        append(source.language.uppercase())
                        append(" · ")
                        append(source.contentType.label(strings))
                        if (source.isNsfw) append(" · NSFW")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (source.canSignIn) {
                IconButton(onClick = onSettings, modifier = Modifier.testTag("source-row-login")) {
                    SourceAccountIcon(source.signedIn)
                }
            }
            Switch(checked = source.enabled, onCheckedChange = { onToggle(source, it) })
            Box {
                IconButton(onClick = { menuVisible = true }) {
                    Icon(Icons.Outlined.MoreVert, strings.text(StringKey.MORE))
                }
                DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (pinned) strings.text(StringKey.UNPIN_X, source.name)
                                else strings.text(StringKey.PIN_X, source.name),
                            )
                        },
                        leadingIcon = { Icon(Icons.Outlined.PushPin, null) },
                        onClick = {
                            menuVisible = false
                            if (source.sourceKey == null) onPinnedChange(source.id, !pinned)
                            else onPinnedKeyChange(source.identityKey, !pinned)
                        },
                    )
                    if (settingsAvailable) {
                        DropdownMenuItem(
                            text = { Text(strings.text(StringKey.SOURCE_SETTINGS)) },
                            leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                            onClick = {
                                menuVisible = false
                                onSettings()
                            },
                        )
                    }
                }
            }
        }
    }
}
