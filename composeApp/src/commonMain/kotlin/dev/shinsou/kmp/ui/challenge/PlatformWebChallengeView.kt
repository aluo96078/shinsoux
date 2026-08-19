package dev.shinsou.kmp.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest

/** Platform WebView surface. Desktop provides a no-op actual and uses the explicit fallback UI. */
@Composable
internal expect fun PlatformWebChallengeView(
    request: SourceWebChallengeRequest,
    captureRequest: Int,
    onPageLoaded: () -> Unit,
    onCookiesCaptured: (List<SourceCookie>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
)
