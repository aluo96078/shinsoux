package dev.shinsou.kmp.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isBackPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.shinsou.kmp.ui.reader.ReaderVolumeKeyEvent

/**
 * The shell's own keys: hardware volume buttons go to the open reader, and Escape, Back and the
 * mouse's back button go back.
 */
internal fun Modifier.shellInput(
    app: ShinsouAppState,
    interceptVolumeKeys: Boolean,
    backKey: HardwareBackKeyLatch,
): Modifier = onPreviewKeyEvent { event ->
    val volumeEvent = when (event.key) {
        Key.VolumeUp -> ReaderVolumeKeyEvent.VOLUME_UP
        Key.VolumeDown -> ReaderVolumeKeyEvent.VOLUME_DOWN
        else -> null
    }
    if (volumeEvent != null) {
        handleVolumeKey(app, event, volumeEvent, interceptVolumeKeys)
    } else {
        handleBackKey(app, event, backKey)
    }
}.pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press && event.buttons.isBackPressed && app.systemBack()) {
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * LiveContainer delivers hardware volume buttons as HID to the focused Compose view. Manga and
 * image readers do not always reclaim focus, so the app surface owns this path and forwards it to
 * the mounted reader.
 */
private fun handleVolumeKey(
    app: ShinsouAppState,
    event: KeyEvent,
    volumeEvent: ReaderVolumeKeyEvent,
    interceptVolumeKeys: Boolean,
): Boolean = when {
    !interceptVolumeKeys -> false
    event.type == KeyEventType.KeyUp -> {
        app.volumeKeyPressTracker.release(volumeEvent)
        true
    }
    event.type != KeyEventType.KeyDown -> false
    !app.volumeKeyPressTracker.shouldDispatchDown(volumeEvent, repeatCount = 0) -> true
    else -> {
        app.volumeKeyRouter.dispatch(volumeEvent)
        true
    }
}

private fun handleBackKey(app: ShinsouAppState, event: KeyEvent, backKey: HardwareBackKeyLatch): Boolean {
    val isBackKey = event.key == Key.Escape || event.key == Key.Back || event.key == Key.NavigatePrevious
    return when {
        !isBackKey -> false
        event.type == KeyEventType.KeyUp && backKey.held -> {
            backKey.held = false
            true
        }
        event.type == KeyEventType.KeyDown && backKey.held -> true
        event.type == KeyEventType.KeyDown -> app.systemBack().also { handled -> backKey.held = handled }
        else -> false
    }
}
