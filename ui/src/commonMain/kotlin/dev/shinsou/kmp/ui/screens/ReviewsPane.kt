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
import dev.shinsou.kmp.sync.v3.SyncReviews
import dev.shinsou.kmp.sync.v3.WireReply
import dev.shinsou.kmp.sync.v3.WireReview
import dev.shinsou.kmp.sync.v3.WireWorkPage
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.ShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
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
internal fun ReviewsEntry(sync: SyncReviews, workHash: String, title: String, inLibrary: Boolean) {
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
        EntryState.Unknown -> if (inLibrary) strings.text(StringKey.NO_REVIEWS_YET) else null
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
                    Text(strings.text(StringKey.REVIEWS), style = MaterialTheme.typography.titleMedium)
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
    val reviews = strings.text(StringKey.REVIEWS_X, page.reviewCount)
    return when {
        average != null -> "★ ${formatAverage(average)} (${page.rating.count}) · $reviews"
        page.reviewCount > 0 -> reviews
        else -> strings.text(StringKey.NO_REVIEWS_YET)
    }
}

private fun formatAverage(value: Double): String = ((value * 100).roundToInt() / 100.0).toString()

private fun stars(rating: Int): String = "★".repeat(rating.coerceIn(0, 5)) + "☆".repeat(5 - rating.coerceIn(0, 5))

/** Error text for the review endpoints (the shared mapping words some codes for sharing). */
private fun Throwable.reviewMessage(strings: ShinsouStrings): String {
    if (this is SyncApiException) {
        when (code) {
            "feature_disabled" -> return strings.text(StringKey.REVIEWS_ARE_TURNED_OFF_ON_THIS_SERVER)
            "email_unverified" -> return strings.text(StringKey.VERIFY_YOUR_EMAIL_ADDRESS_ON_THE_SERVER)
            "unknown_work" -> return strings.text(StringKey.THE_SERVER_DOES_NOT_KNOW_THIS_TITLE)
            "rate_limited" -> {
                val seconds = retryAfterSeconds
                return if (seconds != null) {
                    strings.text(StringKey.YOU_ARE_POSTING_TOO_FAST_TRY_AGAIN, ((seconds + 59) / 60).coerceAtLeast(1))
                } else {
                    strings.text(StringKey.TOO_MANY_ATTEMPTS_TRY_AGAIN_LATER)
                }
            }
            "forbidden" -> return strings.text(StringKey.THE_AUTHOR_OF_THIS_REVIEW_DOES_NOT)
        }
    }
    return userMessage(strings)
}

