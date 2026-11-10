package dev.shinsou.kmp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.sync.v3.SyncApiException
import dev.shinsou.kmp.sync.v3.SyncV3Service
import dev.shinsou.kmp.sync.v3.WireReply
import dev.shinsou.kmp.sync.v3.WireReview
import dev.shinsou.kmp.sync.v3.WireWorkPage
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import dev.shinsou.kmp.ui.markdown.MarkdownBlock
import dev.shinsou.kmp.ui.markdown.MarkdownInline
import dev.shinsou.kmp.ui.markdown.parseReviewMarkdown
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private const val REVIEW_PAGE = 20
private const val REVIEW_RELOAD_MAX = 100
private const val REVIEW_BODY_MAX = 5000
private const val REPLY_BODY_MAX = 2000
private const val REPORT_NOTE_MAX = 500
private val ReportReasons = listOf("spam", "harassment", "spoiler", "illegal", "other")

private sealed interface EntryState {
    data object Loading : EntryState
    data class Loaded(val page: WireWorkPage) : EntryState

    /** The server does not know the work yet (404). */
    data object Unknown : EntryState

    /** Reviews are off, the request failed, ...: the entry stays out of the way. */
    data object Unavailable : EntryState
}

/**
 * Work detail → "Reviews" row with the average rating and review count; opens the reviews sheet.
 * Loaded lazily and silent on failure. A title the server does not know yet (404) still gets the
 * row when it is in the library, so the first review can be written after a sync.
 */
