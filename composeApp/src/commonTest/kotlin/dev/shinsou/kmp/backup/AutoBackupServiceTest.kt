package dev.shinsou.kmp.backup

import dev.shinsou.kmp.data.ShinsouRepository
import dev.shinsou.kmp.domain.model.BackupState
import dev.shinsou.kmp.domain.model.BackupStatus
import dev.shinsou.kmp.domain.model.Manga
import dev.shinsou.kmp.files.AppFileSystem
import dev.shinsou.kmp.sync.v2.CloudflareSnapshotReplacementGuard
import dev.shinsou.kmp.sync.v2.InMemorySyncSessionStore
import dev.shinsou.kmp.sync.v2.SyncSession
import dev.shinsou.kmp.sync.v2.SyncSessionStatus
import dev.shinsou.kmp.sync.v2.SyncWorkspaceDeparture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class AutoBackupServiceTest {
    @Test
    fun dueBackupsUseAtomicWritesAndPruneOldestDeterministically() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        repository.setBackupState(
            BackupState(automaticEnabled = true, intervalHours = 2, retainedBackupCount = 2),
        )
        var currentTime = 1_000L
        val service = AutoBackupService(repository, files, appVersion = "9.1", now = { currentTime })

        val first = assertIs<AutoBackupRunResult.Created>(service.runIfDue()).backup
        assertEquals(1, files.atomicWriteCount)
        assertEquals(1_000L, first.createdAt)
        assertEquals(BackupStatus.COMPLETED, repository.currentSnapshot.backupState.status)

        currentTime += MILLIS_PER_HOUR_FOR_TEST
        val notDue = assertIs<AutoBackupRunResult.NotDue>(service.runIfDue())
        assertEquals(1_000L + 2 * MILLIS_PER_HOUR_FOR_TEST, notDue.nextEligibleAt)
        assertEquals(1, files.atomicWriteCount)

        currentTime += MILLIS_PER_HOUR_FOR_TEST
        val second = assertIs<AutoBackupRunResult.Created>(service.runIfDue()).backup
        currentTime += 2 * MILLIS_PER_HOUR_FOR_TEST
        val third = assertIs<AutoBackupRunResult.Created>(service.runIfDue()).backup

        val backups = service.listBackups()
        assertEquals(listOf(third.createdAt, second.createdAt), backups.map { it.createdAt })
        assertEquals(2, backups.size)
        assertFalse(files.exists("$AUTO_BACKUP_DIRECTORY/${first.fileName}"))
        backups.forEach { entry ->
            assertTrue(entry.recoverable)
            val envelope = SnapshotBackupService.decode(
                assertNotNull(files.read("$AUTO_BACKUP_DIRECTORY/${entry.fileName}")).decodeToString(),
            )
            assertEquals("9.1", envelope.appVersion)
            assertEquals(entry.createdAt, envelope.createdAt)
        }
    }

    @Test
    fun simultaneousDueChecksCreateOnlyOneBackup() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        repository.setBackupState(BackupState(automaticEnabled = true, intervalHours = 24))
        val service = AutoBackupService(repository, files, now = { 50_000L })

        val results = List(6) { async { service.runIfDue() } }.awaitAll()

        assertEquals(1, results.count { it is AutoBackupRunResult.Created })
        assertEquals(5, results.count { it is AutoBackupRunResult.NotDue })
        assertEquals(1, service.listBackups().size)
        assertEquals(1, files.atomicWriteCount)
    }

    @Test
    fun privateBackupCanBeRestoredAndDeleted() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 7, url = "/recover", title = "Recover me"))
        repository.setBackupState(BackupState(retainedBackupCount = 3))
        var currentTime = 80_000L
        val service = AutoBackupService(repository, files, now = { currentTime })
        val backup = service.createNow()

        repository.deleteManga(manga.id)
        assertEquals(null, repository.manga(manga.id))
        currentTime = 90_000L

        service.restore(backup.fileName)

        assertEquals("Recover me", repository.manga(manga.id)?.title)
        assertEquals(90_000L, repository.currentSnapshot.backupState.lastRestoreAt)
        assertEquals(BackupStatus.COMPLETED, repository.currentSnapshot.backupState.status)
        assertTrue(service.delete(backup.fileName))
        assertTrue(service.listBackups().isEmpty())
        assertFailsWith<IllegalArgumentException> { service.delete("../${backup.fileName}") }
    }

    @Test
    fun explicitDeviceOnlyAutomaticRestoreLeavesWorkspaceBeforeReplacing() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        val manga = repository.upsertManga(Manga(source = 7, url = "/recover", title = "Recover safely"))
        val sessionStore = InMemorySyncSessionStore(readyCloudflareSession())
        var departed = false
        repository.setSnapshotReplacementGuard(CloudflareSnapshotReplacementGuard(sessionStore))
        val coordinator = SyncAwareSnapshotRestore(
            repository = repository,
            sessionStore = sessionStore,
            workspaceDeparture = SyncWorkspaceDeparture {
                departed = true
                sessionStore.clear()
            },
        )
        val service = AutoBackupService(
            repository = repository,
            fileSystem = files,
            now = { 85_000L },
        )
        val backup = service.createNow()
        repository.deleteManga(manga.id)

        service.restore(backup.fileName, SnapshotRestoreTarget.THIS_DEVICE_ONLY, coordinator)

        assertTrue(departed)
        assertEquals(null, sessionStore.load())
        assertEquals("Recover safely", repository.manga(manga.id)?.title)
    }

    @Test
    fun damagedAndFailedBackupsRemainActionable() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        val service = AutoBackupService(repository, files, now = { 100_000L })
        val damagedName = "auto-99999-r1.$AUTO_BACKUP_EXTENSION"
        files.write("$AUTO_BACKUP_DIRECTORY/$damagedName", "not-json".encodeToByteArray())

        val damaged = service.listBackups().single()
        assertFalse(damaged.recoverable)
        assertNotNull(damaged.errorMessage)
        assertFailsWith<AutoBackupException> { service.restore(damagedName) }
        assertEquals(BackupStatus.FAILED, repository.currentSnapshot.backupState.status)
        assertTrue(service.delete(damagedName))

        repository.setBackupState(BackupState(automaticEnabled = true))
        files.failAtomicWrites = true
        val failed = assertIs<AutoBackupRunResult.Failed>(service.runIfDue())
        assertTrue(failed.message.contains("atomic", ignoreCase = true))
        assertEquals(BackupStatus.FAILED, repository.currentSnapshot.backupState.status)
        assertTrue(service.listBackups().isEmpty())
    }

    @Test
    fun disabledScheduleDoesNotWrite() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        val service = AutoBackupService(repository, files, now = { 1L })

        assertEquals(AutoBackupRunResult.Disabled, service.runIfDue())
        assertEquals(0, files.atomicWriteCount)
    }

    @Test
    fun loweringRetentionPrunesImmediately() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        repository.setBackupState(BackupState(retainedBackupCount = 3))
        var currentTime = 1_000L
        val service = AutoBackupService(repository, files, now = { currentTime })

        val first = service.createNow()
        currentTime++
        service.createNow()
        currentTime++
        val newest = service.createNow()
        repository.setBackupState(repository.currentSnapshot.backupState.copy(retainedBackupCount = 1))

        val remaining = service.enforceRetention()

        assertEquals(listOf(newest.fileName), remaining.map { it.fileName })
        assertFalse(files.exists("$AUTO_BACKUP_DIRECTORY/${first.fileName}"))
    }

    @Test
    fun foregroundSchedulerStartIsLifecycleReentrySafe() = runTest {
        val files = BackupMemoryFileSystem()
        val repository = ShinsouRepository()
        repository.setBackupState(BackupState(automaticEnabled = true, intervalHours = 24))
        val service = AutoBackupService(repository, files, now = { 123L })
        val scheduler = ForegroundAutoBackupScheduler(service, this, checkIntervalMillis = 1_000L)

        scheduler.start()
        scheduler.start()
        runCurrent()

        assertEquals(1, files.atomicWriteCount)
        scheduler.stop()
    }
}

