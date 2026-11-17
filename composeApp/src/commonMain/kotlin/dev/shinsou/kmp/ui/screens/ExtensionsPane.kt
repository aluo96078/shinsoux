package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import dev.shinsou.kmp.contract.BrowseExtension
import dev.shinsou.kmp.contract.BrowseRepository
import dev.shinsou.kmp.contract.BrowseRepositorySuggestion
import dev.shinsou.kmp.plugin.PluginContentType
import dev.shinsou.kmp.ui.components.EmptyState
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.dismissKeyboardOnMobileBlankTap
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
                    Text(strings.text("Repositories"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(enabled = operation == null, onClick = { manageRepositories = true }) {
                        Icon(Icons.Outlined.Settings, strings.text("Manage repositories"))
                    }
                }
                if (repositorySuggestions.isNotEmpty()) {
                    Text(
                        strings.text("{0} repositories came from other devices", repositorySuggestions.size.toString()),
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
                            strings.text("Add an extension repository before installing sources."),
                            modifier = Modifier.padding(12.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                } else {
                    RepositorySelector(
                        repositories = repositories,
                        selectedRepositoryId = selectedRepositoryId,
                        enabled = operation == null,
                        onSelect = onSelectRepository,
                    )
                    repositories.filter { it.needsPublisherTrust }.forEach { repository ->
                        Text(
                            strings.text("{0} is now signed by its publisher: confirm it in Manage repositories.", repository.name),
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
                        Text(if (allCollapsed) strings.text("Expand all") else strings.text("Collapse all"))
                    }
                }
            }
        }
        if (groups.all { it.extensions.isEmpty() }) {
            item("empty-extensions") {
                EmptyState(
                    title = strings.text("No extensions"),
                    message = strings.text("Refresh the selected repository or try a different language."),
                    icon = { Icon(Icons.Outlined.Extension, null, Modifier.size(30.dp)) },
                )
            }
        } else {
            groups.forEach { group ->
                val collapsed = grouped && group.key() in collapsedGroups
                if (grouped) {
                    item("extension-group-${group.key()}") {
                        CollapsibleGroupHeader(
                            title = group.repository?.name ?: strings.text("Other installed extensions"),
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
            title = { Text(strings.text("Manage repositories")) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (repositories.isEmpty()) {
                        item("none") { Text(strings.text("Add an extension repository before installing sources.")) }
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
                                    ) { Text(strings.text("Confirm publisher"), color = MaterialTheme.colorScheme.error) }
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
                                strings.text("Repositories on other devices"),
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
                                ) { Text(strings.text("Add")) }
                                TextButton(
                                    enabled = operation == null,
                                    onClick = { onDismissRepositorySuggestion(suggestion.url) },
                                ) { Text(strings.text("Ignore")) }
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
                    Text(strings.text("Add"))
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
                    Text(strings.text("Package security"))
                }
            },
        )
    }

    repositoryPendingRemoval?.let { repository ->
        AlertDialog(
            onDismissRequest = { repositoryPendingRemoval = null },
            title = { Text(strings.text("Delete repository")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(strings.text("Delete {0} from this device?", repository.name))
                    if (canRemoveFromAccount) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { removeFromAccount = !removeFromAccount },
                        ) {
                            Checkbox(checked = removeFromAccount, onCheckedChange = { removeFromAccount = it })
                            Text(strings.text("Also remove from the account (other devices stop receiving the suggestion)"))
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
            title = { Text(strings.text("Add repository")) },
            text = {
                OutlinedTextField(
                    value = repositoryUrl,
                    onValueChange = { repositoryUrl = it },
                    label = { Text(strings.text("Repository URL")) },
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
                ) { Text(strings.text("Add")) }
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
            Column(Modifier.weight(1f)) {
                Text(extension.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val details = buildList {
                    add(extension.language.uppercase())
                    add("v${extension.version}")
                    add(extension.contentType.label(strings))
                    if (extension.inDevelopment) add(strings.text("In development"))
                    if (extension.updateAvailable) add(strings.text("Update available"))
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
                        strings.text("Blocked on this device; it will not run"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onUnblock, contentPadding = PaddingValues(0.dp)) {
                        Text(strings.text("Unblock"), style = MaterialTheme.typography.labelSmall)
                    }
                } else if (extension.installed) {
                    Text(
                        if (extension.trusted) strings.text("Execution allowed") else strings.text("Execution blocked"),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (extension.trusted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                }
                if (extension.preinstalledFromAccount) {
                    Text(
                        strings.text("Pre-installed from another device, waiting for permission"),
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
            when {
                !extension.installed ->
                    Button(enabled = operation == null, onClick = { onInstall(extension) }) { Text(strings.install) }
                extension.updateAvailable ->
                    Button(enabled = operation == null, onClick = { onInstall(extension) }) { Text(strings.text("Update")) }
            }
            if (extension.installed) {
                Box {
                    IconButton(enabled = operation == null, onClick = { menuVisible = true }) {
                        Icon(Icons.Outlined.MoreVert, strings.text("More"))
                    }
                    DropdownMenu(expanded = menuVisible, onDismissRequest = { menuVisible = false }) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (extension.trusted) strings.text("Block execution")
                                    else strings.text("Allow execution"),
                                )
                            },
                            leadingIcon = { Icon(Icons.Outlined.Security, null) },
                            onClick = {
                                menuVisible = false
                                onTrust(extension, !extension.trusted)
                            },
                        )
                        if (extension.trusted) {
                            DropdownMenuItem(
                                text = { Text(strings.text("Permissions")) },
                                onClick = {
                                    menuVisible = false
                                    onPermissions(extension)
                                },
                            )
                        }
                        // ADR-0002 D5: the user's own block list; undone from Package security.
                        DropdownMenuItem(
                            text = { Text(strings.text(if (extension.blockedByUser) "Unblock" else "Block")) },
                            onClick = {
                                menuVisible = false
                                if (extension.blockedByUser) onUnblock() else onBlock(extension)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(strings.uninstall, color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuVisible = false
                                onUninstall(extension)
                            },
                        )
                    }
                }
            }
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
            selected?.name ?: strings.text("All repositories ({0})", repositories.size.toString()),
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
        title = { Text(strings.text("Repositories")) },
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
                                title = strings.text("All repositories ({0})", repositories.size.toString()),
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
                if (collapsed) strings.text("Expand") else strings.text("Collapse"),
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
    PluginContentType.MANGA -> strings.text("Manga")
    PluginContentType.NOVEL -> strings.text("Novel")
    PluginContentType.BOTH -> strings.text("Unknown type")
}

internal fun PluginContentType.label(strings: ShinsouStrings): String = when (this) {
    PluginContentType.MANGA -> strings.text("Manga")
    PluginContentType.NOVEL -> strings.text("Novel")
    PluginContentType.BOTH -> strings.text("Manga + novel")
}
