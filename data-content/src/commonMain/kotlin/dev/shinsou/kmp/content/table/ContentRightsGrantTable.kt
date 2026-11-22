package dev.shinsou.kmp.content.table

import dev.shinsou.kmp.content.schema.RIGHTS_GRANT_COLUMNS
import dev.shinsou.kmp.content.schema.TABLE_ACQUISITION_RIGHTS
import dev.shinsou.kmp.content.schema.TABLE_RIGHTS_GRANTS
import dev.shinsou.kmp.content.sql.ContentRow
import dev.shinsou.kmp.content.transaction.ContentRightsGrantMutation
import dev.shinsou.kmp.content.transaction.ContentTransactionFailurePoint
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.rights.RightsGrant

/** Rights-grant rows, each paired with its acquisition's grant reference. */
internal class ContentRightsGrantTable(sql: ContentSqlOps) : ContentSqlOps by sql {
    fun findRightsGrant(reference: RightsGrantRef): RightsGrant? {
        val row = queryRows(
            TABLE_RIGHTS_GRANTS,
            "$RIGHTS_GRANT_COLUMNS " +
                "WHERE grant_id = ?",
            reference.value,
        ).singleOrNull() ?: return null
        check(row.string("grant_id") == reference.value) { "Rights grant lookup key mismatch" }
        val publicationId = row.string("publication_id")
        val acquisitionId = row.string("acquisition_id")
        val pairedReference = queryRows(
            TABLE_ACQUISITION_RIGHTS,
            "grant_ref_json WHERE publication_id = ? " +
                "AND acquisition_id = ?",
            publicationId,
            acquisitionId,
        ).singleOrNull()?.let { pairing ->
            decodeJson(RightsGrantRef.serializer(), pairing.string("grant_ref_json"))
        }
        return decodeRightsGrantRow(row, pairedReference)
    }

    fun decodeRightsGrantRow(
        row: ContentRow,
        pairedReference: RightsGrantRef?,
    ): RightsGrant {
        val reference = RightsGrantRef(row.string("grant_id"))
        val grant = decodeJson(RightsGrant.serializer(), row.string("grant_json"))
        check(grant.grantId == reference) { "Rights grant key/body mismatch" }
        check(grant.scope.publicationId.value == row.string("publication_id")) {
            "Rights grant publication scope mismatch"
        }
        check(grant.scope.acquisitionId == row.string("acquisition_id")) {
            "Rights grant acquisition scope mismatch"
        }
        check(grant.scope.unitId?.value == row.stringOrNull("unit_id")) { "Rights grant unit scope mismatch" }
        check(grant.scope.manifestId == row.stringOrNull("manifest_id")) { "Rights grant manifest scope mismatch" }
        check(grant.scope.contentRevision == row.longOrNull("content_revision")) {
            "Rights grant content revision mismatch"
        }
        check(pairedReference == reference) {
            "Rights grant is not paired with its exact acquisition reference"
        }
        return ContentRightsGrantMutation(grant).deepImmutableSnapshot().grant
    }

    fun loadAllRightsGrants(): List<RightsGrant> = loadRightsGrantRows(
        rightsRows = queryRows(TABLE_RIGHTS_GRANTS, "$RIGHTS_GRANT_COLUMNS ORDER BY grant_id"),
        acquisitionRightsRows = queryRows(
            TABLE_ACQUISITION_RIGHTS,
            "publication_id, acquisition_id, grant_ref_json ORDER BY publication_id, acquisition_id",
        ),
    )

    fun loadRightsGrantsForPublication(publicationKey: PublicationKey): List<RightsGrant> {
        val publication = publicationKey.value
        return loadRightsGrantRows(
            rightsRows = queryRows(
                TABLE_RIGHTS_GRANTS,
                "$RIGHTS_GRANT_COLUMNS WHERE publication_id = ? ORDER BY grant_id",
                publication,
            ),
            acquisitionRightsRows = queryRows(
                TABLE_ACQUISITION_RIGHTS,
                "publication_id, acquisition_id, grant_ref_json " +
                    "WHERE publication_id = ? ORDER BY acquisition_id",
                publication,
            ),
        )
    }

    fun loadRightsGrantRows(
        rightsRows: List<ContentRow>,
        acquisitionRightsRows: List<ContentRow>,
    ): List<RightsGrant> {
        val acquisitionRights = acquisitionRightsRows.associate { row ->
            val key = AcquisitionRightsKey(
                publicationId = row.string("publication_id"),
                acquisitionId = row.string("acquisition_id"),
            )
            key to decodeJson(RightsGrantRef.serializer(), row.string("grant_ref_json"))
        }
        return rightsRows.map { row ->
            val key = AcquisitionRightsKey(
                publicationId = row.string("publication_id"),
                acquisitionId = row.string("acquisition_id"),
            )
            decodeRightsGrantRow(row, acquisitionRights[key])
        }
    }

    private data class AcquisitionRightsKey(
        val publicationId: String,
        val acquisitionId: String,
    )

    fun writeRightsGrants(mutations: List<ContentRightsGrantMutation>) {
        mutations.forEach { mutation ->
            if (findRightsGrant(mutation.grantReference) != null) return@forEach
            val grant = mutation.grant
            execute(
                """
                    INSERT INTO $TABLE_RIGHTS_GRANTS(
                      grant_id, publication_id, acquisition_id, unit_id, manifest_id,
                      content_revision, grant_json
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                7,
            ) {
                bindString(0, grant.grantId.value)
                bindString(1, grant.scope.publicationId.value)
                bindString(2, grant.scope.acquisitionId)
                bindString(3, grant.scope.unitId?.value)
                bindString(4, grant.scope.manifestId)
                bindLong(5, grant.scope.contentRevision)
                bindString(6, encodeJson(RightsGrant.serializer(), grant))
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_RIGHTS_GRANT_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_RIGHTS_GRANT_WRITE)
        }
    }
}
