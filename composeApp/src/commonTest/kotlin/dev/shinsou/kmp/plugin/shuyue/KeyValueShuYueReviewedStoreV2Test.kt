package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyValueShuYueReviewedStoreV2Test {
    @Test
    fun exactQuarantineAndApprovalSurviveStoreRecreationButNeverFlowToAnotherIdentity() = runTest {
        val backing = RecordingKeyValueStore()
        val bytes = "durable reviewed script".encodeToByteArray()
        val identity = identity(bytes, versionCode = 7)
        val record = ShuYueQuarantinedScriptV2(
            quarantineId = "fixture-quarantine",
            identity = identity,
            sourceIds = listOf("fixture-source"),
            bytes = bytes,
            provenance = ShuYueScriptProvenanceV2.LEGACY_BACKUP,
            stagedReviewStatus = ShuYueReviewStatusV2.REVIEWED,
        )
        val permissions = setOf(
            ShuYueExecutionPermissionV2.EXECUTE_SCRIPT,
            ShuYueExecutionPermissionV2.NETWORK,
        )
        KeyValueShuYueReviewedStoreV2(backing).also { first ->
            first.put(record)
            first.approve(identity, permissions)
        }

        val reopened = KeyValueShuYueReviewedStoreV2(backing)
        val restored = reopened.get(record.quarantineId)
        assertEquals(identity, restored?.identity)
        assertContentEquals(bytes, restored?.copyBytes())
        assertTrue(reopened.isTrusted(identity))
        assertEquals(permissions, reopened.grantedPermissions(identity))

        val newerVersion = identity(bytes, versionCode = identity.versionCode + 1)
        val changedDigest = identity("different bytes".encodeToByteArray(), versionCode = identity.versionCode)
        listOf(newerVersion, changedDigest).forEach { changed ->
            assertFalse(reopened.isTrusted(changed))
            assertTrue(reopened.grantedPermissions(changed).isEmpty())
        }
    }

    @Test
    fun corruptDurableQuarantineFailsClosedWithoutReturningBytes() = runTest {
        val backing = RecordingKeyValueStore()
        val bytes = "reviewed script".encodeToByteArray()
        val record = ShuYueQuarantinedScriptV2(
            quarantineId = "corrupt-quarantine",
            identity = identity(bytes, versionCode = 1),
            sourceIds = listOf("fixture-source"),
            bytes = bytes,
            provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
            stagedReviewStatus = ShuYueReviewStatusV2.REVIEWED,
        )
        val store = KeyValueShuYueReviewedStoreV2(backing)
        store.put(record)
        val key = "plugin.shuyue.v2.quarantine.${Sha256.hex(record.quarantineId.encodeToByteArray())}"
        val encoded = requireNotNull(backing.values[key])
        backing.values[key] = encoded.replace(
            oldValue = Sha256.hex(bytes),
            newValue = "0".repeat(64),
        )

        assertNull(KeyValueShuYueReviewedStoreV2(backing).get(record.quarantineId))
    }

    private fun identity(bytes: ByteArray, versionCode: Int): ShuYueArtifactIdentityV2 =
        ShuYueArtifactIdentityV2(
            packageId = "fixture.shuyue",
            version = "1.0.0",
            versionCode = versionCode,
            sha256 = Sha256.hex(bytes),
        )

    private class RecordingKeyValueStore : PluginKeyValueStore {
        val values = linkedMapOf<String, String>()
        override suspend fun getString(key: String): String? = values[key]
        override suspend fun putString(key: String, value: String) {
            values[key] = value
        }
        override suspend fun remove(key: String) {
            values.remove(key)
        }
    }
}
