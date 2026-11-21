package dev.shinsou.kmp.content

import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.RightsGrantRef
import dev.shinsou.kmp.domain.model.WorkLink

/** Publication graph rows: publications, acquisitions, acquisition rights, units and work links. */
internal class ContentPublicationTables(sql: ContentSqlOps) : ContentSqlOps by sql {
    /** Reads a typed publication and verifies every normalized child row before returning it. */
    fun findPublication(key: PublicationKey): Publication? {
        val row = queryRows(
            TABLE_PUBLICATIONS,
            "publication_json WHERE publication_id = ?",
            key.value,
        ).singleOrNull() ?: return null
        // Older rows may still carry every manifest; either way only the outline is authoritative here.
        val publication = decodeJson(Publication.serializer(), row.string("publication_json")).withoutManifestRevisions()
        check(publication.key == key) { "Publication key/body mismatch" }

        val expectedAcquisitions = publication.acquisitions.associateBy(Acquisition::id)
        val acquisitions = queryRows(
            TABLE_ACQUISITIONS,
            "acquisition_id, publication_id, acquisition_json " +
                "WHERE publication_id = ? ORDER BY acquisition_id",
            key.value,
        ).associate { acquisitionRow ->
            val acquisitionId = acquisitionRow.string("acquisition_id")
            check(acquisitionRow.string("publication_id") == key.value) { "Acquisition publication scope mismatch" }
            val encoded = acquisitionRow.string("acquisition_json")
            val expected = expectedAcquisitions[acquisitionId]
            // The row is written by this codec from the same outline, so an identical encoding
            // proves equality without decoding every unit a second time. Older rows (still
            // carrying manifests) or any difference take the full decode and comparison.
            val acquisition = if (expected != null && encoded == codec.encodeToString(Acquisition.serializer(), expected)) {
                expected
            } else {
                decodeJson(Acquisition.serializer(), encoded).withoutManifestRevisions()
            }
            check(acquisition.id == acquisitionId) { "Acquisition key/body mismatch" }
            acquisitionId to acquisition
        }
        check(acquisitions == expectedAcquisitions) {
            "Publication acquisition ledger mismatch"
        }
        val rights = queryRows(
            TABLE_ACQUISITION_RIGHTS,
            "acquisition_id, publication_id, grant_ref_json " +
                "WHERE publication_id = ? ORDER BY acquisition_id",
            key.value,
        ).associate { rightsRow ->
            val acquisitionId = rightsRow.string("acquisition_id")
            check(rightsRow.string("publication_id") == key.value) { "Rights publication scope mismatch" }
            acquisitionId to decodeJson(RightsGrantRef.serializer(), rightsRow.string("grant_ref_json"))
        }
        val expectedRights = publication.acquisitions.mapNotNull { acquisition ->
            acquisition.rightsGrantRef?.let { acquisition.id to it }
        }.toMap()
        check(rights == expectedRights) { "Publication acquisition-rights ledger mismatch" }

        // Keyed once per acquisition: scanning the whole unit list for every unit made this check
        // quadratic (about 40k deep comparisons for a 288-chapter title, on every read).
        val acquisitionUnitsByKey = acquisitions.mapValues { (_, acquisition) ->
            acquisition.units.groupBy { it.key }
        }
        val units = queryRows(
            TABLE_UNITS,
            "unit_id, publication_id, acquisition_id, unit_json " +
                "WHERE publication_id = ? ORDER BY unit_id",
            key.value,
        ).associate { unitRow ->
            val unitId = unitRow.string("unit_id")
            check(unitRow.string("publication_id") == key.value) { "Unit publication scope mismatch" }
            val acquisitionId = unitRow.string("acquisition_id")
            val unit = decodeJson(PublicationUnit.serializer(), unitRow.string("unit_json"))
            check(unit.key.value == unitId && unit.key.publicationKey == key) { "Unit key/body mismatch" }
            val outline = unit.copy(manifestRevisions = emptyList())
            check(acquisitionUnitsByKey[acquisitionId]?.get(outline.key)?.any { it == outline } == true) {
                "Unit acquisition scope mismatch"
            }
            unitId to unit
        }
        check(
            units.mapValues { (_, unit) -> unit.copy(manifestRevisions = emptyList()) } ==
                publication.acquisitions.flatMap(Acquisition::units).associateBy { it.key.value },
        ) {
            "Publication unit ledger mismatch"
        }

        val workLinks = queryRows(
            TABLE_WORK_LINKS,
            "ordinal, target_publication_id, link_json " +
                "WHERE publication_id = ? ORDER BY ordinal",
            key.value,
        ).mapIndexed { expectedOrdinal, linkRow ->
            check(linkRow.long("ordinal") == expectedOrdinal.toLong()) {
                "Publication work-link ordinal mismatch"
            }
            val link = decodeJson(WorkLink.serializer(), linkRow.string("link_json"))
            check(link.target.value == linkRow.string("target_publication_id")) { "Publication work-link target mismatch" }
            link
        }
        check(workLinks == publication.workLinks) { "Publication work-link ledger mismatch" }
        // Manifests live only in the unit rows, so the outline rows stay small however many chapters
        // are downloaded.
        return publication.copy(
            acquisitions = publication.acquisitions.map { acquisition ->
                acquisition.copy(units = acquisition.units.map { units.getValue(it.key.value) })
            },
        )
    }

