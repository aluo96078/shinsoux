package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.ChapterPatch
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.SyncKindSettings
import dev.shinsou.kmp.domain.model.ThemeMode
import dev.shinsou.kmp.plugin.AccountPackage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The per-device "sync items" switches and the account's `package` kind. */
class SyncV3KindSwitchTest {
    private fun workHash(remoteId: String) = WorkKey(1, TestSourceKey.packageId, TestSourceKey.sourceId, remoteId).hash

    private suspend fun TestDevice.addTitle(remoteId: String, title: String, units: List<String>): Long {
        val manga = repository.upsertManga(fixtures.manga(remoteId, title))
        repository.upsertChapters(fixtures.chapters(manga.id, remoteId, units))
        return manga.id
    }

    private suspend fun TestDevice.kinds(change: (SyncKindSettings) -> SyncKindSettings) {
        repository.updateSettings { it.copy(sync = it.sync.copy(kinds = change(it.sync.kinds))) }
        service.kindsChanged()
    }

    private fun TestDevice.serverHas(server: FakeSyncServer, kind: String, key: String) =
        server.accountRecords("alice")[kind to key]?.isLive == true

    private val repo = ExtensionRepo(baseUrl = "https://plugins.example.org/repo", name = "Example", signingKeyFingerprint = "ab12")
    private val pkg = AccountPackage(
        packageId = "zh.test~0123456789abcdef",
        repositoryUrl = "https://plugins.example.org/repo",
        name = "Test package",
        version = "1.2.0",
        versionCode = 12,
        signingKeyFingerprint = "ab12",
    )

    @Test
    fun theSwitchesAreOnByDefaultAndNeverPortable() {
        val all = SyncKinds.enabled(SyncKindSettings())
        assertEquals(SyncKinds.ALL.toSet(), all, "every kind is on until the user switches it off")
        assertEquals(
            SyncKinds.ALL.toSet() - setOf(SyncKinds.UNIT, SyncKinds.PROGRESS, SyncKinds.HISTORY),
            SyncKinds.enabled(SyncKindSettings(readingProgress = false)),
            "reading progress and history are one switch",
        )
        // Device-local: not on the portable list, and changing them records nothing to sync.
        assertTrue(PortableSettings.paths.none { it.startsWith("sync.") })
        val before = AppSnapshot()
        val after = before.copy(settings = before.settings.copy(sync = before.settings.sync.copy(kinds = SyncKindSettings(library = false, packages = false))))
        assertTrue(PortableSettings.changed(before.settings, after.settings).isEmpty())
        assertFalse(PortableSettings.isPortable("sync.kinds.library"))
    }

    @Test
    fun aKindSwitchedOffIsNotSentAndIsSentWhenSwitchedOn() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            assertTrue(a.sync())
            val baseline = server.accountRecords("alice").keys
            a.kinds { it.copy(library = false, readingProgress = false, categories = false, repositories = false, settings = false) }

            val mangaId = a.addTitle("book-1", "One", listOf("u1"))
            a.repository.patchChapter(a.repository.currentSnapshot.chapters.single { it.mangaId == mangaId }.id, ChapterPatch(read = true))
            a.repository.recordHistory(a.repository.currentSnapshot.chapters.single().id, lastRead = 1_700_000_100_000)
            a.repository.createCategory("Favourites")
            a.repository.upsertExtensionRepository(repo)
            a.repository.updateSettings { it.copy(appearance = it.appearance.copy(theme = ThemeMode.DARK)) }
            assertTrue(a.sync())
            assertEquals(baseline, server.accountRecords("alice").keys, "nothing leaves while every item is off")
            assertEquals(0, a.service.state.value.pendingCount, "what waits behind a switch is not a backlog")

            a.kinds { it.copy(library = true) }
            assertTrue(a.sync())
            val records = server.accountRecords("alice")
            assertTrue(records.getValue(SyncKinds.LIBRARY to workHash("book-1")).isLive)
            assertEquals(baseline + (SyncKinds.LIBRARY to workHash("book-1")), records.keys, "only the switched-on kind")

