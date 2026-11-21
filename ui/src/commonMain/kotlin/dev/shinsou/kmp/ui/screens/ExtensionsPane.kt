package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.plugin.BrowseExtension
import dev.shinsou.kmp.plugin.BrowseRepository
import dev.shinsou.kmp.plugin.BrowseRepositorySuggestion
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text

// R4-4 (docs/architecture/0006): split out of BrowseScreen.kt without changing it.

@Composable
internal fun ExtensionsPane(
    operation: ExtensionOperation?,
    repositories: List<BrowseRepository>,
    selectedRepositoryId: String?,
    extensions: List<BrowseExtension>,
    onAddRepository: (String) -> Unit,
    repositorySuggestions: List<BrowseRepositorySuggestion>,
    canRemoveFromAccount: Boolean,
    onRemoveRepository: (id: String, fromAccount: Boolean) -> Unit,
    onDismissRepositorySuggestion: (String) -> Unit,
    onSelectRepository: (String?) -> Unit,
    onInstall: (BrowseExtension) -> Unit,
    onUninstall: (BrowseExtension) -> Unit,
    onTrust: (BrowseExtension, Boolean) -> Unit,
    onOpenPackageSecurity: () -> Unit,
    onBlock: (BrowseExtension) -> Unit,
    onUnblock: (BrowseExtension) -> Unit,
    onPermissions: (BrowseExtension) -> Unit = {},
    /** A repository action is running or waiting for other extension work to finish. */
    repositoryBusy: Boolean = false,
) {
    val strings = LocalShinsouStrings.current
    var repositoryDialog by remember { mutableStateOf(false) }
    var repositoryUrl by remember { mutableStateOf("") }
    var manageRepositories by remember { mutableStateOf(false) }
    var repositoryPendingRemoval by remember { mutableStateOf<BrowseRepository?>(null) }
    var removeFromAccount by remember { mutableStateOf(false) }
    // Headings collapsed in the all-repositories view, by group key; kept while the screen lives.
    var collapsedGroups by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LazyColumn(
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 104.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item("repositories") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(strings.text(StringKey.REPOSITORIES), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (repositoryBusy) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            strings.text(StringKey.WORKING_ON_REPOSITORIES),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(enabled = operation == null && !repositoryBusy, onClick = { manageRepositories = true }) {
                        Icon(Icons.Outlined.Settings, strings.text(StringKey.MANAGE_REPOSITORIES))
                    }
                }
                if (repositorySuggestions.isNotEmpty()) {
                    Text(
                        strings.text(StringKey.X_REPOSITORIES_CAME_FROM_OTHER_DEVICES, repositorySuggestions.size.toString()),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(enabled = operation == null) { manageRepositories = true },
                    )
                }
                if (repositories.isEmpty()) {
                    Surface(
                        onClick = { repositoryDialog = true },
                        enabled = operation == null,
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            strings.text(StringKey.ADD_AN_EXTENSION_REPOSITORY_BEFORE_INSTALLING_SOURCES),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                } else {
                    RepositorySelector(
                        repositories = repositories,
                        selectedRepositoryId = selectedRepositoryId,
                        enabled = operation == null && !repositoryBusy,
                        onSelect = onSelectRepository,
                    )
                    repositories.filter { it.needsPublisherTrust }.forEach { repository ->
                        Text(
                            strings.text(StringKey.X_IS_NOW_SIGNED_BY_ITS_PUBLISHER, repository.name),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
        val groups = extensionRepositoryGroups(extensions, repositories, selectedRepositoryId)
        val grouped = selectedRepositoryId == null || repositories.none { it.id == selectedRepositoryId }
        fun ExtensionRepositoryGroup.key(): String = repository?.id ?: "unlisted"
        item("extensions-title") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
            ) {
                Text(strings.extensions, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (grouped && groups.size > 1) {
                    val allCollapsed = groups.all { it.key() in collapsedGroups }
                    TextButton(onClick = {
                        collapsedGroups = if (allCollapsed) emptyList() else groups.map { it.key() }
                    }) {
                        Text(if (allCollapsed) strings.text(StringKey.EXPAND_ALL) else strings.text(StringKey.COLLAPSE_ALL))
                    }
                }
            }
        }
        if (groups.all { it.extensions.isEmpty() }) {
            item("empty-extensions") {
                EmptyState(
                    title = strings.text(StringKey.NO_EXTENSIONS),
                    message = strings.text(StringKey.REFRESH_THE_SELECTED_REPOSITORY_OR_TRY_A),
                    icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
                )
            }
        } else {
            groups.forEach { group ->
                val collapsed = grouped && group.key() in collapsedGroups
                if (grouped) {
                    item("extension-group-${group.key()}") {
                        CollapsibleGroupHeader(
                            title = group.repository?.name ?: strings.text(StringKey.OTHER_INSTALLED_EXTENSIONS),
                            count = group.extensions.size,
                            collapsed = collapsed,
                        ) {
                            collapsedGroups = if (collapsed) collapsedGroups - group.key() else collapsedGroups + group.key()
                        }
                    }
                }
                if (!collapsed) {
                    items(group.extensions, key = { "extension-${group.key()}-${it.id}" }) { extension ->
                        ExtensionRow(
                            extension,
                            operation,
                            onInstall,
                            onUninstall,
                            onTrust,
                            onBlock,
                            onPermissions,
                            onUnblock = { onUnblock(extension) },
                        )
                    }
                }
            }
        }
    }

    if (manageRepositories) {
        AlertDialog(
            onDismissRequest = { manageRepositories = false },
            title = { Text(strings.text(StringKey.MANAGE_REPOSITORIES)) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (repositories.isEmpty()) {
                        item("none") { Text(strings.text(StringKey.ADD_AN_EXTENSION_REPOSITORY_BEFORE_INSTALLING_SOURCES)) }
                    }
                    items(repositories, key = { "manage-${it.id}" }) { repository ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.weight(1f)) {
                                Text(repository.name, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    repository.url,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (repository.needsPublisherTrust) {
                                    TextButton(
                                        enabled = operation == null,
                                        onClick = {
                                            manageRepositories = false
                                            onAddRepository(repository.url)
                                        },
                                    ) { Text(strings.text(StringKey.CONFIRM_PUBLISHER), color = MaterialTheme.colorScheme.error) }
                                }
                            }
                            // No repository is official (ADR-0002): none gets a badge.
                            IconButton(
                                enabled = operation == null,
                                onClick = {
                                    removeFromAccount = false
                                    repositoryPendingRemoval = repository
                                },
                            ) {
                                Icon(Icons.Outlined.Delete, strings.delete)
                            }
                        }
                    }
                    if (repositorySuggestions.isNotEmpty()) {
                        item("suggestions-title") {
                            Text(
                                strings.text(StringKey.REPOSITORIES_ON_OTHER_DEVICES),
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                        items(repositorySuggestions, key = { "suggestion-${it.url}" }) { suggestion ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Column(Modifier.weight(1f)) {
                                    Text(suggestion.name, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        suggestion.url,
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                // Adding goes through the usual flow, including the publisher trust prompt.
                                TextButton(
                                    enabled = operation == null,
                                    onClick = {
                                        manageRepositories = false
                                        onAddRepository(suggestion.url)
                                    },
                                ) { Text(strings.text(StringKey.ADD)) }
                                TextButton(
                                    enabled = operation == null,
                                    onClick = { onDismissRepositorySuggestion(suggestion.url) },
                                ) { Text(strings.text(StringKey.IGNORE)) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = operation == null,
                    onClick = {
                        manageRepositories = false
                        repositoryDialog = true
                    },
                ) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.text(StringKey.ADD))
                }
            },
            dismissButton = {
                TextButton(
                    enabled = operation == null,
                    onClick = {
                        manageRepositories = false
                        onOpenPackageSecurity()
                    },
                ) {
                    Icon(Icons.Outlined.Security, null, Modifier.size(17.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(strings.text(StringKey.PACKAGE_SECURITY))
                }
            },
        )
    }

    repositoryPendingRemoval?.let { repository ->
        AlertDialog(
            onDismissRequest = { repositoryPendingRemoval = null },
            title = { Text(strings.text(StringKey.DELETE_REPOSITORY)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.text(StringKey.DELETE_X_FROM_THIS_DEVICE, repository.name))
                    if (canRemoveFromAccount) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { removeFromAccount = !removeFromAccount },
                        ) {
                            Checkbox(checked = removeFromAccount, onCheckedChange = { removeFromAccount = it })
                            Text(strings.text(StringKey.ALSO_REMOVE_FROM_THE_ACCOUNT_OTHER_DEVICES))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemoveRepository(repository.id, canRemoveFromAccount && removeFromAccount)
                        repositoryPendingRemoval = null
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { repositoryPendingRemoval = null }) { Text(strings.cancel) } },
        )
    }

    if (repositoryDialog) {
        AlertDialog(
            modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
            onDismissRequest = { repositoryDialog = false },
            title = { Text(strings.text(StringKey.ADD_REPOSITORY)) },
            text = {
                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it },
                    label = { Text(strings.text(StringKey.REPOSITORY_URL)) },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    enabled = operation == null && repositoryUrl.startsWith("http"),
                    onClick = {
                        onAddRepository(repositoryUrl.trim())
                        repositoryUrl = ""
                        repositoryDialog = false
                    },
                ) { Text(strings.text(StringKey.ADD)) }
            },
            dismissButton = { TextButton(onClick = { repositoryDialog = false }) { Text(strings.cancel) } },
        )
    }
}

@Composable
private fun ExtensionRow(
    extension: BrowseExtension,
    operation: ExtensionOperation?,
    onInstall: (BrowseExtension) -> Unit,
    onUninstall: (BrowseExtension) -> Unit,
    onTrust: (BrowseExtension, Boolean) -> Unit,
    onBlock: (BrowseExtension) -> Unit,
    onPermissions: (BrowseExtension) -> Unit,
    onUnblock: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var menuVisible by remember(extension.id) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(shape = RoundedCornerShape(9.dp), modifier = Modifier.size(36.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Extension, null, Modifier.size(20.dp))
                }
            }
            ExtensionRowDetails(extension, operation, onUnblock)
            when {
                !extension.installed ->
                    Button(enabled = operation == null, onClick = { onInstall(extension) }) { Text(strings.install) }
                extension.updateAvailable ->
                    Button(enabled = operation == null, onClick = { onInstall(extension) }) { Text(strings.text(StringKey.UPDATE)) }
            }
            if (extension.installed) {
                ExtensionRowMenu(
                    extension = extension,
                    enabled = operation == null,
                    menuVisible = menuVisible,
                    onMenuVisibleChange = { menuVisible = it },
                    onTrust = onTrust,
                    onBlock = onBlock,
                    onPermissions = onPermissions,
                    onUnblock = onUnblock,
                    onUninstall = onUninstall,
                )
            }
        }
    }
}

/** The extension's name, version details, trust and block state, and any running operation. */
@Composable
private fun RowScope.ExtensionRowDetails(
    extension: BrowseExtension,
    operation: ExtensionOperation?,
    onUnblock: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Column(Modifier.weight(1f)) {
        Text(extension.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val details = buildList {
            add(extension.language.uppercase())
            add("v${extension.version}")
            add(extension.contentType.label(strings))
            if (extension.inDevelopment) add(strings.text(StringKey.IN_DEVELOPMENT))
            if (extension.updateAvailable) add(strings.text(StringKey.UPDATE_AVAILABLE))
        }.joinToString(" · ")
        Text(
            details,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The user's block list wins over the permission grant: a blocked package never
        // runs, installed or not, and the only way back is unblocking it here.
        if (extension.blockedByUser) {
            Text(
                strings.text(StringKey.BLOCKED_ON_THIS_DEVICE_IT_WILL_NOT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onUnblock, contentPadding = PaddingValues(0.dp)) {
                Text(strings.text(StringKey.UNBLOCK), style = MaterialTheme.typography.labelSmall)
            }
        } else if (extension.installed) {
            Text(
                if (extension.trusted) strings.text(StringKey.EXECUTION_ALLOWED) else strings.text(StringKey.EXECUTION_BLOCKED),
                style = MaterialTheme.typography.labelSmall,
                color = if (extension.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (extension.preinstalledFromAccount) {
            Text(
                strings.text(StringKey.PRE_INSTALLED_FROM_ANOTHER_DEVICE_WAITING_FOR),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (operation?.extensionId == extension.id) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(strings.text(operation.labelKey), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

/** The overflow menu of an installed extension: trust, permissions, block and uninstall. */
@Composable
private fun ExtensionRowMenu(
    extension: BrowseExtension,
    enabled: Boolean,
    menuVisible: Boolean,
    onMenuVisibleChange: (Boolean) -> Unit,
    onTrust: (BrowseExtension, Boolean) -> Unit,
    onBlock: (BrowseExtension) -> Unit,
    onPermissions: (BrowseExtension) -> Unit,
    onUnblock: () -> Unit,
    onUninstall: (BrowseExtension) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Box {
        IconButton(enabled = enabled, onClick = { onMenuVisibleChange(true) }) {
            Icon(Icons.Outlined.MoreVert, strings.text(StringKey.MORE))
        }
        DropdownMenu(expanded = menuVisible, onDismissRequest = { onMenuVisibleChange(false) }) {
            DropdownMenuItem(
                text = {
                    Text(
                        if (extension.trusted) strings.text(StringKey.BLOCK_EXECUTION)
                        else strings.text(StringKey.ALLOW_EXECUTION),
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Security, null) },
                onClick = {
                    onMenuVisibleChange(false)
                    onTrust(extension, !extension.trusted)
                },
            )
            if (extension.trusted) {
                DropdownMenuItem(
                    text = { Text(strings.text(StringKey.PERMISSIONS)) },
                    onClick = {
                        onMenuVisibleChange(false)
                        onPermissions(extension)
                    },
                )
            }
            // ADR-0002 D5: the user's own block list; undone from Package security.
            DropdownMenuItem(
                text = { Text(strings.text(if (extension.blockedByUser) StringKey.UNBLOCK else StringKey.BLOCK)) },
                onClick = {
                    onMenuVisibleChange(false)
                    if (extension.blockedByUser) onUnblock() else onBlock(extension)
                },
            )
            DropdownMenuItem(
                text = { Text(strings.uninstall, color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                onClick = {
                    onMenuVisibleChange(false)
                    onUninstall(extension)
                },
            )
        }
    }
}

private const val REPOSITORY_PICKER_SEARCH_THRESHOLD: Int = 6

/** One line however many repositories there are; the picker it opens is searchable. */
@Composable
internal fun RepositorySelector(
    repositories: List<BrowseRepository>,
    selectedRepositoryId: String?,
    enabled: Boolean,
    onSelect: (String?) -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var picker by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("") }
    val selected = repositories.firstOrNull { it.id == selectedRepositoryId }
    OutlinedButton(enabled = enabled, onClick = { picker = true }, modifier = Modifier.fillMaxWidth()) {
        if (repositories.any { it.needsPublisherTrust }) {
            Icon(Icons.Outlined.Security, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            selected?.name ?: strings.text(StringKey.ALL_REPOSITORIES_X, repositories.size.toString()),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(Icons.Outlined.ArrowDropDown, null)
    }
    if (!picker) return
    val close = {
        picker = false
        filter = ""
    }
    val matching = repositoryPickerMatches(repositories, filter)
    AlertDialog(
        modifier = Modifier.dismissKeyboardOnMobileBlankTap(),
        onDismissRequest = close,
        title = { Text(strings.text(StringKey.REPOSITORIES)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (repositories.size > REPOSITORY_PICKER_SEARCH_THRESHOLD) {
                    OutlinedTextField(
                        value = filter,
                        onValueChange = { filter = it },
                        label = { Text(strings.search) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    if (filter.isBlank()) {
                        item("pick-all") {
                            RepositoryPickerRow(
                                title = strings.text(StringKey.ALL_REPOSITORIES_X, repositories.size.toString()),
                                subtitle = null,
                                selected = selected == null,
                                warning = false,
                            ) {
                                close()
                                onSelect(null)
                            }
                        }
                    }
                    items(matching, key = { "pick-${it.id}" }) { repository ->
                        RepositoryPickerRow(
                            title = repository.name,
                            subtitle = repository.url,
                            selected = repository.id == selectedRepositoryId,
                            warning = repository.needsPublisherTrust,
                        ) {
                            close()
                            onSelect(repository.id)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text(strings.cancel) } },
    )
}

@Composable
internal fun CollapsibleGroupHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    pinned: Boolean = false,
    onToggle: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    Surface(
        onClick = onToggle,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (pinned) {
                Icon(Icons.Outlined.PushPin, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                if (collapsed) strings.text(StringKey.EXPAND) else strings.text(StringKey.COLLAPSE),
            )
        }
    }
}

@Composable
private fun RepositoryPickerRow(
    title: String,
    subtitle: String?,
    selected: Boolean,
    warning: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitle?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (warning) Icon(Icons.Outlined.Security, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
        }
    }
}

internal const val MIGRATION_SEARCH_PARALLELISM: Int = 4
internal const val MIGRATION_RESULT_LIMIT: Int = 30

internal fun PluginContentType.migrationLabel(strings: ShinsouStrings): String = when (this) {
    PluginContentType.MANGA -> strings.text(StringKey.MANGA)
    PluginContentType.NOVEL -> strings.text(StringKey.NOVEL)
    PluginContentType.BOTH -> strings.text(StringKey.UNKNOWN_TYPE)
}

internal fun PluginContentType.label(strings: ShinsouStrings): String = when (this) {
    PluginContentType.MANGA -> strings.text(StringKey.MANGA)
    PluginContentType.NOVEL -> strings.text(StringKey.NOVEL)
    PluginContentType.BOTH -> strings.text(StringKey.MANGA_NOVEL)
}
