package dev.shinsou.kmp.content.blob

import app.cash.sqldelight.db.SqlDriver
import kotlin.random.Random
import kotlin.time.Clock
import dev.shinsou.kmp.domain.content.BlobRef
import dev.shinsou.kmp.content.schema.SchemaComponent
import dev.shinsou.kmp.content.schema.SchemaMigrator
import dev.shinsou.kmp.content.schema.columnExists
import dev.shinsou.kmp.content.schema.tableExists
import dev.shinsou.kmp.content.sql.executeStatement
import dev.shinsou.kmp.content.sql.executeUncached
import dev.shinsou.kmp.content.transaction.AuxiliaryBlobAttachment
import dev.shinsou.kmp.content.transaction.SqlContentTransactionState

/**
 * Restart-safe immutable blob participant backed by the platform-owned content SQLite driver.
 *
 * Payloads are deliberately separate from [SqlContentTransactionState]. Publication and manifest
 * queries therefore never materialize body bytes. Cold start reconstructs only bounded identity,
 * generation and lifecycle metadata; an exact payload is fetched only when that blob is opened or
 * deduplicated. This prevents a large library from putting every body on the startup critical path.
 * [close] is intentionally absent: the platform database host is the single driver owner.
 */
public class SqlDriverContentBlobStore private constructor(
    private val delegate: InMemoryContentBlobStore,
) : ContentBlobStore by delegate {
    public constructor(
        driver: SqlDriver,
        /** Null is retained only for isolated compatibility tests and legacy database migration. */
        blobDirectoryPath: String? = null,
        maximumBlobSizeBytes: Long = InMemoryContentBlobStore.DEFAULT_MAXIMUM_BLOB_SIZE_BYTES,
        blobIdFactory: () -> String = ::randomContentUuid,
        commitTokenFactory: () -> String = { "sql-content-${randomContentUuid()}" },
        storeInstanceIdFactory: () -> String = { "sql-store-${randomContentUuid()}" },
        clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(
        createPersistentDelegate(
            driver = driver,
            blobDirectoryPath = blobDirectoryPath,
            maximumBlobSizeBytes = maximumBlobSizeBytes,
            blobIdFactory = blobIdFactory,
            commitTokenFactory = commitTokenFactory,
            storeInstanceIdFactory = storeInstanceIdFactory,
            clock = clock,
        ),
    )

    public val storeInstanceId: String get() = delegate.storeInstanceId
    public val count: Int get() = delegate.count
    public val pendingReceiptCount: Int get() = delegate.pendingReceiptCount
    internal var beginStageCountForTesting: Int = 0
        private set
    public fun pendingReceipts(): List<BlobPublishReceipt> = delegate.pendingReceipts()
    public fun lifecycleState(reference: BlobRef): BlobLifecycleState? = delegate.lifecycleState(reference)

    override fun beginStage(expectedSizeBytes: Long?, mediaType: String): ContentBlobStage {
        beginStageCountForTesting += 1
        return delegate.beginStage(expectedSizeBytes, mediaType)
    }

    internal fun runStorageMaintenanceSlice(
        request: ContentBlobStorageMaintenanceRequest,
    ): ContentBlobStorageMaintenanceResult = delegate.runStorageMaintenanceSlice(request)

    internal val transactionParticipant: ContentBlobTransactionParticipant =
        InMemoryContentBlobParticipant(delegate)
}

internal fun ContentBlobStore.transactionParticipantOrNull(): ContentBlobTransactionParticipant? = when (this) {
    is InMemoryContentBlobStore -> InMemoryContentBlobParticipant(this)
    is SqlDriverContentBlobStore -> transactionParticipant
    else -> null
}

private class InMemoryContentBlobParticipant(
    private val store: InMemoryContentBlobStore,
) : ContentBlobTransactionParticipant {
    override val isRestartSafe: Boolean get() = store.isRestartSafe

    override fun <T> withExclusiveTransaction(block: () -> T): T =
        store.withExclusiveTransaction(block)

    override fun deferAttachmentHydration(load: () -> DeferredAttachmentHydration) =
        store.deferAttachmentHydration(load)

    override fun snapshotForTransactionLocked(): ContentBlobRollback =
        store.snapshotForTransactionLocked()

    override fun hydrateAttachmentsLocked(
        attachmentsToHydrate: List<BlobAttachment>,
        auxiliaryAttachmentsToHydrate: List<AuxiliaryBlobAttachment>,
    ) = store.hydrateAttachmentsLocked(attachmentsToHydrate, auxiliaryAttachmentsToHydrate)

    override fun clearManifestAttachmentsLocked() = store.clearManifestAttachmentsLocked()

    override fun detachManifestAttachmentsLocked(
        publicationKey: dev.shinsou.kmp.domain.model.PublicationKey,
    ): List<BlobAttachment> = store.detachManifestAttachmentsLocked(publicationKey)

    override fun attachedLocked(
        owner: ContentManifestOwner,
        manifestId: String,
        contentRevision: Long,
    ): BlobAttachment? = store.attachedLocked(owner, manifestId, contentRevision)

    override fun auxiliaryAttachedLocked(attachmentKey: String): AuxiliaryBlobAttachment? =
        store.auxiliaryAttachedLocked(attachmentKey)

    override fun validateAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
    ) = store.validateAtomicAttachmentsLocked(receipts, attachments, auxiliaryAttachments)

    override fun validateReceiptsOnlyLocked(receipts: List<BlobPublishReceipt>) =
        store.validateReceiptsOnlyLocked(receipts)

    override fun consumeAtomicAttachmentsLocked(
        receipts: List<BlobPublishReceipt>,
        attachments: List<BlobAttachment>,
        auxiliaryAttachments: List<AuxiliaryBlobAttachment>,
        afterAttachment: (() -> Unit)?,
    ) = store.consumeAtomicAttachmentsLocked(
        receipts,
        attachments,
        auxiliaryAttachments,
        afterAttachment,
    )

    override fun consumeReceiptsLocked(receipts: List<BlobPublishReceipt>) =
        store.consumeReceiptsLocked(receipts)
}