    fun loadAllPublications(): List<Publication> =
        queryRows(TABLE_PUBLICATIONS, "publication_id ORDER BY publication_id")
            .map { row -> requireNotNull(findPublication(PublicationKey(row.string("publication_id")))) }

    fun writePublications(mutations: List<ContentPublicationMutation>) {
        mutations.forEach { mutation ->
            val publication = mutation.publication
            val durable = findPublication(mutation.publicationKey)
            if (durable == publication) return@forEach
            if (durable == null) {
                execute(
                    "INSERT INTO $TABLE_PUBLICATIONS(publication_id, publication_json) VALUES (?, ?)",
                    2,
                ) {
                    bindString(0, publication.key.value)
                    bindString(1, encodeJson(Publication.serializer(), publication.withoutManifestRevisions()))
                }
            } else {
                execute(
                    "UPDATE $TABLE_PUBLICATIONS SET publication_json = ? WHERE publication_id = ?",
                    2,
                ) {
                    bindString(0, encodeJson(Publication.serializer(), publication.withoutManifestRevisions()))
                    bindString(1, publication.key.value)
                }
            }
            maybeFail(ContentTransactionFailurePoint.AFTER_PUBLICATION_WRITE)
            maybeFail(ContentTransactionFailurePoint.AFTER_EACH_PUBLICATION_WRITE)
            val durableAcquisitions = durable?.acquisitions?.associateBy(Acquisition::id).orEmpty()
            publication.acquisitions.forEach { acquisition ->
                val durableAcquisition = durableAcquisitions[acquisition.id]
                if (durableAcquisition == null) {
                    execute(
                        """
                            INSERT INTO $TABLE_ACQUISITIONS(
                              acquisition_id, publication_id, acquisition_json
                            ) VALUES (?, ?, ?)
                        """.trimIndent(),
                        3,
                    ) {
                        bindString(0, acquisition.id)
                        bindString(1, publication.key.value)
                        bindString(2, encodeJson(Acquisition.serializer(), acquisition.withoutManifestRevisions()))
                    }
                } else if (durableAcquisition != acquisition) {
                    execute(
                        """
                            UPDATE $TABLE_ACQUISITIONS SET acquisition_json = ?
                            WHERE acquisition_id = ? AND publication_id = ?
                        """.trimIndent(),
                        3,
                    ) {
                        bindString(0, encodeJson(Acquisition.serializer(), acquisition.withoutManifestRevisions()))
                        bindString(1, acquisition.id)
                        bindString(2, publication.key.value)
                    }
                }
                acquisition.rightsGrantRef?.let { rights ->
                    if (durableAcquisition?.rightsGrantRef == null) {
                        execute(
                            """
                                INSERT INTO $TABLE_ACQUISITION_RIGHTS(
                                  acquisition_id, publication_id, grant_ref_json
                                ) VALUES (?, ?, ?)
                            """.trimIndent(),
                            3,
                        ) {
                            bindString(0, acquisition.id)
                            bindString(1, publication.key.value)
                            bindString(2, encodeJson(RightsGrantRef.serializer(), rights))
                        }
                    }
                }
                val durableUnits = durableAcquisition?.units?.associateBy { it.key }.orEmpty()
                acquisition.units.forEach { unit ->
                    val durableUnit = durableUnits[unit.key]
                    if (durableUnit == null) {
                        execute(
                            """
                                INSERT INTO $TABLE_UNITS(
                                  unit_id, publication_id, acquisition_id, unit_json
                                ) VALUES (?, ?, ?, ?)
                            """.trimIndent(),
                            4,
                        ) {
                            bindString(0, unit.key.value)
                            bindString(1, publication.key.value)
                            bindString(2, acquisition.id)
                            bindString(3, encodeJson(PublicationUnit.serializer(), unit))
                        }
                    } else if (durableUnit != unit) {
                        execute(
                            """
                                UPDATE $TABLE_UNITS SET unit_json = ?
                                WHERE unit_id = ? AND publication_id = ? AND acquisition_id = ?
                            """.trimIndent(),
                            4,
                        ) {
                            bindString(0, encodeJson(PublicationUnit.serializer(), unit))
                            bindString(1, unit.key.value)
                            bindString(2, publication.key.value)
                            bindString(3, acquisition.id)
                        }
                    }
                }
            }
            if (durable == null) publication.workLinks.forEachIndexed { index, link ->
                execute(
                    """
                        INSERT INTO $TABLE_WORK_LINKS(
                          publication_id, ordinal, target_publication_id, link_json
                        ) VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    4,
                ) {
                    bindString(0, publication.key.value)
                    bindLong(1, index.toLong())
                    bindString(2, link.target.value)
                    bindString(3, encodeJson(WorkLink.serializer(), link))
                }
            }
        }
    }
}

/** Publication and acquisition rows store this outline; each unit row holds its own manifests. */
private fun Acquisition.withoutManifestRevisions(): Acquisition =
    copy(units = units.map { unit -> unit.copy(manifestRevisions = emptyList()) })
