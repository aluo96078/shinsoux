package dev.shinsou.kmp.plugin.shuyue

import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.plugin.PluginKeyValueStore
import dev.shinsou.kmp.plugin.Sha256
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

        val failure = assertFailsWith<ShuYueAdmissionException.CorruptQuarantine> {
            KeyValueShuYueReviewedStoreV2(backing).get(record.quarantineId)
        }
        assertEquals("Stored ShuYue quarantine could not be decoded safely.", failure.message)
    }

    @Test
    fun unknownDurableQuarantineEnumsFailClosedWithSafeDiagnostic() = runTest {
        val backing = RecordingKeyValueStore()
        val bytes = "reviewed script".encodeToByteArray()
        val record = ShuYueQuarantinedScriptV2(
            quarantineId = "unknown-enum-quarantine",
            identity = identity(bytes, versionCode = 2),
            sourceIds = listOf("fixture-source"),
            bytes = bytes,
            provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
            stagedReviewStatus = ShuYueReviewStatusV2.REVIEWED,
        )
        val store = KeyValueShuYueReviewedStoreV2(backing)
        store.put(record)
        val key = "plugin.shuyue.v2.quarantine.${Sha256.hex(record.quarantineId.encodeToByteArray())}"
        val encoded = requireNotNull(backing.values[key])

        listOf(
            "\"provenance\":\"REVIEWED_REPOSITORY\"" to "\"provenance\":\"UNTRUSTED_IMPORT\"",
            "\"reviewStatus\":\"REVIEWED\"" to "\"reviewStatus\":\"UNSAFE_STATUS\"",
        ).forEach { (knownValue, unknownValue) ->
            backing.values[key] = encoded.replace(knownValue, unknownValue)
            val failure = assertFailsWith<ShuYueAdmissionException.CorruptQuarantine> {
                KeyValueShuYueReviewedStoreV2(backing).get(record.quarantineId)
            }
            assertEquals("Stored ShuYue quarantine could not be decoded safely.", failure.message)
        }
    }

    @Test
    fun fileBackedQuarantineLeavesSiblingInstallationsIntact() = runTest {
        val backing = RecordingKeyValueStore()
        val files = RecordingFileSystem()
        val store = KeyValueShuYueReviewedStoreV2(backing, files)
        val firstBytes = ByteArray(64 * 1_024) { 0x11 }
        val secondBytes = ByteArray(64 * 1_024) { 0x22 }
        val first = record("file-quarantine-a", firstBytes, versionCode = 1, packageId = "fixture.shuyue.a")
        val second = record("file-quarantine-b", secondBytes, versionCode = 2, packageId = "fixture.shuyue.b")
        store.put(first)
        store.put(second)
        store.putInstalled(ShuYueReviewedInstallationV2(first.quarantineId, first.identity))
        store.putInstalled(ShuYueReviewedInstallationV2(second.quarantineId, second.identity))

        assertTrue(backing.values.keys.none { it.startsWith("plugin.shuyue.v2.quarantine.") })
        assertEquals(2, files.values.size)
        assertEquals(2, store.listInstalled().size)
        assertContentEquals(firstBytes, store.get(first.quarantineId)?.copyBytes())
        assertContentEquals(secondBytes, store.get(second.quarantineId)?.copyBytes())
    }

    @Test
    fun corruptInstallationsFailClosedWithoutErasingTheDurableRecord() = runTest {
        val backing = RecordingKeyValueStore()
        val store = KeyValueShuYueReviewedStoreV2(backing)
        val bytes = "reviewed script".encodeToByteArray()
        val record = record("install-quarantine", bytes, versionCode = 3)
        store.put(record)
        store.putInstalled(ShuYueReviewedInstallationV2(record.quarantineId, record.identity))
        backing.values["plugin.shuyue.v2.installations"] = "{not-json"
        val failure = assertFailsWith<ShuYueAdmissionException.CorruptInstallations> {
            KeyValueShuYueReviewedStoreV2(backing).listInstalled()
        }
        assertEquals("Stored ShuYue installation records could not be decoded safely.", failure.message)
        assertEquals("{not-json", backing.values["plugin.shuyue.v2.installations"])
        assertFailsWith<ShuYueAdmissionException.CorruptInstallations> {
            KeyValueShuYueReviewedStoreV2(backing).getInstalled(record.identity.packageId)
        }
    }

    @Test
    fun oversizedInstallationsFailClosedInsteadOfLookingUninstalled() = runTest {
        val backing = RecordingKeyValueStore()
        backing.values["plugin.shuyue.v2.installations"] = "x".repeat(2 * 1_024 * 1_024)
        assertFailsWith<ShuYueAdmissionException.CorruptInstallations> {
            KeyValueShuYueReviewedStoreV2(backing).listInstalled()
        }
        assertEquals("x".repeat(2 * 1_024 * 1_024), backing.values["plugin.shuyue.v2.installations"])
    }

    private fun record(
        quarantineId: String,
        bytes: ByteArray,
        versionCode: Int,
        packageId: String = "fixture.shuyue",
    ): ShuYueQuarantinedScriptV2 =
        ShuYueQuarantinedScriptV2(
            quarantineId = quarantineId,
            identity = identity(bytes, versionCode, packageId),
            sourceIds = listOf("fixture-source"),
            bytes = bytes,
            provenance = ShuYueScriptProvenanceV2.REVIEWED_REPOSITORY,
            stagedReviewStatus = ShuYueReviewStatusV2.REVIEWED,
        )

    private fun identity(
        bytes: ByteArray,
        versionCode: Int,
        packageId: String = "fixture.shuyue",
    ): ShuYueArtifactIdentityV2 =
        ShuYueArtifactIdentityV2(
            packageId = packageId,
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

    private class RecordingFileSystem : AppFileSystem {
        val values = linkedMapOf<String, ByteArray>()
        override suspend fun write(relativePath: String, bytes: ByteArray) {
            values[relativePath] = bytes.copyOf()
        }
        override suspend fun writeAtomically(relativePath: String, bytes: ByteArray) = write(relativePath, bytes)
        override suspend fun read(relativePath: String): ByteArray? = values[relativePath]?.copyOf()
        override suspend fun exists(relativePath: String): Boolean = relativePath in values
        override suspend fun delete(relativePath: String): Boolean = values.remove(relativePath) != null
        override suspend fun deleteTree(relativeDirectory: String): Boolean {
            val prefix = relativeDirectory.trimEnd('/') + "/"
            val removed = values.keys.filter { it == relativeDirectory || it.startsWith(prefix) }
            removed.forEach(values::remove)
            return removed.isNotEmpty()
        }
        override suspend fun list(relativeDirectory: String): List<String> {
            val prefix = relativeDirectory.trimEnd('/') + "/"
            return values.keys.filter { it.startsWith(prefix) }
        }
        override fun uri(relativePath: String): String = "memory://$relativePath"
    }
}
