@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.shinsou.kmp.plugin

import dev.shinsou.interop.network.shinsou_receive_default_context_probe
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosNetworkReceiveInteropTest {
    @Test
    fun appleDefaultContextNeverCrossesTheKotlinObjectBridge() {
        var calls = 0
        shinsou_receive_default_context_probe { data, complete, error ->
            assertNull(data)
            assertNull(error)
            assertTrue(complete)
            calls++
        }
        assertEquals(1, calls)
    }
}
