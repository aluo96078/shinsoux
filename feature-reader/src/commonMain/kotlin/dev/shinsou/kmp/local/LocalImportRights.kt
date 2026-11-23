package dev.shinsou.kmp.local

import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.access.ContentOperationGate
import dev.shinsou.kmp.content.acquisition.LocalAcquisitionIdentityDeriver
import dev.shinsou.kmp.content.acquisition.LocalAcquisitionTarget
import dev.shinsou.kmp.content.acquisition.LocalContentFormat
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.rights.ContentAccessRequest
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsOperationContext
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The rights a local import stages and commits its bodies under ([LocalTypedContentController]):
 * which grant, asked for what, and the withdrawal of a provisional one the import admitted.
 */
internal class LocalImportRights(
    private val foundation: ContentFoundationRuntime,
    private val operationGate: ContentOperationGate,
    private val identityDeriver: LocalAcquisitionIdentityDeriver,
) {
    /**
     * The staging was authorized under the import's grant, but [bodyless] keeps the grant a
     * restore gave it, if any: storing the body offline must be allowed under that one too.
     */
    fun requireOfflineStoreUnderRestoredGrant(bodyless: BodylessUnit, importGrant: RightsGrant) {
        val manifest = bodyless.unit.latestManifest ?: return
        operationGate.requireAllowed(
            ContentAccessRequest(
                grantReference = bodyless.acquisition.rightsGrantRef ?: importGrant.grantId,
                scope = RightsScope(
                    publicationId = bodyless.publication.key,
                    acquisitionId = bodyless.acquisition.id,
                    unitId = bodyless.unit.key,
                    manifestId = manifest.manifestId,
                    contentRevision = manifest.contentRevision,
                ),
                context = RightsOperationContext(
                    offlineBytes = manifest.referencedBlobs.distinctBy { it.blobId }.sumOf { it.byteSize },
                ),
            ),
            ContentOperation.OFFLINE_STORE,
        )
    }

    /**
     * Withdraws the authority an import admitted for itself, unless the grant is durable by now:
     * the import's own commit (or one of a durable grant it staged under) made it so, and
     * revoking it would leave that title unreadable until restart.
     *
     * A restore can commit between the check and the revoke, and no lock covers both: the grant
     * row is written under the repository lock, which the prepare-time failures here do not hold,
     * and reading SQLite under the authority's own lock would deadlock against a transaction that
     * admits its grants. So the durable row is looked for again after the revoke, and a grant that
     * became durable meanwhile is admitted again, as the restore itself does for every durable
     * grant once it has committed. An admission always follows its row, so whichever side runs
     * last leaves a durable grant admitted.
     */
    fun revokeProvisional(grant: RightsGrant) {
        if (foundation.rightsGrants.find(grant.grantId) != null) return
        foundation.rightsAuthority.revoke(grant.grantId)
        foundation.rightsGrants.find(grant.grantId)?.let(foundation.rightsAuthority::admit)
    }

    /**
     * What storing the import's bodies offline is asked for: for a title a restore brought back
     * without its body, the restored unit and manifest exactly, whose body this is (a grant scoped
     * to them allows it, one scoped to other content does not); for a new title, its acquisition.
     * Always the acquisition the import stages ([target] in [format]), never the one a grant
     * names: a grant for another acquisition then covers nothing that is asked here.
     */
    fun stagingScope(target: LocalAcquisitionTarget, format: LocalContentFormat): RightsScope {
        val acquisitionId = identityDeriver.acquisitionId(target, format)
        val acquisition = foundation.publications.find(target.publicationKey)
            ?.acquisitions?.singleOrNull { it.id == acquisitionId }
        val unit = acquisition?.units?.singleOrNull()
        val manifest = unit?.latestManifest
        return if (manifest == null) {
            RightsScope(publicationId = target.publicationKey, acquisitionId = acquisitionId)
        } else {
            RightsScope(
                publicationId = target.publicationKey,
                acquisitionId = acquisitionId,
                unitId = unit.key,
                manifestId = manifest.manifestId,
                contentRevision = manifest.contentRevision,
            )
        }
    }

    /**
     * The authority an import stages its bodies under. A title a metadata-only backup restored
     * without its body has its grant already, durable, and perhaps narrower than an import's or
     * revoked: that one is used, and nothing is admitted over it. Only an import with no durable
     * grant admits its own all-operations grant, provisionally until its commit
     * ([revokeProvisional]).
     *
     * That grant has an id of its own, never one a durable grant can have: the id of a title's
     * grant is derived from the title, and a restore can commit a narrower grant under it between
     * the lookup here and the admission, which would then put the import's policy in its place.
     * A durable grant derived the old way is still found and used.
     */
    fun stagingGrant(target: LocalAcquisitionTarget, format: LocalContentFormat): RightsGrant {
        val acquisitionId = identityDeriver.acquisitionId(target, format)
        val restoredReference = foundation.publications.find(target.publicationKey)
            ?.acquisitions?.singleOrNull { it.id == acquisitionId }?.rightsGrantRef
        val derived = RightsGrantRef(identityDeriver.rightsGrantId(target, format))
        val durable = listOfNotNull(restoredReference, derived).distinct()
            .firstNotNullOfOrNull(foundation.rightsGrants::find)
        return durable ?: provisionalGrant(target, acquisitionId).also(foundation.rightsAuthority::admit)
    }

    @OptIn(ExperimentalUuidApi::class)
    private fun provisionalGrant(target: LocalAcquisitionTarget, acquisitionId: String): RightsGrant = RightsGrant(
        schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
        grantId = RightsGrantRef(Uuid.random().toString()),
        scope = RightsScope(publicationId = target.publicationKey, acquisitionId = acquisitionId),
        provenance = RightsProvenance.HostPolicy("local-user-owned-v1"),
        protectionScheme = ProtectionScheme.None,
        validFromEpochMillis = 0,
        validUntilEpochMillis = null,
        allowedOperations = ContentOperation.entries.toSet(),
    )
}