// ---- sheet ---------------------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ReviewsSheet(sync: SyncReviews, workHash: String, title: String, inLibrary: Boolean, onDismiss: () -> Unit) {
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
                    Text(strings.text(StringKey.REVIEWS), style = MaterialTheme.typography.titleLarge)
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
                        Text(strings.text(StringKey.YOUR_REVIEW), style = MaterialTheme.typography.titleSmall)
                        ReviewCard(sync, mine, onChanged = { reloadTick++ })
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { editing = true }) { Text(strings.text(StringKey.EDIT_REVIEW)) }
                            TextButton(onClick = { deleting = true }) { Text(strings.text(StringKey.DELETE_REVIEW)) }
                        }
                    } else if (inLibrary) {
                        Button(onClick = { editing = true }) { Text(strings.text(StringKey.WRITE_A_REVIEW)) }
                    } else {
                        Text(
                            strings.text(StringKey.ADD_THIS_TITLE_TO_YOUR_LIBRARY_TO),
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
                        label = { Text(strings.text(StringKey.NEWEST)) },
                    )
                    FilterChip(
                        selected = sort == "helpful",
                        onClick = { if (sort != "helpful") { reviews = null; sort = "helpful" } },
                        label = { Text(strings.text(StringKey.MOST_HELPFUL)) },
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
                        strings.text(StringKey.NO_REVIEWS_YET),
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
                    ) { Text(strings.text(StringKey.LOAD_MORE)) }
                }
            }
        }
    }

    if (editing) {
        ReviewEditorDialog(
            initial = mine,
            onDismiss = { editing = false },
            save = { rating, body, spoiler -> sync.writeReview(workHash, rating, body, spoiler).map { } },
            onSaved = { editing = false; notice = strings.text(StringKey.REVIEW_SAVED); reloadTick++ },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { if (!deleteBusy) deleting = false },
            title = { Text(strings.text(StringKey.DELETE_YOUR_REVIEW)) },
            text = { Text(strings.text(StringKey.YOUR_REVIEW_IS_REMOVED_TOGETHER_WITH_ITS)) },
            confirmButton = {
                TextButton(
                    enabled = !deleteBusy,
                    onClick = {
                        deleteBusy = true
                        scope.launch {
                            sync.deleteReview(workHash).fold(
                                onSuccess = { notice = strings.text(StringKey.REVIEW_DELETED); error = null; reloadTick++ },
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
private fun ReviewCard(sync: SyncReviews, review: WireReview, onChanged: () -> Unit) {
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
                listOfNotNull(formatTime(review.createdAt), strings.text(StringKey.EDITED).takeIf { review.edited }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (review.status == "hidden") {
                Text(
                    strings.text(StringKey.MODERATORS_HID_THIS_REVIEW_SO_ONLY_YOU) +
                        review.hiddenReason?.takeIf { it.isNotBlank() }?.let { " " + strings.text(StringKey.REASON_X, it) }.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            ReviewCardBody(review, revealed, onReveal = { revealed = true })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                if (review.mine) {
                    Text(
                        strings.text(StringKey.HELPFUL_X_2, helpful),
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
                        label = { Text(strings.text(StringKey.HELPFUL_X, helpful)) },
                    )
                }
                TextButton(onClick = { repliesOpen = !repliesOpen }) {
                    Text(strings.text(StringKey.REPLIES_X, replyCount))
                }
                if (!review.mine) {
                    TextButton(onClick = { reporting = true }) { Text(strings.text(StringKey.REPORT)) }
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
            title = strings.text(StringKey.REPORT_THIS_REVIEW),
            onDismiss = { reporting = false },
            submit = { reason, note -> sync.reportReview(review.id, reason, note).map { } },
            onSent = { reporting = false; notice = strings.text(StringKey.REPORT_SENT_THANK_YOU) },
        )
    }
}

/** The review text, or the tap-to-show spoiler cover while [revealed] is false. */
@Composable
private fun ReviewCardBody(review: WireReview, revealed: Boolean, onReveal: () -> Unit) {
    val strings = LocalShinsouStrings.current
    if (review.body.isNotBlank()) {
        if (review.spoiler && !revealed) {
            Surface(
                onClick = onReveal,
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    strings.text(StringKey.CONTAINS_SPOILERS_TAP_TO_SHOW),
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        } else {
            ReviewMarkdownText(review.body)
        }
    }
}

// ---- replies -------------------------------------------------------------------------------------

@Composable
private fun RepliesPanel(sync: SyncReviews, review: WireReview, onCountChange: (Int) -> Unit, onChanged: () -> Unit) {
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
                strings.text(StringKey.NO_REPLIES_YET),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows != null -> rows.forEach { reply ->
                ReplyRow(reply, onDelete = { deletingReply = reply }, onReport = { reportingReply = reply })
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
            ) { Text(strings.text(StringKey.LOAD_MORE)) }
        }
        ErrorText(error)
        notice?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary) }
        ReplyDraftField(draft) { draft = it }
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
        ) { Text(strings.text(StringKey.REPLY)) }
    }

    reportingReply?.let { reply ->
        ReportDialog(
            title = strings.text(StringKey.REPORT_THIS_REPLY),
            onDismiss = { reportingReply = null },
            submit = { reason, note -> sync.reportReply(reply.id, reason, note).map { } },
            onSent = { reportingReply = null; notice = strings.text(StringKey.REPORT_SENT_THANK_YOU) },
        )
    }
    deletingReply?.let { reply ->
        DeleteReplyDialog(
            onDismiss = { deletingReply = null },
            onConfirm = {
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
        )
    }
}

/** The reply editor with its length counter. */
@Composable
private fun ReplyDraftField(draft: String, onDraftChange: (String) -> Unit) {
    OutlinedTextField(
        value = draft,
        onValueChange = { onDraftChange(it.take(REPLY_BODY_MAX)) },
        label = { Text(LocalShinsouStrings.current.text(StringKey.WRITE_A_REPLY)) },
        supportingText = { Text("${draft.length} / $REPLY_BODY_MAX") },
        minLines = 2,
        maxLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One reply: author and time, the moderation notice for a hidden reply, its text and delete / report. */
@Composable
private fun ReplyRow(reply: WireReply, onDelete: () -> Unit, onReport: () -> Unit) {
    val strings = LocalShinsouStrings.current
    Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "@${reply.author.username} · ${formatTime(reply.createdAt)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (reply.status == "hidden") {
            Text(
                strings.text(StringKey.MODERATORS_HID_THIS_REPLY_SO_ONLY_YOU) +
                    reply.hiddenReason?.takeIf { it.isNotBlank() }?.let { " " + strings.text(StringKey.REASON_X, it) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ReviewMarkdownText(reply.body)
        if (reply.mine) {
            TextButton(onClick = onDelete) { Text(strings.delete) }
        } else {
            TextButton(onClick = onReport) { Text(strings.text(StringKey.REPORT)) }
        }
    }
}

/** Confirms deleting the user's own reply. */
@Composable
private fun DeleteReplyDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val strings = LocalShinsouStrings.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(strings.text(StringKey.DELETE_YOUR_REPLY)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(strings.delete) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(strings.cancel) } },
    )
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
        title = { Text(if (initial == null) strings.text(StringKey.WRITE_A_REVIEW) else strings.text(StringKey.EDIT_REVIEW)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(strings.text(StringKey.RATING), style = MaterialTheme.typography.titleSmall)
                FlowRowStars(rating = rating, enabled = !busy, onPick = { rating = it })
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it.take(REVIEW_BODY_MAX) },
                    label = { Text(strings.text(StringKey.YOUR_REVIEW)) },
                    supportingText = { Text("${body.length} / $REVIEW_BODY_MAX") },
                    minLines = 4,
                    maxLines = 10,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    strings.text(StringKey.YOU_CAN_USE_BOLD_ITALIC_CODE_QUOTES),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = spoiler, onCheckedChange = { spoiler = it }, enabled = !busy)
                    Text(strings.text(StringKey.CONTAINS_SPOILERS), style = MaterialTheme.typography.bodyMedium)
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
        FilterChip(selected = rating == null, enabled = enabled, onClick = { onPick(null) }, label = { Text(strings.text(StringKey.NO_RATING)) })
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
                    label = { Text(strings.text(StringKey.NOTE_OPTIONAL)) },
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
            ) { Text(strings.text(StringKey.SEND_REPORT)) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(strings.cancel) } },
    )
}

private fun reportReasonLabel(strings: ShinsouStrings, reason: String): String = when (reason) {
    "spam" -> strings.text(StringKey.SPAM)
    "harassment" -> strings.text(StringKey.HARASSMENT)
    "spoiler" -> strings.text(StringKey.UNMARKED_SPOILER)
    "illegal" -> strings.text(StringKey.ILLEGAL_CONTENT)
    else -> strings.text(StringKey.OTHER)
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
