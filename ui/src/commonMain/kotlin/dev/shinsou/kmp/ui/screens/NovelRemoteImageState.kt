package dev.shinsou.kmp.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.shinsou.kmp.contract.ReaderRemoteImageFetcher

/**
 * The fetch state of one remote image in a novel chapter: [bytes] once the source returned a
 * decodable body, [failed] when there is nothing to show. Callers may flip [failed] from the
 * decoder callbacks.
 */
@Stable
internal class NovelRemoteImageState(initiallyFailed: Boolean) {
    var bytes by mutableStateOf<ByteArray?>(null)
    var failed by mutableStateOf(initiallyFailed)
}

/**
 * Fetches [url] through the source's typed remote-asset scope. The state and the fetch restart when
 * [url], [remoteAssetScope] or [key] change; without a scope the image fails immediately.
 */
@Composable
internal fun rememberNovelRemoteImage(
    url: String,
    remoteAssetScope: ReaderRemoteImageFetcher?,
    key: Any?,
): NovelRemoteImageState {
    val state = remember(url, remoteAssetScope, key) { NovelRemoteImageState(initiallyFailed = remoteAssetScope == null) }
    LaunchedEffect(url, remoteAssetScope, key) {
        val scope = remoteAssetScope
        if (scope == null) {
            state.failed = true
            return@LaunchedEffect
        }
        val body = scope.fetchImage(url)
        state.bytes = body
        state.failed = body == null
    }
    return state
}
