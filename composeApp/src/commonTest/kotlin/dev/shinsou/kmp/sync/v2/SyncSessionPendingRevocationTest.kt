package dev.shinsou.kmp.sync.v2

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SyncSessionPendingRevocationTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun pendingRevocationRoundTripsAsNonSecretReadySessionMetadata() {
        val pending = PendingDeviceRevocation(REVOCATION_ID, TARGET_DEVICE_ID)
        val encoded = json.encodeToString(session().copy(pendingDeviceRevocation = pending))
        val decoded = json.decodeFromString<SyncSession>(encoded)

        assertEquals(pending, decoded.pendingDeviceRevocation)
        assertEquals(SyncSessionStatus.READY, decoded.status)
        assertFalse("token" in encoded.lowercase())
        assertFalse("signature" in encoded.lowercase())
    }

    @Test
    fun pendingRevocationRejectsNonCanonicalOrSelfIdentityAndNonReadyState() {
        assertFailsWith<IllegalArgumentException> {
            PendingDeviceRevocation(REVOCATION_ID.uppercase(), TARGET_DEVICE_ID)
        }
        assertFailsWith<IllegalArgumentException> {
            session().copy(pendingDeviceRevocation = PendingDeviceRevocation(REVOCATION_ID, DEVICE_ID))
        }
        assertFailsWith<IllegalArgumentException> {
            session().copy(
                status = SyncSessionStatus.ERROR,
                pendingDeviceRevocation = PendingDeviceRevocation(REVOCATION_ID, TARGET_DEVICE_ID),
            )
        }
    }

    private fun session() = SyncSession(
        endpoint = "https://sync.example.test",
        instanceId = INSTANCE_ID,
        userId = USER_ID,
        workspaceId = WORKSPACE_ID,
        deviceId = DEVICE_ID,
        deviceDisplayName = "Phone",
        platform = "ios",
        status = SyncSessionStatus.READY,
        deviceAuthEpoch = 1,
        membershipAuthEpoch = 1,
        activeKeyEpoch = 1,
    )

    private companion object {
        const val INSTANCE_ID = "00000000-0000-4000-8000-000000000001"
        const val USER_ID = "00000000-0000-4000-8000-000000000002"
        const val WORKSPACE_ID = "00000000-0000-4000-8000-000000000003"
        const val DEVICE_ID = "00000000-0000-4000-8000-000000000004"
        const val TARGET_DEVICE_ID = "00000000-0000-4000-8000-000000000005"
        const val REVOCATION_ID = "00000000-0000-4000-8000-000000000a06"
    }
}
