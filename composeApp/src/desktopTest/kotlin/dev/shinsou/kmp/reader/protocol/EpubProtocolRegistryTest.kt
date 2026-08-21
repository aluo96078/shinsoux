package dev.shinsou.kmp.reader.protocol

import dev.shinsou.kmp.content.BlobRef
import dev.shinsou.kmp.content.ContentRepresentation
import dev.shinsou.kmp.content.EpubPackage
import dev.shinsou.kmp.content.EpubResource
import dev.shinsou.kmp.content.EpubSpineDocument
import dev.shinsou.kmp.content.ResourceRef
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.reader.EpubPublicationResourceResolver
import dev.shinsou.kmp.reader.EpubRenderRequest
import dev.shinsou.kmp.reader.EpubRenderResource
import dev.shinsou.kmp.reader.EpubSpineNavigation
import dev.shinsou.kmp.reader.EpubUserStyleSheet
import dev.shinsou.kmp.reader.ReadingScope
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EpubProtocolRegistryTest {
    @Test
    fun sessionsUseIndependentUnguessableOriginsAndFailClosedOutsideTheirRegistryEntry() {
        val request = request("A")
        val firstOwner = Any()
        val secondOwner = Any()
        val firstResolver = EpubPublicationResourceResolver(request)
        val secondResolver = EpubPublicationResourceResolver(request)
        val first = EpubProtocolRegistry.register(firstOwner, firstResolver)
        val second = EpubProtocolRegistry.register(secondOwner, secondResolver)

        try {
            assertNotEquals(first.browserPublicationRootUrl, second.browserPublicationRootUrl)
            assertTrue(SESSION_ROOT.matches(first.browserPublicationRootUrl))
            assertTrue(SESSION_ROOT.matches(second.browserPublicationRootUrl))

            val firstDocument = assertNotNull(first.browserUrl(request.documentUrl))
            val secondDocument = assertNotNull(second.browserUrl(request.documentUrl))
            val firstResponse = assertNotNull(EpubProtocolRegistry.resolve(firstDocument))
            val rendered = firstResponse.bytes.decodeToString()
            assertTrue(first.browserPublicationRootUrl in rendered)
            assertFalse(request.publicationRootUrl in rendered)
            assertTrue(
                assertNotNull(firstResponse.headers["Content-Security-Policy"])
                    .contains("navigate-to shinsou-epub:"),
            )
            assertNotNull(EpubProtocolRegistry.resolve(secondDocument))

            assertNull(EpubProtocolRegistry.resolve(request.documentUrl))
            assertNull(EpubProtocolRegistry.resolve("https://example.com/chapter.xhtml"))
            assertNull(EpubProtocolRegistry.resolve(first.browserPublicationRootUrl + "../foreign/chapter.xhtml"))
            assertNull(EpubProtocolRegistry.resolve(first.browserPublicationRootUrl + "OPS/missing.xhtml"))
        } finally {
            EpubProtocolRegistry.unregister(firstOwner, first)
            EpubProtocolRegistry.unregister(secondOwner, second)
        }
        assertTrue(firstResolver.isClosed)
        assertTrue(secondResolver.isClosed)
    }

    @Test
    fun updateStagesResolverUntilRollbackOrCommitAtTheSessionBoundary() {
        val owner = Any()
        val firstResolver = EpubPublicationResourceResolver(request("first"))
        val session = EpubProtocolRegistry.register(owner, firstResolver)
        val documentUrl = assertNotNull(session.browserUrl(firstResolver.request.documentUrl))
        assertNotNull(EpubProtocolRegistry.resolve(documentUrl))
        val replacement = EpubPublicationResourceResolver(request("replacement"))
        val committed = EpubPublicationResourceResolver(request("committed"))

        try {
            EpubProtocolRegistry.update(owner, session, replacement)
            assertFalse(firstResolver.isClosed)
            assertFalse(replacement.isClosed)
            assertTrue(
                "replacement" in assertNotNull(EpubProtocolRegistry.resolve(documentUrl)).bytes.decodeToString(),
            )

            EpubProtocolRegistry.rollback(owner, session)
            assertTrue(replacement.isClosed)
            assertFalse(firstResolver.isClosed)
            assertTrue("first" in assertNotNull(EpubProtocolRegistry.resolve(documentUrl)).bytes.decodeToString())

            EpubProtocolRegistry.update(owner, session, committed)
            assertFalse(firstResolver.isClosed)
            EpubProtocolRegistry.commit(owner, session)
            assertTrue(firstResolver.isClosed)
            assertFalse(committed.isClosed)
            assertTrue("committed" in assertNotNull(EpubProtocolRegistry.resolve(documentUrl)).bytes.decodeToString())
        } finally {
            EpubProtocolRegistry.unregister(owner, session)
            if (!replacement.isClosed) replacement.close()
            if (!committed.isClosed) committed.close()
        }
        assertTrue(committed.isClosed)
        assertNull(EpubProtocolRegistry.resolve(documentUrl))
    }

    @Test
    fun desktopHtmlScopeRemovesRefreshAndOnlyRewritesExactHostStyleLinks() {
        val canonicalStyle = CANONICAL_ROOT + "__shinsou_user_styles__/0.css"
        val browserRoot = "shinsou-epub://s-0123456789abcdef0123456789abcdef0123456789abcdef.invalid/" +
            CANONICAL_ROOT.removePrefix("shinsou-epub://")
        val browserStyle = browserRoot + "__shinsou_user_styles__/0.css"
        val comment = "<!-- <meta http-equiv=refresh content='0;url=https://comment.invalid'/> -->"
        val unmatchedCss =
            "<style>.probe::before{content:'<meta http-equiv=refresh content=css-keep>;color:red}</style>"
        val scriptLiteral =
            "<script>const x=\"<link rel='stylesheet' href='$canonicalStyle'>\";if(1<2){x.length}</script>"
        val html = """
            <html><head>
            $comment
            <![CDATA[<meta http-equiv="refresh" content="0;url=https://cdata.invalid"/>]]>
            $unmatchedCss
            $scriptLiteral
            <MeTa content="0;url=https://example.com" HTTP-EQUIV="r&#101;fresh"/>
            <meta http-equiv='r&#x65;fresh' content='remove-hex'/>
            <meta content=remove-me HTTP-EQUIV=REFRESH/>
            <meta content=raw-remove HTTP-EQUIV=REFRESH/>
            <link href=$canonicalStyle rel=stylesheet/>
            <link rel="stylesheet" href="${CANONICAL_ROOT}publisher.css"/>
            </head><body data-root="$CANONICAL_ROOT"><a href="$CANONICAL_ROOT">publisher text</a></body></html>
        """.trimIndent()

        val scoped = scopeDesktopEpubHtml(html, mapOf(canonicalStyle to browserStyle))

        assertFalse("0;url=https://example.com" in scoped)
        assertFalse("remove-hex" in scoped)
        assertFalse("remove-me" in scoped)
        assertTrue(comment in scoped)
        assertTrue("https://cdata.invalid" in scoped)
        assertTrue(unmatchedCss in scoped)
        assertTrue(scriptLiteral in scoped)
        assertFalse("raw-remove" in scoped)
        assertTrue("href=$browserStyle" in scoped)
        assertTrue("href=\"${CANONICAL_ROOT}publisher.css\"" in scoped)
        assertTrue("data-root=\"$CANONICAL_ROOT\"" in scoped)
        assertTrue("<a href=\"$CANONICAL_ROOT\">publisher text</a>" in scoped)
    }

    @Test
    fun protocolResponsePreservesPublisherCanonicalRootTextWhileScopingHostStyleUrl() {
        val owner = Any()
        val resolver = EpubPublicationResourceResolver(request(CANONICAL_ROOT))
        val session = EpubProtocolRegistry.register(owner, resolver)

        try {
            val documentUrl = assertNotNull(session.browserUrl(resolver.request.documentUrl))
            val rendered = assertNotNull(EpubProtocolRegistry.resolve(documentUrl)).bytes.decodeToString()
            assertTrue("<p>$CANONICAL_ROOT</p>" in rendered)
            assertTrue(session.browserPublicationRootUrl in rendered)
        } finally {
            EpubProtocolRegistry.unregister(owner, session)
        }
    }

    private fun request(body: String): EpubRenderRequest {
        val packageResource = epubResource(
            "package",
            "OPS/package.opf",
            "application/oebps-package+xml",
            "11111111-1111-4111-8111-111111111111",
        )
        val chapterResource = epubResource(
            "chapter",
            "OPS/chapter.xhtml",
            "application/xhtml+xml",
            "22222222-2222-4222-8222-222222222222",
        )
        val representation = ContentRepresentation.EpubSpine(
            representationId = "33333333-3333-4333-8333-333333333333",
            packageGraph = EpubPackage(
                archive = blob("44444444-4444-4444-8444-444444444444", "application/epub+zip"),
                packageDocumentId = packageResource.id,
                resources = listOf(packageResource, chapterResource),
            ),
            documents = listOf(EpubSpineDocument("spine", chapterResource.href, chapterResource.id)),
        )
        val publication = PublicationKey("55555555-5555-4555-8555-555555555555")
        val navigation = EpubSpineNavigation(
            ReadingScope(
                schemaVersion = ReadingScope.CURRENT_SCHEMA_VERSION,
                publicationId = publication,
                acquisitionId = "66666666-6666-4666-8666-666666666666",
                unitId = UnitKey(publication, "77777777-7777-4777-8777-777777777777"),
                contentRevision = 1,
            ),
            representation,
        )
        return EpubRenderRequest(
            navigation = navigation,
            documentIndex = 0,
            initialLocator = navigation.locatorAt(0),
            publisherResources = listOf(
                EpubRenderResource(
                    resourceId = "chapter",
                    href = "OPS/chapter.xhtml",
                    mediaType = "application/xhtml+xml",
                    bytes = (
                        "<html xmlns=\"http://www.w3.org/1999/xhtml\"><head></head>" +
                            "<body><p>$body</p></body></html>"
                        ).encodeToByteArray(),
                ),
            ),
            userStyleSheets = listOf(EpubUserStyleSheet("reader", "body { color: black; }")),
        )
    }

    private fun epubResource(
        id: String,
        href: String,
        mediaType: String,
        blobId: String,
    ): EpubResource = EpubResource(id, href, ResourceRef(id, blob(blobId, mediaType)))

    private fun blob(blobId: String, mediaType: String): BlobRef = BlobRef(
        blobId = blobId,
        schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
        digestAlgorithm = BlobRef.SHA_256,
        plaintextDigest = EMPTY_SHA256,
        byteSize = 0,
        mediaType = mediaType,
    )

    private companion object {
        const val CANONICAL_ROOT: String =
            "shinsou-epub://publication/" +
                "55555555-5555-4555-8555-555555555555/" +
                "66666666-6666-4666-8666-666666666666/" +
                "77777777-7777-4777-8777-777777777777/1/" +
                "33333333-3333-4333-8333-333333333333/"
        val SESSION_ROOT = Regex(
            "shinsou-epub://s-[0-9a-f]{48}\\.invalid/publication/" +
                "55555555-5555-4555-8555-555555555555/" +
                "66666666-6666-4666-8666-666666666666/" +
                "77777777-7777-4777-8777-777777777777/1/" +
                "33333333-3333-4333-8333-333333333333/",
        )
        const val EMPTY_SHA256: String =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
}
