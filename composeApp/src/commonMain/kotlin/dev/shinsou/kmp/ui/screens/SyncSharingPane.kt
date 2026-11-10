package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.sync.v3.SyncRelation
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.sync.v3.WireShare
import dev.shinsou.kmp.sync.v3.WireUserSummary
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class SharingTab { Received, Sent, People }

private const val SHARE_MESSAGE_MAX = 500
private const val USERNAME_QUERY_MAX = 33
private const val SEARCH_DEBOUNCE_MILLIS = 350L

/** Settings → Sync → Sharing: received and sent shares, and the people this account follows or blocks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SharingSection(sync: SyncV3Service, copyText: (label: String, text: String) -> Boolean) {
    val strings = LocalShinsouStrings.current
    var tab by remember { mutableStateOf(SharingTab.Received) }
    var pending by remember { mutableIntStateOf(0) }
    var pendingTick by remember { mutableIntStateOf(0) }

    LaunchedEffect(pendingTick) { sync.pendingShares().onSuccess { pending = it } }

    SyncCard {
        Text(strings.text("Sharing"), style = MaterialTheme.typography.titleMedium)
        Text(
            strings.text("Recommend a title from your library to another account on this server. Accepting a share adds the title to your library."),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = tab == SharingTab.Received,
                onClick = { tab = SharingTab.Received },
                label = { Text(if (pending > 0) strings.text("Received ({0})", pending) else strings.text("Received")) },
            )
            FilterChip(selected = tab == SharingTab.Sent, onClick = { tab = SharingTab.Sent }, label = { Text(strings.text("Sent")) })
            FilterChip(selected = tab == SharingTab.People, onClick = { tab = SharingTab.People }, label = { Text(strings.text("People")) })
        }
        when (tab) {
            SharingTab.Received -> ShareBox(sync, sent = false, copyText = copyText, onChanged = { pendingTick++ })
            SharingTab.Sent -> ShareBox(sync, sent = true, copyText = copyText, onChanged = { pendingTick++ })
            SharingTab.People -> PeoplePanel(sync, onChanged = { pendingTick++ })
        }
    }
}

// ---- shares -------------------------------------------------------------------------------------

@Composable
private fun ShareBox(sync: SyncV3Service, sent: Boolean, copyText: (String, String) -> Boolean, onChanged: () -> Unit) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var shares by remember(sent) { mutableStateOf<List<WireShare>?>(null) }
    var total by remember(sent) { mutableIntStateOf(0) }
    var error by remember(sent) { mutableStateOf<String?>(null) }
    var notice by remember(sent) { mutableStateOf<String?>(null) }
    var busy by remember(sent) { mutableStateOf(false) }
    var reloadTick by remember(sent) { mutableIntStateOf(0) }
    var deleting by remember(sent) { mutableStateOf<WireShare?>(null) }

    LaunchedEffect(sent, reloadTick) {
        // Reload as many rows as were on screen, so an answer does not collapse the list.
        val want = (shares?.size ?: 0).coerceIn(SyncSharePageSize, SyncShareReloadMax)
        sync.listShares(sent, offset = 0, limit = want).fold(
            onSuccess = { shares = it.shares; total = it.total; error = null },
            onFailure = { error = it.userMessage(strings) },
        )
    }

    fun run(done: (WireShare?) -> String, action: suspend () -> Result<WireShare?>) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            action().fold(
                onSuccess = { notice = done(it); reloadTick++; onChanged() },
                onFailure = { error = it.userMessage(strings) },
            )
            busy = false
        }
    }

    ErrorText(error)
    notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
    val rows = shares
    when {
        rows == null && error == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        rows != null && rows.isEmpty() -> Text(
            if (sent) {
                strings.text("You have not shared anything yet. Open a title in your library and choose Share with a user.")
            } else {
                strings.text("Nothing has been shared with you yet.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        rows != null -> {
            rows.forEachIndexed { index, share ->
                if (index > 0) HorizontalDivider()
                ShareRow(
                    share = share,
                    sent = sent,
                    busy = busy,
                    copyText = copyText,
                    onAccept = {
                        run({ accepted -> acceptedNotice(strings, accepted ?: share) }) { sync.acceptShare(share.id) }
                    },
                    onDismiss = { run({ strings.text("Share dismissed.") }) { sync.dismissShare(share.id) } },
                    onDelete = { deleting = share },
                )
            }
            if (rows.size < total) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            sync.listShares(sent, offset = rows.size).fold(
                                onSuccess = { page ->
                                    val known = rows.mapTo(HashSet()) { it.id }
                                    shares = rows + page.shares.filterNot { it.id in known }
                                    total = page.total
                                },
                                onFailure = { error = it.userMessage(strings) },
                            )
                            busy = false
                        }
                    },
                ) { Text(strings.text("Load more")) }
            }
        }
    }

    deleting?.let { share ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(if (sent) strings.text("Withdraw this share?") else strings.text("Delete this share?")) },
            text = {
                Text(
                    if (sent) {
                        strings.text("The recipient will no longer see it. A title they already added stays in their library.")
                    } else {
                        strings.text("It is removed from your inbox. Your library is not changed.")
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = null
                        run({ if (sent) strings.text("Share withdrawn.") else strings.text("Share deleted.") }) {
                            sync.deleteShare(share.id).map { null }
                        }
                    },
                ) { Text(if (sent) strings.text("Withdraw") else strings.delete) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(strings.cancel) } },
        )
    }
}

private fun acceptedNotice(strings: ShinsouStrings, share: WireShare): String {
    val source = share.work.sourceName?.takeIf { it.isNotBlank() }
    return if (source != null) {
        strings.text("Added “{0}” to your library. It appears after the next sync if the source “{1}” is installed on this device.", share.work.title, source)
    } else {
        strings.text("Added “{0}” to your library. It appears after the next sync if its source is installed on this device.", share.work.title)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ShareRow(
    share: WireShare,
    sent: Boolean,
    busy: Boolean,
    copyText: (String, String) -> Boolean,
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    var copied by remember(share.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(share.work.title, style = MaterialTheme.typography.bodyLarge)
        share.work.author?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        val who = if (sent) strings.text("To @{0}", share.recipient.username) else strings.text("From @{0}", share.sender.username)
        val status = when (share.status) {
            "accepted" -> strings.text("Accepted")
            "dismissed" -> strings.text("Dismissed")
            else -> strings.text("Waiting for an answer")
        }
        Text(
            listOfNotNull(who, formatTime(share.createdAt), status, strings.text("In your library").takeIf { share.inLibrary })
                .joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (share.message.isNotBlank()) Text("“" + share.message + "”", style = MaterialTheme.typography.bodyMedium)
        Text(
            strings.text("Source: {0}", share.work.sourceName?.takeIf { it.isNotBlank() } ?: strings.text("Unknown")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The address comes from another account: it is shown and can be copied, never added for the user.
        val repository = share.work.repositoryUrl?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
        if (!sent && repository != null) {
            Text(
                strings.text("Repository named by the sender (add it only if you trust it): {0}", repository),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!sent && share.status == "pending") {
                Button(enabled = !busy, onClick = onAccept) { Text(strings.text("Add to library")) }
                OutlinedButton(enabled = !busy, onClick = onDismiss) { Text(strings.text("Dismiss")) }
            } else if (!sent && share.status == "dismissed") {
                OutlinedButton(enabled = !busy, onClick = onAccept) { Text(strings.text("Add to library")) }
            }
            if (!sent && repository != null) {
                TextButton(onClick = { copied = copyText(strings.text("Repository address"), repository) }) {
                    Text(if (copied) strings.text("Copied") else strings.text("Copy repository address"))
                }
            }
            TextButton(enabled = !busy, onClick = onDelete) {
                Text(
                    if (sent) strings.text("Withdraw") else strings.delete,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---- people -------------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PeoplePanel(sync: SyncV3Service, onChanged: () -> Unit) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var receiveFrom by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<WireUserSummary>?>(null) }
    var searched by remember { mutableStateOf("") }
    var relation by remember { mutableStateOf(SyncRelation.Following) }
    var list by remember { mutableStateOf<List<WireUserSummary>?>(null) }
    var listTotal by remember { mutableIntStateOf(0) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var blocking by remember { mutableStateOf<WireUserSummary?>(null) }

    LaunchedEffect(Unit) {
        sync.receiveSharesFrom().fold(onSuccess = { receiveFrom = it }, onFailure = { error = it.userMessage(strings) })
    }
    LaunchedEffect(relation, reloadTick) {
        val want = (list?.size ?: 0).coerceIn(SyncSharePageSize, SyncShareReloadMax)
        sync.listRelation(relation, offset = 0, limit = want).fold(
            onSuccess = { list = it.users; listTotal = it.total },
            onFailure = { error = it.userMessage(strings) },
        )
    }

    suspend fun search(text: String) {
        val q = text.trim().removePrefix("@")
        if (q.isEmpty()) {
            results = null
            searched = ""
            return
        }
        sync.searchUsers(q).fold(
            onSuccess = { results = it; searched = q },
            onFailure = { error = it.userMessage(strings) },
        )
    }

    fun act(done: String, refreshPending: Boolean = false, action: suspend () -> Result<Unit>) {
        if (busy) return
        busy = true
        error = null
        notice = null
        scope.launch {
            action().fold(
                onSuccess = {
                    notice = done
                    reloadTick++
                    if (searched.isNotEmpty()) search(searched)
                    if (refreshPending) onChanged()
                },
                onFailure = { error = it.userMessage(strings) },
            )
            busy = false
        }
    }

    val userActions = UserActions(
        follow = { u -> act(strings.text("You now follow @{0}.", u.username)) { sync.setFollowing(u.id, true) } },
        unfollow = { u -> act(strings.text("You no longer follow @{0}.", u.username)) { sync.setFollowing(u.id, false) } },
        block = { u -> blocking = u },
        unblock = { u -> act(strings.text("@{0} is no longer blocked.", u.username)) { sync.setBlocked(u.id, false) } },
    )

    Text(strings.text("Who can share with you"), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            "everyone" to strings.text("Everyone"),
            "following" to strings.text("People I follow"),
            "nobody" to strings.text("Nobody"),
        ).forEach { (value, label) ->
            FilterChip(
                selected = receiveFrom == value,
                enabled = receiveFrom != null && !busy,
                onClick = {
                    if (receiveFrom != value) {
                        busy = true
                        error = null
                        notice = null
                        scope.launch {
                            sync.setReceiveSharesFrom(value).fold(
                                onSuccess = { receiveFrom = it; notice = strings.text("Saved.") },
                                onFailure = { error = it.userMessage(strings) },
                            )
                            busy = false
                        }
                    }
                },
                label = { Text(label) },
            )
        }
    }
    ErrorText(error)
    notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }

    Text(strings.text("Find people"), style = MaterialTheme.typography.titleSmall)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(USERNAME_QUERY_MAX) },
            label = { Text(strings.text("Username starts with")) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(
            enabled = !busy && query.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    search(query)
                    busy = false
                }
            },
        ) { Text(strings.search) }
    }
    results?.let { found ->
        if (found.isEmpty()) {
            Text(
                strings.text("No account starts with “{0}”.", searched),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        found.forEach { user -> UserRow(user, busy, userActions) }
    }

    HorizontalDivider()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            SyncRelation.Following to strings.text("Following"),
            SyncRelation.Followers to strings.text("Followers"),
            SyncRelation.Blocks to strings.text("Blocked"),
        ).forEach { (value, label) ->
            FilterChip(
                selected = relation == value,
                onClick = {
                    if (relation != value) {
                        list = null
                        relation = value
                    }
                },
                label = { Text(label) },
            )
        }
    }
    val rows = list
    when {
        rows == null -> if (error == null) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        rows.isEmpty() -> Text(
            when (relation) {
                SyncRelation.Following -> strings.text("You are not following anyone.")
                SyncRelation.Followers -> strings.text("Nobody follows you yet.")
                SyncRelation.Blocks -> strings.text("You have not blocked anyone.")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            rows.forEach { user ->
                // The block list only says who is blocked; the other lists come with live flags.
                UserRow(if (relation == SyncRelation.Blocks) user.copy(blocked = true) else user, busy, userActions)
            }
            if (rows.size < listTotal) {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        val requested = relation
                        scope.launch {
                            sync.listRelation(requested, offset = rows.size).fold(
                                onSuccess = { page ->
                                    // The tab may have changed while this page was loading.
                                    if (relation != requested || list !== rows) return@fold
                                    val known = rows.mapTo(HashSet()) { it.id }
                                    list = rows + page.users.filterNot { it.id in known }
                                    listTotal = page.total
                                },
                                onFailure = { error = it.userMessage(strings) },
                            )
                            busy = false
                        }
                    },
                ) { Text(strings.text("Load more")) }
            }
        }
    }

    blocking?.let { user ->
        AlertDialog(
            onDismissRequest = { blocking = null },
            title = { Text(strings.text("Block @{0}?", user.username)) },
            text = {
                Text(strings.text("They can no longer share with you or follow you, you stop following each other, and their unanswered shares are dismissed. They are not told."))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        blocking = null
                        act(strings.text("@{0} is blocked.", user.username), refreshPending = true) { sync.setBlocked(user.id, true) }
                    },
                ) { Text(strings.text("Block")) }
            },
            dismissButton = { TextButton(onClick = { blocking = null }) { Text(strings.cancel) } },
        )
    }
}

private class UserActions(
    val follow: (WireUserSummary) -> Unit,
    val unfollow: (WireUserSummary) -> Unit,
    val block: (WireUserSummary) -> Unit,
    val unblock: (WireUserSummary) -> Unit,
)

private fun userBadges(strings: ShinsouStrings, user: WireUserSummary): String = listOfNotNull(
    strings.text("Blocked").takeIf { user.blocked },
    strings.text("You follow").takeIf { user.following },
    strings.text("Follows you").takeIf { user.followsYou },
    strings.text("Not accepting your shares").takeIf { !user.blocked && !user.canShare },
).joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserRow(user: WireUserSummary, busy: Boolean, actions: UserActions) {
    val strings = LocalShinsouStrings.current
    Column(Modifier.fillMaxWidth()) {
        Text("@" + user.username, style = MaterialTheme.typography.bodyLarge)
        val badges = userBadges(strings, user)
        if (badges.isNotEmpty()) {
            Text(badges, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (user.blocked) {
                TextButton(enabled = !busy, onClick = { actions.unblock(user) }) { Text(strings.text("Unblock")) }
            } else {
                if (user.following) {
                    TextButton(enabled = !busy, onClick = { actions.unfollow(user) }) { Text(strings.text("Unfollow")) }
                } else {
                    TextButton(enabled = !busy, onClick = { actions.follow(user) }) { Text(strings.text("Follow")) }
                }
                TextButton(enabled = !busy, onClick = { actions.block(user) }) {
                    Text(strings.text("Block"), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

// ---- share a title ------------------------------------------------------------------------------

/** Picks a recipient for [workHash] among the accounts the user follows, or by username search. */
@Composable
internal fun ShareWithUserDialog(sync: SyncV3Service, workHash: String, title: String, onDismiss: () -> Unit) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var candidates by remember { mutableStateOf<List<WireUserSummary>?>(null) }
    var suggested by remember { mutableStateOf(true) }
    var picked by remember { mutableStateOf<WireUserSummary?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var sentTo by remember { mutableStateOf<String?>(null) }

    // Searching is rate limited per account, so wait for a pause in typing.
    LaunchedEffect(query) {
        val q = query.trim().removePrefix("@")
        if (q.isNotEmpty()) delay(SEARCH_DEBOUNCE_MILLIS)
        val found = if (q.isEmpty()) {
            sync.listRelation(SyncRelation.Following, offset = 0, limit = SyncShareReloadMax).map { it.users }
        } else {
            sync.searchUsers(q)
        }
        found.fold(
            onSuccess = { candidates = it; suggested = q.isEmpty(); error = null },
            onFailure = { candidates = emptyList(); error = it.userMessage(strings) },
        )
    }

    sentTo?.let { username ->
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(strings.text("Share with a user")) },
            text = { Text(strings.text("Shared “{0}” with @{1}.", title, username)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(strings.close) } },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(strings.text("Share with a user")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.text("Recommend “{0}” to another account on your sync server.", title), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(USERNAME_QUERY_MAX) },
                    label = { Text(strings.text("Username starts with")) },
                    supportingText = { Text(strings.text("Leave empty to choose among the people you follow.")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                val shown = candidates
                val rows = when {
                    shown == null -> emptyList()
                    picked != null && shown.none { it.id == picked?.id } -> listOfNotNull(picked) + shown
                    else -> shown
                }
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    when {
                        shown == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        rows.isEmpty() -> Text(
                            if (suggested) {
                                strings.text("You are not following anyone. Search for a username above.")
                            } else {
                                strings.text("No account starts with “{0}”.", query.trim().removePrefix("@"))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        else -> rows.forEach { user ->
                            val selectable = user.canShare && !user.blocked
                            Row(
                                Modifier.fillMaxWidth().selectable(
                                    selected = picked?.id == user.id,
                                    enabled = selectable && !busy,
                                    role = Role.RadioButton,
                                    onClick = { picked = user },
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = picked?.id == user.id, onClick = null, enabled = selectable)
                                Column(Modifier.weight(1f)) {
                                    Text("@" + user.username, style = MaterialTheme.typography.bodyLarge)
                                    val badges = userBadges(strings, user)
                                    if (badges.isNotEmpty()) {
                                        Text(badges, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it.take(SHARE_MESSAGE_MAX) },
                    label = { Text(strings.text("Message (optional)")) },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && picked != null,
                onClick = {
                    val to = picked ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        sync.shareWork(workHash, to.id, message).fold(
                            onSuccess = { sentTo = to.username },
                            onFailure = { error = it.userMessage(strings) },
                        )
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(strings.text("Send"))
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings.cancel) } },
    )
}

private const val SyncSharePageSize = 20
private const val SyncShareReloadMax = 100
