package dev.shinsou.kmp.sync.trust

import dev.shinsou.kmp.sync.v2.BootstrapResponse
import dev.shinsou.kmp.sync.v2.CatchUpPage
import dev.shinsou.kmp.sync.v2.CloudflareSyncApi
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.WorkspaceCapability

/**
 * Production trust boundary for every encrypted server download.
 *
 * Bootstrap may advance the durable full-directory pin after verifying its complete attestation
 * chain. Catch-up pages can only prove the exact sender subset at that already-pinned revision;
 * when a page references a newer revision, a full bootstrap is verified before the page is
 * returned to SyncEngine/SyncCheckpointCoordinator. Consequently SyncCrypto never receives an
 * envelope whose sender key came directly from an untrusted response.
 */
class TrustVerifyingCloudflareSyncApi(
    private val delegate: CloudflareSyncApi,
    private val directoryVerifier: DeviceDirectoryVerifier,
    private val trustContext: suspend (SyncSession) -> DeviceDirectoryTrustContext,
) : CloudflareSyncApi by delegate {
    override suspend fun bootstrap(
        session: SyncSession,
        capability: WorkspaceCapability,
    ): BootstrapResponse {
        val response = delegate.bootstrap(session, capability)
        verifyFullDirectory(session, response)
        return response
    }

    override suspend fun catchUp(
        session: SyncSession,
        capability: WorkspaceCapability,
        afterExclusive: Long,
        untilInclusive: Long?,
        limit: Int,
    ): CatchUpPage {
        val page = delegate.catchUp(session, capability, afterExclusive, untilInclusive, limit)
        val directory = page.senderDeviceDirectory
            ?: throw DeviceDirectoryTrustException.Malformed(
                "Catch-up response omitted its authenticated sender directory",
            )
        val expectedSenders = page.events.mapTo(linkedSetOf()) { it.envelope.header.deviceId }
        try {
            directoryVerifier.verifyPinnedSenderSubset(session.workspaceId, directory, expectedSenders)
        } catch (required: DeviceDirectoryTrustException.FullDirectoryRequired) {
            val bootstrap = delegate.bootstrap(session, capability)
            verifyFullDirectory(session, bootstrap)
            directoryVerifier.verifyPinnedSenderSubset(session.workspaceId, directory, expectedSenders)
        }
        return page
    }

    private suspend fun verifyFullDirectory(session: SyncSession, response: BootstrapResponse) {
        val directory = response.deviceDirectory
            ?: throw DeviceDirectoryTrustException.Malformed(
                "Bootstrap response omitted the full authenticated device directory",
            )
        val context = trustContext(session)
        if (context.instanceId != session.instanceId || context.workspaceId != session.workspaceId) {
            throw DeviceDirectoryTrustException.Malformed("Device directory trust context crossed a tenant boundary")
        }
        directoryVerifier.verifyAndPinFullDirectory(context, directory)
    }
}
