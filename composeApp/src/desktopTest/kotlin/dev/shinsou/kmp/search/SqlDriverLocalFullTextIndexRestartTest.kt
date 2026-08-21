package dev.shinsou.kmp.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.annotation.ContentAnnotation
import dev.shinsou.kmp.annotation.ContentAnnotationKind
import dev.shinsou.kmp.annotation.ContentAnnotationState
import dev.shinsou.kmp.app.ContentFeatureRuntime
import dev.shinsou.kmp.content.ContentCommitBatch
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.ContentManifest
import dev.shinsou.kmp.content.ContentManifestOwner
import dev.shinsou.kmp.content.ContentPublicationMutation
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentRightsGrantMutation
import dev.shinsou.kmp.content.ContentSyncMode
import dev.shinsou.kmp.content.ManifestAttachment
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.ContentAccessRequest
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingRange
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import dev.shinsou.kmp.rights.ContentOperation
import dev.shinsou.kmp.rights.ProtectionScheme
import dev.shinsou.kmp.rights.RightsGrant
import dev.shinsou.kmp.rights.RightsGrantRef
import dev.shinsou.kmp.rights.RightsProvenance
import dev.shinsou.kmp.rights.RightsScope
import dev.shinsou.kmp.sync.v2.SyncDraft
import dev.shinsou.kmp.tts.PlatformSpeechRequest
import dev.shinsou.kmp.tts.PlatformSpeechResult
import dev.shinsou.kmp.tts.PlatformTextToSpeechEngine
import dev.shinsou.kmp.tts.SpeechPlaybackStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlDriverLocalFullTextIndexRestartTest {
    @Test
    fun foundationReconcileIndexesEveryLatestPlainTextWithoutOpeningReaderAndSurvivesRestart() = runTest {
        withDatabase("content-search-restart") { database ->
            val firstDriver = driver(database)
            val firstFoundation = foundation(firstDriver)
            val fixture = importTwoTextUnits(firstFoundation)
            val firstFeatures = features(firstFoundation)

            assertTrue(firstFeatures.searchIndex.search("global").isEmpty())
            val reconcile = firstFeatures.reconcileSearchIndex()
            assertEquals(2, reconcile.representationsExamined)
            assertEquals(2, reconcile.representationsIndexed)
            assertEquals(3, reconcile.documentsIndexed)
            assertEquals(0, reconcile.unauthorizedRepresentations)
            assertEquals(0, reconcile.unavailableRepresentations)

            val globalHits = firstFeatures.searchIndex.searchForeground("global")
            assertEquals(2, globalHits.size, "One query must search across durable units, not one open reader")
            assertEquals(fixture.unitIds, globalHits.map { it.locator.scope.unitId.value }.toSet())
            val cjkHit = firstFeatures.searchIndex.searchForeground("搜尋").single()
            assertTrue(cjkHit.snippet.contains("搜尋"))
            assertEquals(fixture.firstText.indexOf("搜"), cjkHit.locator.offset)

            firstFeatures.close()
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopenedFoundation = foundation(reopenedDriver)
            val reopenedFeatures = features(reopenedFoundation)
            val unchanged = reopenedFeatures.reconcileSearchIndex()
            assertEquals(0, unchanged.representationsIndexed)
            assertEquals(0, unchanged.documentsIndexed)
            assertEquals(2, unchanged.representationsAlreadyCurrent)
            assertEquals(3, unchanged.documentsAlreadyCurrent)
            val restartedHits = reopenedFeatures.searchIndex.searchForeground("global")
            assertEquals(2, restartedHits.size, "Global body hits must not require reader hydration after restart")
            assertEquals(fixture.unitIds, restartedHits.map { it.locator.scope.unitId.value }.toSet())
            assertEquals(3, reopenedFeatures.searchIndex.documentCount)
            reopenedFeatures.close()
            reopenedDriver.close()
        }
    }

    @Test
    fun revokedRightsPurgePersistedPlaintextAndTokensAcrossRestart() = runTest {
        withDatabase("content-search-rights-purge") { database ->
            val firstDriver = driver(database)
            val firstFoundation = foundation(firstDriver)
            val fixture = importTwoTextUnits(firstFoundation)
            val firstFeatures = features(firstFoundation)
            assertEquals(3, firstFeatures.reconcileSearchIndex().documentsIndexed)
            assertEquals(3, firstFeatures.searchIndex.documentCount)

            firstFoundation.rightsAuthority.revoke(fixture.grantReference)
            assertTrue(firstFeatures.searchIndex.search("global").isEmpty())
            assertEquals(0, firstFeatures.searchIndex.documentCount)
            assertEquals(0, scalarLong(firstDriver, "SELECT COUNT(*) FROM content_search_documents"))
            assertEquals(0, scalarLong(firstDriver, "SELECT COUNT(*) FROM content_search_tokens"))
            firstFeatures.close()
            firstDriver.close()

            // The durable grant is rehydrated by M1, but revoked derived plaintext must stay gone.
            val reopenedDriver = driver(database)
            val reopenedFoundation = foundation(reopenedDriver)
            val reopenedFeatures = features(reopenedFoundation)
            assertTrue(reopenedFeatures.searchIndex.search("global").isEmpty())
            assertEquals(0, reopenedFeatures.searchIndex.documentCount)
            reopenedFeatures.close()
            reopenedDriver.close()
        }
    }

    @Test
    fun grantScopedBackgroundRevocationPurgesEveryUnitAndAnnotationButNotOtherPublication() = runTest {
        withDatabase("content-rights-grant-scoped") { database ->
            val firstDriver = driver(database)
            val firstFoundation = foundation(firstDriver)
            val fixture = importTwoTextUnits(firstFoundation)
            val speech = RecordingSpeechEngine()
            val firstFeatures = features(firstFoundation, nowEpochMillis = 50, speechEngine = speech)
            assertEquals(3, firstFeatures.reconcileSearchIndex().documentsIndexed)

            val revokedAnnotation = createAnnotation(
                features = firstFeatures,
                annotationId = REVOKED_ANNOTATION_ID,
                scope = secondUnitReadingScope(),
                access = secondUnitAccess(fixture.grantReference),
                body = "revoked private note",
            )
            val external = externalFixture(firstFoundation, firstFeatures)
            assertEquals(4, scalarLong(firstDriver, "SELECT COUNT(*) FROM content_search_documents"))
            assertEquals(2, firstFoundation.annotations.list().size)

            firstFoundation.rightsAuthority.revoke(fixture.grantReference)
            assertEquals(1, speech.stopCount, "Authority revocation must stop native TTS immediately")
            val cleanup = firstFeatures.reconcilePendingRightsInvalidations()

            assertEquals(3, cleanup.searchDocumentsPurged)
            assertEquals(1, cleanup.annotationsRedacted)
            assertTrue(cleanup.speechStopped)
            assertEquals(
                0,
                scalarLong(
                    firstDriver,
                    "SELECT COUNT(*) FROM content_search_documents WHERE publication_id = ?",
                    PUBLICATION_ID,
                ),
            )
            assertEquals(
                1,
                scalarLong(
                    firstDriver,
                    "SELECT COUNT(*) FROM content_search_documents WHERE publication_id = ?",
                    EXTERNAL_PUBLICATION_ID,
                ),
            )
            val tombstone = requireNotNull(firstFoundation.annotations.find(revokedAnnotation.annotationId))
            assertEquals(ContentAnnotationState.TOMBSTONE, tombstone.state)
            assertEquals(null, tombstone.body)
            assertEquals(null, tombstone.range.quote)
            assertEquals(null, (tombstone.range.start as ReadingLocator.Text).quote)
            val unrelated = requireNotNull(firstFoundation.annotations.find(external.annotationId))
            assertEquals(ContentAnnotationState.ACTIVE, unrelated.state)
            assertEquals("unrelated private note", unrelated.body)

            firstFeatures.close()
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopenedFoundation = foundation(reopenedDriver)
            val reopenedFeatures = features(reopenedFoundation, nowEpochMillis = 50)
            assertEquals(
                0,
                scalarLong(
                    reopenedDriver,
                    "SELECT COUNT(*) FROM content_search_documents WHERE publication_id = ?",
                    PUBLICATION_ID,
                ),
                "Purged plaintext must not reappear merely by reopening the runtime",
            )
            val restartedTombstone = requireNotNull(
                reopenedFoundation.annotations.find(REVOKED_ANNOTATION_ID),
            )
            assertEquals(ContentAnnotationState.TOMBSTONE, restartedTombstone.state)
            assertEquals(null, restartedTombstone.body)
            reopenedFeatures.close()
            reopenedDriver.close()
        }
    }

    @Test
    fun expiredGrantSweepAfterRestartPurgesWithoutOpeningReader() = runTest {
        withDatabase("content-rights-expiry-restart") { database ->
            val firstDriver = driver(database)
            val firstFoundation = foundation(firstDriver)
            val fixture = importTwoTextUnits(firstFoundation, validUntilEpochMillis = 100)
            val firstFeatures = features(firstFoundation, nowEpochMillis = 50)
            assertEquals(3, firstFeatures.reconcileSearchIndex().documentsIndexed)
            createAnnotation(
                features = firstFeatures,
                annotationId = EXPIRED_ANNOTATION_ID,
                scope = secondUnitReadingScope(),
                access = secondUnitAccess(fixture.grantReference),
                body = "expires after restart",
            )
            firstFeatures.close()
            firstDriver.close()

            val reopenedDriver = driver(database)
            val reopenedFoundation = foundation(reopenedDriver)
            val speech = RecordingSpeechEngine()
            val reopenedFeatures = features(
                reopenedFoundation,
                nowEpochMillis = 100,
                speechEngine = speech,
            )
            val cleanup = reopenedFeatures.sweepExpiredRightsDerivedData()

            assertEquals(1, cleanup.targetsExamined)
            assertEquals(3, cleanup.searchDocumentsPurged)
            assertEquals(1, cleanup.annotationsRedacted)
            assertEquals(1, speech.stopCount)
            assertEquals(0, scalarLong(reopenedDriver, "SELECT COUNT(*) FROM content_search_documents"))
            assertEquals(0, scalarLong(reopenedDriver, "SELECT COUNT(*) FROM content_search_tokens"))
            val tombstone = requireNotNull(reopenedFoundation.annotations.find(EXPIRED_ANNOTATION_ID))
            assertEquals(ContentAnnotationState.TOMBSTONE, tombstone.state)
            assertEquals(null, tombstone.body)
            reopenedFeatures.close()
            reopenedDriver.close()
        }
    }

    @Test
    fun foregroundGlobalSearchDoesNotSweepUnrelatedRevokedRows() = runTest {
        withDatabase("content-search-no-foreground-sweep") { database ->
            val driver = driver(database)
            val foundation = foundation(driver)
            val fixture = importTwoTextUnits(foundation)
            val features = features(foundation)
            assertEquals(3, features.reconcileSearchIndex().documentsIndexed)
            val external = externalFixture(foundation, features, searchText = "unique-needle")

            foundation.rightsAuthority.revoke(fixture.grantReference)
            val hit = features.searchIndex.searchForeground("unique needle").single()

            assertEquals(external.documentId, hit.documentId)
            assertEquals(
                3,
                scalarLong(
                    driver,
                    "SELECT COUNT(*) FROM content_search_documents WHERE publication_id = ?",
                    PUBLICATION_ID,
                ),
                "Foreground global search must inspect query candidates, not sweep the library",
            )
            features.close()
            driver.close()
        }
    }

    @Test
    fun readerResourceScopeIsAppliedBeforeGlobalRankingLimit() = runTest {
        withDatabase("content-search-reader-scope") { database ->
            val driver = driver(database)
            val foundation = foundation(driver)
            importTwoTextUnits(foundation)
            val features = features(foundation)
            assertEquals(3, features.reconcileSearchIndex().documentsIndexed)

            val externalPublication = PublicationKey(EXTERNAL_PUBLICATION_ID)
            val externalUnit = UnitKey(externalPublication, EXTERNAL_UNIT_ID)
            val externalReadingScope = ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = externalPublication,
                acquisitionId = EXTERNAL_ACQUISITION_ID,
                unitId = externalUnit,
                contentRevision = 1,
            )
            val externalRightsScope = RightsScope(
                publicationId = externalPublication,
                acquisitionId = EXTERNAL_ACQUISITION_ID,
                unitId = externalUnit,
                manifestId = EXTERNAL_MANIFEST_ID,
                contentRevision = 1,
            )
            val externalGrantReference = RightsGrantRef(EXTERNAL_GRANT_ID)
            foundation.rightsAuthority.admit(
                RightsGrant(
                    schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
                    grantId = externalGrantReference,
                    scope = RightsScope(externalPublication, EXTERNAL_ACQUISITION_ID),
                    provenance = RightsProvenance.HostPolicy("search-reader-scope-test"),
                    protectionScheme = ProtectionScheme.None,
                    validFromEpochMillis = 0,
                    validUntilEpochMillis = null,
                    allowedOperations = setOf(ContentOperation.SEARCH_INDEX),
                ),
            )
            val externalAccess = ContentAccessRequest(externalGrantReference, externalRightsScope)
            repeat(101) { index ->
                features.searchIndex.upsertForeground(
                    SearchableTextDocument(
                        documentId = "external-search-$index",
                        scope = externalReadingScope,
                        resourceId = EXTERNAL_RESOURCE_ID,
                        blockId = "external-block-$index",
                        text = "global global global distraction-$index",
                        access = externalAccess,
                    ),
                )
            }

            val targetDocumentId = fullTextDocumentId(SECOND_REPRESENTATION_ID, "paragraph-1")
            val unscoped = features.searchIndex.searchForeground("global", limit = 100)
            assertEquals(100, unscoped.size)
            assertFalse(
                unscoped.any { it.documentId == targetDocumentId },
                "The fixture must prove a post-limit UI filter would lose the open book",
            )

            val scoped = features.searchIndex.searchForegroundInResource(
                query = "global",
                scope = ReadingScope(
                    schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                    publicationId = PublicationKey(PUBLICATION_ID),
                    acquisitionId = ACQUISITION_ID,
                    unitId = UnitKey(PublicationKey(PUBLICATION_ID), SECOND_UNIT_ID),
                    contentRevision = 1,
                ),
                resourceId = "body-1",
                limit = 100,
            )
            assertEquals(listOf(targetDocumentId), scoped.map(FullTextSearchHit::documentId))

            features.close()
            driver.close()
        }
    }

    @Test
    fun readerResourceSearchRechecksRightsBeforeReturningPersistedText() = runTest {
        withDatabase("content-search-reader-rights") { database ->
            val driver = driver(database)
            val foundation = foundation(driver)
            val fixture = importTwoTextUnits(foundation)
            val features = features(foundation)
            assertEquals(3, features.reconcileSearchIndex().documentsIndexed)

            foundation.rightsAuthority.revoke(fixture.grantReference)
            val hits = features.searchIndex.searchForegroundInResource(
                query = "global",
                scope = secondUnitReadingScope(),
                resourceId = "body-1",
                limit = 100,
            )

            assertTrue(hits.isEmpty())
            val targetDocumentId = fullTextDocumentId(SECOND_REPRESENTATION_ID, "paragraph-1")
            assertEquals(2, scalarLong(driver, "SELECT COUNT(*) FROM content_search_documents"))
            assertEquals(
                0,
                scalarLong(
                    driver,
                    "SELECT COUNT(*) FROM content_search_documents WHERE document_id = ?",
                    targetDocumentId,
                ),
            )
            assertEquals(
                0,
                scalarLong(
                    driver,
                    "SELECT COUNT(*) FROM content_search_tokens WHERE document_id = ?",
                    targetDocumentId,
                ),
            )
            features.close()
            driver.close()
        }
    }

    @Test
    fun cancelledReaderResourceSearchStopsBeforeSynchronousDriverWork() = runTest {
        withDatabase("content-search-reader-cancellation") { database ->
            val driver = driver(database)
            val foundation = foundation(driver)
            val fixture = importTwoTextUnits(foundation)
            val features = features(foundation)
            assertEquals(3, features.reconcileSearchIndex().documentsIndexed)
            foundation.rightsAuthority.revoke(fixture.grantReference)

            var completed = false
            val query = launch(start = CoroutineStart.UNDISPATCHED) {
                features.searchIndex.searchForegroundInResource(
                    query = "global",
                    scope = secondUnitReadingScope(),
                    resourceId = "body-1",
                    limit = 100,
                )
                completed = true
            }
            query.cancelAndJoin()

            assertTrue(query.isCancelled)
            assertFalse(completed)
            assertEquals(
                3,
                scalarLong(driver, "SELECT COUNT(*) FROM content_search_documents"),
                "Cancellation must win before the rights purge opens a synchronous SQL slice",
            )
            features.close()
            driver.close()
        }
    }

    @Test
    fun oversizedSemanticBlockUsesSurrogateSafeBoundedSearchTransactions() {
        val text = "a".repeat(MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH - 1) +
            "\uD83D\uDE00" +
            "b".repeat(MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH)
        val block = TextBlock("semantic-block", 0, text.length)

        val segments = fullTextDocumentSegments(FIRST_REPRESENTATION_ID, block, text)

        assertTrue(segments.size >= 2)
        assertEquals(MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH - 1, segments.first().endUtf16)
        assertTrue(segments.all { it.blockId == block.blockId })
        assertTrue(segments.all {
            it.endUtf16 - it.startUtf16 <= MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH
        })
        assertEquals(segments.size, segments.map { it.documentId }.distinct().size)
        assertTrue(segments.zipWithNext().all { (left, right) -> left.endUtf16 == right.startUtf16 })
        assertTrue(segments.all { segment ->
            val boundary = segment.endUtf16
            boundary == text.length ||
                !(text[boundary - 1].isHighSurrogate() && text[boundary].isLowSurrogate())
        })
        assertEquals(
            text,
            segments.joinToString("") { segment ->
                text.substring(segment.startUtf16, segment.endUtf16)
            },
        )
    }

    private fun importTwoTextUnits(
        foundation: ContentFoundationRuntime,
        validUntilEpochMillis: Long? = null,
    ): ImportedFixture {
        val publicationKey = PublicationKey(PUBLICATION_ID)
        val grantReference = RightsGrantRef(GRANT_ID)
        val bodies = listOf(
            ImportedBody(
                unitId = FIRST_UNIT_ID,
                manifestId = FIRST_MANIFEST_ID,
                representationId = FIRST_REPRESENTATION_ID,
                // One semantic locator block intentionally exceeds the per-transaction search
                // bound. Reconciliation must preserve its block id while indexing it in slices.
                text = "Alpha global 搜尋入口 " +
                    "界".repeat(MAX_FULL_TEXT_DOCUMENT_UTF16_LENGTH + 32),
            ),
            ImportedBody(
                unitId = SECOND_UNIT_ID,
                manifestId = SECOND_MANIFEST_ID,
                representationId = SECOND_REPRESENTATION_ID,
                text = "Beta global 跨書庫命中",
            ),
        )
        val published = bodies.map { body ->
            body to foundation.blobStore.put(body.text.encodeToByteArray(), "text/plain")
        }
        val units = published.mapIndexed { index, (body, receipt) ->
            val unitKey = UnitKey(publicationKey, body.unitId)
            val representation = ContentRepresentation.PlainText(
                representationId = body.representationId,
                resource = ResourceRef("body-$index", receipt.reference),
                canonicalUtf16Length = body.text.length,
                blocks = listOf(TextBlock("paragraph-1", 0, body.text.length)),
            )
            val manifest = ContentManifest(
                manifestId = body.manifestId,
                schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
                contentRevision = 1,
                representations = listOf(representation),
            )
            PublicationUnit(
                key = unitKey,
                title = "Unit ${index + 1}",
                manifestRevisions = listOf(manifest),
                ordinal = index,
            )
        }
        val publication = Publication(
            key = publicationKey,
            title = "Global search fixture",
            acquisitions = listOf(
                Acquisition(
                    id = ACQUISITION_ID,
                    origin = AcquisitionOrigin.LocalText,
                    units = units,
                    contentRevision = 1,
                    rightsGrantRef = grantReference,
                ),
            ),
        )
        val grant = RightsGrant(
            schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
            grantId = grantReference,
            scope = RightsScope(publicationKey, ACQUISITION_ID),
            provenance = RightsProvenance.HostPolicy("search-restart-test"),
            protectionScheme = ProtectionScheme.None,
            validFromEpochMillis = 0,
            validUntilEpochMillis = validUntilEpochMillis,
            allowedOperations = setOf(
                ContentOperation.DISPLAY,
                ContentOperation.OFFLINE_STORE,
                ContentOperation.SEARCH_INDEX,
                ContentOperation.ANNOTATE,
            ),
        )
        val attachments = units.map { unit ->
            ManifestAttachment(
                owner = ContentManifestOwner(publicationKey, ACQUISITION_ID, unit.key),
                manifest = unit.manifestRevisions.single(),
            )
        }
        foundation.transactions.commit(
            ContentCommitBatch<SyncDraft>(
                commitId = "search-foundation-import",
                receipts = published.map { it.second },
                attachments = attachments,
                publications = listOf(ContentPublicationMutation(publication)),
                rightsGrants = listOf(ContentRightsGrantMutation(grant)),
            ),
        )
        return ImportedFixture(
            grantReference = grantReference,
            unitIds = bodies.map(ImportedBody::unitId).toSet(),
            firstText = bodies.first().text,
        )
    }

    private fun foundation(driver: JdbcSqliteDriver): ContentFoundationRuntime = ContentFoundationRuntime(
        driver = driver,
        syncModeProvider = { ContentSyncMode.INACTIVE },
    )

    private fun features(
        foundation: ContentFoundationRuntime,
        nowEpochMillis: Long = 100,
        speechEngine: PlatformTextToSpeechEngine? = null,
    ): ContentFeatureRuntime = ContentFeatureRuntime(
        foundation = foundation,
        platformTextToSpeechEngine = speechEngine,
        nowEpochMillis = { nowEpochMillis },
    )

    private fun secondUnitAccess(reference: RightsGrantRef): ContentAccessRequest {
        val publication = PublicationKey(PUBLICATION_ID)
        return ContentAccessRequest(
            grantReference = reference,
            scope = RightsScope(
                publicationId = publication,
                acquisitionId = ACQUISITION_ID,
                unitId = UnitKey(publication, SECOND_UNIT_ID),
                manifestId = SECOND_MANIFEST_ID,
                contentRevision = 1,
            ),
        )
    }

    private fun createAnnotation(
        features: ContentFeatureRuntime,
        annotationId: String,
        scope: ReadingScope,
        access: ContentAccessRequest,
        body: String,
    ): ContentAnnotation {
        val text = "private selected passage"
        val endOffset = "private".length
        val quote = TextQuote(exact = text.substring(0, endOffset), suffix = text.substring(endOffset))
        val start = ReadingLocator.Text(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            scope = scope,
            resourceId = "annotation-body",
            blockId = "paragraph-1",
            offset = 0,
            progression = 0.0,
            quote = quote,
        )
        val end = ReadingLocator.Text(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            scope = scope,
            resourceId = "annotation-body",
            blockId = "paragraph-1",
            offset = endOffset,
            progression = endOffset.toDouble() / text.length,
        )
        return features.annotations.create(
            annotationId = annotationId,
            kind = ContentAnnotationKind.NOTE,
            range = ReadingRange(start, end, quote),
            access = access,
            nowEpochMillis = 10,
            body = body,
        )
    }

    private suspend fun externalFixture(
        foundation: ContentFoundationRuntime,
        features: ContentFeatureRuntime,
        searchText: String = "external searchable text",
    ): ExternalFixture {
        val publication = PublicationKey(EXTERNAL_PUBLICATION_ID)
        val unit = UnitKey(publication, EXTERNAL_UNIT_ID)
        val readingScope = ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = EXTERNAL_ACQUISITION_ID,
            unitId = unit,
            contentRevision = 1,
        )
        val reference = RightsGrantRef(EXTERNAL_GRANT_ID)
        foundation.rightsAuthority.admit(
            RightsGrant(
                schemaVersion = RightsGrant.CURRENT_SCHEMA_VERSION,
                grantId = reference,
                scope = RightsScope(publication, EXTERNAL_ACQUISITION_ID),
                provenance = RightsProvenance.HostPolicy("unrelated-rights-cleanup-test"),
                protectionScheme = ProtectionScheme.None,
                validFromEpochMillis = 0,
                validUntilEpochMillis = null,
                allowedOperations = setOf(ContentOperation.SEARCH_INDEX, ContentOperation.ANNOTATE),
            ),
        )
        val access = ContentAccessRequest(
            grantReference = reference,
            scope = RightsScope(
                publicationId = publication,
                acquisitionId = EXTERNAL_ACQUISITION_ID,
                unitId = unit,
                manifestId = EXTERNAL_MANIFEST_ID,
                contentRevision = 1,
            ),
        )
        features.searchIndex.upsertForeground(
            SearchableTextDocument(
                documentId = EXTERNAL_DOCUMENT_ID,
                scope = readingScope,
                resourceId = EXTERNAL_RESOURCE_ID,
                blockId = "external-paragraph",
                text = searchText,
                access = access,
            ),
        )
        val annotation = createAnnotation(
            features = features,
            annotationId = EXTERNAL_ANNOTATION_ID,
            scope = readingScope,
            access = access,
            body = "unrelated private note",
        )
        return ExternalFixture(EXTERNAL_DOCUMENT_ID, annotation.annotationId)
    }

    private fun secondUnitReadingScope(): ReadingScope {
        val publication = PublicationKey(PUBLICATION_ID)
        return ReadingScope(
            schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
            publicationId = publication,
            acquisitionId = ACQUISITION_ID,
            unitId = UnitKey(publication, SECOND_UNIT_ID),
            contentRevision = 1,
        )
    }

    private fun driver(database: Path): JdbcSqliteDriver = JdbcSqliteDriver("jdbc:sqlite:$database")

    private fun scalarLong(
        driver: JdbcSqliteDriver,
        sql: String,
        argument: String? = null,
    ): Long = driver.executeQuery(
        identifier = null,
        sql = sql,
        mapper = { cursor ->
            check(cursor.next().value)
            QueryResult.Value(requireNotNull(cursor.getLong(0)))
        },
        parameters = if (argument == null) 0 else 1,
        binders = { argument?.let { bindString(0, it) } },
    ).value

    private suspend fun withDatabase(prefix: String, block: suspend (Path) -> Unit) {
        val directory = Files.createTempDirectory(prefix)
        try {
            block(directory.resolve("content.sqlite"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private data class ImportedBody(
        val unitId: String,
        val manifestId: String,
        val representationId: String,
        val text: String,
    )

    private data class ImportedFixture(
        val grantReference: RightsGrantRef,
        val unitIds: Set<String>,
        val firstText: String,
    )

    private data class ExternalFixture(
        val documentId: String,
        val annotationId: String,
    )

    private class RecordingSpeechEngine : PlatformTextToSpeechEngine {
        var stopCount: Int = 0

        override suspend fun speak(request: PlatformSpeechRequest): PlatformSpeechResult =
            PlatformSpeechResult(request.utteranceId, SpeechPlaybackStatus.COMPLETED)

        override fun stop() {
            stopCount++
        }
    }

    private companion object {
        const val PUBLICATION_ID = "11111111-1111-4111-8111-111111111111"
        const val ACQUISITION_ID = "22222222-2222-4222-8222-222222222222"
        const val FIRST_UNIT_ID = "33333333-3333-4333-8333-333333333333"
        const val SECOND_UNIT_ID = "44444444-4444-4444-8444-444444444444"
        const val FIRST_MANIFEST_ID = "55555555-5555-4555-8555-555555555555"
        const val SECOND_MANIFEST_ID = "66666666-6666-4666-8666-666666666666"
        const val FIRST_REPRESENTATION_ID = "77777777-7777-4777-8777-777777777777"
        const val SECOND_REPRESENTATION_ID = "88888888-8888-4888-8888-888888888888"
        const val GRANT_ID = "99999999-9999-4999-8999-999999999999"
        const val EXTERNAL_PUBLICATION_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
        const val EXTERNAL_ACQUISITION_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
        const val EXTERNAL_UNIT_ID = "cccccccc-cccc-4ccc-8ccc-cccccccccccc"
        const val EXTERNAL_MANIFEST_ID = "dddddddd-dddd-4ddd-8ddd-dddddddddddd"
        const val EXTERNAL_GRANT_ID = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
        const val EXTERNAL_RESOURCE_ID = "external-body"
        const val EXTERNAL_DOCUMENT_ID = "external-rights-cleanup-document"
        const val REVOKED_ANNOTATION_ID = "10101010-1010-4010-8010-101010101010"
        const val EXPIRED_ANNOTATION_ID = "20202020-2020-4020-8020-202020202020"
        const val EXTERNAL_ANNOTATION_ID = "30303030-3030-4030-8030-303030303030"
    }
}
