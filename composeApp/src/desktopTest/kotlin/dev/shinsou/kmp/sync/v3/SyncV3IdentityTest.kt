package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.domain.model.UnitKey
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.local.encodeTypedLocalPublicationUrl
import dev.shinsou.kmp.reader.ReadingLocator
import dev.shinsou.kmp.reader.ReadingScope
import dev.shinsou.kmp.reader.TextQuote
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncV3IdentityTest {
    private val fixtures = ExtensionFixtures()
    private val legacy = SourceKey(packageId = "zh.legacy", sourceId = "legacy-src")
    private val identity = SyncIdentityResolver({ fixtures.publications[it] }, { id -> legacy.takeIf { id == 77L } })

    @Test
    fun extensionV2TitleKeysComeFromTheLibraryUrlNotFromLocalIds() {
        val manga = fixtures.manga("remote-1", "T").copy(id = 41)
        val chapters = fixtures.chapters(41, "remote-1", listOf("c-a", "c-b")).mapIndexed { i, c -> c.copy(id = 900L + i) }
        val work = assertIs<SyncedWork.Sourced>(identity.workOf(manga))
        assertEquals(WorkKey(1, TestSourceKey.packageId, TestSourceKey.sourceId, "remote-1"), work.key)
        assertEquals(work.key.hash, work.recordKey)
        assertEquals("c-a", identity.unitRemoteId(manga, work, chapters[0]))
        assertEquals("c-b", identity.unitRemoteId(manga, work, chapters[1]))
        // The contract version is not part of the work key.
        val otherContract = manga.copy(
            url = dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl(TestSourceKey.copy(contractVersion = 3), "remote-1"),
        )
        assertEquals(work.key, assertIs<SyncedWork.Sourced>(identity.workOf(otherContract)).key)
        // A chapter whose publication graph is not materialised yet cannot be addressed.
        val orphan = Chapter(id = 1, mangaId = 41, url = chapters[0].url.replace(chapters[0].url.substringAfterLast('/'), "00000000-0000-4000-8000-000000000000"))
        assertNull(identity.unitRemoteId(manga, work, orphan))
    }

    @Test
    fun aSourceTitleHasTheSameWorkBeforeAndAfterItIsInTheLibrary() {
        val inLibrary = assertIs<SyncedWork.Sourced>(identity.workOf(fixtures.manga("remote-1", "T")))
        assertEquals(inLibrary, identity.sourceWorkOf(TestSourceKey, "remote-1"))
        assertEquals(inLibrary.recordKey, identity.sourceWorkOf(TestSourceKey.copy(contractVersion = 3), "remote-1")?.recordKey)
        // An id the work key cannot carry gives no work instead of throwing.
        assertNull(identity.sourceWorkOf(TestSourceKey, ""))
    }

    @Test
    fun legacyV1TitlesNeedTheirSourceToBeResolved() {
        val resolved = Manga(id = 1, source = 77, url = "/b/1", title = "L", favorite = true)
        val unresolved = Manga(id = 2, source = 88, url = "/b/2", title = "U", favorite = true)
        val work = assertIs<SyncedWork.Sourced>(identity.workOf(resolved))
        assertEquals(WorkKey(1, "zh.legacy", "legacy-src", "/b/1"), work.key)
        assertEquals("/ch/5", identity.unitRemoteId(resolved, work, Chapter(id = 3, mangaId = 1, url = "/ch/5")))
        assertNull(identity.workOf(unresolved))
        assertNull(identity.workOf(resolved.copy(url = "")))
    }

    @Test
    fun localImportsUseThePublicationUuidAndNeverTheSourceKey() {
        val pub = PublicationKey("11111111-1111-4111-8111-111111111111")
        val acquisitionId = "22222222-2222-4222-8222-222222222222"
        val unit = UnitKey(pub, "33333333-3333-4333-8333-333333333333")
        fixtures.publications[pub] = Publication(
            key = pub,
            title = "Local book",
            acquisitions = listOf(Acquisition(acquisitionId, AcquisitionOrigin.LocalEpub, listOf(PublicationUnit(unit, "Body")))),
        )
        val manga = Manga(id = 5, source = 0, url = encodeTypedLocalPublicationUrl(pub), title = "Local book", favorite = true)
        val work = assertIs<SyncedWork.Local>(identity.workOf(manga))
        assertEquals("local:${pub.value}", work.recordKey)
        val chapter = Chapter(id = 6, mangaId = 5, url = encodeTypedLocalChapterUrl(pub, acquisitionId, unit))
        assertEquals(unit.value, identity.unitRemoteId(manga, work, chapter))
        assertEquals("manga", identity.contentKindOf(manga, work), "unknown content defaults to manga")
        assertNull(identity.workOf(manga.copy(url = "local://manga/some-folder")), "other local namespaces are not portable")
        assertNotNull(identity.scopeFor(chapter))
    }

    @Test
    fun descriptorsOnlyCarryHttpsCoversAndBoundedTitles() {
        val manga = fixtures.manga("remote-2", "x".repeat(900)).copy(thumbnailUrl = "http://insecure/cover.png", author = "  ")
        val work = assertIs<SyncedWork.Sourced>(identity.workOf(manga))
        val described = identity.describe(manga, work)
        assertEquals(512, described.title.length)
        assertNull(described.coverUrl)
        assertNull(described.author)
        assertEquals(work.key.hash, described.hash)
        assertEquals("https://x/c.png", identity.describe(manga.copy(thumbnailUrl = "https://x/c.png"), work).coverUrl)
    }

    @Test
    fun locatorScopeIsStrippedForTheWireAndRebuiltFromLocalIdentity() {
        val chapters = fixtures.chapters(1, "remote-3", listOf("u1"))
        val localScope = identity.scopeFor(chapters.single())!!
        val locator = ReadingLocator.Text(
            schemaVersion = 1,
            scope = localScope,
            resourceId = "body",
            blockId = "b-7",
            offset = 12,
            progression = 0.4,
            quote = TextQuote(exact = "hello", prefix = "oh ", suffix = " world"),
        )
        val wire = PortableLocator.toWire(locator)
        assertFalse("scope" in wire, "device-local identity must not be sent")
        assertEquals(JsonPrimitive("text"), wire["type"])
        val otherDevice = ReadingScope(
            schemaVersion = 1,
            publicationId = localScope.publicationId,
            acquisitionId = localScope.acquisitionId,
            unitId = localScope.unitId,
            contentRevision = 9,
        )
        val rebuilt = assertIs<ReadingLocator.Text>(PortableLocator.fromWire(wire, otherDevice))
        assertEquals(otherDevice, rebuilt.scope)
        assertEquals(locator.copy(scope = otherDevice), rebuilt)
    }

    @Test
    fun progressLocatorTravelsBetweenDevicesWithTheReceiversScope() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A", fixtures = ExtensionFixtures(contentRevision = 0))
        val b = TestDevice(server, "B", fixtures = ExtensionFixtures(contentRevision = 5))
        try {
            val manga = a.repository.upsertManga(a.fixtures.manga("remote-4", "Four"))
            val chapter = a.repository.upsertChapters(a.fixtures.chapters(manga.id, "remote-4", listOf("u1"))).single()
            val scopeA = a.identity.scopeFor(chapter)!!
            assertEquals(0, scopeA.contentRevision)
            val locator = ReadingLocator.Text(
                schemaVersion = 1, scope = scopeA, resourceId = "body", blockId = "b-1", offset = 3, progression = 0.25,
            )
            a.repository.markChapterProgress(chapter.id, lastPageRead = 0, read = false, readAt = 1_700_000_500_000, lastLocator = locator)
            a.signIn(register = true)
            assertTrue(a.sync())
            val record = server.accountRecords("alice").getValue(SyncKinds.PROGRESS to WorkKey(1, TestSourceKey.packageId, TestSourceKey.sourceId, "remote-4").hash)
            assertFalse(record.fields.getValue("locator").value.toString().contains("scope"))
            assertEquals(JsonPrimitive(0.25), record.fields.getValue("progression").value)

            b.signIn()
            assertTrue(b.sync())
            val created = b.repository.currentSnapshot.mangas.single()
            val chB = b.repository.upsertChapters(b.fixtures.chapters(created.id, "remote-4", listOf("u1"))).single()
            b.service.retryPendingRemote()
            val history = b.repository.currentSnapshot.histories.single { it.chapterId == chB.id }
            val rebuilt = assertIs<ReadingLocator.Text>(history.lastLocator)
            assertEquals(5, rebuilt.scope.contentRevision, "scope is rebuilt from B's own content")
            assertEquals(b.identity.scopeFor(chB), rebuilt.scope)
            assertEquals(3, rebuilt.offset)
            assertEquals(1_700_000_500_000, history.lastRead)
        } finally {
            a.close(); b.close()
        }
    }
}