private fun createPersistentDelegate(
    driver: SqlDriver,
    blobDirectoryPath: String?,
    maximumBlobSizeBytes: Long,
    blobIdFactory: () -> String,
    commitTokenFactory: () -> String,
    storeInstanceIdFactory: () -> String,
    clock: () -> Long,
): InMemoryContentBlobStore {
    val durability = SqlDriverContentBlobDurability(
        driver = driver,
        storeInstanceIdFactory = storeInstanceIdFactory,
        bodyFiles = blobDirectoryPath?.let(::AppPrivateContentBlobFiles),
    )
    return InMemoryContentBlobStore(
        maximumBlobSizeBytes = maximumBlobSizeBytes,
        blobIdFactory = blobIdFactory,
        commitTokenFactory = commitTokenFactory,
        clock = clock,
        configuredStoreInstanceId = durability.storeInstanceId,
        durability = durability,
    )
}

internal enum class ContentBlobCrashFileKind {
    OBJECT,
    STAGING;

    fun next(): ContentBlobCrashFileKind = if (this == OBJECT) STAGING else OBJECT
}

internal data class ContentBlobCrashFile(
    val kind: ContentBlobCrashFileKind,
    val opaqueName: String,
    val byteSize: Long,
    val modifiedAtEpochMillis: Long?,
)

/**
 * The blob tables of the unified platform database. Format 2 added `content_blobs.opaque_name`
 * (bodies kept as files); format 1 kept every body in the row.
 */
internal object ContentBlobPersistenceSchema {
    val component: SchemaComponent = SchemaComponent(
        name = "content_blobs",
        version = 2,
        create = ::createTables,
        steps = mapOf(2L to ::addOpaqueName),
        unrecordedVersion = { driver ->
            when {
                !tableExists(driver, TABLE_BLOBS) -> null
                columnExists(driver, TABLE_BLOBS, "opaque_name") -> 2
                else -> 1
            }
        },
    )

    fun create(driver: SqlDriver) {
        SchemaMigrator.migrate(driver, component)
    }

