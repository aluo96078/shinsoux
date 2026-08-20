package dev.shinsou.kmp.sync.v2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SyncEntityKeyTest {
    @Test
    fun urlNormalizationIsStableWithoutChangingOpaqueQueryOrder() {
        val key = SyncEntityKey.manga(
            sourceIdentity = " Plugin.Example ",
            urlOrCanonicalId = "HTTPS://EXAMPLE.com:443/a/../Series/%7eOne/?b=2&a=%2f#fragment",
        )

        assertEquals("source:plugin.example", key.namespace)
        assertEquals("https://example.com/Series/~One/?b=2&a=%2F", key.canonicalValue)
        assertEquals(key, SyncEntityKey.manga("plugin.example", key.canonicalValue))
    }

    @Test
    fun extensionRepositoryRequiresHttpsAndCategoryNameIsNotIdentity() {
        assertFailsWith<IllegalArgumentException> {
            SyncEntityKey.extensionRepository("http://repo.example/index.json")
        }
        val first = SyncEntityKey.category("550E8400-E29B-41D4-A716-446655440000")
        val renamed = SyncEntityKey.category("550e8400-e29b-41d4-a716-446655440000")
        assertEquals(first, renamed)
        assertNotEquals(first, SyncEntityKey.category("another-portable-id"))
    }

    @Test
    fun identityMapRejectsBothDirectionsOfCollisionAndSupportsVersionRemap() {
        val v1 = SyncEntityKey.manga("1", "/manga", version = 1)
        val other = SyncEntityKey.manga("1", "/other", version = 1)
        val v2 = SyncEntityKey.manga("1", "/canonical/manga", version = 2)
        val bound = SyncIdentityMap().bind(v1, 7)

        assertFailsWith<SyncIdentityCollisionException> { bound.bind(v1, 8) }
        assertFailsWith<SyncIdentityCollisionException> { bound.bind(other, 7) }

        val remapped = bound.remap(v1, v2)
        assertEquals(7, remapped.localId(v2))
        assertEquals(null, remapped.localId(v1))
        assertTrue(v1 !in remapped.blockedKeys)
    }

    @Test
    fun wireRemapStaysVersionStrictWhileInternalAliasRelocationCanFollowDeterministicForkWinner() {
        val lowerV2 = SyncEntityKey.manga("1", "/lower", version = 2)
        val higherV2 = SyncEntityKey.manga("1", "/higher", version = 2)
        val (loser, winner) = listOf(lowerV2, higherV2).sorted()
        val bound = SyncIdentityMap().bind(loser, 7)

        assertFailsWith<IllegalArgumentException> { bound.remap(loser, winner) }
        assertFailsWith<IllegalArgumentException> { bound.relocateCanonicalAlias(winner, loser) }

        val relocated = bound.relocateCanonicalAlias(loser, winner)
        assertEquals(7, relocated.localId(winner))
        assertEquals(null, relocated.localId(loser))
    }
}
