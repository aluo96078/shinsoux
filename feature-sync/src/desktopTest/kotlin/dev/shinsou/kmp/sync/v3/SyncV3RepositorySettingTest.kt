package dev.shinsou.kmp.sync.v3

import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.domain.model.AppSettings
import dev.shinsou.kmp.domain.model.ExtensionRepo
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.ReadingMode
import dev.shinsou.kmp.domain.model.ThemeMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SyncV3RepositorySettingTest {
    private val repoUrl = "https://plugins.example.org/repo"
    private val repo = ExtensionRepo(baseUrl = repoUrl, name = "Example", signingKeyFingerprint = "ab12")

    private var tick = 0
    private val env = object : SyncDiffEnv {
        override fun nextClock(): String = "${1000 + tick++}:0:dev"
        override fun tombstoneWork(manga: Manga) = null
        override fun categoryUuid(localId: Long): String? = null
        override fun forgetCategory(localId: Long) = Unit
    }

    private fun fields(entries: List<DirtyEntry>) =
        entries.map { "${it.kind}/${it.ref}/${it.field}${it.aux?.let { a -> "=$a" } ?: ""}" }.sorted()

    @Test
    fun everyPortablePathNamesARealSettingsField() {
        val encoded = PortableSettings.encode(AppSettings())
        PortableSettings.paths.forEach { path ->
            assertTrue(Regex("^[a-z][a-zA-Z0-9.]{0,127}$").matches(path), "$path is a valid record key")
            // A nullable field encodes as JSON null, which is still a present key.
            val section = encoded.getValue(path.substringBefore('.')) as JsonObject
            assertTrue(path.substringAfter('.') in section, "$path exists in AppSettings")
        }
        assertEquals(PortableSettings.paths.size, PortableSettings.paths.toSet().size)
        // Device-local settings are not on the list.
        listOf("sync", "security", "downloads", "advanced").forEach { section ->
            assertTrue(PortableSettings.paths.none { it.startsWith("$section.") })
        }
        assertFalse(PortableSettings.isPortable("library.portraitColumns"))
    }

    @Test
    fun diffMarksRepositoryListAndPortableSettingsOnly() {
        val base = AppSnapshot()
        val added = base.copy(extensionRepositories = listOf(repo))
        assertEquals(
            listOf(
                "repository/r:$repoUrl/name",
                "repository/r:$repoUrl/trustedPublishers",
                "repository/r:$repoUrl/url",
                "repository/r:$repoUrl/~deleted=false",
            ),
            fields(SyncDiff.diff(base, added, env)),
        )
        val renamed = added.copy(extensionRepositories = listOf(repo.copy(name = "Renamed", website = "https://example.org")))
        assertEquals(listOf("repository/r:$repoUrl/name"), fields(SyncDiff.diff(added, renamed, env)))
        assertTrue(
            SyncDiff.diff(added, base, env).isEmpty(),
            "removing a repository here is local: the account keeps suggesting it",
        )
        val readded = SyncDiff.diff(base, added, env)
        assertTrue("repository/r:$repoUrl/~deleted=false" in fields(readded), "re-adding revives a removed account record")

        val settings = base.settings
        val portable = base.copy(
            settings = settings.copy(
                reader = settings.reader.copy(readingMode = ReadingMode.WEBTOON, keepScreenOn = false),
                appearance = settings.appearance.copy(theme = ThemeMode.DARK),
            ),
        )
        assertEquals(
            listOf("setting/s:appearance.theme/value", "setting/s:reader.readingMode/value"),
            fields(SyncDiff.diff(base, portable, env)),
        )
        val deviceLocal = base.copy(
            settings = settings.copy(
                library = settings.library.copy(portraitColumns = 4),
                downloads = settings.downloads.copy(parallelDownloads = 2),
            ),
        )
        assertTrue(SyncDiff.diff(base, deviceLocal, env).isEmpty(), "device-local settings never leave the device")
    }

    @Test
    fun firstSignInSendsRepositoriesAndOnlyCustomisedSettings() {
        val snapshot = AppSnapshot(
            extensionRepositories = listOf(repo),
            settings = AppSettings().let { it.copy(appearance = it.appearance.copy(amoledDark = true)) },
        )
        val entries = SyncDiff.markAll(snapshot) { millis -> "$millis:0:dev" }
            .filter { it.kind == SyncKinds.REPOSITORY || it.kind == SyncKinds.SETTING }
        assertEquals(
            listOf(
                "repository/r:$repoUrl/name",
                "repository/r:$repoUrl/trustedPublishers",
                "repository/r:$repoUrl/url",
                "setting/s:appearance.amoledDark/value",
            ),
            fields(entries),
        )
        assertTrue(entries.all { it.clock == "0:0:dev" }, "a value already on the server wins")
    }

    private fun TestDevice.suggested() = service.accountRepositories.value.map { it.url }

    @Test
    fun anAddedRepositoryIsOnlySuggestedToOtherDevices() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())

            val record = server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl)
            assertEquals(JsonPrimitive(repoUrl), record.fields.getValue("url").value)
            assertEquals(JsonPrimitive("Example"), record.fields.getValue("name").value)
            assertEquals(JsonArray(listOf(JsonPrimitive("ab12"))), record.fields.getValue("trustedPublishers").value)

            assertTrue(b.sync())
            assertTrue(b.repository.currentSnapshot.extensionRepositories.isEmpty(), "never added without the user")
            assertEquals(listOf(repoUrl), b.suggested())
            val suggestion = b.service.accountRepositories.value.single()
            assertEquals(AccountRepositoryExpectation("Example", "ab12"), AccountRepositoryExpectation(suggestion.name, suggestion.signingKeyFingerprint))
            assertEquals(0, b.store.dirtyRefCount(), "an applied record is not an edit")
        } finally {
            a.close(); b.close()
        }
    }

    private data class AccountRepositoryExpectation(val name: String, val fingerprint: String)

    @Test
    fun removingARepositoryHereLeavesTheAccountAndOtherDevicesAlone() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())
            b.repository.upsertExtensionRepository(repo)
            assertTrue(b.sync())

            a.repository.deleteExtensionRepository(repoUrl)
            assertTrue(a.sync())
            assertTrue(server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl).isLive)
            assertTrue(b.sync())
            assertEquals(listOf(repo), b.repository.currentSnapshot.extensionRepositories)
            assertEquals(listOf(repoUrl), a.suggested(), "the account still knows it")

            // Adding it again later is fine and stays quiet.
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())
            assertTrue(server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl).isLive)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun forgettingARepositoryStopsSuggestingItButKeepsItWhereItWasAdded() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        val c = TestDevice(server, "C")
        try {
            a.signIn(register = true)
            b.signIn()
            c.signIn()
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())
            b.repository.upsertExtensionRepository(repo)
            assertTrue(b.sync())
            assertTrue(c.sync())
            assertEquals(listOf(repoUrl), c.suggested())

            a.repository.deleteExtensionRepository(repoUrl)
            a.service.forgetRepository(repoUrl)
            assertTrue(a.sync())
            assertFalse(server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl).isLive)

            assertTrue(b.sync())
            assertTrue(c.sync())
            assertTrue(c.suggested().isEmpty())
            assertTrue(b.suggested().isEmpty())
            assertEquals(listOf(repo), b.repository.currentSnapshot.extensionRepositories, "already added: kept")
            assertTrue(c.repository.currentSnapshot.extensionRepositories.isEmpty())

            // Adding it again brings the account record back.
            c.repository.upsertExtensionRepository(repo)
            assertTrue(c.sync())
            assertTrue(server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl).isLive)
            assertTrue(a.sync())
            assertEquals(listOf(repoUrl), a.suggested())
        } finally {
            a.close(); b.close(); c.close()
        }
    }

    @Test
    fun addingARepositoryBackBeforeTheRemovalWasSentKeepsItOnTheAccount() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())

            // Removed from the account, then added again, with no sync in between.
            a.repository.deleteExtensionRepository(repoUrl)
            a.service.forgetRepository(repoUrl)
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())

            assertTrue(server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl).isLive)
            assertTrue(b.sync())
            assertEquals(listOf(repoUrl), b.suggested())
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun aRemoteTombstoneAndA410ResyncNeverRemoveALocalRepository() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())
            b.repository.upsertExtensionRepository(repo)
            assertTrue(b.sync())

            server.deleteRecord("alice", SyncKinds.REPOSITORY, repoUrl, "${System.currentTimeMillis() + 1000}:0:other")
            assertTrue(b.sync())
            assertEquals(listOf(repo), b.repository.currentSnapshot.extensionRepositories)
            assertTrue(b.suggested().isEmpty())

            // The tombstone is purged and the floor moves past B's cursor: B resyncs from zero.
            a.repository.updateSettings { it.copy(reader = it.reader.copy(readingMode = ReadingMode.WEBTOON)) }
            assertTrue(a.sync())
            server.purgeRecord("alice", SyncKinds.REPOSITORY, repoUrl)
            server.raiseFloor("alice")
            assertTrue(b.sync())
            assertTrue(server.goneCount > 0, "the server answered 410")
            assertEquals(listOf(repo), b.repository.currentSnapshot.extensionRepositories)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun aRemoteRenameAndKeyChangeUpdateOnlyARepositoryThisDeviceHas() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())
            b.repository.upsertExtensionRepository(repo)
            assertTrue(b.sync())

            a.repository.upsertExtensionRepository(repo.copy(name = "Renamed", signingKeyFingerprint = "cd34"))
            assertTrue(a.sync())
            assertTrue(b.sync())
            val onB = b.repository.currentSnapshot.extensionRepositories.single()
            assertEquals("Renamed", onB.name)
            assertEquals("cd34", onB.signingKeyFingerprint)

            val c = TestDevice(server, "C")
            try {
                c.signIn()
                assertTrue(c.sync())
                assertTrue(c.repository.currentSnapshot.extensionRepositories.isEmpty())
                assertEquals("Renamed", c.service.accountRepositories.value.single().name)
            } finally {
                c.close()
            }
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun reseedingRevivesARepositoryOlderVersionsRemovedFromTheAccount() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A", initial = AppSnapshot(extensionRepositories = listOf(repo)))
        try {
            a.signIn(register = true)
            assertTrue(a.sync())
            // As the old behaviour left it: the account holds a newer tombstone, and one more
            // removal is still queued locally.
            Thread.sleep(5)
            server.deleteRecord("alice", SyncKinds.REPOSITORY, repoUrl, "${System.currentTimeMillis()}:0:other")
            assertFalse(server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl).isLive)
            Thread.sleep(5)
            a.store.mark(
                listOf(DirtyEntry(SyncKinds.REPOSITORY, repositoryRef("https://gone.example.org/r"), DirtyEntry.DELETED_FIELD, "1:0:x", "true", "https://gone.example.org/r")),
            )
            a.store.setMeta(SyncV3Service.META_SEEDED_KINDS, "2")

            assertTrue(a.sync())
            assertTrue(server.accountRecords("alice").getValue(SyncKinds.REPOSITORY to repoUrl).isLive, "the repository still on this device is back")
            assertNull(server.accountRecords("alice")[SyncKinds.REPOSITORY to "https://gone.example.org/r"])
            assertEquals(listOf(repo), a.repository.currentSnapshot.extensionRepositories)
            assertEquals(SyncV3Service.SEEDED_KINDS_VERSION, a.store.meta(SyncV3Service.META_SEEDED_KINDS))
        } finally {
            a.close()
        }
    }

    @Test
    fun signingOutEmptiesTheSuggestions() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertEquals(listOf(repoUrl), b.suggested())
            b.service.logout()
            assertTrue(b.suggested().isEmpty())
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun aRecordThatIsNotAWebAddressIsParkedInsteadOfListed() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            a.repository.upsertExtensionRepository(ExtensionRepo(baseUrl = "file:///etc/passwd", name = "Nope"))
            a.repository.upsertExtensionRepository(repo)
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertTrue(b.repository.currentSnapshot.extensionRepositories.isEmpty())
            assertEquals(listOf(repoUrl), b.suggested(), "only a web address is ever suggested")
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun portableSettingsFollowTheAccountAndDeviceSettingsStay() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            b.signIn()
            b.repository.updateSettings { it.copy(library = it.library.copy(portraitColumns = 6)) }
            a.repository.updateSettings {
                it.copy(
                    reader = it.reader.copy(readingMode = ReadingMode.WEBTOON, novelFontSizeSp = 24f, fullscreen = false),
                    browse = it.browse.copy(enabledLanguages = setOf("zh", "ja")),
                    library = it.library.copy(portraitColumns = 2),
                )
            }
            assertTrue(a.sync())
            val records = server.accountRecords("alice")
            assertEquals(JsonPrimitive("WEBTOON"), records.getValue(SyncKinds.SETTING to "reader.readingMode").fields.getValue("value").value)
            assertNull(records[SyncKinds.SETTING to "library.portraitColumns"])
            assertNull(records[SyncKinds.SETTING to "reader.fullscreen"])

            assertTrue(b.sync())
            val settings = b.repository.currentSnapshot.settings
            assertEquals(ReadingMode.WEBTOON, settings.reader.readingMode)
            assertEquals(24f, settings.reader.novelFontSizeSp)
            assertEquals(setOf("zh", "ja"), settings.browse.enabledLanguages)
            assertTrue(settings.reader.fullscreen, "not portable: B keeps its own value")
            assertEquals(6, settings.library.portraitColumns)
            assertEquals(0, b.store.dirtyRefCount())

            // The later edit wins, field by field; "follow the system" (null) is a value too.
            a.repository.updateSettings { it.copy(general = it.general.copy(languagePreference = "ja")) }
            assertTrue(a.sync())
            assertTrue(b.sync())
            assertEquals("ja", b.repository.currentSnapshot.settings.general.languagePreference)
            b.repository.updateSettings {
                it.copy(reader = it.reader.copy(readingMode = ReadingMode.PAGER_RTL), general = it.general.copy(languagePreference = null))
            }
            assertTrue(b.sync())
            assertTrue(a.sync())
            val onA = a.repository.currentSnapshot.settings
            assertEquals(ReadingMode.PAGER_RTL, onA.reader.readingMode)
            assertNull(onA.general.languagePreference)
            assertEquals(24f, onA.reader.novelFontSizeSp)
            assertEquals(2, onA.library.portraitColumns)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun aFreshDeviceTakesTheAccountSettingsInsteadOfUploadingDefaults() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val custom = AppSettings().let { it.copy(appearance = it.appearance.copy(theme = ThemeMode.DARK)) }
        val a = TestDevice(server, "A", initial = AppSnapshot(settings = custom, extensionRepositories = listOf(repo)))
        val b = TestDevice(server, "B")
        try {
            a.signIn(register = true)
            assertTrue(a.sync())
            assertNotNull(server.accountRecords("alice")[SyncKinds.SETTING to "appearance.theme"])
            assertNull(server.accountRecords("alice")[SyncKinds.SETTING to "appearance.amoledDark"], "defaults are not uploaded")
            b.signIn()
            assertTrue(b.sync())
            assertEquals(ThemeMode.DARK, b.repository.currentSnapshot.settings.appearance.theme)
            assertTrue(b.repository.currentSnapshot.extensionRepositories.isEmpty(), "repositories are suggested, not copied")
            assertEquals(listOf(repoUrl), b.suggested())
            assertTrue(a.sync())
            assertEquals(ThemeMode.DARK, a.repository.currentSnapshot.settings.appearance.theme)
        } finally {
            a.close(); b.close()
        }
    }

    @Test
    fun aValueThatDoesNotFitTheFieldIsParkedAndLeavesSettingsAlone() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(server, "A")
        try {
            a.signIn(register = true)
            a.repository.updateSettings { it.copy(reader = it.reader.copy(readingMode = ReadingMode.WEBTOON)) }
            assertTrue(a.sync())
            val before = a.repository.currentSnapshot.settings
            val applier = SyncApplier(
                a.repository.currentSnapshot,
                SyncApplyContext(a.identity, emptyMap(), emptyMap(), { _, _ -> emptySet() }, 0),
                HashMap(),
            )
            val bad = StoredRecord(
                kind = SyncKinds.SETTING,
                key = "reader.readingMode",
                state = LwwMerge.LocalState(mapOf("value" to LwwMerge.LocalField(JsonPrimitive("SIDEWAYS"), "5:0:x", dirty = false))),
            )
            val unknown = bad.copy(key = "reader.futureSetting")
            val result = applier.apply(listOf(bad, unknown))
            assertTrue(result.outcomes.getValue(SyncKinds.SETTING to "reader.readingMode") is ApplyOutcome.Pending)
            assertEquals(ApplyOutcome.Applied, result.outcomes.getValue(SyncKinds.SETTING to "reader.futureSetting"))
            assertFalse(result.changed)
            assertEquals(before, result.snapshot.settings)
        } finally {
            a.close()
        }
    }

    @Test
    fun aSessionFromBeforeTheseKindsSyncedSendsWhatItAlreadyHas() = runBlocking<Unit> {
        val server = FakeSyncServer()
        val a = TestDevice(
            server,
            "A",
            initial = AppSnapshot(
                extensionRepositories = listOf(repo),
                settings = AppSettings().let { it.copy(appearance = it.appearance.copy(amoledDark = true)) },
            ),
        )
        try {
            a.signIn(register = true)
            assertTrue(a.sync())
            // As the older version left it: signed in and marked, but neither kind ever sent.
            server.purgeRecord("alice", SyncKinds.REPOSITORY, repoUrl)
            server.purgeRecord("alice", SyncKinds.SETTING, "appearance.amoledDark")
            a.store.deleteRecord(SyncKinds.REPOSITORY, repoUrl)
            a.store.deleteRecord(SyncKinds.SETTING, "appearance.amoledDark")
            a.store.setMeta(SyncV3Service.META_SEEDED_KINDS, null)
            // An edit made after upgrading and not sent yet keeps its own clock.
            a.repository.upsertExtensionRepository(repo.copy(name = "Renamed"))

            assertTrue(a.sync())
            val records = server.accountRecords("alice")
            val repository = records.getValue(SyncKinds.REPOSITORY to repoUrl)
            assertEquals(JsonPrimitive(repoUrl), repository.fields.getValue("url").value)
            assertEquals(JsonPrimitive("Renamed"), repository.fields.getValue("name").value)
            assertFalse(repository.fields.getValue("name").clock.startsWith("0:"), "the pending edit is not re-stamped")
            assertEquals(JsonPrimitive(true), records.getValue(SyncKinds.SETTING to "appearance.amoledDark").fields.getValue("value").value)
            assertEquals(0, a.store.dirtyRefCount())

            // Once only: nothing is marked again on later cycles.
            server.purgeRecord("alice", SyncKinds.SETTING, "appearance.amoledDark")
            assertTrue(a.sync())
            assertNull(server.accountRecords("alice")[SyncKinds.SETTING to "appearance.amoledDark"])
        } finally {
            a.close()
        }
    }
}
