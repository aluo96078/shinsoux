package dev.shinsou.kmp.plugin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginJsonShapeTest {
    private fun ok(json: String, total: Int = 100, arr: Int = 10, obj: Int = 10, depth: Int = 4) =
        pluginJsonShapeWithin(json, total, arr, obj, depth)

    @Test
    fun acceptsOrdinaryValuesWithinBudget() {
        assertTrue(ok("[]"))
        assertTrue(ok("[1, \"a\", true, null, {\"k\": [1,2]}]"))
        assertTrue(ok("\"just a string with [ { , : ] inside\""))
        assertTrue(ok("[\"esc \\\" quote\", 2]"))
    }

    @Test
    fun countsEveryValueAcrossContainers() {
        assertTrue(ok("[[1,2],[3,4]]", total = 7))
        assertFalse(ok("[[1,2],[3,4]]", total = 6))
        // Keys count as values: {"a":1} is two values plus the object.
        assertTrue(ok("{\"a\":1}", total = 3))
        assertFalse(ok("{\"a\":1}", total = 2))
    }

    @Test
    fun enforcesPerContainerAndDepthLimits() {
        assertTrue(ok("[1,2,3]", arr = 3))
        assertFalse(ok("[1,2,3,4]", arr = 3))
        assertFalse(ok("{\"a\":1,\"b\":2,\"c\":3}", obj = 2))
        assertTrue(ok("[[[[]]]]", depth = 4))
        assertFalse(ok("[[[[[]]]]]", depth = 4))
    }

    @Test
    fun rejectsUnbalancedOrEmptyElementShapes() {
        assertFalse(ok("["))
        assertFalse(ok("[1,,2]"))
        assertFalse(ok("[}"))
        assertFalse(ok("\"unterminated"))
        assertFalse(ok("]"))
    }
}
