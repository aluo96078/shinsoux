package dev.shinsou.kmp.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals

class MoreModesTest {
    @Test
    fun activeModesUseStableOriginalAppOrder() {
        assertEquals(emptyList(), activeMoreModes(incognitoMode = false, downloadOnlyMode = false))
        assertEquals(
            listOf(MoreActiveMode.INCOGNITO),
            activeMoreModes(incognitoMode = true, downloadOnlyMode = false),
        )
        assertEquals(
            listOf(MoreActiveMode.DOWNLOAD_ONLY),
            activeMoreModes(incognitoMode = false, downloadOnlyMode = true),
        )
        assertEquals(
            listOf(MoreActiveMode.INCOGNITO, MoreActiveMode.DOWNLOAD_ONLY),
            activeMoreModes(incognitoMode = true, downloadOnlyMode = true),
        )
    }
}
