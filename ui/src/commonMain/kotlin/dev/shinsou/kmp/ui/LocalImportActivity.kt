package dev.shinsou.kmp.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.contract.ImportedDocument
import dev.shinsou.kmp.contract.LocalTextEncoding
import dev.shinsou.kmp.contract.LocalTextEncodingQuestion
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.StringKey
import dev.shinsou.kmp.ui.i18n.text
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext

/**
 * Runs [import] on [context] and hands it the picked [documents]: the import closes them from its
 * first line on. Until then they belong to the caller, and the hop to [context] can be cancelled
 * before [import] starts; they are closed here in that case, so a picker copy (iOS copy mode) is
 * not left on disk.
 */
internal suspend fun <T> handOverPickedDocuments(
    documents: List<ImportedDocument>,
    context: CoroutineContext,
    import: suspend () -> T,
): T {
    var handedOver = false
    try {
        return withContext(context) {
            handedOver = true
            import()
        }
    } finally {
        if (!handedOver) documents.forEach(ImportedDocument::close)
    }
}

/**
 * Whether a local import (picker and import) is running, and the TXT encoding question it waits
 * on. It outlives the More screen, so leaving and reopening it still shows the import and still
 * blocks a second one. Touched on the UI thread.
 */
internal object LocalImportActivity {
    var running: Boolean by mutableStateOf(false)
        private set

    /** The question [LocalImportEncodingDialog] shows; null when the import asks nothing. */
    var encodingQuestion: PendingTextEncodingQuestion? by mutableStateOf(null)
        private set

    suspend fun <T> track(block: suspend () -> T): T {
        running = true
        try {
            return block()
        } finally {
            running = false
        }
    }

    /** Shows [question] and waits for the user's encoding; null when they skip the file. */
    suspend fun chooseTextEncoding(question: LocalTextEncodingQuestion): LocalTextEncoding? {
        val pending = PendingTextEncodingQuestion(question)
        encodingQuestion = pending
        try {
            return pending.await()
        } finally {
            if (encodingQuestion === pending) encodingQuestion = null
        }
    }
}

internal class PendingTextEncodingQuestion(val question: LocalTextEncodingQuestion) {
    private val answer = CompletableDeferred<LocalTextEncoding?>()

    fun answer(encoding: LocalTextEncoding?) {
        answer.complete(encoding)
    }

    suspend fun await(): LocalTextEncoding? = answer.await()
}

/** Asks which encoding a TXT is in, showing the text each one gives where they differ. */
@Composable
internal fun LocalImportEncodingDialog(shown: Boolean) {
    val pending = LocalImportActivity.encodingQuestion?.takeIf { shown } ?: return
    val question = pending.question
    val strings = LocalShinsouStrings.current
    AlertDialog(
        onDismissRequest = { pending.answer(null) },
        title = { Text(strings.text(StringKey.CHOOSE_TEXT_ENCODING)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(strings.text(StringKey.X_CAN_BE_READ_IN_SEVERAL_ENCODINGS, question.documentName))
                if (question.imported.isNotEmpty()) {
                    Text(
                        strings.text(StringKey.CHOOSING_ANOTHER_ENCODING_REPLACES_THE_COPY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                question.candidates.forEach { candidate ->
                    OutlinedCard(
                        onClick = { pending.answer(candidate.encoding) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    strings.text(
                                        when (candidate.encoding) {
                                            LocalTextEncoding.GB18030 -> StringKey.SIMPLIFIED_CHINESE_GB18030
                                            LocalTextEncoding.BIG5 -> StringKey.TRADITIONAL_CHINESE_BIG5
                                            LocalTextEncoding.UTF_8 -> StringKey.UNICODE_UTF8
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                val tag = when (candidate.encoding) {
                                    in question.imported -> StringKey.IN_LIBRARY
                                    question.recommended -> StringKey.LIKELY
                                    else -> null
                                }
                                tag?.let {
                                    Text(
                                        strings.text(it),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                            Text(
                                candidate.preview,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { pending.answer(null) }) { Text(strings.text(StringKey.SKIP_THIS_FILE)) }
        },
    )
}
