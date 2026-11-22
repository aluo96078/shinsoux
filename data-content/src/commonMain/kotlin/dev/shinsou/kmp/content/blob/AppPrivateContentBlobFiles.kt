package dev.shinsou.kmp.content.blob

import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.HashingSink
import okio.HashingSource
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import dev.shinsou.kmp.domain.content.BlobRef

// R4-4 (docs/architecture/0006): moved out of SqlDriverContentBlobStore.kt without changing it.

internal data class ContentBlobCrashFilePage(
    val entries: List<ContentBlobCrashFile>,
    val nextAfterName: String?,
    val reachedEnd: Boolean,
    val visitedCount: Int = entries.size,
)

/**
 * Device-local immutable body adapter. SQLite contains only lifecycle metadata and this adapter's
 * canonical opaque name. A complete temporary file is flushed and closed before atomic publish.
 */
internal class AppPrivateContentBlobFiles(
    rootPath: String,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) {
    private val root: Path = rootPath.toPath(normalize = true)
    private val objects: Path = root.resolve("objects")
    private val staging: Path = root.resolve("staging")
    /** Access is serialized by the owning blob state machine. */
    private val activeStageNames = LinkedHashSet<String>()

    init {
        require(root.isAbsolute) { "Content blob directory must be absolute" }
        fileSystem.createDirectories(objects)
        fileSystem.createDirectories(staging)
        // Directory enumeration is intentionally absent here. Crash files and legacy inline
        // bodies are handled only by the bounded background maintenance slice.
    }

    fun beginStage(
        expectedSizeBytes: Long?,
        mediaType: String,
        maximumBlobSizeBytes: Long,
    ): DurableContentBlobStageHandle {
        val name = "stage-${randomContentUuid()}.tmp"
        check(activeStageNames.add(name)) { "Generated a duplicate blob staging name" }
        return try {
            FileStage(
                expectedSizeBytes = expectedSizeBytes,
                mediaType = mediaType,
                maximumBlobSizeBytes = maximumBlobSizeBytes,
                temporary = staging.resolve(name),
                stagingName = name,
            )
        } catch (failure: Throwable) {
            activeStageNames.remove(name)
            throw failure
        }
    }

    fun scanCrashFiles(
        kind: ContentBlobCrashFileKind,
        afterName: String?,
        maximumFiles: Int,
    ): ContentBlobCrashFilePage {
        require(maximumFiles > 0)
        val directory = if (kind == ContentBlobCrashFileKind.OBJECT) objects else staging
        val paths = fileSystem.listOrNull(directory).orEmpty()
            .sortedBy(Path::name)
        val remaining = if (afterName == null) paths else paths.filter { it.name > afterName }
        val selected = remaining.take(maximumFiles)
        val reachedEnd = selected.size == remaining.size
        val entries = selected.mapNotNull { path ->
            val metadata = fileSystem.metadataOrNull(path) ?: return@mapNotNull null
            if (!metadata.isRegularFile || metadata.symlinkTarget != null) return@mapNotNull null
            val size = metadata.size ?: return@mapNotNull null
            if (size < 0L) return@mapNotNull null
            ContentBlobCrashFile(
                kind = kind,
                opaqueName = path.name,
                byteSize = size,
                modifiedAtEpochMillis = metadata.lastModifiedAtMillis,
            )
        }
        return ContentBlobCrashFilePage(
            entries = entries,
            nextAfterName = if (reachedEnd) null else selected.lastOrNull()?.name ?: afterName,
            reachedEnd = reachedEnd,
            visitedCount = selected.size,
        )
    }

    fun isActiveStage(opaqueName: String): Boolean = opaqueName in activeStageNames

    fun deleteCrashFile(file: ContentBlobCrashFile): Boolean {
        val directory = if (file.kind == ContentBlobCrashFileKind.OBJECT) objects else staging
        val path = directory.resolve(file.opaqueName)
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        if (!metadata.isRegularFile || metadata.symlinkTarget != null ||
            metadata.size != file.byteSize || metadata.lastModifiedAtMillis != file.modifiedAtEpochMillis
        ) {
            return false
        }
        fileSystem.delete(path, mustExist = false)
        return !fileSystem.exists(path)
    }

    fun migrationStageSize(stagedName: String): Long? {
        requireSafeMaintenanceName(stagedName)
        val metadata = fileSystem.metadataOrNull(staging.resolve(stagedName)) ?: return null
        if (!metadata.isRegularFile || metadata.symlinkTarget != null) {
            throw ContentBlobStoreException.InvalidStage("Blob migration stage is not a regular file")
        }
        return metadata.size
    }

    fun truncateMigrationStage(stagedName: String, byteSize: Long) {
        requireSafeMaintenanceName(stagedName)
        require(byteSize >= 0L)
        val path = staging.resolve(stagedName)
        val handle = fileSystem.openReadWrite(path, mustCreate = false, mustExist = true)
        try {
            handle.resize(byteSize)
            handle.flush()
        } finally {
            handle.close()
        }
    }

    fun deleteMigrationStage(stagedName: String) {
        requireSafeMaintenanceName(stagedName)
        fileSystem.delete(staging.resolve(stagedName), mustExist = false)
    }

    fun appendMigrationChunk(stagedName: String, expectedOffset: Long, chunk: ByteArray) {
        requireSafeMaintenanceName(stagedName)
        require(expectedOffset >= 0L)
        require(chunk.isNotEmpty())
        val path = staging.resolve(stagedName)
        val handle = fileSystem.openReadWrite(
            path,
            mustCreate = expectedOffset == 0L && !fileSystem.exists(path),
            mustExist = expectedOffset != 0L || fileSystem.exists(path),
        )
        try {
            if (handle.size() != expectedOffset) {
                throw ContentBlobStoreException.InvalidStage("Blob migration stage offset changed")
            }
            handle.write(expectedOffset, chunk, 0, chunk.size)
            handle.flush()
        } finally {
            handle.close()
        }
    }

    fun createEmptyMigrationStage(stagedName: String) {
        requireSafeMaintenanceName(stagedName)
        val path = staging.resolve(stagedName)
        if (fileSystem.exists(path)) return
        val handle = fileSystem.openReadWrite(path, mustCreate = true, mustExist = false)
        try {
            handle.flush()
        } finally {
            handle.close()
        }
    }

    fun publishMigrationStage(reference: BlobRef, stagedName: String): String {
        requireSafeMaintenanceName(stagedName)
        val opaqueName = canonicalOpaqueName(reference)
        val source = staging.resolve(stagedName)
        val destination = objects.resolve(opaqueName)
        val destinationMetadata = fileSystem.metadataOrNull(destination)
        if (destinationMetadata == null) {
            val sourceMetadata = fileSystem.metadataOrNull(source)
                ?: throw ContentBlobStoreException.InvalidStage("Completed blob migration stage is missing")
            if (!sourceMetadata.isRegularFile || sourceMetadata.symlinkTarget != null ||
                sourceMetadata.size != reference.byteSize
            ) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            fileSystem.atomicMove(source, destination)
        } else if (!destinationMetadata.isRegularFile || destinationMetadata.symlinkTarget != null ||
            destinationMetadata.size != reference.byteSize
        ) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        val published = fileSystem.metadataOrNull(destination)
        if (published == null || !published.isRegularFile || published.symlinkTarget != null ||
            published.size != reference.byteSize
        ) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        return opaqueName
    }

    fun readMigrationObjectChunk(
        reference: BlobRef,
        opaqueName: String,
        offset: Long,
        maximumBytes: Int,
    ): ByteArray {
        requireCanonicalOpaqueName(reference, opaqueName)
        require(offset in 0..reference.byteSize)
        require(maximumBytes > 0)
        val count = minOf(maximumBytes.toLong(), reference.byteSize - offset).toInt()
        if (count == 0) return ByteArray(0)
        val handle = fileSystem.openReadOnly(objects.resolve(opaqueName))
        return try {
            if (handle.size() != reference.byteSize) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            ByteArray(count).also { output ->
                val read = handle.read(offset, output, 0, count)
                if (read != count) throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
        } finally {
            handle.close()
        }
    }

    private fun requireSafeMaintenanceName(value: String) {
        require(value.isNotBlank() && value.length <= 256 && '/' !in value && '\\' !in value) {
            "Unsafe blob maintenance filename"
        }
        require(value != "." && value != "..") { "Unsafe blob maintenance filename" }
    }

    fun publish(reference: BlobRef, pending: DurableContentBlobPendingHandle): String {
        val filePending = pending as? FilePending
            ?: throw ContentBlobStoreException.InvalidStage("Pending body belongs to another durability adapter")
        if (filePending.stage.owner !== this || filePending.reference != reference) {
            throw ContentBlobStoreException.InvalidStage("Pending body identity does not match this blob store")
        }
        return filePending.stage.publish(reference)
    }

    fun publish(reference: BlobRef, bytes: ByteArray): String {
        reference.validate()
        if (bytes.size.toLong() != reference.byteSize ||
            dev.shinsou.kmp.crypto.Sha256.hex(bytes) != reference.plaintextDigest
        ) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
        val opaqueName = canonicalOpaqueName(reference)
        val destination = objects.resolve(opaqueName)
        if (fileSystem.exists(destination)) {
            if (!verify(reference, destination)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            return opaqueName
        }

        val temporary = staging.resolve("stage-${randomContentUuid()}.tmp")
        try {
            val handle = fileSystem.openReadWrite(temporary, mustCreate = true, mustExist = false)
            try {
                val sink = handle.sink().buffer()
                try {
                    sink.write(bytes)
                    sink.flush()
                    handle.flush()
                } finally {
                    sink.close()
                }
            } finally {
                handle.close()
            }
            if (!verify(reference, temporary)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            fileSystem.atomicMove(temporary, destination)
            if (!verify(reference, destination)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            return opaqueName
        } catch (failure: Throwable) {
            runCatching { fileSystem.delete(temporary, mustExist = false) } // cleanup only; rethrown below
            throw failure
        }
    }

    fun read(reference: BlobRef, opaqueName: String): ByteArray? {
        requireCanonicalOpaqueName(reference, opaqueName)
        val path = objects.resolve(opaqueName)
        if (!fileSystem.exists(path) || !verify(reference, path)) return null
        return fileSystem.read(path) { readByteArray() }
    }

    fun openRead(reference: BlobRef, opaqueName: String): DurableContentBlobReadHandle? {
        requireCanonicalOpaqueName(reference, opaqueName)
        val path = objects.resolve(opaqueName)
        if (!verify(reference, path)) return null
        return FileReadHandle(fileSystem.source(path).buffer(), reference.byteSize)
    }

    fun delete(reference: BlobRef, opaqueName: String) {
        requireCanonicalOpaqueName(reference, opaqueName)
        fileSystem.delete(objects.resolve(opaqueName), mustExist = false)
    }

    fun requireCanonicalOpaqueName(reference: BlobRef, opaqueName: String) {
        if (opaqueName != canonicalOpaqueName(reference)) {
            throw ContentBlobStoreException.CorruptBlob(reference.blobId)
        }
    }

    fun verify(reference: BlobRef, opaqueName: String): Boolean {
        requireCanonicalOpaqueName(reference, opaqueName)
        return verify(reference, objects.resolve(opaqueName))
    }

    private fun verify(reference: BlobRef, path: Path): Boolean {
        val metadata = fileSystem.metadataOrNull(path) ?: return false
        if (!metadata.isRegularFile || metadata.symlinkTarget != null || metadata.size != reference.byteSize) {
            return false
        }
        val hashing = HashingSource.sha256(fileSystem.source(path))
        val scratch = Buffer()
        return try {
            while (hashing.read(scratch, VERIFY_CHUNK_BYTES) != -1L) scratch.clear()
            hashing.hash.hex() == reference.plaintextDigest
        } finally {
            hashing.close()
        }
    }

    fun canonicalOpaqueName(reference: BlobRef): String = "v1-${reference.blobId}.blob"

    private inner class FileStage(
        override val expectedSizeBytes: Long?,
        private val mediaType: String,
        private val maximumBlobSizeBytes: Long,
        private val temporary: Path,
        private val stagingName: String,
    ) : DurableContentBlobStageHandle {
        val owner: AppPrivateContentBlobFiles get() = this@AppPrivateContentBlobFiles
        private val fileHandle = fileSystem.openReadWrite(temporary, mustCreate = true, mustExist = false)
        private val hashingSink = HashingSink.sha256(fileHandle.sink())
        private val sink = hashingSink.buffer()
        override var bytesWritten: Long = 0L
            private set
        override var isSealed: Boolean = false
            private set
        private var closed = false
        private var published = false

        override fun append(chunk: ByteArray) {
            if (closed || isSealed || published) {
                throw ContentBlobStoreException.InvalidStage("Stage is closed")
            }
            if (chunk.isEmpty()) return
            val next = checkedFileSize(bytesWritten, chunk.size.toLong(), maximumBlobSizeBytes)
            if (expectedSizeBytes != null && next > expectedSizeBytes) {
                throw ContentBlobStoreException.SizeMismatch(expectedSizeBytes, next)
            }
            sink.write(chunk)
            bytesWritten = next
        }

        override fun seal(
            expected: BlobRef?,
            blobIdFactory: () -> String,
        ): DurableContentBlobPendingHandle {
            if (closed || isSealed || published) {
                throw ContentBlobStoreException.InvalidStage("Stage is already closed")
            }
            if (expectedSizeBytes != null && bytesWritten != expectedSizeBytes) {
                throw ContentBlobStoreException.SizeMismatch(expectedSizeBytes, bytesWritten)
            }
            sink.flush()
            fileHandle.flush()
            val digest = hashingSink.hash.hex()
            sink.close()
            fileHandle.close()
            closed = true
            val reference = expected ?: BlobRef(
                blobId = blobIdFactory(),
                schemaVersion = BlobRef.CURRENT_SCHEMA_VERSION,
                digestAlgorithm = BlobRef.SHA_256,
                plaintextDigest = digest,
                byteSize = bytesWritten,
                mediaType = mediaType,
            )
            reference.validate()
            if (reference.byteSize != bytesWritten) {
                throw ContentBlobStoreException.SizeMismatch(reference.byteSize, bytesWritten)
            }
            if (reference.plaintextDigest != digest) {
                throw ContentBlobStoreException.DigestMismatch(reference.plaintextDigest, digest)
            }
            if (reference.mediaType != mediaType) {
                throw ContentBlobStoreException.InvalidStage("Blob media type does not match its stage")
            }
            if (!verify(reference, temporary)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            isSealed = true
            return FilePending(this, reference)
        }

        override fun abort() {
            if (published) return
            if (!closed) {
                // Closing an abandoned stage is best effort: the file is deleted right after.
                runCatching { sink.close() }
                runCatching { fileHandle.close() }
                closed = true
            }
            fileSystem.delete(temporary, mustExist = false)
            activeStageNames.remove(stagingName)
        }

        fun publish(reference: BlobRef): String {
            if (!isSealed || published) {
                throw ContentBlobStoreException.InvalidStage("Stage is not a sealed live candidate")
            }
            val opaqueName = canonicalOpaqueName(reference)
            val destination = objects.resolve(opaqueName)
            if (fileSystem.exists(destination)) {
                if (!verify(reference, destination)) {
                    throw ContentBlobStoreException.CorruptBlob(reference.blobId)
                }
                fileSystem.delete(temporary, mustExist = false)
            } else {
                fileSystem.atomicMove(temporary, destination)
            }
            if (!verify(reference, destination)) {
                throw ContentBlobStoreException.CorruptBlob(reference.blobId)
            }
            published = true
            activeStageNames.remove(stagingName)
            return opaqueName
        }
    }

    private data class FilePending(
        override val stage: FileStage,
        override val reference: BlobRef,
    ) : DurableContentBlobPendingHandle

    private class FileReadHandle(
        private val source: BufferedSource,
        private val byteSize: Long,
    ) : DurableContentBlobReadHandle {
        private var offset = 0L
        private var closed = false

        override fun readChunk(maxBytes: Int): ByteArray? {
            if (closed) throw ContentBlobStoreException.InvalidStage("Reader is closed")
            require(maxBytes > 0) { "Read chunk size must be positive" }
            if (offset == byteSize) return null
            val count = minOf(maxBytes.toLong(), byteSize - offset)
            return source.readByteArray(count).also { offset += it.size }
        }

        override fun close() {
            if (!closed) {
                closed = true
                source.close()
            }
        }
    }

    private companion object {
        const val VERIFY_CHUNK_BYTES: Long = 64L * 1024L
    }
}

private fun checkedFileSize(current: Long, added: Long, maximum: Long): Long {
    if (current < 0 || added < 0 || added > Long.MAX_VALUE - current) {
        throw ContentBlobStoreException.SizeLimitExceeded(Long.MAX_VALUE, maximum)
    }
    val next = current + added
    if (next > maximum) throw ContentBlobStoreException.SizeLimitExceeded(next, maximum)
    return next
}