            a.kinds { it.copy(readingProgress = true, categories = true, repositories = true, settings = true) }
            assertTrue(a.sync())
            val all = server.accountRecords("alice").keys.map { it.first }.toSet()
            assertTrue(setOf(SyncKinds.UNIT, SyncKinds.HISTORY, SyncKinds.PROGRESS, SyncKinds.CATEGORY, SyncKinds.REPOSITORY, SyncKinds.SETTING).all { it in all }, all.toString())
            assertEquals(0, a.store.dirtyRefCount())
        } finally {
            a.close()
        }
    }

    @Test
    fun aKindSwitchedOffKeepsWhatArrivesUnappliedUntilSwitchedOn() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            assertTrue(b.sync())
            b.kinds { it.copy(library = false, categories = false, repositories = false, settings = false) }

            a.addTitle("book-1", "One", listOf("u1"))
            a.repository.createCategory("Favourites")
            a.repository.upsertExtensionRepository(repo)
            a.repository.updateSettings { it.copy(appearance = it.appearance.copy(theme = ThemeMode.DARK)) }
            assertTrue(a.sync())

            assertTrue(b.sync())
            val snapshot = b.repository.currentSnapshot
            assertTrue(snapshot.mangas.none { it.favorite }, "library is off")
            assertTrue(snapshot.categories.none { it.name == "Favourites" })
            assertTrue(snapshot.extensionRepositories.isEmpty())
            assertEquals(AppSnapshot().settings.appearance.theme, snapshot.settings.appearance.theme)
            assertTrue(b.service.accountRepositories.value.isEmpty(), "no suggestions while repositories are off")
            assertEquals(0, b.service.state.value.pendingRemoteCount, "disabled kinds do not count as waiting")
            assertTrue(b.store.unappliedCount() > 0, "the records are kept")

            b.kinds { it.copy(library = true, categories = true, repositories = true, settings = true) }
            assertTrue(b.sync())
            val after = b.repository.currentSnapshot
            assertEquals(listOf("One"), after.mangas.filter { it.favorite }.map { it.title })
            assertTrue(after.categories.any { it.name == "Favourites" })
            assertEquals(ThemeMode.DARK, after.settings.appearance.theme)
            assertEquals(listOf(repo.baseUrl), b.service.accountRepositories.value.map { it.url })
            assertEquals(0, b.store.unappliedCount())
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun readingProgressStaysUnappliedWhileItsSwitchIsOff() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            val mangaA = a.addTitle("book-1", "One", listOf("u1", "u2"))
            a.signIn(register = true)
            b.signIn()
            assertTrue(a.sync())
            assertTrue(b.sync())
            val mangaB = b.repository.currentSnapshot.mangas.single().id
            b.repository.upsertChapters(b.fixtures.chapters(mangaB, "book-1", listOf("u1", "u2")))
            b.kinds { it.copy(readingProgress = false) }

            a.repository.patchChapter(a.repository.currentSnapshot.chapters.filter { it.mangaId == mangaA }[0].id, ChapterPatch(read = true))
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertFalse(b.repository.currentSnapshot.chapters.first { it.mangaId == mangaB }.read)

            // Reading on B while it is off stays on B, too.
            b.repository.patchChapter(b.repository.currentSnapshot.chapters.filter { it.mangaId == mangaB }[1].id, ChapterPatch(read = true))
            assertTrue(b.sync())
            assertNull(server.accountRecords("alice")[SyncKinds.UNIT to WorkKey(1, TestSourceKey.packageId, TestSourceKey.sourceId, "book-1").unitHash("u2")])

            b.kinds { it.copy(readingProgress = true) }
            assertTrue(b.sync())
            val chapters = b.repository.currentSnapshot.chapters.filter { it.mangaId == mangaB }
            assertTrue(chapters[0].read, "what A read while B was off is applied")
            assertTrue(chapters[1].read)
            assertNotNull(server.accountRecords("alice")[SyncKinds.UNIT to WorkKey(1, TestSourceKey.packageId, TestSourceKey.sourceId, "book-1").unitHash("u2")], "and B's own reading is sent")
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun anInstalledPackageReachesTheOtherDevicesAndUninstallingSendsNoTombstone() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.service.packagesChanged(listOf(pkg))
            assertTrue(a.sync())

            val record = server.accountRecords("alice").getValue(SyncKinds.PACKAGE to pkg.packageId)
            assertTrue(record.isLive)
            assertEquals(JsonPrimitive("https://plugins.example.org/repo"), record.fields.getValue("repositoryUrl").value)
            assertEquals(JsonPrimitive("Test package"), record.fields.getValue("name").value)
            assertEquals(JsonPrimitive(12), record.fields.getValue("versionCode").value)
            assertEquals(JsonPrimitive("ab12"), record.fields.getValue("signingKeyFingerprint").value)

            assertTrue(b.sync())
            assertEquals(listOf(pkg), b.service.accountPackages.value)
            assertEquals(0, b.store.unappliedCount(), "a package record never touches the library")

            a.service.packagesChanged(emptyList())
            assertTrue(a.sync())
            assertTrue(server.accountRecords("alice").getValue(SyncKinds.PACKAGE to pkg.packageId).isLive, "uninstalling is local")
            assertTrue(b.sync())
            assertEquals(listOf(pkg), b.service.accountPackages.value)

            // An update of the installed version is sent as a change of the same record.
            a.service.packagesChanged(listOf(pkg.copy(version = "1.3.0", versionCode = 13)))
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertEquals("1.3.0", b.service.accountPackages.value.single().version)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun packagesReportedBeforeSigningInAreSentAfterwards() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.service.packagesChanged(listOf(pkg))
            a.signIn(register = true)
            assertTrue(a.sync())
            assertTrue(server.accountRecords("alice").containsKey(SyncKinds.PACKAGE to pkg.packageId))
            // A key the server could never accept is not even tried.
            a.service.packagesChanged(listOf(pkg.copy(packageId = "bad key!")))
            assertTrue(a.sync())
            assertFalse(server.accountRecords("alice").containsKey(SyncKinds.PACKAGE to "bad key!"))
        } finally {
            a.close()
        }
    }

    @Test
    fun thePackagesSwitchHidesAndKeepsTheAccountsList() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.service.packagesChanged(listOf(pkg))
            assertTrue(a.sync())
            b.kinds { it.copy(packages = false) }
            assertTrue(b.sync())
            assertTrue(b.service.accountPackages.value.isEmpty())
            b.kinds { it.copy(packages = true) }
            assertTrue(b.sync())
            assertEquals(listOf(pkg), b.service.accountPackages.value)

            // Off on the sending side: new installs wait, then go out when switched on.
            a.kinds { it.copy(packages = false) }
            a.service.packagesChanged(listOf(pkg, pkg.copy(packageId = "zh.other", name = "Other")))
            assertTrue(a.sync())
            assertFalse(server.accountRecords("alice").containsKey(SyncKinds.PACKAGE to "zh.other"))
            a.kinds { it.copy(packages = true) }
            assertTrue(a.sync())
            assertTrue(server.accountRecords("alice").containsKey(SyncKinds.PACKAGE to "zh.other"))
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun anOlderServerRefusingPackagesDoesNotWedgeTheOtherRecords() = runBlocking<Unit> {
        val server = FakeSyncServer()
        server.unknownKinds = setOf(SyncKinds.PACKAGE)
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            a.service.packagesChanged(listOf(pkg))
            a.addTitle("book-1", "One", listOf("u1"))
            assertTrue(a.sync(), "a refused record is not a failed cycle")
            assertTrue(server.accountRecords("alice").getValue(SyncKinds.LIBRARY to workHash("book-1")).isLive)
            assertEquals(REJECT_UNSUPPORTED_KIND, a.store.loadRecord(SyncKinds.PACKAGE, pkg.packageId)?.rejectReason)
            assertEquals(0, a.service.state.value.rejectedCount, "not shown as a failure")
            assertEquals(0, a.service.state.value.pendingCount, "and not a backlog")

            // Parked: further cycles do not send it again, and later edits still flow.
            val pushes = server.pushCount
            assertTrue(a.sync())
            a.repository.updateSettings { it.copy(appearance = it.appearance.copy(theme = ThemeMode.DARK)) }
            assertTrue(a.sync())
            assertTrue(server.pushCount <= pushes + 1, "only the settings edit was pushed (${server.pushCount} vs $pushes)")
            assertTrue(server.accountRecords("alice").containsKey(SyncKinds.SETTING to "appearance.theme"))
        } finally {
            a.close()
        }
    }
}
