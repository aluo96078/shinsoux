package dev.shinsou.kmp.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.shinsou.kmp.domain.model.DeveloperSettings
import dev.shinsou.kmp.ui.i18n.LocalShinsouStrings
import dev.shinsou.kmp.ui.i18n.text
import kotlinx.coroutines.delay
import kotlin.time.TimeSource

/** Effective developer-mode diagnostics: the sub-switches already gated by the master switch. */
data class DeveloperDiagnostics(
    val showLoadingElapsed: Boolean = false,
    val showImageRequests: Boolean = false,
)

fun DeveloperSettings.toDiagnostics(): DeveloperDiagnostics = DeveloperDiagnostics(
    showLoadingElapsed = enabled && showLoadingElapsed,
    showImageRequests = enabled && showImageRequests,
)

val LocalDeveloperDiagnostics = compositionLocalOf { DeveloperDiagnostics() }

/** Minutes and a pre-formatted seconds part; minutes is 0 below one minute. */
data class ElapsedDisplay(val minutes: Int, val seconds: String)

/** Seconds with one decimal below 60 s, then whole minutes plus zero-padded seconds. */
fun elapsedDisplay(elapsedMillis: Long): ElapsedDisplay {
    val millis = elapsedMillis.coerceAtLeast(0L)
    if (millis < 60_000L) {
        val tenths = millis / 100L
        return ElapsedDisplay(0, "${tenths / 10}.${tenths % 10}")
    }
    val totalSeconds = millis / 1000L
    return ElapsedDisplay((totalSeconds / 60L).toInt(), (totalSeconds % 60L).toString().padStart(2, '0'))
}

/** Display-only URL for diagnostics: drops the `#...` fragment, which can carry request headers. */
fun diagnosticUrl(url: String?): String? =
    url?.substringBefore('#')?.trim()?.takeIf(String::isNotEmpty)

/**
 * The app's standalone loading spinner. Draws exactly a [CircularProgressIndicator] unless a
 * developer-mode diagnostic is on, in which case a caption with the elapsed time (and, for image
 * loads, [requestUrl]) is shown beneath it. Timing starts when this enters composition.
 */
@Composable
fun ShinsouLoadingIndicator(
    modifier: Modifier = Modifier,
    indicatorModifier: Modifier = Modifier,
    color: Color = ProgressIndicatorDefaults.circularColor,
    strokeWidth: Dp = ProgressIndicatorDefaults.CircularStrokeWidth,
    captionColor: Color = Color.Unspecified,
    requestUrl: String? = null,
) {
    val diagnostics = LocalDeveloperDiagnostics.current
    val url = requestUrl?.takeIf { diagnostics.showImageRequests && it.isNotBlank() }
    val captionVisible = url != null || diagnostics.showLoadingElapsed
    if (!captionVisible) {
        CircularProgressIndicator(modifier.then(indicatorModifier), color = color, strokeWidth = strokeWidth)
        return
    }
    val strings = LocalShinsouStrings.current
    val start = remember { TimeSource.Monotonic.markNow() }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            elapsedMillis = start.elapsedNow().inWholeMilliseconds
            delay(100)
        }
    }
    val display = elapsedDisplay(elapsedMillis)
    val elapsedText = if (display.minutes == 0) {
        strings.text("{0} s", display.seconds)
    } else {
        strings.text("{0} min {1} s", display.minutes, display.seconds)
    }
    val resolvedCaptionColor = captionColor.takeIf { it != Color.Unspecified }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(indicatorModifier, color = color, strokeWidth = strokeWidth)
        if (url != null) {
            Text(
                url,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 280.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = resolvedCaptionColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            strings.text(if (url != null) "Requested {0}" else "Processed {0}", elapsedText),
            modifier = Modifier.padding(top = if (url != null) 2.dp else 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = resolvedCaptionColor,
            textAlign = TextAlign.Center,
        )
    }
}
