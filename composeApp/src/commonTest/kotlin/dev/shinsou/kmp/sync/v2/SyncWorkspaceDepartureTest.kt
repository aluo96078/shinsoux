package dev.shinsou.kmp.sync.v2

import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentCommitSemantics
import dev.shinsou.kmp.content.ContentMetadataMutation
import dev.shinsou.kmp.content.ContentPublicationReplicaCursor
import dev.shinsou.kmp.content.ContentPublicationReplicaReplacement
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.InMemoryContentBlobStore
import dev.shinsou.kmp.content.InMemorySharedContentTransactionStore
import dev.shinsou.kmp.content.SyncDraftContentOutboxAdapter
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.sync.trust.DeviceDirectoryPinStore
import dev.shinsou.kmp.sync.trust.DeviceDirectoryRevision
import dev.shinsou.kmp.sync.trust.PinnedDeviceDirectory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncWorkspaceDepartureTest {
    @Test
    fun departureStopsProducersDeletesSecretsAndResetsWorkspaceState() = runTest {
        val session = SyncSession(
            endpoint = "https://sync.example",
            instanceId = "instance",
            userId = "user",
            workspaceId = "workspace",
            deviceId = "device",
            deviceDisplayName = "Phone",
            platform = "ios",
            status = SyncSessionStatus.READY,
            deviceAuthEpoch = 1,
            membershipAuthEpoch = 1,
            activeKeyEpoch = 2,
        )
        val sessionStore = InMemorySyncSessionStore(session)
        val secretStore = InMemorySyncSecretStore()
        val localStore = InMemoryLocalSyncStore()
        localStore.transaction {
            retainKeyEpoch(KeyEpochMetadata(1, "workspace-epoch-1", KeyEpochStatus.RETAINED, 1))
            retainKeyEpoch(KeyEpochMetadata(2, "workspace-epoch-2", KeyEpochStatus.ACTIVE, 2))
            val hlc = nextLocalHlc("device", 10)
            applyLocalEvent(
                SyncEvent(
                    opId = "pending",
                    hlc = hlc,
                    mutations = listOf(PortableSettingPatch(mapOf("appearance.theme" to SyncValue.StringValue("DARK")))),
                ),
                nowMillis = 10,
            )
        }
        val keys = listOf(
            SyncSecretKey.WorkspaceEpochKey("workspace", 1),
            SyncSecretKey.WorkspaceEpochKey("workspace", 2),
            SyncSecretKey.WorkspaceCapability("workspace"),
            SyncSecretKey.AccessToken,
            SyncSecretKey.DeviceCredential,
            SyncSecretKey.PendingBootstrapSecret,
            SyncSecretKey.PendingInvitePayload,
            SyncSecretKey.PendingPairingPayload,
            SyncSecretKey.DeviceSigningPrivateKey,
            SyncSecretKey.DeviceWrappingPrivateKey,
            SyncSecretKey.RecoverySigningPrivateKey,
            SyncSecretKey.RecoveryWrappingPrivateKey,
            SyncSecretKey.PendingRecoverySigningPrivateKey,
            SyncSecretKey.PendingRecoveryWrappingPrivateKey,
        )
        keys.forEach { secretStore.write(it, SecretMaterial(listOf(1, 2, 3))) }
        val contentStore = InMemorySharedContentTransactionStore(
            blobStore = InMemoryContentBlobStore(),
            outboxAdapter = SyncDraftContentOutboxAdapter,
            syncModeProvider = { ContentSyncMode.V2_ACTIVE },
        )
        val replicaCursor = ContentPublicationReplicaCursor(
            publicationKey = PublicationKey("11111111-1111-4111-8111-111111111111"),
            instanceId = session.instanceId,
            workspaceId = session.workspaceId,
            throughWorkspaceSeq = 7,
            present = false,
            graphFingerprintSha256 = "a".repeat(64),
        )
        val replacement = ContentPublicationReplicaReplacement(
            expected = null,
            replacement = replicaCursor,
        )
        contentStore.commit(
            ContentCommitBatch(
                commitId = replacement.commitId,
                replicaReplacement = replacement,
                semantics = ContentCommitSemantics.REPLACE_PUBLICATION_REPLICA,
            ),
        )
        contentStore.commit(
            ContentCommitBatch(
                commitId = "local-content-metadata",
                metadata = listOf(ContentMetadataMutation("local/bookmark", "retained")),
            ),
        )
        var stopped = false
        var completed = false
        var clearedPinWorkspace: String? = null
        val pinStore = object : DeviceDirectoryPinStore {
            override suspend fun load(workspaceId: String): PinnedDeviceDirectory? = null
            override suspend fun compareAndSet(
                workspaceId: String,
                expected: DeviceDirectoryRevision?,
                updated: PinnedDeviceDirectory,
            ): Boolean = error("unused")

            override suspend fun clear(workspaceId: String) {
                clearedPinWorkspace = workspaceId
            }
        }
        val departure = LocalSyncWorkspaceDeparture(
            sessionStore = sessionStore,
            secretStore = secretStore,
            localStore = localStore,
            deviceDirectoryPinStore = pinStore,
            contentStore = contentStore,
            stopMutationProducers = { stopped = true },
            afterDeparture = { completed = true },
        )

        departure.leaveWorkspace()

        assertTrue(stopped && completed)
        assertEquals(null, sessionStore.load())
        assertEquals(LocalSyncStoreState(), localStore.readState())
        assertEquals("workspace", clearedPinWorkspace)
        assertNull(contentStore.publicationReplicaCursor(replicaCursor.publicationKey))
        assertFalse(replacement.commitId in contentStore.state.committedIds)
        assertEquals(
            mapOf("local/bookmark" to "retained"),
            contentStore.state.metadata,
        )
        assertTrue("local-content-metadata" in contentStore.state.committedIds)
        keys.forEach { key -> assertIs<SyncSecretReadResult.Missing>(secretStore.read(key)) }
    }
}
