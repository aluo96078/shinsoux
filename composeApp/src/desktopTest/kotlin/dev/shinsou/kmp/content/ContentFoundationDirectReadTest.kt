package dev.shinsou.kmp.content

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.sync.v2.SyncDraft
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ContentFoundationDirectReadTest {
    @Test
    fun portableAuxiliaryReadSurvivesRestartWithoutDecodingUnrelatedRows() {
        val directory = Files.createTempDirectory("content-auxiliary-read")
        val database = directory.resolve("content.sqlite")
        val migration = ContentMigrationLedgerMutation(
            namespace = "shuyue.backup.v1",
            sourceDigestSha256 = "1".repeat(64),
            resultFingerprintSha256 = "2".repeat(64),
        )
        val expected = ContentPortableAuxiliaryState(
            metadata = listOf(ContentMetadataMutation("migration.shuyue.category.fixture", "Novel")),
            aliases = listOf(ContentAliasMutation("shuyue-v1-book:fixture", PUBLICATION_ID)),
            migrations = listOf(migration),
        )
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:$database")
            ContentFoundationRuntime(
                firstDriver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            ).transactions.commit(
                ContentCommitBatch(
                    commitId = migration.commitId,
                    metadata = expected.metadata,
                    aliases = expected.aliases,
                    migrations = expected.migrations,
                ),
            )
            firstDriver.close()

            val reopenedDriver = JdbcSqliteDriver("jdbc:sqlite:$database")
            val reopened = ContentFoundationRuntime(
                reopenedDriver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            reopenedDriver.execute(
                identifier = null,
                sql = "INSERT INTO content_transaction_outbox(draft_id, payload) VALUES (?, ?)",
                parameters = 2,
            ) {
                bindString(0, "malformed-unrelated-draft")
                bindString(1, "not-json")
            }.value

            assertEquals(expected, reopened.portableAuxiliaryState())
            assertFails { reopened.transactions.pendingOutbox() }
            reopenedDriver.close()
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun publicationAndRightsReadsDoNotHydrateAnUnrelatedOutbox() {
        val directory = Files.createTempDirectory("content-direct-read")
        val driver = JdbcSqliteDriver("jdbc:sqlite:${directory.resolve("content.sqlite")}")
        try {
            val runtime = ContentFoundationRuntime(
                driver,
                syncModeProvider = { ContentSyncMode.INACTIVE },
            )
            val publicationKey = PublicationKey(PUBLICATION_ID)
            val grantReference = RightsGrantRef(GRANT_ID)
            val publication = Publication(
                key = publicationKey,
                title = "Focused SQL read",
                acquisitions = listOf(
                    Acquisition(
                        id = ACQUISITION_ID,
                        origin = AcquisitionOrigin.LocalText,
                        rightsGrantRef = grantReference,
                    ),
                ),
            )
            val grant = RightsGrant(
                schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
                grantId = grantReference,
                scope = RightsScope(publicationKey, ACQUISITION_ID),
                provenance = RightsProvenance.HostPolicy("direct-read-test"),
                protectionScheme = ProtectionScheme.None,
                validFromEpochMillis = 0,
                validUntilEpochMillis = null,
                allowedOperations = setOf(ContentOperation.DISPLAY),
            )
            runtime.transactions.commit(
                ContentCommitBatch<SyncDraft>(
                    commitId = "direct-read-fixture",
                    publications = listOf(ContentPublicationMutation(publication)),
                    rightsGrants = listOf(ContentRightsGrantMutation(grant)),
                ),
            )
            driver.execute(
                identifier = null,
                sql = "INSERT INTO content_transaction_outbox(draft_id, payload) VALUES (?, ?)",
                parameters = 2,
            ) {
                bindString(0, "malformed-unrelated-draft")
                bindString(1, "not-json")
            }.value

            assertEquals(publication, runtime.publications.find(publicationKey))
            assertEquals(listOf(publication), runtime.publications.all())
            assertEquals(grant, runtime.rightsGrants.find(grantReference))
            assertEquals(listOf(grant), runtime.rightsGrants.all())
            assertFails { runtime.transactions.pendingOutbox() }

            driver.execute(
                identifier = null,
                sql = "UPDATE content_publications SET publication_json = ? WHERE publication_id = ?",
                parameters = 2,
            ) {
                bindString(0, "not-json")
                bindString(1, PUBLICATION_ID)
            }.value
            // Grant hydration validates its normalized acquisition pairing and must not decode the
            // much larger publication graph. Publication reads still fail closed on corruption.
            assertEquals(grant, runtime.rightsGrants.find(grantReference))
            assertEquals(listOf(grant), runtime.rightsGrants.all())
            assertFails { runtime.publications.find(publicationKey) }
        } finally {
            driver.close()
            directory.toFile().deleteRecursively()
        }
    }

    private companion object {
        const val PUBLICATION_ID = "11111111-1111-4111-8111-111111111111"
        const val ACQUISITION_ID = "22222222-2222-4222-8222-222222222222"
        const val GRANT_ID = "33333333-3333-4333-8333-333333333333"
    }
}