@Composable
internal fun ReviewsEntry(sync: SyncV3Service, workHash: String, title: String, inLibrary: Boolean) {
    val strings = LocalShinsouStrings.current
    var tick by remember(workHash) { mutableIntStateOf(0) }
    var state by remember(workHash) { mutableStateOf<EntryState>(EntryState.Loading) }
    var sheetVisible by remember(workHash) { mutableStateOf(false) }

    LaunchedEffect(workHash, tick, inLibrary) {
        sync.workPage(workHash).fold(
            onSuccess = { state = EntryState.Loaded(it) },
            onFailure = { state = if (it is SyncApiException && it.status == 404) EntryState.Unknown else EntryState.Unavailable },
        )
    }

    val summary = when (val current = state) {
        is EntryState.Loaded -> summaryLine(strings, current.page)
        EntryState.Unknown -> if (inLibrary) strings.text("No reviews yet") else null
        else -> null
    }
    if (summary != null) {
        Surface(
            onClick = { sheetVisible = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(strings.text("Reviews"), style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (sheetVisible) {
        ReviewsSheet(sync, workHash, title, inLibrary, onDismiss = { sheetVisible = false; tick++ })
    }
}

private fun summaryLine(strings: ShinsouStrings, page: WireWorkPage): String {
    val average = page.rating.average
    val reviews = strings.text("Reviews: {0}", page.reviewCount)
    return when {
        average != null -> "★ ${formatAverage(average)} (${page.rating.count}) · $reviews"
        page.reviewCount > 0 -> reviews
        else -> strings.text("No reviews yet")
    }
}

private fun formatAverage(value: Double): String = ((value * 100).roundToInt() / 100.0).toString()

private fun stars(rating: Int): String = "★".repeat(rating.coerceIn(0, 5)) + "☆".repeat(5 - rating.coerceIn(0, 5))

/** Error text for the review endpoints (the shared mapping words some codes for sharing). */
private fun Throwable.reviewMessage(strings: ShinsouStrings): String {
    if (this is SyncApiException) {
        when (code) {
            "feature_disabled" -> return strings.text("Reviews are turned off on this server.")
            "email_unverified" -> return strings.text("Verify your email address on the server's website to use reviews.")
            "unknown_work" -> return strings.text("The server does not know this title yet. Sync your library first, then try again.")
            "rate_limited" -> {
                val seconds = retryAfterSeconds
                return if (seconds != null) {
                    strings.text("You are posting too fast. Try again in {0} minutes.", ((seconds + 59) / 60).coerceAtLeast(1))
                } else {
                    strings.text("Too many attempts. Try again later.")
                }
            }
            "forbidden" -> return strings.text("The author of this review does not accept replies from you.")
        }
    }
    return userMessage(strings)
}

// ---- sheet ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReviewsSheet(sync: SyncV3Service, workHash: String, title: String, inLibrary: Boolean, onDismiss: () -> Unit) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var sort by remember { mutableStateOf("newest") }
    var work by remember { mutableStateOf<WireWorkPage?>(null) }
    var reviews by remember { mutableStateOf<List<WireReview>?>(null) }
    var total by remember { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteBusy by remember { mutableStateOf(false) }

    LaunchedEffect(sort, reloadTick) {
        // Reload as many rows as were on screen, so a change does not collapse the list.
        val want = (reviews?.size ?: 0).coerceIn(REVIEW_PAGE, REVIEW_RELOAD_MAX)
        val pageResult = sync.workPage(workHash)
        val page = pageResult.getOrNull()
        if (page == null) {
            val failure = pageResult.exceptionOrNull()
            if (failure is SyncApiException && failure.status == 404) {
                work = null
                reviews = emptyList()
                total = 0
                error = null
            } else {
                error = failure?.reviewMessage(strings)
            }
            return@LaunchedEffect
        }
        work = page
        sync.listReviews(workHash, sort, offset = 0, limit = want).fold(
            onSuccess = { reviews = it.reviews; total = it.total; error = null },
            onFailure = { error = it.reviewMessage(strings) },
        )
    }

    val mine = work?.myReview
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            Modifier.fillMaxWidth().fillMaxHeight(0.92f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(strings.text("Reviews"), style = MaterialTheme.typography.titleLarge)
                    Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    work?.let { Text(summaryLine(strings, it), style = MaterialTheme.typography.titleMedium) }
                }
            }
            if (error != null || notice != null) {
                item(key = "status") {
                    ErrorText(error)
                    notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
                }
            }
            item(key = "mine") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mine != null) {
                        Text(strings.text("Your review"), style = MaterialTheme.typography.titleSmall)
                        ReviewCard(sync, mine, onChanged = { reloadTick++ })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = true }) { Text(strings.text("Edit review")) }
                            TextButton(onClick = { deleting = true }) { Text(strings.text("Delete review")) }
                        }
                    } else if (inLibrary) {
                        Button(onClick = { editing = true }) { Text(strings.text("Write a review")) }
                    } else {
                        Text(
                            strings.text("Add this title to your library to write a review."),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item(key = "sort") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = sort == "newest",
                        onClick = { if (sort != "newest") { reviews = null; sort = "newest" } },
                        label = { Text(strings.text("Newest")) },
                    )
                    FilterChip(
                        selected = sort == "helpful",
                        onClick = { if (sort != "helpful") { reviews = null; sort = "helpful" } },
                        label = { Text(strings.text("Most helpful")) },
                    )
                }
            }
            val rows = reviews?.filterNot { it.mine }
            when {
                reviews == null && error == null -> item(key = "loading") {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                rows != null && rows.isEmpty() && mine == null -> item(key = "empty") {
                    Text(
                        strings.text("No reviews yet"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                rows != null -> items(rows, key = { it.id }) { review ->
                    ReviewCard(sync, review, onChanged = { reloadTick++ })
                }
            }
            val loaded = reviews
            if (loaded != null && loaded.size < total) {
                item(key = "more") {
                    TextButton(
                        enabled = !loadingMore,
                        onClick = {
                            loadingMore = true
                            scope.launch {
                                sync.listReviews(workHash, sort, offset = loaded.size).fold(
                                    onSuccess = { page ->
                                        val known = loaded.mapTo(HashSet()) { it.id }
                                        reviews = loaded + page.reviews.filterNot { it.id in known }
                                        total = page.total
                                    },
                                    onFailure = { error = it.reviewMessage(strings) },
                                )
                                loadingMore = false
                            }
                        },
                    ) { Text(strings.text("Load more")) }
                }
            }
        }
    }

    if (editing) {
        ReviewEditorDialog(
            initial = mine,
            onDismiss = { editing = false },
            save = { rating, body, spoiler -> sync.writeReview(workHash, rating, body, spoiler).map { } },
            onSaved = { editing = false; notice = strings.text("Review saved."); reloadTick++ },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { if (!deleteBusy) deleting = false },
            title = { Text(strings.text("Delete your review?")) },
            text = { Text(strings.text("Your review is removed together with its replies and helpful votes.")) },
            confirmButton = {
                TextButton(
                    enabled = !deleteBusy,
                    onClick = {
                        deleteBusy = true
                        scope.launch {
                            sync.deleteReview(workHash).fold(
                                onSuccess = { notice = strings.text("Review deleted."); error = null; reloadTick++ },
                                onFailure = { error = it.reviewMessage(strings) },
                            )
                            deleteBusy = false
                            deleting = false
                        }
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = { TextButton(enabled = !deleteBusy, onClick = { deleting = false }) { Text(strings.cancel) } },
        )
    }
}

// ---- review card ---------------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewCard(sync: SyncV3Service, review: WireReview, onChanged: () -> Unit) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var revealed by remember(review.id) { mutableStateOf(false) }
    var helpful by remember(review.id, review.helpful) { mutableIntStateOf(review.helpful) }
    var voted by remember(review.id, review.votedHelpful) { mutableStateOf(review.votedHelpful) }
    var voteBusy by remember(review.id) { mutableStateOf(false) }
    var repliesOpen by remember(review.id) { mutableStateOf(false) }
    var replyCount by remember(review.id, review.replyCount) { mutableIntStateOf(review.replyCount) }
    var reporting by remember(review.id) { mutableStateOf(false) }
    var error by remember(review.id) { mutableStateOf<String?>(null) }
    var notice by remember(review.id) { mutableStateOf<String?>(null) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("@${review.author.username}", style = MaterialTheme.typography.titleSmall)
                review.rating?.let { Text(stars(it), color = MaterialTheme.colorScheme.primary) }
            }
            Text(
                listOfNotNull(formatTime(review.createdAt), strings.text("edited").takeIf { review.edited }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (review.status == "hidden") {
                Text(
                    strings.text("Moderators hid this review, so only you can see it.") +
                        review.hiddenReason?.takeIf { it.isNotBlank() }?.let { " " + strings.text("Reason: {0}", it) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (review.body.isNotBlank()) {
                if (review.spoiler && !revealed) {
                    Surface(
                        onClick = { revealed = true },
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            strings.text("Contains spoilers. Tap to show."),
                            modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else {
                    ReviewMarkdownText(review.body)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (review.mine) {
                    Text(
                        strings.text("Helpful: {0}", helpful),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                } else {
                    FilterChip(
                        selected = voted,
                        enabled = !voteBusy,
                        onClick = {
                            voteBusy = true
                            scope.launch {
                                sync.setHelpful(review.id, !voted).fold(
                                    onSuccess = { helpful = it.helpful; voted = it.votedHelpful; error = null },
                                    onFailure = { error = it.reviewMessage(strings) },
                                )
                                voteBusy = false
                            }
                        },
                        label = { Text(strings.text("Helpful ({0})", helpful)) },
                    )
                }
                TextButton(onClick = { repliesOpen = !repliesOpen }) {
                    Text(strings.text("Replies ({0})", replyCount))
                }
                if (!review.mine) {
                    TextButton(onClick = { reporting = true }) { Text(strings.text("Report")) }
                }
            }
            ErrorText(error)
            notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
            if (repliesOpen) {
                HorizontalDivider()
                RepliesPanel(sync, review, onCountChange = { replyCount = it }, onChanged = onChanged)
            }
        }
    }

    if (reporting) {
        ReportDialog(
            title = strings.text("Report this review"),
            onDismiss = { reporting = false },
            submit = { reason, note -> sync.reportReview(review.id, reason, note).map { } },
            onSent = { reporting = false; notice = strings.text("Report sent. Thank you.") },
        )
    }
}

// ---- replies -------------------------------------------------------------------------------------

@Composable
private fun RepliesPanel(sync: SyncV3Service, review: WireReview, onCountChange: (Int) -> Unit, onChanged: () -> Unit) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var replies by remember(review.id) { mutableStateOf<List<WireReply>?>(null) }
    var total by remember(review.id) { mutableIntStateOf(0) }
    var error by remember(review.id) { mutableStateOf<String?>(null) }
    var draft by remember(review.id) { mutableStateOf("") }
    var busy by remember(review.id) { mutableStateOf(false) }
    var reloadTick by remember(review.id) { mutableIntStateOf(0) }
    var reportingReply by remember(review.id) { mutableStateOf<WireReply?>(null) }
    var deletingReply by remember(review.id) { mutableStateOf<WireReply?>(null) }
    var notice by remember(review.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(review.id, reloadTick) {
        val want = (replies?.size ?: 0).coerceIn(REVIEW_PAGE, REVIEW_RELOAD_MAX)
        sync.listReplies(review.id, offset = 0, limit = want).fold(
            onSuccess = { replies = it.replies; total = it.total; error = null; onCountChange(it.total) },
            onFailure = { error = it.reviewMessage(strings) },
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val rows = replies
        when {
            rows == null && error == null -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            rows != null && rows.isEmpty() -> Text(
                strings.text("No replies yet."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows != null -> rows.forEach { reply ->
                Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "@${reply.author.username} · ${formatTime(reply.createdAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (reply.status == "hidden") {
                        Text(
                            strings.text("Moderators hid this reply, so only you can see it.") +
                                reply.hiddenReason?.takeIf { it.isNotBlank() }?.let { " " + strings.text("Reason: {0}", it) }.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    ReviewMarkdownText(reply.body)
                    if (reply.mine) {
                        TextButton(onClick = { deletingReply = reply }) { Text(strings.delete) }
                    } else {
                        TextButton(onClick = { reportingReply = reply }) { Text(strings.text("Report")) }
                    }
                }
            }
        }
        if (rows != null && rows.size < total) {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        sync.listReplies(review.id, offset = rows.size).fold(
                            onSuccess = { page ->
                                val known = rows.mapTo(HashSet()) { it.id }
                                replies = rows + page.replies.filterNot { it.id in known }
                                total = page.total
                            },
                            onFailure = { error = it.reviewMessage(strings) },
                        )
                        busy = false
                    }
                },
            ) { Text(strings.text("Load more")) }
        }
        ErrorText(error)
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it.take(REPLY_BODY_MAX) },
            label = { Text(strings.text("Write a reply")) },
            supportingText = { Text("${draft.length} / $REPLY_BODY_MAX") },
            minLines = 2,
            maxLines = 6,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = !busy && draft.isNotBlank(),
            onClick = {
                busy = true
                error = null
                scope.launch {
                    sync.writeReply(review.id, draft).fold(
                        onSuccess = { draft = ""; reloadTick++; onChanged() },
                        onFailure = { error = it.reviewMessage(strings) },
                    )
                    busy = false
                }
            },
        ) { Text(strings.text("Reply")) }
    }

    reportingReply?.let { reply ->
        ReportDialog(
            title = strings.text("Report this reply"),
            onDismiss = { reportingReply = null },
            submit = { reason, note -> sync.reportReply(reply.id, reason, note).map { } },
            onSent = { reportingReply = null; notice = strings.text("Report sent. Thank you.") },
        )
    }
    deletingReply?.let { reply ->
        AlertDialog(
            onDismissRequest = { deletingReply = null },
            title = { Text(strings.text("Delete your reply?")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deletingReply = null
                        busy = true
                        scope.launch {
                            sync.deleteReply(reply.id).fold(
                                onSuccess = { error = null; reloadTick++; onChanged() },
                                onFailure = { error = it.reviewMessage(strings) },
                            )
                            busy = false
                        }
                    },
                ) { Text(strings.delete) }
            },
            dismissButton = { TextButton(onClick = { deletingReply = null }) { Text(strings.cancel) } },
        )
    }
}

// ---- dialogs -------------------------------------------------------------------------------------

@Composable
private fun ReviewEditorDialog(
    initial: WireReview?,
    onDismiss: () -> Unit,
    save: suspend (rating: Int?, body: String, spoiler: Boolean) -> Result<Unit>,
    onSaved: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var rating by remember { mutableStateOf(initial?.rating) }
    var body by remember { mutableStateOf(initial?.body.orEmpty()) }
    var spoiler by remember { mutableStateOf(initial?.spoiler ?: false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (initial == null) strings.text("Write a review") else strings.text("Edit review")) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.text("Rating"), style = MaterialTheme.typography.titleSmall)
                FlowRowStars(rating = rating, enabled = !busy, onPick = { rating = it })
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(REVIEW_BODY_MAX) },
                    label = { Text(strings.text("Your review")) },
                    supportingText = { Text("${body.length} / $REVIEW_BODY_MAX") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    strings.text("You can use **bold**, *italic*, `code`, > quotes and - lists."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = spoiler, onCheckedChange = { spoiler = it }, enabled = !busy)
                    Text(strings.text("Contains spoilers"), style = MaterialTheme.typography.bodyMedium)
                }
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (rating != null || body.isNotBlank()),
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        save(rating, body, spoiler).fold(
                            onSuccess = { onSaved() },
                            onFailure = { error = it.reviewMessage(strings) },
                        )
                        busy = false
                    }
                },
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(strings.save)
            }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings.cancel) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowStars(rating: Int?, enabled: Boolean, onPick: (Int?) -> Unit) {
    val strings = LocalShinsouStrings.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = rating == null, enabled = enabled, onClick = { onPick(null) }, label = { Text(strings.text("No rating")) })
        for (n in 1..5) {
            FilterChip(
                selected = rating == n,
                enabled = enabled,
                onClick = { onPick(n) },
                label = { Text("$n ★") },
            )
        }
    }
}

@Composable
private fun ReportDialog(
    title: String,
    onDismiss: () -> Unit,
    submit: suspend (reason: String, note: String) -> Result<Unit>,
    onSent: () -> Unit,
) {
    val strings = LocalShinsouStrings.current
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf(ReportReasons.first()) }
    var note by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReportReasons.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = reason == option,
                            enabled = !busy,
                            role = Role.RadioButton,
                            onClick = { reason = option },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == option, onClick = null, enabled = !busy)
                        Spacer(Modifier.width(8.dp))
                        Text(reportReasonLabel(strings, option))
                    }
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(REPORT_NOTE_MAX) },
                    label = { Text(strings.text("Note (optional)")) },
                    supportingText = { Text("${note.length} / $REPORT_NOTE_MAX") },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth(),
                )
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        submit(reason, note).fold(
                            onSuccess = { onSent() },
                            onFailure = { error = it.reviewMessage(strings) },
                        )
                        busy = false
                    }
                },
            ) { Text(strings.text("Send report")) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings.cancel) } },
    )
}

