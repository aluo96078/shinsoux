package dev.shinsou.kmp.sync.provisioning

import dev.shinsou.kmp.sync.v2.EphemeralSyncPayload
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncOneTimeLinkCodecTest {
    @Test
    fun secretlessSetupMetadataRoundTripsWithoutInventingASecret() {
        val payload = ParsedSyncOneTimePayload(
            SyncOneTimeAction.SETUP,
            ENDPOINT,
            INSTANCE_ID,
            sessionId = null,
            secret = null,
        )
        val link = SyncOneTimeLinkCodec.encodeLink(payload).use { it }
        val parsed = SyncOneTimeLinkCodec.parse(link)

        assertEquals(payload.action, parsed.action)
        assertEquals(ENDPOINT, parsed.endpoint)
        assertEquals(INSTANCE_ID, parsed.instanceId)
        assertNull(parsed.secret)
        assertTrue("secret=" !in link)
    }

    @Test
    fun manualPairCodeRoundTripsAndAllDebugStringsAreRedacted() = runTest {
        val secret = "cGFpcmluZy1zZWNyZXQtMzItYnl0ZXMtbG9uZyEhISE"
        val payload = ParsedSyncOneTimePayload(
            SyncOneTimeAction.PAIR,
            ENDPOINT,
            INSTANCE_ID,
            PAIRING_ID,
            EphemeralSyncPayload(secret),
        )
        val manual = SyncOneTimeLinkCodec.encodeManualCode(payload).use { it }
        val parsed = SyncOneTimeLinkCodec.parse(manual)

        assertEquals(secret, parsed.secret?.use { it })
        assertTrue(secret !in parsed.toString())
        assertTrue(secret !in payload.toString())
    }

    @Test
    fun rejectsDuplicateFieldsAndSecretlessInviteOrPair() {
        assertFailsWith<SyncProvisioningException> {
            SyncOneTimeLinkCodec.parse(
                "shinsou://sync/setup?endpoint=https%3A%2F%2Fsync.example.test&endpoint=https%3A%2F%2Fevil.test",
            )
        }
        assertFailsWith<SyncProvisioningException> {
            SyncOneTimeLinkCodec.parse("shinsou://sync/invite?endpoint=https%3A%2F%2Fsync.example.test")
        }
        assertFailsWith<SyncProvisioningException> {
            SyncOneTimeLinkCodec.parse(
                "shinsou://sync/pair?endpoint=https%3A%2F%2Fsync.example.test&session=$PAIRING_ID",
            )
        }
    }

    @Test
    fun emergencyResetRoundTripsEveryNonSecretBindingAndRedactsHandoff() {
        val secret = "cGFydGljdWxhcmx5LXNlY3VyZS1oYW5kb2ZmLXNlY3JldA"
        val payload = ParsedSyncOneTimePayload(
            action = SyncOneTimeAction.EMERGENCY_RESET,
            endpoint = ENDPOINT,
            instanceId = INSTANCE_ID,
            sessionId = RESET_ID,
            secret = EphemeralSyncPayload(secret),
            userId = USER_ID,
            workspaceId = WORKSPACE_ID,
        )
        val encoded = SyncOneTimeLinkCodec.encodeLink(payload).use { it }
        val parsed = SyncOneTimeLinkCodec.parse(encoded)

        assertEquals(SyncOneTimeAction.EMERGENCY_RESET, parsed.action)
        assertEquals(RESET_ID, parsed.sessionId)
        assertEquals(USER_ID, parsed.userId)
        assertEquals(WORKSPACE_ID, parsed.workspaceId)
        assertEquals(secret, parsed.secret?.use { it })
        assertTrue(secret !in parsed.toString())
    }

    @Test
    fun emergencyResetRejectsMissingOwnerBinding() {
        assertFailsWith<SyncProvisioningException> {
            SyncOneTimeLinkCodec.parse(
                "shinsou://sync/emergency-reset?endpoint=https%3A%2F%2Fsync.example.test" +
                    "&instance=$INSTANCE_ID&session=$RESET_ID&workspace=$WORKSPACE_ID" +
                    "&secret=cGFydGljdWxhcmx5LXNlY3VyZS1oYW5kb2ZmLXNlY3JldA",
            )
        }
    }

    private companion object {
        const val ENDPOINT = "https://sync.example.test"
        const val INSTANCE_ID = "00000000-0000-4000-8000-000000000001"
        const val PAIRING_ID = "00000000-0000-4000-8000-000000000013"
        const val RESET_ID = "00000000-0000-4000-8000-000000000020"
        const val USER_ID = "00000000-0000-4000-8000-000000000002"
        const val WORKSPACE_ID = "00000000-0000-4000-8000-000000000007"
    }
}
