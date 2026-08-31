@file:OptIn(ExtensionImplementationApi::class)

package dev.shinsou.kmp.plugin.v2

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.acquisition.EpubArchiveEntry
import dev.shinsou.kmp.acquisition.EpubArchiveExtractor
import dev.shinsou.kmp.content.ContentFoundationRuntime
import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentBlobStage
import dev.shinsou.kmp.content.ContentKind
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.ContentTransactionFailurePoint
import dev.shinsou.kmp.content.ImageProgression
import dev.shinsou.kmp.content.ImageTransform
import dev.shinsou.kmp.content.PendingBlob
import dev.shinsou.kmp.content.TextBlock
import dev.shinsou.kmp.content.access.HostContentBodyOfflineStoreAuthorizer
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.plugin.Sha256
import dev.shinsou.kmp.rights.ContentOperation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExtensionContentConsumerV2Test {
    @Test
    fun reversibleLibraryBindingReloadsTheLiveExtensionUnitPage() = runTest {
        withFoundation { foundation ->
            val source = FixtureSource(payloads = { remoteUnitId ->
                listOf(inlinePayload("primary", remoteUnitId, "body"))
            })
            val consumer = consumer(foundation, source)
            val binding = ExtensionLibraryBindingV2(
                publicationKey = extensionPublicationKey(SOURCE_KEY, PUBLICATION_ID),
                sourceKey = SOURCE_KEY,
                remotePublicationId = PUBLICATION_ID,
            )

            val page = consumer.publicationPage(
                sourceKey = binding.sourceKey,
                remotePublicationId = binding.remotePublicationId,
            )

            assertEquals(
                binding.publicationKey,
                extensionPublicationKey(page.sourceKey, page.publication.remoteId),
            )
            assertEquals(listOf(UNIT_ID), page.units.map { it.unit.remoteId })
            assertEquals(listOf(SOURCE_KEY, SOURCE_KEY), source.requestedSourceKeys)
            assertEquals(listOf(PUBLICATION_ID, PUBLICATION_ID), source.requestedPublicationIds)
        }
    }

    @Test
    fun exactHostSelectionCommitsInlineTextBindingsRightsBlobAndOutboxAtomically() = runTest {
        withFoundation { foundation ->
            val source = FixtureSource(payloads = { remoteUnitId ->
                listOf(
                    inlinePayload("primary", remoteUnitId, "primary body"),
                    inlinePayload("alternate", remoteUnitId, "alternate body"),
                )
            })
            val consumer = consumer(foundation, source)
            val page = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID)

            assertEquals(SOURCE_KEY, page.sourceKey)
            assertEquals(PUBLICATION_ID, page.publication.remoteId)
            assertEquals(UNIT_ID, page.units.single().unit.remoteId)
            assertEquals(listOf(SOURCE_KEY, SOURCE_KEY), source.requestedSourceKeys)
            assertEquals(listOf(PUBLICATION_ID, PUBLICATION_ID), source.requestedPublicationIds)

            val selection = page.units.single()
            val foreignConsumer = consumer(foundation, source)
            assertFailsWith<ExtensionContentConsumerException.ForeignSelection> {
                foreignConsumer.materialize(selection, "primary")
            }
            assertTrue(foundation.publications.all().isEmpty())

            val required = assertFailsWith<ExtensionContentConsumerException.RepresentationSelectionRequired> {
                consumer.materialize(selection)
            }
            assertEquals(listOf("primary", "alternate"), required.availableIds)
            assertTrue(foundation.publications.all().isEmpty())

            val materialized = consumer.materialize(selection, "primary")
            val publication = assertNotNull(foundation.publications.find(materialized.publicationKey))
            val acquisition = publication.acquisitions.single()
            val origin = assertIs<AcquisitionOrigin.ExtensionSource>(acquisition.origin)
            val unit = acquisition.units.single()
            val representation = assertIs<ContentRepresentation.PlainText>(
                unit.manifestRevisions.single().representations.single(),
            )
            val libraryBinding = assertNotNull(consumer.extensionLibraryBinding(publication.key))

            assertEquals(SOURCE_KEY, origin.sourceBinding.sourceKey)
            assertEquals(PUBLICATION_ID, origin.sourceBinding.remoteId)
            assertEquals(publication.key, libraryBinding.publicationKey)
            assertEquals(SOURCE_KEY, libraryBinding.sourceKey)
            assertEquals(PUBLICATION_ID, libraryBinding.remotePublicationId)
            assertEquals(SOURCE_KEY, assertNotNull(unit.sourceBinding).sourceKey)
            assertEquals(UNIT_ID, unit.sourceBinding?.remoteId)
            assertEquals(
                origin.sourceBinding.remoteEntityKey,
                unit.sourceBinding?.remoteEntityKey?.parentPublication,
            )
            assertContentEquals(
                "primary body".encodeToByteArray(),
                foundation.blobStore.read(representation.resource.blob),
            )

            val grant = assertNotNull(foundation.rightsGrants.find(assertNotNull(acquisition.rightsGrantRef)))
            assertEquals(
                setOf(
                    ContentOperation.DISPLAY,
                    ContentOperation.OFFLINE_STORE,
                    ContentOperation.TTS,
                    ContentOperation.SEARCH_INDEX,
                    ContentOperation.ANNOTATE,
                ),
                grant.allowedOperations,
            )
            assertFalse(ContentOperation.COPY in grant.allowedOperations)
            assertFalse(ContentOperation.EXPORT in grant.allowedOperations)
            assertFalse(ContentOperation.SYNC_BLOB in grant.allowedOperations)
            assertTrue(foundation.transactions.pendingOutbox().isNotEmpty())
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
            assertEquals(listOf(publication.key.value), materialized.commit.publicationIds)
            assertEquals(listOf(grant.grantId.value), materialized.commit.rightsGrantIds)
            val readable = consumer.open(materialized)
            assertEquals("primary body", readable.canonicalText)
            assertEquals(unit.key, readable.content.navigation.scope.unitId)
            assertEquals(unit.manifestRevisions.single().contentRevision, readable.content.navigation.scope.contentRevision)
        }
    }

    @Test
    fun localReadingStateMapsTypedUnitKeysBackToOpaqueRemoteIds() = runTest {
        withFoundation { foundation ->
            var completedUnitKey: UnitKey? = null
            var latestUnitKey: UnitKey? = null
            val source = FixtureSource(
                payloads = { remoteUnitId -> listOf(inlinePayload("primary", remoteUnitId, remoteUnitId)) },
                unitItems = {
                    listOf(
                        RemoteUnitV2("unit-a", "Unit A"),
                        RemoteUnitV2("opaque-chapter-215", "Unit 215"),
                    )
                },
            )
            val consumer = consumer(
                foundation = foundation,
                source = source,
                localUnitProgress = {
                    ExtensionLocalUnitProgressV2(
                        completedUnitKeys = setOfNotNull(completedUnitKey),
                        lastReadUnitKey = latestUnitKey,
                    )
                },
            )
            val selections = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID)
                .units.associateBy { it.unit.remoteId }
            completedUnitKey = consumer.materialize(selections.getValue("unit-a")).unitKey
            latestUnitKey = consumer.materialize(selections.getValue("opaque-chapter-215")).unitKey

            assertEquals(
                ExtensionLocalReadingStateV2(
                    completedRemoteUnitIds = setOf("unit-a"),
                    lastReadRemoteUnitId = "opaque-chapter-215",
                ),
                consumer.localReadingState(extensionPublicationKey(SOURCE_KEY, PUBLICATION_ID)),
            )
        }
    }

    @Test
    fun fetchedTextSizeOrDigestMismatchFailsBeforeAnyBlobOrMetadataCommit() = runTest {
        val bytes = "verified remote body".encodeToByteArray()
        listOf(
            RemoteBlobPlanV2(
                resource = textResource(),
                expectedPlaintextDigest = Sha256.hex(bytes),
                expectedByteSize = bytes.size.toLong() + 1,
            ),
            RemoteBlobPlanV2(
                resource = textResource(),
                expectedPlaintextDigest = "0".repeat(64),
                expectedByteSize = bytes.size.toLong(),
            ),
        ).forEach { bodyPlan ->
            withFoundation { foundation ->
                val source = FixtureSource(payloads = { remoteUnitId ->
                    listOf(
                        UnitContentPayload.HostFetchTextPayload(
                            schemaVersion = 2,
                            representationId = "remote-text",
                            sourceKey = SOURCE_KEY,
                            remoteUnitId = remoteUnitId,
                            source = TextPayloadSourceV2.HostFetchResource(bodyPlan),
                        ),
                    )
                })
                val consumer = consumer(
                    foundation = foundation,
                    source = source,
                    fetcher = ExtensionResourceFetcherV2 { sourceKey, request ->
                        assertEquals(SOURCE_KEY, sourceKey)
                        assertEquals("https://fixture.example/body.txt", request.effectiveUri)
                        ExtensionFetchedResourceV2(bytes, "text/plain")
                    },
                )
                val selection = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID).units.single()

                assertFailsWith<IllegalArgumentException> { consumer.materialize(selection) }

                assertTrue(foundation.publications.all().isEmpty())
                assertTrue(foundation.rightsGrants.all().isEmpty())
                assertTrue(foundation.transactions.pendingOutbox().isEmpty())
                assertEquals(0, foundation.blobStore.count)
                assertEquals(0, foundation.blobStore.pendingReceiptCount)
            }
        }
    }

    @Test
    fun sourcePublicationMergesMultipleUnitsAndRepresentationsWithoutReplayDuplicates() = runTest {
        withFoundation { foundation ->
            var availableRepresentations = listOf("primary")
            val bodies = mutableMapOf(
                ("unit-a" to "primary") to "unit A primary",
                ("unit-a" to "alternate") to "unit A alternate",
                ("unit-b" to "primary") to "unit B primary",
            )
            val source = FixtureSource(
                payloads = { remoteUnitId ->
                    availableRepresentations.map { representationId ->
                        inlinePayload(
                            representationId,
                            remoteUnitId,
                            bodies.getValue(remoteUnitId to representationId),
                        )
                    }
                },
                unitItems = {
                    listOf(
                        RemoteUnitV2("unit-a", "Unit A"),
                        RemoteUnitV2("unit-b", "Unit B"),
                    )
                },
            )
            val consumer = consumer(foundation, source)
            val selections = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID)
                .units.associateBy { it.unit.remoteId }

            val first = consumer.materialize(selections.getValue("unit-a"))
            val second = consumer.materialize(selections.getValue("unit-b"))
            availableRepresentations = listOf("primary", "alternate")
            val alternate = consumer.materialize(selections.getValue("unit-a"), "alternate")
            val replay = consumer.materialize(selections.getValue("unit-a"), "alternate")

            assertEquals(first.publicationKey, second.publicationKey)
            assertEquals(first.publicationKey, alternate.publicationKey)
            assertEquals(first.publicationKey, replay.publicationKey)
            val publication = foundation.publications.all().single()
            val acquisition = publication.acquisitions.single()
            assertEquals(first.publicationKey, publication.key)
            assertEquals(2, acquisition.units.size)
            assertEquals(1, foundation.rightsGrants.all().size)
            assertEquals(acquisition.id, foundation.rightsGrants.all().single().scope.acquisitionId)

            val unitA = acquisition.units.single { it.sourceBinding?.remoteId == "unit-a" }
            val unitB = acquisition.units.single { it.sourceBinding?.remoteId == "unit-b" }
            assertEquals(2, unitA.manifestRevisions.size)
            assertEquals(listOf(0L, 1L), unitA.manifestRevisions.map { it.contentRevision })
            assertEquals(2, unitA.latestManifest?.representations?.size)
            assertEquals(1, unitB.manifestRevisions.size)
            assertEquals(1, unitB.latestManifest?.representations?.size)
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
        }
    }

    @Test
    fun representationUpdateAndRollbackAppendImmutableRevisions() = runTest {
        withFoundation { foundation ->
            var body = "body v1"
            val source = FixtureSource(payloads = { remoteUnitId ->
                listOf(inlinePayload("primary", remoteUnitId, body))
            })
            val consumer = consumer(foundation, source)
            val selection = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID).units.single()

            consumer.materialize(selection)
            body = "body v2"
            consumer.materialize(selection)
            body = "body v1"
            consumer.materialize(selection)

            val unit = foundation.publications.all().single().acquisitions.single().units.single()
            assertEquals(listOf(0L, 1L, 2L), unit.manifestRevisions.map { it.contentRevision })
            assertEquals(3, unit.manifestRevisions.map { it.manifestId }.distinct().size)
            assertEquals(
                listOf("body v1", "body v2", "body v1"),
                unit.manifestRevisions.map { manifest ->
                    val representation = assertIs<ContentRepresentation.PlainText>(
                        manifest.representations.single(),
                    )
                    assertNotNull(foundation.blobStore.read(representation.resource.blob)).decodeToString()
                },
            )
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
        }
    }

    @Test
    fun transactionFailureLeavesPreviouslyCommittedGraphUntouched() = runTest {
        withFoundation { foundation ->
            var body = "stable body"
            val source = FixtureSource(payloads = { remoteUnitId ->
                listOf(inlinePayload("primary", remoteUnitId, body))
            })
            val consumer = consumer(foundation, source)
            val selection = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID).units.single()
            val materialized = consumer.materialize(selection)
            val durableBeforeFailure = assertNotNull(foundation.publications.find(materialized.publicationKey))
            val outboxBeforeFailure = foundation.transactions.pendingOutbox()

            body = "replacement body"
            foundation.injectTransactionFailureForTesting(ContentTransactionFailurePoint.AFTER_PUBLICATION_WRITE)
            assertFailsWith<IllegalStateException> { consumer.materialize(selection) }

            assertEquals(durableBeforeFailure, foundation.publications.find(materialized.publicationKey))
            assertEquals(outboxBeforeFailure, foundation.transactions.pendingOutbox())
            assertEquals(1, durableBeforeFailure.acquisitions.single().units.single().manifestRevisions.size)
            assertEquals(1, foundation.blobStore.pendingReceiptCount)
        }
    }

    @Test
    fun supportedImageTransformIsPersistedAsTypedLosslessManifestMetadata() = runTest {
        withFoundation { foundation ->
            val imageBytes = byteArrayOf(1, 2, 3, 4)
            val source = FixtureSource(
                payloads = { remoteUnitId ->
                    listOf(
                        UnitContentPayload.ImageSequence(
                            schemaVersion = 2,
                            representationId = "images",
                            sourceKey = SOURCE_KEY,
                            remoteUnitId = remoteUnitId,
                            pages = listOf(
                                ImagePageV2(
                                    resourceId = "page-1",
                                    request = RemoteRequestPlanV2(
                                        method = HttpMethodV2.GET,
                                        url = "https://fixture.example/page-1.png",
                                    ),
                                    mediaType = "image/png",
                                    transform = ImageTransformPlanV2(
                                        transformId = "reverse-vertical-segments",
                                        parameters = mapOf("segmentCount" to "3"),
                                    ),
                                    spread = "center",
                                ),
                            ),
                        ),
                    )
                },
                supportedContentKinds = setOf(ContentKind.IMAGE_SEQUENCE),
            )
            val consumer = consumer(
                foundation = foundation,
                source = source,
                fetcher = ExtensionResourceFetcherV2 { _, _ ->
                    ExtensionFetchedResourceV2(imageBytes, "image/png")
                },
            )

            consumer.materialize(consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID).units.single())

            val representation = assertIs<ContentRepresentation.ImageSequence>(
                foundation.publications.all().single().acquisitions.single().units.single()
                    .latestManifest?.representations?.single(),
            )
            val transform = assertNotNull(representation.pages.single().transform)
            assertEquals("reverse_vertical_segments", transform.transformId)
            assertEquals(mapOf("segmentCount" to "3"), transform.parameters)
            assertEquals("center", representation.pages.single().spread)
            assertTrue(transform.transformId in ImageTransform.SUPPORTED_TRANSFORMS)
        }
    }

    @Test
    fun epubArchiveIsHostParsedAndMustExactlyMatchTheDeclaredRemoteGraph() = runTest {
        withReopenableFoundation { driver, foundation ->
            val archiveBytes = "bounded fake EPUB archive".encodeToByteArray()
            val entries = minimalEpubEntries()
            val remoteIdsByHref = mapOf(
                "mimetype" to "remote-mimetype",
                "META-INF/container.xml" to "remote-container",
                "OPS/package.opf" to "remote-package",
                "OPS/chapter.xhtml" to "remote-chapter",
                "OPS/fallback.xhtml" to "remote-fallback",
                "OPS/overlay.smil" to "remote-overlay",
                "OPS/nav.xhtml" to "remote-nav",
                "OPS/toc.ncx" to "remote-ncx",
                "OPS/style.css" to "remote-style",
                "OPS/cover.png" to "remote-cover",
            )
            val mediaTypesByHref = mapOf(
                "mimetype" to "text/plain",
                "META-INF/container.xml" to "application/xml",
                "OPS/package.opf" to "application/oebps-package+xml",
                "OPS/chapter.xhtml" to "application/xhtml+xml",
                "OPS/fallback.xhtml" to "application/xhtml+xml",
                "OPS/overlay.smil" to "application/smil+xml",
                "OPS/nav.xhtml" to "application/xhtml+xml",
                "OPS/toc.ncx" to "application/x-dtbncx+xml",
                "OPS/style.css" to "text/css",
                "OPS/cover.png" to "image/png",
            )
            val resources = entries.map { entry ->
                remoteEpubResource(
                    id = remoteIdsByHref.getValue(entry.path),
                    href = entry.path,
                    mediaType = mediaTypesByHref.getValue(entry.path),
                    bytes = entry.bytes,
                )
            }
            val archivePlan = RemoteBlobPlanV2(
                resource = RemoteResourceV2(
                    id = "archive",
                    request = RemoteRequestPlanV2(
                        method = HttpMethodV2.GET,
                        url = "https://fixture.example/book.epub",
                    ),
                    mediaType = "application/epub+zip",
                ),
                expectedPlaintextDigest = Sha256.hex(archiveBytes),
                expectedByteSize = archiveBytes.size.toLong(),
            )
            val source = FixtureSource(
                payloads = { remoteUnitId ->
                    listOf(
                        UnitContentPayload.EpubSpine(
                            schemaVersion = 2,
                            representationId = "epub",
                            sourceKey = SOURCE_KEY,
                            remoteUnitId = remoteUnitId,
                            packageGraph = RemoteEpubPackageV2(
                                archive = archivePlan,
                                packageDocumentId = "remote-package",
                                resources = resources,
                            ),
                            documents = listOf(
                                RemoteEpubSpineDocumentV2(
                                    id = "spine-1",
                                    href = "OPS/chapter.xhtml",
                                    resourceId = "remote-chapter",
                                ),
                            ),
                        ),
                    )
                },
                supportedContentKinds = setOf(ContentKind.EPUB_SPINE),
            )
            var fetchCount = 0
            val consumer = consumer(
                foundation = foundation,
                source = source,
                fetcher = ExtensionResourceFetcherV2 { _, request ->
                    fetchCount += 1
                    assertEquals("https://fixture.example/book.epub", request.effectiveUri)
                    ExtensionFetchedResourceV2(archiveBytes, "application/epub+zip")
                },
                epubArchiveExtractor = EpubArchiveExtractor { received, _ ->
                    assertContentEquals(archiveBytes, received)
                    entries
                },
            )

            val materialized = consumer.materialize(
                consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID).units.single(),
            )

            assertEquals(1, fetchCount)
            val representation = assertIs<ContentRepresentation.EpubSpine>(
                foundation.publications.all().single().acquisitions.single().units.single()
                    .latestManifest?.representations?.single(),
            )
            assertEquals("remote-package", representation.packageGraph.packageDocumentId)
            assertEquals(resources.map { it.id to it.href }, representation.packageGraph.resources.map { it.id to it.href })
            assertEquals("spine-1", representation.documents.single().id)
            assertEquals("remote-chapter", representation.documents.single().resourceId)
            assertEquals("chapter", representation.documents.single().manifestIdRef)
            assertEquals(
                setOf("rendition:layout-pre-paginated", "page-spread-left"),
                representation.documents.single().properties,
            )
            assertEquals("pre-paginated", representation.documents.single().rendition?.layout)
            assertEquals(ImageProgression.RIGHT_TO_LEFT, representation.documents.single().pageProgression)
            assertEquals("reflowable", representation.packageGraph.renditions.single().layout)
            assertEquals("ncx", representation.packageGraph.spineTocManifestIdRef)
            assertTrue(representation.packageGraph.manifest.all { declaration ->
                declaration.resourceId == remoteIdsByHref.getValue(declaration.resolvedHref)
            })
            val chapterDeclaration = representation.packageGraph.manifest.single {
                it.manifestIdRef == "chapter"
            }
            assertEquals("fallback", chapterDeclaration.fallbackIdRef)
            assertEquals("overlay", chapterDeclaration.mediaOverlayIdRef)
            assertEquals(2, representation.packageGraph.navigation.size)
            assertTrue(representation.packageGraph.navigation.all { navigation ->
                navigation.documentResourceId == remoteIdsByHref.getValue(navigation.documentHref) &&
                    navigation.points.all { point ->
                        point.resolvedHref?.let { href ->
                            point.resourceId == remoteIdsByHref.getValue(href)
                        } ?: true
                    }
            })
            assertTrue(representation.packageGraph.cssDependencies.single().references.any { reference ->
                reference.resolvedHref == "OPS/cover.png"
            })
            assertContentEquals(archiveBytes, foundation.blobStore.read(representation.packageGraph.archive))

            val reopened = ContentFoundationRuntime(driver)
            val reopenedRepresentation = assertIs<ContentRepresentation.EpubSpine>(
                assertNotNull(reopened.publications.find(materialized.publicationKey))
                    .acquisitions.single().units.single().latestManifest?.representations?.single(),
            )
            assertEquals(representation, reopenedRepresentation)
            assertContentEquals(
                archiveBytes,
                reopened.blobStore.read(reopenedRepresentation.packageGraph.archive),
            )
        }
    }

    @Test
    fun everyRemoteEpubMismatchFailsBeforeAStageReceiptOrMetadataWrite() = runTest {
        val archiveBytes = "bounded fake EPUB archive".encodeToByteArray()
        val entries = minimalEpubEntries()
        val resources = remoteEpubResources(entries)
        val archivePlan = remoteEpubArchivePlan(archiveBytes)
        val basePackage = RemoteEpubPackageV2(
            archive = archivePlan,
            packageDocumentId = "remote-package",
            resources = resources,
        )
        val baseDocuments = listOf(
            RemoteEpubSpineDocumentV2(
                id = "spine-1",
                href = "OPS/chapter.xhtml",
                resourceId = "remote-chapter",
            ),
        )
        fun payload(
            graph: RemoteEpubPackageV2 = basePackage,
            documents: List<RemoteEpubSpineDocumentV2> = baseDocuments,
        ): UnitContentPayload.EpubSpine = UnitContentPayload.EpubSpine(
            schemaVersion = 2,
            representationId = "epub",
            sourceKey = SOURCE_KEY,
            remoteUnitId = UNIT_ID,
            packageGraph = graph,
            documents = documents,
        )
        fun replaceResource(
            id: String,
            transform: (RemoteEpubResourceV2) -> RemoteEpubResourceV2,
        ): List<RemoteEpubResourceV2> = resources.map { resource ->
            if (resource.id == id) transform(resource) else resource
        }
        data class Mismatch(
            val name: String,
            val archiveEntries: List<EpubArchiveEntry> = entries,
            val content: UnitContentPayload.EpubSpine,
        )

        val extraBytes = byteArrayOf(9)
        val twoSpineEntries = entries.map { entry ->
            if (entry.path == "OPS/package.opf") {
                epubEntry(
                    entry.path,
                    MINIMAL_OPF_XML.replace(
                        "</spine>",
                        "<itemref idref=\"fallback\" linear=\"no\"/></spine>",
                    ),
                )
            } else {
                entry
            }
        }
        val twoSpineResources = remoteEpubResources(twoSpineEntries)
        val mismatches = listOf(
            Mismatch(
                "missing href",
                content = payload(basePackage.copy(resources = resources.dropLast(1))),
            ),
            Mismatch(
                "extra href",
                content = payload(
                    basePackage.copy(
                        resources = resources + remoteEpubResource(
                            id = "remote-extra",
                            href = "OPS/extra.bin",
                            mediaType = "application/octet-stream",
                            bytes = extraBytes,
                        ),
                    ),
                ),
            ),
            Mismatch(
                "duplicate href",
                content = payload(
                    basePackage.copy(
                        resources = resources + remoteEpubResource(
                            id = "remote-duplicate-cover",
                            href = "OPS/cover.png",
                            mediaType = "image/png",
                            bytes = byteArrayOf(1, 2, 3),
                        ),
                    ),
                ),
            ),
            Mismatch(
                "wrong package document",
                content = payload(basePackage.copy(packageDocumentId = "remote-chapter")),
            ),
            Mismatch(
                "media type",
                content = payload(
                    basePackage.copy(
                        resources = replaceResource("remote-chapter") { remote ->
                            remote.copy(
                                body = remote.body.copy(
                                    resource = remote.body.resource.copy(mediaType = "text/html"),
                                ),
                                mediaType = "text/html",
                            )
                        },
                    ),
                ),
            ),
            Mismatch(
                "digest",
                content = payload(
                    basePackage.copy(
                        resources = replaceResource("remote-chapter") { remote ->
                            remote.copy(body = remote.body.copy(expectedPlaintextDigest = "0".repeat(64)))
                        },
                    ),
                ),
            ),
            Mismatch(
                "size",
                content = payload(
                    basePackage.copy(
                        resources = replaceResource("remote-chapter") { remote ->
                            remote.copy(
                                body = remote.body.copy(
                                    expectedByteSize = requireNotNull(remote.body.expectedByteSize) + 1,
                                ),
                            )
                        },
                    ),
                ),
            ),
            Mismatch(
                "archive digest",
                content = payload(
                    basePackage.copy(archive = archivePlan.copy(expectedPlaintextDigest = "0".repeat(64))),
                ),
            ),
            Mismatch(
                "archive size",
                content = payload(
                    basePackage.copy(archive = archivePlan.copy(expectedByteSize = archiveBytes.size.toLong() + 1)),
                ),
            ),
            Mismatch(
                "spine linearity",
                content = payload(documents = baseDocuments.map { it.copy(linear = false) }),
            ),
            Mismatch(
                name = "spine order",
                archiveEntries = twoSpineEntries,
                content = payload(
                    graph = basePackage.copy(resources = twoSpineResources),
                    documents = listOf(
                        RemoteEpubSpineDocumentV2(
                            id = "spine-2",
                            href = "OPS/fallback.xhtml",
                            resourceId = "remote-fallback",
                            linear = false,
                        ),
                        baseDocuments.single(),
                    ),
                ),
            ),
        )

        mismatches.forEach { mismatch ->
            withFoundation { foundation ->
                val source = FixtureSource(
                    payloads = { listOf(mismatch.content) },
                    supportedContentKinds = setOf(ContentKind.EPUB_SPINE),
                )
                val consumer = consumer(
                    foundation = foundation,
                    source = source,
                    fetcher = ExtensionResourceFetcherV2 { _, _ ->
                        ExtensionFetchedResourceV2(archiveBytes, "application/epub+zip")
                    },
                    epubArchiveExtractor = EpubArchiveExtractor { received, _ ->
                        assertContentEquals(archiveBytes, received)
                        mismatch.archiveEntries
                    },
                )
                val selection = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID).units.single()

                assertFailsWith<IllegalArgumentException>(mismatch.name) {
                    consumer.materialize(selection)
                }

                assertTrue(foundation.publications.all().isEmpty(), mismatch.name)
                assertTrue(foundation.rightsGrants.all().isEmpty(), mismatch.name)
                assertTrue(foundation.transactions.pendingOutbox().isEmpty(), mismatch.name)
                assertEquals(0, foundation.blobStore.count, mismatch.name)
                assertEquals(0, foundation.blobStore.currentGeneration, mismatch.name)
                assertEquals(0, foundation.blobStore.pendingReceiptCount, mismatch.name)
                assertEquals(0, foundation.blobStore.beginStageCountForTesting, mismatch.name)
            }
        }

        withFoundation { foundation ->
            val chapter = resources.single { it.id == "remote-chapter" }
            assertFailsWith<IllegalArgumentException> {
                chapter.body.copy(
                    resource = chapter.body.resource.copy(
                        request = chapter.body.resource.request.copy(
                            maxResponseBytes = chapter.body.expectedByteSize!! - 1,
                        ),
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                archivePlan.copy(
                    resource = archivePlan.resource.copy(
                        request = archivePlan.resource.request.copy(
                            maxResponseBytes = archiveBytes.size.toLong() - 1,
                        ),
                    ),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                payload(
                    documents = listOf(
                        baseDocuments.single().copy(resourceId = "remote-fallback"),
                    ),
                )
            }
            assertEquals(0, foundation.blobStore.currentGeneration)
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
            assertEquals(0, foundation.blobStore.beginStageCountForTesting)
        }
    }

    @Test
    fun remappedEpubGraphOverTheSqlJsonLimitFailsBeforeOpeningAnyBlobStage() = runTest {
        val archiveBytes = "bounded fake EPUB archive".encodeToByteArray()
        val extraCount = 4_000
        val extraManifest = buildString {
            repeat(extraCount) { index ->
                append("<item id=\"extra-")
                append(index)
                append("\" href=\"extra-")
                append(index)
                append(".bin\" media-type=\"application/octet-stream\"/>")
            }
        }
        val expandedOpf = MINIMAL_OPF_XML.replace(
            "</manifest>",
            "$extraManifest</manifest>",
        )
        val baseEntries = minimalEpubEntries().map { entry ->
            if (entry.path == "OPS/package.opf") epubEntry(entry.path, expandedOpf) else entry
        }
        val extraEntries = List(extraCount) { index ->
            EpubArchiveEntry(
                path = "OPS/extra-$index.bin",
                bytes = byteArrayOf((index and 0xff).toByte()),
                compressedSizeBytes = 1,
            )
        }
        val entries = baseEntries + extraEntries
        val resources = remoteEpubResources(baseEntries) + extraEntries.mapIndexed { index, entry ->
            remoteEpubResource(
                id = "remote-extra-$index-${"x".repeat(430)}",
                href = entry.path,
                mediaType = "application/octet-stream",
                bytes = entry.bytes,
            )
        }
        val content = UnitContentPayload.EpubSpine(
            schemaVersion = 2,
            representationId = "epub",
            sourceKey = SOURCE_KEY,
            remoteUnitId = UNIT_ID,
            packageGraph = RemoteEpubPackageV2(
                archive = remoteEpubArchivePlan(archiveBytes),
                packageDocumentId = "remote-package",
                resources = resources,
            ),
            documents = listOf(
                RemoteEpubSpineDocumentV2(
                    id = "spine-1",
                    href = "OPS/chapter.xhtml",
                    resourceId = "remote-chapter",
                ),
            ),
        )

        withFoundation { foundation ->
            val source = FixtureSource(
                payloads = { listOf(content) },
                supportedContentKinds = setOf(ContentKind.EPUB_SPINE),
            )
            val consumer = consumer(
                foundation = foundation,
                source = source,
                fetcher = ExtensionResourceFetcherV2 { _, _ ->
                    ExtensionFetchedResourceV2(archiveBytes, "application/epub+zip")
                },
                epubArchiveExtractor = EpubArchiveExtractor { _, _ -> entries },
            )
            val selection = consumer.publicationPage(SOURCE_KEY, PUBLICATION_ID).units.single()

            assertFailsWith<IllegalArgumentException> {
                consumer.materialize(selection)
            }

            assertTrue(foundation.publications.all().isEmpty())
            assertTrue(foundation.transactions.pendingOutbox().isEmpty())
            assertEquals(0, foundation.blobStore.count)
            assertEquals(0, foundation.blobStore.currentGeneration)
            assertEquals(0, foundation.blobStore.pendingReceiptCount)
            assertEquals(0, foundation.blobStore.beginStageCountForTesting)
        }
    }

    @Test
    fun stableExtensionStageHashesMultipleChunksWithoutAnIntSizedBodyCopy() {
        val syntheticSize = Int.MAX_VALUE.toLong() + 17L
        var sealedReference: BlobRef? = null
        val delegate = object : ContentBlobStage {
            override val expectedSizeBytes: Long? = null
            override val bytesWritten: Long = syntheticSize
            override var isSealed: Boolean = false

            override fun append(chunk: ByteArray) = Unit

            override fun seal(expected: BlobRef?): PendingBlob {
                val reference = requireNotNull(expected)
                sealedReference = reference
                isSealed = true
                return object : PendingBlob {
                    override val reference: BlobRef = reference
                }
            }

            override fun abort() = Unit
        }
        val stage = StableExtensionBlobStage(
            delegate = delegate,
            mediaType = "application/octet-stream",
            stableBlobId = { "55555555-5555-5555-8555-555555555555" },
        )
        val first = "first chunk".encodeToByteArray()
        val second = " and second chunk".encodeToByteArray()

        stage.append(first)
        stage.append(second)
        val pending = stage.seal()

        assertEquals(syntheticSize, pending.reference.byteSize)
        assertEquals(
            Sha256.hex(first + second),
            pending.reference.plaintextDigest,
        )
        assertEquals(pending.reference, sealedReference)
    }

    private fun consumer(
        foundation: ContentFoundationRuntime,
        source: FixtureSource,
        fetcher: ExtensionResourceFetcherV2? = null,
        epubArchiveExtractor: EpubArchiveExtractor = EpubArchiveExtractor { _, _ -> error("not used") },
        localUnitProgress: (dev.shinsou.kmp.domain.model.PublicationKey) -> ExtensionLocalUnitProgressV2 = {
            ExtensionLocalUnitProgressV2()
        },
    ): ExtensionContentConsumerV2 {
        val runtime = ImmutableExtensionPackageRuntimeV2(
            descriptor = ExtensionPackageV2(
                contractVersion = 2,
                packageId = SOURCE_KEY.packageId,
                version = "1.0.0",
                displayName = "Fixture extension",
                sources = listOf(source.descriptor),
                supportedContentKinds = source.descriptor.supportedContentKinds,
            ),
            implementations = listOf(source),
        )
        val host = requireNotNull(ExtensionHostFacadeV2(runtime).source(SOURCE_KEY))
        return ExtensionContentConsumerV2(
            gateway = ExtensionBrowseContentGatewayV2(
                ExtensionSourceResolverV2 { requested -> host.takeIf { requested == SOURCE_KEY } },
            ),
            foundation = foundation,
            offlineStoreAuthorizer = HostContentBodyOfflineStoreAuthorizer(
                authority = foundation.rightsAuthority,
                durableGrant = foundation.rightsGrants::find,
                nowEpochMillis = { 1_000L },
            ),
            resourceFetcher = fetcher,
            nowEpochMillis = { 1_000L },
            localUnitProgress = localUnitProgress,
            epubArchiveExtractor = epubArchiveExtractor,
        )
    }

    private inline fun withFoundation(block: (ContentFoundationRuntime) -> Unit) {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        try {
            block(ContentFoundationRuntime(driver))
        } finally {
            driver.close()
        }
    }

    private inline fun withReopenableFoundation(
        block: (JdbcSqliteDriver, ContentFoundationRuntime) -> Unit,
    ) {
        val driver = JdbcSqliteDriver("jdbc:sqlite::memory:")
        try {
            block(driver, ContentFoundationRuntime(driver))
        } finally {
            driver.close()
        }
    }

    private class FixtureSource(
        private val payloads: (String) -> List<UnitContentPayload>,
        private val unitItems: () -> List<RemoteUnitV2> = {
            listOf(RemoteUnitV2(UNIT_ID, "Exact unit"))
        },
        supportedContentKinds: Set<ContentKind> = setOf(ContentKind.PLAIN_TEXT),
    ) : ExtensionSourceV2 {
        val requestedSourceKeys = mutableListOf<SourceKey>()
        val requestedPublicationIds = mutableListOf<String>()

        override val descriptor: SourceDescriptorV2 = SourceDescriptorV2(
            sourceKey = SOURCE_KEY,
            displayName = "Fixture source",
            languageTag = "en",
            supportedContentKinds = supportedContentKinds,
            capabilities = setOf(
                ExtensionCapability.METADATA,
                ExtensionCapability.UNITS,
                ExtensionCapability.CONTENT,
            ),
            baseUrl = "https://fixture.example",
        )

        override suspend fun browseOptions(): BrowseOptionsSchemaV2 = BrowseOptionsSchemaV2()
        override suspend fun search(query: String, page: Int): PagedResultV2<RemotePublicationV2> =
            error("not used")
        override suspend fun latest(page: Int): PagedResultV2<RemotePublicationV2> = error("not used")
        override suspend fun browse(options: BrowseOptionsV2, page: Int): PagedResultV2<RemotePublicationV2> =
            error("not used")

        override suspend fun details(remotePublicationId: String): RemotePublicationV2 {
            requestedSourceKeys += descriptor.sourceKey
            requestedPublicationIds += remotePublicationId
            return RemotePublicationV2(remotePublicationId, "Exact publication")
        }

        override suspend fun units(
            remotePublicationId: String,
            page: Int,
        ): PagedResultV2<RemoteUnitV2> {
            requestedSourceKeys += descriptor.sourceKey
            requestedPublicationIds += remotePublicationId
            return PagedResultV2(unitItems(), false)
        }

        override suspend fun content(
            remotePublicationId: String,
            remoteUnitId: String,
        ): UnitContentResultV2 = UnitContentResultV2(
            schemaVersion = 2,
            sourceKey = descriptor.sourceKey,
            remotePublicationId = remotePublicationId,
            remoteUnitId = remoteUnitId,
            representations = payloads(remoteUnitId),
        )

        override suspend fun openTextStream(streamId: String): TextChunkStreamV2 = error("not used")
        override suspend fun login(credentials: LoginCredentialsV2): LoginResultV2 = error("not used")
        override suspend fun logout(): Unit = error("not used")
        override suspend fun preferences(): List<PreferenceV2> = error("not used")
        override suspend fun favorite(remotePublicationId: String, favorite: Boolean): Unit = error("not used")
    }

    private companion object {
        val SOURCE_KEY = SourceKey(2, "fixture.extension", "opaque-source")
        const val PUBLICATION_ID = "opaque-publication"
        const val UNIT_ID = "opaque-unit"

        fun inlinePayload(
            representationId: String,
            remoteUnitId: String,
            text: String,
        ): UnitContentPayload.InlineTextPayload = UnitContentPayload.InlineTextPayload(
            schemaVersion = 2,
            representationId = representationId,
            sourceKey = SOURCE_KEY,
            remoteUnitId = remoteUnitId,
            source = TextPayloadSourceV2.InlineTextPayload(text),
            blocks = listOf(TextBlock("body", 0, text.length)),
        )

        fun textResource(): RemoteResourceV2 = RemoteResourceV2(
            id = "remote-body",
            request = RemoteRequestPlanV2(
                method = HttpMethodV2.GET,
                url = "https://fixture.example/body.txt",
            ),
            mediaType = "text/plain",
        )

        fun remoteEpubResource(
            id: String,
            href: String,
            mediaType: String,
            bytes: ByteArray,
        ): RemoteEpubResourceV2 = RemoteEpubResourceV2(
            id = id,
            href = href,
            body = RemoteBlobPlanV2(
                resource = RemoteResourceV2(
                    id = id,
                    request = RemoteRequestPlanV2(
                        method = HttpMethodV2.GET,
                        url = "https://fixture.example/$href",
                        maxResponseBytes = 1_000_000,
                    ),
                    mediaType = mediaType,
                ),
                expectedPlaintextDigest = Sha256.hex(bytes),
                expectedByteSize = bytes.size.toLong(),
            ),
            mediaType = mediaType,
        )

        fun remoteEpubArchivePlan(bytes: ByteArray): RemoteBlobPlanV2 = RemoteBlobPlanV2(
            resource = RemoteResourceV2(
                id = "archive",
                request = RemoteRequestPlanV2(
                    method = HttpMethodV2.GET,
                    url = "https://fixture.example/book.epub",
                ),
                mediaType = "application/epub+zip",
            ),
            expectedPlaintextDigest = Sha256.hex(bytes),
            expectedByteSize = bytes.size.toLong(),
        )

        fun remoteEpubResources(entries: List<EpubArchiveEntry>): List<RemoteEpubResourceV2> {
            val remoteIdsByHref = mapOf(
                "mimetype" to "remote-mimetype",
                "META-INF/container.xml" to "remote-container",
                "OPS/package.opf" to "remote-package",
                "OPS/chapter.xhtml" to "remote-chapter",
                "OPS/fallback.xhtml" to "remote-fallback",
                "OPS/overlay.smil" to "remote-overlay",
                "OPS/nav.xhtml" to "remote-nav",
                "OPS/toc.ncx" to "remote-ncx",
                "OPS/style.css" to "remote-style",
                "OPS/cover.png" to "remote-cover",
            )
            val mediaTypesByHref = mapOf(
                "mimetype" to "text/plain",
                "META-INF/container.xml" to "application/xml",
                "OPS/package.opf" to "application/oebps-package+xml",
                "OPS/chapter.xhtml" to "application/xhtml+xml",
                "OPS/fallback.xhtml" to "application/xhtml+xml",
                "OPS/overlay.smil" to "application/smil+xml",
                "OPS/nav.xhtml" to "application/xhtml+xml",
                "OPS/toc.ncx" to "application/x-dtbncx+xml",
                "OPS/style.css" to "text/css",
                "OPS/cover.png" to "image/png",
            )
            return entries.map { entry ->
                remoteEpubResource(
                    id = remoteIdsByHref.getValue(entry.path),
                    href = entry.path,
                    mediaType = mediaTypesByHref.getValue(entry.path),
                    bytes = entry.bytes,
                )
            }
        }

        fun minimalEpubEntries(): List<EpubArchiveEntry> = listOf(
            epubEntry("mimetype", "application/epub+zip"),
            epubEntry("META-INF/container.xml", MINIMAL_CONTAINER_XML),
            epubEntry("OPS/package.opf", MINIMAL_OPF_XML),
            epubEntry("OPS/chapter.xhtml", MINIMAL_XHTML),
            epubEntry("OPS/fallback.xhtml", MINIMAL_XHTML),
            epubEntry("OPS/overlay.smil", MINIMAL_SMIL),
            epubEntry("OPS/nav.xhtml", MINIMAL_NAV),
            epubEntry("OPS/toc.ncx", MINIMAL_NCX),
            epubEntry("OPS/style.css", MINIMAL_CSS),
            EpubArchiveEntry(
                "OPS/cover.png",
                byteArrayOf(1, 2, 3),
                compressedSizeBytes = 3,
            ),
        )

        fun epubEntry(path: String, text: String): EpubArchiveEntry {
            val bytes = text.trimIndent().encodeToByteArray()
            return EpubArchiveEntry(path, bytes, bytes.size.toLong().coerceAtLeast(1L))
        }

        const val MINIMAL_CONTAINER_XML: String = """
            <container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
              <rootfiles>
                <rootfile full-path="OPS/package.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """
        const val MINIMAL_OPF_XML: String = """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Fixture EPUB</dc:title>
                <meta property="rendition:layout">reflowable</meta>
              </metadata>
              <manifest>
                <item id="chapter" href="chapter.xhtml" media-type="application/xhtml+xml"
                      fallback="fallback" media-overlay="overlay"/>
                <item id="fallback" href="fallback.xhtml" media-type="application/xhtml+xml"/>
                <item id="overlay" href="overlay.smil" media-type="application/smil+xml"/>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="style" href="style.css" media-type="text/css"/>
                <item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/>
              </manifest>
              <spine toc="ncx" page-progression-direction="rtl">
                <itemref idref="chapter" properties="rendition:layout-pre-paginated page-spread-left"/>
              </spine>
            </package>
        """
        const val MINIMAL_XHTML: String = """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><link rel="stylesheet" href="style.css"/></head>
              <body><p>Fixture</p></body>
            </html>
        """
        const val MINIMAL_SMIL: String = """
            <smil xmlns="http://www.w3.org/ns/SMIL"><body><seq/></body></smil>
        """
        const val MINIMAL_NAV: String = """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:book="http://www.idpf.org/2007/ops">
              <body><nav book:type="toc"><a href="chapter.xhtml#start">Start</a></nav></body>
            </html>
        """
        const val MINIMAL_NCX: String = """
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/">
              <navMap>
                <navPoint id="start">
                  <navLabel><text>NCX Start</text></navLabel>
                  <content src="chapter.xhtml#start"/>
                </navPoint>
              </navMap>
            </ncx>
        """
        const val MINIMAL_CSS: String = """
            body { background-image: url('cover.png'); }
        """
    }
}
