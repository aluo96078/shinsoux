package dev.shinsou.kmp.content.access

import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.InMemoryRightsAuthority
import dev.shinsou.kmp.rights.ProtectionAuthorization
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsScope

/** Exact host-owned authorization input for publishing one immutable content body locally. */
public data class PendingContentBodyStoreRequest(
    val grant: RightsGrant,
    val scope: RightsScope,
    val byteCount: Long,
    val protectionAuthorization: ProtectionAuthorization? = null,
) {
    init {
        grant.validate()
        scope.validate()
        require(grant.scope.covers(scope)) { "Content body scope exceeds its proposed rights grant" }
        require(byteCount >= 0) { "Content body byte count must be non-negative" }
    }

    internal fun access(): ContentAccessRequest = ContentAccessRequest(
        grantReference = grant.grantId,
        scope = scope,
        context = RightsOperationContext(offlineBytes = byteCount),
        protectionAuthorization = protectionAuthorization,
    )
}

/**
 * Host boundary for body imports whose rights grant is committed in the same later SQL batch.
 * Implementations must re-check inside [execute] immediately before the blob side effect.
 */
public interface ContentBodyOfflineStoreAuthorizer {
    public fun requireAllowed(request: PendingContentBodyStoreRequest)

    public fun <T> execute(request: PendingContentBodyStoreRequest, block: () -> T): T
}

/**
 * Evaluates a proposed grant through the normal host operation gate without pretending it is
 * durable. A provisional admission is always revoked after the check/side effect unless the exact
 * grant became durable inside the block. Durable grants are never re-admitted here, so an explicit
 * runtime revocation cannot be silently undone by a later import.
 *
 * Callers must serialize operations for one grant id; import/restore coordinators already do so.
 */
public class HostContentBodyOfflineStoreAuthorizer(
    private val authority: InMemoryRightsAuthority,
    private val durableGrant: (RightsGrantRef) -> RightsGrant?,
    nowEpochMillis: () -> Long,
) : ContentBodyOfflineStoreAuthorizer {
    private val gate: ContentOperationGate = HostContentOperationGate(authority, nowEpochMillis)

    override fun requireAllowed(request: PendingContentBodyStoreRequest) {
        withCurrentOrProvisionalGrant(request.grant) {
            gate.requireAllowed(request.access(), ContentOperation.OFFLINE_STORE)
        }
    }

    override fun <T> execute(request: PendingContentBodyStoreRequest, block: () -> T): T =
        withCurrentOrProvisionalGrant(request.grant) {
            gate.execute(request.access(), ContentOperation.OFFLINE_STORE, block)
        }

    private fun <T> withCurrentOrProvisionalGrant(grant: RightsGrant, block: () -> T): T {
        val durable = durableGrant(grant.grantId)
        if (durable != null) {
            require(durable == grant) { "Proposed content rights grant conflicts with durable policy" }
            return block()
        }

        authority.admit(grant)
        return try {
            block()
        } finally {
            if (durableGrant(grant.grantId) == null) authority.revoke(grant.grantId)
        }
    }
}