private fun readyCloudflareSession() = SyncSession(
    endpoint = "https://sync.example",
    instanceId = "instance",
    userId = "user",
    workspaceId = "workspace",
    deviceId = "device",
    deviceDisplayName = "Phone",
    platform = "ios",
    status = SyncSessionStatus.READY,
    deviceAuthEpoch = 1,
    membershipAuthEpoch = 1,
    activeKeyEpoch = 1,
)

private class BackupMemoryFileSystem : AppFileSystem {
    private val files = linkedMapOf<String, ByteArray>()
    var atomicWriteCount = 0
    var failAtomicWrites = false

    override suspend fun write(relativePath: String, bytes: ByteArray) {
        files[relativePath] = bytes.copyOf()
    }

    override suspend fun writeAtomically(relativePath: String, bytes: ByteArray) {
        if (failAtomicWrites) throw IllegalStateException("Atomic write failed")
        atomicWriteCount++
        write(relativePath, bytes)
    }

    override suspend fun read(relativePath: String): ByteArray? = files[relativePath]?.copyOf()

    override suspend fun exists(relativePath: String): Boolean = relativePath in files

    override suspend fun delete(relativePath: String): Boolean = files.remove(relativePath) != null

    override suspend fun deleteTree(relativeDirectory: String): Boolean {
        val prefix = relativeDirectory.trimEnd('/') + "/"
        val matching = files.keys.filter { it.startsWith(prefix) }
        matching.forEach(files::remove)
        return matching.isNotEmpty()
    }

    override suspend fun list(relativeDirectory: String): List<String> {
        val prefix = relativeDirectory.trimEnd('/') + "/"
        return files.keys.filter { it.startsWith(prefix) }
    }

    override fun uri(relativePath: String): String = "memory://$relativePath"
}

private const val MILLIS_PER_HOUR_FOR_TEST = 60L * 60L * 1_000L