private fun reportReasonLabel(strings: ShinsouStrings, reason: String): String = when (reason) {
    "spam" -> strings.text("Spam")
    "harassment" -> strings.text("Harassment")
    "spoiler" -> strings.text("Unmarked spoiler")
    "illegal" -> strings.text("Illegal content")
    else -> strings.text("Other")
}

// ---- markdown ------------------------------------------------------------------------------------

/** Draws a review or reply body from its Markdown source (the server's HTML is not used). */
@Composable
internal fun ReviewMarkdownText(source: String, modifier: Modifier = Modifier) {
    val blocks = remember(source) { parseReviewMarkdown(source) }
    val codeBackground = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> Text(
                    linesToAnnotated(block.lines, codeBackground),
                    style = MaterialTheme.typography.bodyMedium,
                )
                is MarkdownBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(
                        Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outline),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        linesToAnnotated(block.lines, codeBackground),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                is MarkdownBlock.BulletList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    block.items.forEach { item ->
                        Row {
                            Text("•", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(16.dp))
                            Text(
                                linesToAnnotated(listOf(item), codeBackground),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun linesToAnnotated(lines: List<List<MarkdownInline>>, codeBackground: androidx.compose.ui.graphics.Color): AnnotatedString =
    buildAnnotatedString {
        lines.forEachIndexed { index, line ->
            if (index > 0) append('\n')
            appendInline(line, codeBackground)
        }
    }

private fun AnnotatedString.Builder.appendInline(spans: List<MarkdownInline>, codeBackground: androidx.compose.ui.graphics.Color) {
    for (span in spans) {
        when (span) {
            is MarkdownInline.Text -> append(span.value)
            is MarkdownInline.Code -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(span.value) }
            is MarkdownInline.Bold -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(span.children, codeBackground) }
            is MarkdownInline.Italic -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(span.children, codeBackground) }
        }
    }
}
