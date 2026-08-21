package dev.shinsou.kmp.content

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContentManifestContractTest {
    @Test
    fun manifestSupportsMultipleRepresentationsOfTheSameKindWithoutBodyBytes() {
        val pageOne = ResourceRef("page-1", blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "image/png"))
        val pageTwo = ResourceRef("page-2", blob("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "image/png"))
        val manifest = ContentManifest(
            manifestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            schemaVersion = ContentManifest.CURRENT_SCHEMA_VERSION,
            contentRevision = 7,
            representations = listOf(
                ContentRepresentation.ImageSequence(
                    "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    listOf(ImagePage(pageOne)),
                ),
                ContentRepresentation.ImageSequence(
                    "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                    listOf(ImagePage(pageTwo)),
                ),
            ),
        )

        assertEquals(2, manifest.imageSequences.size)
        assertTrue(manifest.referencedBlobs.all { it is BlobRef })
    }

    @Test
    fun plainTextBlockMapIsBoundedAndManifestRejectsConflictingBlobAliases() {
        val textBlob = blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "text/plain")
        val text = ContentRepresentation.PlainText(
            representationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            resource = ResourceRef("text-body", textBlob),
            canonicalUtf16Length = 5,
            blocks = listOf(TextBlock("block-1", 0, 5)),
        )
        ContentManifest(
            manifestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            schemaVersion = 1,
            contentRevision = 0,
            representations = listOf(text),
        )
        assertFailsWith<IllegalArgumentException> {
            ContentRepresentation.PlainText(
                "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                ResourceRef("bad", blob("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee", "text/plain")),
                canonicalUtf16Length = 2,
                blocks = listOf(TextBlock("block", 0, 3)),
            )
        }
    }

    @Test
    fun blobRefsRequireCanonicalSchemaAndDigest() {
        assertFailsWith<IllegalArgumentException> {
            BlobRef("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", 2, "SHA-256", "bad", 1, "text/plain")
        }
        assertFailsWith<IllegalArgumentException> {
            BlobRef("00000000-0000-0000-0000-000000000000", 1, "SHA-256", DIGEST, 0, "text/plain")
        }
    }

    @Test
    fun hrefValidationRejectsNestedEscapesAndDeclaredSizeCannotOverflow() {
        val body = ResourceRef(
            "body",
            blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "application/xhtml+xml"),
        )
        assertFailsWith<IllegalArgumentException> {
            EpubResource("body", "%252e%252e/secret.xhtml", body)
        }
        EpubResource("body", "Text/Chapter%201.xhtml", body)

        val hugeA = BlobRef(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
            1,
            BlobRef.SHA_256,
            DIGEST,
            Long.MAX_VALUE,
            "image/png",
        )
        val hugeB = BlobRef(
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            1,
            BlobRef.SHA_256,
            DIGEST,
            1,
            "image/png",
        )
        assertFailsWith<IllegalArgumentException> {
            ContentManifest(
                manifestId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                schemaVersion = 1,
                contentRevision = 0,
                representations = listOf(
                    ContentRepresentation.ImageSequence(
                        "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
                        listOf(
                            ImagePage(ResourceRef("page-a", hugeA)),
                            ImagePage(ResourceRef("page-b", hugeB)),
                        ),
                    ),
                ),
                declaredSizeBytes = 0,
            )
        }
    }

    @Test
    fun imageMediaTypeAndTransformWhitelistRemainHostControlled() {
        assertFailsWith<IllegalArgumentException> {
            ImagePage(ResourceRef("page", blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "text/plain")))
        }

        (ImageTransform.SUPPORTED_TRANSFORMS as? MutableSet<String>)?.add("untrusted-transform")
        assertFalse("untrusted-transform" in ImageTransform.SUPPORTED_TRANSFORMS)
        assertFailsWith<IllegalArgumentException> {
            ImageTransform(1, "untrusted-transform")
        }
    }

    @Test
    fun epubRepresentationRequiresCanonicalArchiveMediaTypeAndResolvableGraph() {
        val archive = blob(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
            "application/epub+zip",
        )
        val packageDocument = EpubResource(
            id = "opf",
            href = "OPS/Content%20Package.opf",
            resource = ResourceRef(
                "opf",
                blob("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "application/oebps-package+xml"),
            ),
        )
        val graph = EpubPackage(
            archive = archive,
            packageDocumentId = "opf",
            resources = listOf(packageDocument),
        )
        val manifest = ContentManifest(
            manifestId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
            schemaVersion = 1,
            contentRevision = 1,
            representations = listOf(
                ContentRepresentation.EpubSpine(
                    representationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
                    packageGraph = graph,
                    documents = listOf(
                        EpubSpineDocument("spine-opf", packageDocument.href, packageDocument.id),
                    ),
                ),
            ),
        )
        assertEquals(setOf(ContentKind.EPUB_SPINE), manifest.kinds)

        assertFailsWith<IllegalArgumentException> {
            graph.copy(archive = archive.copy(mediaType = "application/zip"))
        }
    }

    @Test
    fun legacyEpubJsonWithoutAdditivePackageGraphFieldsStillDecodes() {
        val packageDocument = EpubResource(
            id = "opf",
            href = "OPS/package.opf",
            resource = ResourceRef(
                "opf",
                blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "application/oebps-package+xml"),
            ),
        )
        val legacyGraph = EpubPackage(
            archive = blob("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "application/epub+zip"),
            packageDocumentId = packageDocument.id,
            resources = listOf(packageDocument),
        )
        val encoded = Json.encodeToString(legacyGraph)

        assertFalse(encoded.contains("packageMetadata"))
        assertFalse(encoded.contains("spineTocManifestIdRef"))
        assertEquals(legacyGraph, Json.decodeFromString<EpubPackage>(encoded))

        val legacySpine = EpubSpineDocument("spine", packageDocument.href, packageDocument.id)
        val encodedSpine = Json.encodeToString(legacySpine)
        assertFalse(encodedSpine.contains("manifestIdRef"))
        assertEquals(legacySpine, Json.decodeFromString<EpubSpineDocument>(encodedSpine))
    }

    @Test
    fun epubAuxiliaryGraphHasOnePackageWideAggregateLimit() {
        val packageDocument = EpubResource(
            id = "opf",
            href = "OPS/package.opf",
            resource = ResourceRef(
                "opf",
                blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "application/oebps-package+xml"),
            ),
        )
        val stylesheet = EpubResource(
            id = "style",
            href = "OPS/style.css",
            resource = ResourceRef(
                "style",
                blob("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "text/css"),
            ),
        )
        val repeatedExternalReference = EpubPackageCssReference(
            declaredReference = "https://example.test/theme.css",
            external = true,
        )
        val dependency = EpubPackageCssDependency(
            stylesheetHref = stylesheet.href,
            references = List(MAX_EPUB_AUXILIARY_GRAPH_ENTRIES) { repeatedExternalReference },
        )

        assertFailsWith<IllegalArgumentException> {
            EpubPackage(
                archive = blob("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "application/epub+zip"),
                packageDocumentId = packageDocument.id,
                resources = listOf(packageDocument, stylesheet),
                manifest = listOf(
                    EpubPackageManifestItem(
                        manifestIdRef = "style",
                        declaredHref = "style.css",
                        resolvedHref = stylesheet.href,
                        resourceId = stylesheet.id,
                        mediaType = "text/css",
                    ),
                ),
                cssDependencies = listOf(dependency),
            )
        }
    }

    @Test
    fun epubManifestIdrefUsesAuthoritativeHrefAcrossHostResourceIdRemapping() {
        val packageDocument = EpubResource(
            id = "remote-opf",
            href = "OPS/package.opf",
            resource = ResourceRef(
                "remote-opf",
                blob("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "application/oebps-package+xml"),
            ),
        )
        val chapter = EpubResource(
            id = "remote-chapter",
            href = "OPS/chapter.xhtml",
            resource = ResourceRef(
                "remote-chapter",
                blob("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", "application/xhtml+xml"),
            ),
        )
        val declaration = EpubPackageManifestItem(
            manifestIdRef = "chapter",
            declaredHref = "chapter.xhtml",
            resolvedHref = chapter.href,
            resourceId = "stale-host-derived-id",
            mediaType = chapter.mediaType,
        )
        val graph = EpubPackage(
            archive = blob("cccccccc-cccc-4ccc-8ccc-cccccccccccc", "application/epub+zip"),
            packageDocumentId = packageDocument.id,
            resources = listOf(packageDocument, chapter),
            manifest = listOf(declaration),
        )

        ContentRepresentation.EpubSpine(
            representationId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd",
            packageGraph = graph,
            documents = listOf(
                EpubSpineDocument(
                    id = "spine-chapter",
                    href = chapter.href,
                    resourceId = chapter.id,
                    manifestIdRef = declaration.manifestIdRef,
                ),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            graph.copy(
                manifest = listOf(declaration.copy(resourceId = packageDocument.id)),
            )
        }
    }

    private fun blob(id: String, mediaType: String): BlobRef =
        BlobRef(id, 1, BlobRef.SHA_256, DIGEST, 0, mediaType)

    private companion object {
        const val DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
