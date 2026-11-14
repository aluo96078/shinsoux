package dev.shinsou.kmp.sync.v3

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.shinsou.kmp.data.AppSnapshot
import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.Acquisition
import dev.shinsou.kmp.domain.model.AcquisitionOrigin
import dev.shinsou.kmp.domain.model.Chapter
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.domain.model.Publication
import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.PublicationUnit
import dev.shinsou.kmp.domain.model.RemoteEntityKey
import dev.shinsou.kmp.domain.model.RemoteEntityKind
import dev.shinsou.kmp.domain.model.SourceBinding
import dev.shinsou.kmp.domain.model.SourceKey
import dev.shinsou.kmp.local.encodeTypedLocalChapterUrl
import dev.shinsou.kmp.plugin.InMemoryPluginKeyValueStore
import dev.shinsou.kmp.plugin.v2.encodeExtensionLibraryPublicationUrl
import dev.shinsou.kmp.plugin.v2.extensionAcquisitionId
import dev.shinsou.kmp.plugin.v2.extensionPublicationKey
import dev.shinsou.kmp.plugin.v2.extensionUnitKey
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Files

/** The v2 extension source shared by every test device. */
internal val TestSourceKey = SourceKey(packageId = "zh.test", sourceId = "zh.test.src")

/** Builds extension-backed titles the way the browse UI does (library URL + typed chapter URLs). */
internal class ExtensionFixtures(
    val publications: MutableMap<PublicationKey, Publication> = HashMap(),
    private val contentRevision: Long = 0,
) {
    fun manga(remoteId: String, title: String, favorite: Boolean = true) = Manga(
        source = 0,
        favorite = favorite,
        dateAdded = 1_700_000_000_000,
        url = encodeExtensionLibraryPublicationUrl(TestSourceKey, remoteId),
        title = title,
        thumbnailUrl = "https://example.com/$remoteId.jpg",
        initialized = true,
    )

    /** Registers the publication graph for [remoteId] and returns the chapter rows for [mangaId]. */
    fun chapters(mangaId: Long, remoteId: String, unitIds: List<String>): List<Chapter> {
        val publicationKey = extensionPublicationKey(TestSourceKey, remoteId)
        val acquisitionId = extensionAcquisitionId(TestSourceKey, remoteId)
        val publicationRemote = RemoteEntityKey(1, TestSourceKey, RemoteEntityKind.PUBLICATION, remoteId, remoteId)
        val units = unitIds.map { unitId ->
            PublicationUnit(
                key = extensionUnitKey(TestSourceKey, remoteId, unitId),
                title = "Chapter $unitId",
                sourceBinding = SourceBinding(
                    sourceKey = TestSourceKey,
                    remoteId = unitId,
                    entityKind = RemoteEntityKind.UNIT,
                    parentPublication = publicationRemote,
                ),
            )
        }
        publications[publicationKey] = Publication(
            key = publicationKey,
            title = remoteId,
            acquisitions = listOf(
                Acquisition(
                    id = acquisitionId,
                    origin = AcquisitionOrigin.ExtensionSource(SourceBinding(TestSourceKey, remoteId)),
                    units = units,
                    contentRevision = contentRevision,
                ),
            ),
        )
        return units.map { unit ->
            Chapter(
                mangaId = mangaId,
                url = encodeTypedLocalChapterUrl(publicationKey, acquisitionId, unit.key),
                name = unit.title,
            )
        }
    }
}

/** One simulated device: its own repository, SQLite file, key store and sync service. */
internal class TestDevice(
    val baseUrl: String,
    val name: String,
    http: HttpClient,
    val fixtures: ExtensionFixtures = ExtensionFixtures(),
    val legacy: Map<Long, SourceKey> = emptyMap(),
    initial: AppSnapshot = AppSnapshot(),
    pushDebounceMillis: Long = 3_600_000L,
    automaticIntervalMillis: Long = 0L,
) {
    constructor(
        server: FakeSyncServer,
        name: String,
        fixtures: ExtensionFixtures = ExtensionFixtures(),
        legacy: Map<Long, SourceKey> = emptyMap(),
        initial: AppSnapshot = AppSnapshot(),
        pushDebounceMillis: Long = 3_600_000L,
    ) : this(server.baseUrl, name, server.client(), fixtures, legacy, initial, pushDebounceMillis)

    /** Runs every time the service reads the clock; lets a test interleave work mid-cycle. */
    @Volatile var onClockRead: (() -> Unit)? = null

    /** How far this device's time is from real time; the test server keeps real time. */
    @Volatile var clockOffsetMillis: Long = 0L

    /** What the installed packages say about their sources; empty means nothing is installed. */
    @Volatile var sources: Map<Pair<String, String>, SyncSourceInfo> = emptyMap()

    /** Whether the test source's package is installed, so titles from it can be created here. */
    @Volatile var sourceInstalled: Boolean = true

    private val file = Files.createTempFile("sync-v3-$name", ".db").toFile().also { it.deleteOnExit() }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    val repository = ShinsouRepository(initial)
    val keyValues = InMemoryPluginKeyValueStore()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val store = SyncV3Store(driver)
    val legacyKeys = LegacySourceKeyCache().also { cache -> legacy.forEach { (id, key) -> cache.put(id, key) } }
    val identity = SyncIdentityResolver({ fixtures.publications[it] }, legacyKeys::lookup)
    val service = SyncV3Service(
        repository = repository,
        store = store,
        identity = identity,
        legacyKeys = legacyKeys,
        api = SyncApi(http),
        keyValues = keyValues,
        scope = scope,
        device = SyncDeviceInfo(name, "desktop", "test"),
        resolveLegacySource = { legacy[it] },
        resolveCreatableSource = { pkg, source ->
            TestSourceKey.takeIf { sourceInstalled && it.packageId == pkg && it.sourceId == source }
        },
        nowMillis = {
            onClockRead?.invoke()
            System.currentTimeMillis() + clockOffsetMillis
        },
        pushDebounceMillis = pushDebounceMillis,
        autoSyncAfterSignIn = false,
        automaticIntervalMillis = automaticIntervalMillis,
        sourceInfo = { sources },
    )

    suspend fun signIn(register: Boolean = false, user: String = "alice", password: String = "password-123") {
        val address = SyncNetworkPolicy.parse(baseUrl)
        val result = if (register) {
            service.register(address, user, password, null, null, null)
        } else {
            service.login(address, user, password)
        }
        result.getOrThrow()
    }

    suspend fun sync(): Boolean = service.syncNow()

    fun close() {
        service.close()
        scope.cancel()
        driver.close()
    }
}
