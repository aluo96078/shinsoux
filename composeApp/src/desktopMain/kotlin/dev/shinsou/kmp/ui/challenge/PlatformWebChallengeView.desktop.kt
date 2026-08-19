package dev.shinsou.kmp.ui.challenge

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.shinsou.kmp.ui.SourceCookie
import dev.shinsou.kmp.ui.SourceWebChallengeRequest

internal actual val platformWebChallengeMode: PlatformWebChallengeMode =
    PlatformWebChallengeMode.ExternalBrowserOnly

@Composable
internal actual fun PlatformWebChallengeView(
    request: SourceWebChallengeRequest,
    captureRequest: Int,
    onPageLoaded: () -> Unit,
    onCookiesCaptured: (List<SourceCookie>) -> Unit,
    onError: (String) -> Unit,
    modifier: Modifier,
) = Unit
