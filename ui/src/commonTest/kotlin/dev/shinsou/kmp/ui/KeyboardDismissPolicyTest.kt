package dev.shinsou.kmp.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeyboardDismissPolicyTest {
    @Test
    fun mobileBackgroundTapDismissesKeyboard() {
        assertTrue(
            shouldDismissKeyboardAfterPointerGesture(
                isMobile = true,
                childConsumed = false,
                movedBeyondTouchSlop = false,
            ),
        )
    }

    @Test
    fun textFieldButtonOrCardTapStaysWithChild() {
        assertFalse(
            shouldDismissKeyboardAfterPointerGesture(
                isMobile = true,
                childConsumed = true,
                movedBeyondTouchSlop = false,
            ),
        )
    }

    @Test
    fun scrollGestureDoesNotDismissKeyboard() {
        assertFalse(
            shouldDismissKeyboardAfterPointerGesture(
                isMobile = true,
                childConsumed = false,
                movedBeyondTouchSlop = true,
            ),
        )
    }

    @Test
    fun desktopPointerGestureNeverDiscardsHardwareKeyboardFocus() {
        for (childConsumed in listOf(false, true)) {
            for (movedBeyondTouchSlop in listOf(false, true)) {
                assertFalse(
                    shouldDismissKeyboardAfterPointerGesture(
                        isMobile = false,
                        childConsumed = childConsumed,
                        movedBeyondTouchSlop = movedBeyondTouchSlop,
                    ),
                )
            }
        }
    }

    @Test
    fun policyTruthTableHasExactlyOneDismissCase() {
        for (isMobile in listOf(false, true)) {
            for (childConsumed in listOf(false, true)) {
                for (movedBeyondTouchSlop in listOf(false, true)) {
                    assertEquals(
                        isMobile && !childConsumed && !movedBeyondTouchSlop,
                        shouldDismissKeyboardAfterPointerGesture(
                            isMobile = isMobile,
                            childConsumed = childConsumed,
                            movedBeyondTouchSlop = movedBeyondTouchSlop,
                        ),
                    )
                }
            }
        }
    }
}
