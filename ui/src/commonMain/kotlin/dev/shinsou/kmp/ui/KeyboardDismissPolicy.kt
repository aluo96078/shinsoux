package dev.shinsou.kmp.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

internal val LocalMobileKeyboardDismissEnabled = staticCompositionLocalOf { false }

/**
 * Decides whether a completed pointer gesture represents a mobile background tap.
 *
 * The pointer observer that calls this function must not consume any pointer changes. A child
 * input, button, or card can then keep ownership of its tap, while an unhandled tap on otherwise
 * empty mobile content clears focus and hides the software keyboard. Desktop is deliberately
 * excluded so a mouse click does not discard hardware-keyboard focus.
 */
internal fun shouldDismissKeyboardAfterPointerGesture(
    isMobile: Boolean,
    childConsumed: Boolean,
    movedBeyondTouchSlop: Boolean,
): Boolean = isMobile && !childConsumed && !movedBeyondTouchSlop

/**
 * Observes a complete gesture at the Final pass without consuming it. Descendant text fields,
 * buttons, cards and scroll containers therefore retain ownership of their pointer input.
 */
internal fun Modifier.onUnconsumedBlankTap(
    enabled: Boolean,
    onBlankTap: () -> Unit,
): Modifier {
    if (!enabled) return this
    return pointerInput(enabled) {
        awaitPointerEventScope {
            var trackingBlankTap = false
            var childConsumed = false
            var movedBeyondTouchSlop = false
            var downX = 0f
            var downY = 0f
            val touchSlopSquared = viewConfiguration.touchSlop * viewConfiguration.touchSlop

            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                val singleChange = event.changes.singleOrNull()

                if (event.type == PointerEventType.Press) {
                    trackingBlankTap = singleChange != null
                    childConsumed = singleChange?.isConsumed == true
                    movedBeyondTouchSlop = false
                    downX = singleChange?.position?.x ?: 0f
                    downY = singleChange?.position?.y ?: 0f
                } else if (trackingBlankTap && singleChange != null) {
                    childConsumed = childConsumed || singleChange.isConsumed
                    val deltaX = singleChange.position.x - downX
                    val deltaY = singleChange.position.y - downY
                    if (deltaX * deltaX + deltaY * deltaY > touchSlopSquared) {
                        movedBeyondTouchSlop = true
                    }
                } else if (singleChange == null) {
                    trackingBlankTap = false
                }

                if (event.type == PointerEventType.Release) {
                    if (
                        trackingBlankTap &&
                        shouldDismissKeyboardAfterPointerGesture(
                            isMobile = true,
                            childConsumed = childConsumed,
                            movedBeyondTouchSlop = movedBeyondTouchSlop,
                        )
                    ) {
                        onBlankTap()
                    }
                    trackingBlankTap = false
                } else if (event.changes.isNotEmpty() && event.changes.none { it.pressed }) {
                    trackingBlankTap = false
                }
            }
        }
    }
}

/** Mobile-only convenience used by Dialog and bottom-sheet surfaces. */
@Composable
internal fun Modifier.dismissKeyboardOnMobileBlankTap(): Modifier {
    val enabled = LocalMobileKeyboardDismissEnabled.current
    val focusManager = LocalFocusManager.current
    val softwareKeyboardController = LocalSoftwareKeyboardController.current
    val dismissInput by rememberUpdatedState<() -> Unit> {
        focusManager.clearFocus(force = true)
        softwareKeyboardController?.hide()
    }
    return onUnconsumedBlankTap(enabled = enabled) { dismissInput() }
}
