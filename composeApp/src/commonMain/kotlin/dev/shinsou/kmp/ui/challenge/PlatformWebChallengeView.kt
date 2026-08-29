package dev.shinsou.kmp.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest

internal data class WebChallengeCapture(
    val cookies: List<SourceCookie>,
    val userAgent: String,
)

/** Platform WebView surface backed by an isolated, non-persistent browser session. */
@Composable
internal expect fun PlatformWebChallengeView(
    request: SourceWebChallengeRequest,
    captureRequest: Int,
    onPageLoaded: () -> Unit,
    onSessionCaptured: (WebChallengeCapture) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)