    private fun createTables(driver: SqlDriver) {
        listOf(
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_STORE(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  store_instance_id TEXT NOT NULL UNIQUE
                )
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOBS(
                  blob_id TEXT NOT NULL PRIMARY KEY,
                  reference_json TEXT NOT NULL,
                  payload BLOB NOT NULL,
                  opaque_name TEXT,
                  incarnation INTEGER NOT NULL CHECK(incarnation > 0),
                  generation INTEGER NOT NULL CHECK(generation >= 0),
                  published_at_epoch_millis INTEGER NOT NULL CHECK(published_at_epoch_millis >= 0),
                  lifecycle_state TEXT NOT NULL,
                  discovered_at_epoch_millis INTEGER
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_FILE_MIGRATIONS(
                  blob_id TEXT NOT NULL PRIMARY KEY,
                  reference_json TEXT NOT NULL,
                  incarnation INTEGER NOT NULL CHECK(incarnation > 0),
                  generation INTEGER NOT NULL CHECK(generation >= 0),
                  staged_name TEXT NOT NULL UNIQUE,
                  phase TEXT NOT NULL,
                  copy_offset INTEGER NOT NULL CHECK(copy_offset >= 0),
                  copy_hash_state TEXT NOT NULL,
                  verify_offset INTEGER NOT NULL CHECK(verify_offset >= 0),
                  verify_hash_state TEXT NOT NULL
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_FILE_ORPHANS(
                  file_kind TEXT NOT NULL,
                  opaque_name TEXT NOT NULL,
                  byte_size INTEGER NOT NULL CHECK(byte_size >= 0),
                  modified_at_epoch_millis INTEGER,
                  discovered_at_epoch_millis INTEGER NOT NULL CHECK(discovered_at_epoch_millis >= 0),
                  PRIMARY KEY(file_kind, opaque_name)
                ) WITHOUT ROWID
            """.trimIndent(),
            """
                CREATE TABLE IF NOT EXISTS $TABLE_BLOB_MAINTENANCE(
                  singleton_id INTEGER NOT NULL PRIMARY KEY CHECK(singleton_id = 1),
                  scan_kind TEXT NOT NULL,
                  scan_after_name TEXT
                )
            """.trimIndent(),
        ).forEach { sql -> driver.executeUncached(sql, 0).value }
        driver.executeStatement(
            sql = "INSERT OR IGNORE INTO $TABLE_BLOB_MAINTENANCE(" +
                "singleton_id, scan_kind, scan_after_name) VALUES (1, 'OBJECT', NULL)",
            parameters = 0,
        ).value
    }

    private fun addOpaqueName(driver: SqlDriver) {
        if (!columnExists(driver, TABLE_BLOBS, "opaque_name")) {
            driver.executeUncached("ALTER TABLE $TABLE_BLOBS ADD COLUMN opaque_name TEXT", 0).value
        }
    }
}

/**
 * Small exportable SHA-256 state used to keep each legacy-body copy/verification slice bounded.
 * Only the eight chaining words and the sub-64-byte tail are persisted; body bytes never enter
 * migration metadata.
 */
internal class ResumableSha256 private constructor(
    private val words: IntArray,
    private var tail: ByteArray,
    var totalBytes: Long,
) {
    fun update(input: ByteArray) {
        if (input.isEmpty()) return
        require(totalBytes <= Long.MAX_VALUE - input.size.toLong()) { "SHA-256 input is too large" }
        var offset = 0
        if (tail.isNotEmpty()) {
            val needed = 64 - tail.size
            val copied = minOf(needed, input.size)
            val joined = ByteArray(tail.size + copied)
            tail.copyInto(joined)
            input.copyInto(joined, tail.size, 0, copied)
            tail = joined
            offset += copied
            if (tail.size == 64) {
                processBlock(tail, 0)
                tail = ByteArray(0)
            }
        }
        while (input.size - offset >= 64) {
            processBlock(input, offset)
            offset += 64
        }
        if (offset < input.size) tail = input.copyOfRange(offset, input.size)
        totalBytes += input.size.toLong()
    }

    fun snapshot(): String = buildString {
        append("v1:")
        append(totalBytes)
        append(':')
        words.forEach { append(it.toUInt().toString(16).padStart(8, '0')) }
        append(':')
        if (tail.isEmpty()) append('-') else tail.forEach {
            append((it.toInt() and 0xff).toString(16).padStart(2, '0'))
        }
    }

    fun digestHex(): String {
        require(totalBytes <= Long.MAX_VALUE / 8L) { "SHA-256 input is too large" }
        val finished = ResumableSha256(words.copyOf(), tail.copyOf(), totalBytes)
        val paddedSize = if (finished.tail.size < 56) 64 else 128
        val padding = ByteArray(paddedSize)
        finished.tail.copyInto(padding)
        padding[finished.tail.size] = 0x80.toByte()
        val bitLength = totalBytes * 8L
        for (index in 0 until 8) {
            padding[padding.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }
        var offset = 0
        while (offset < padding.size) {
            finished.processBlock(padding, offset)
            offset += 64
        }
        return finished.words.joinToString("") { it.toUInt().toString(16).padStart(8, '0') }
    }

    private fun processBlock(block: ByteArray, offset: Int) {
        val schedule = IntArray(64)
        for (index in 0 until 16) {
            val at = offset + index * 4
            schedule[index] = ((block[at].toInt() and 0xff) shl 24) or
                ((block[at + 1].toInt() and 0xff) shl 16) or
                ((block[at + 2].toInt() and 0xff) shl 8) or
                (block[at + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val left = rotateRight(schedule[index - 15], 7) xor
                rotateRight(schedule[index - 15], 18) xor (schedule[index - 15] ushr 3)
            val right = rotateRight(schedule[index - 2], 17) xor
                rotateRight(schedule[index - 2], 19) xor (schedule[index - 2] ushr 10)
            schedule[index] = schedule[index - 16] + left + schedule[index - 7] + right
        }
        var a = words[0]
        var b = words[1]
        var c = words[2]
        var d = words[3]
        var e = words[4]
        var f = words[5]
        var g = words[6]
        var h = words[7]
        for (index in 0 until 64) {
            val bigSigma1 = rotateRight(e, 6) xor rotateRight(e, 11) xor rotateRight(e, 25)
            val choose = (e and f) xor (e.inv() and g)
            val first = h + bigSigma1 + choose + CONSTANTS[index] + schedule[index]
            val bigSigma0 = rotateRight(a, 2) xor rotateRight(a, 13) xor rotateRight(a, 22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val second = bigSigma0 + majority
            h = g
            g = f
            f = e
            e = d + first
            d = c
            c = b
            b = a
            a = first + second
        }
        words[0] += a
        words[1] += b
        words[2] += c
        words[3] += d
        words[4] += e
        words[5] += f
        words[6] += g
        words[7] += h
    }

    companion object {
        fun initial(): ResumableSha256 = ResumableSha256(INITIAL.copyOf(), ByteArray(0), 0L)

        fun restore(snapshot: String): ResumableSha256 {
            val parts = snapshot.split(':')
            require(parts.size == 4 && parts[0] == "v1") { "Invalid SHA-256 migration state" }
            val total = parts[1].toLongOrNull()?.takeIf { it >= 0L }
                ?: throw IllegalArgumentException("Invalid SHA-256 migration byte count")
            require(parts[2].length == 64) { "Invalid SHA-256 migration chaining state" }
            val words = IntArray(8) { index ->
                parts[2].substring(index * 8, index * 8 + 8).toUInt(16).toInt()
            }
            val tail = if (parts[3] == "-") ByteArray(0) else decodeHex(parts[3])
            require(tail.size < 64 && tail.size.toLong() == total % 64L) {
                "Invalid SHA-256 migration tail"
            }
            return ResumableSha256(words, tail, total)
        }

        private fun decodeHex(value: String): ByteArray {
            require(value.length % 2 == 0 && value.all { it in '0'..'9' || it in 'a'..'f' }) {
                "Invalid SHA-256 migration tail encoding"
            }
            return ByteArray(value.length / 2) { index ->
                value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }

        private fun rotateRight(value: Int, count: Int): Int =
            (value ushr count) or (value shl (32 - count))

        private val INITIAL = intArrayOf(
            0x6a09e667,
            0xbb67ae85.toInt(),
            0x3c6ef372,
            0xa54ff53a.toInt(),
            0x510e527f,
            0x9b05688c.toInt(),
            0x1f83d9ab,
            0x5be0cd19,
        )

        private val CONSTANTS = intArrayOf(
            0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
            0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
            0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
            0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
            0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
            0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
            0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
            0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
        )
    }
}

/** Focused contract seam proving persisted hash snapshots match the host SHA-256 implementation. */
internal fun resumableContentBlobSha256ForTesting(chunks: List<ByteArray>): String {
    var hasher = ResumableSha256.initial()
    chunks.forEach { chunk ->
        hasher.update(chunk)
        hasher = ResumableSha256.restore(hasher.snapshot())
    }
    return hasher.digestHex()
}

internal fun randomContentUuid(): String {
    val bytes = Random.Default.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-" +
        "${hex.substring(16, 20)}-${hex.substring(20)}"
}

internal const val TABLE_BLOB_STORE = "content_blob_store"

internal const val TABLE_BLOBS = "content_blobs"

internal const val TABLE_BLOB_FILE_MIGRATIONS = "content_blob_file_migrations"

internal const val TABLE_BLOB_FILE_ORPHANS = "content_blob_file_orphans"

internal const val TABLE_BLOB_MAINTENANCE = "content_blob_maintenance"
