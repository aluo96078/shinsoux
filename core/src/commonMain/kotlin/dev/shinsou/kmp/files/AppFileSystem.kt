package dev.shinsou.kmp.files

import okio.Buffer
import okio.BufferedSink

/** Sandboxed application files used for downloaded pages, local sources and backups. */
public interface AppFileSystem {
    public suspend fun write(relativePath: String, bytes: ByteArray)

    /**
     * Replaces [relativePath] without exposing a partially-written destination.
     *
     * Production file systems override this with their platform's atomic replace primitive. The
     * default keeps in-memory/test implementations source-compatible.
     */
    public suspend fun writeAtomically(relativePath: String, bytes: ByteArray): Unit = write(relativePath, bytes)

    /**
     * [writeAtomically] for content produced by [writer] straight into the file (R3-5), so a large
     * backup is never held in memory as one array. Returns the bytes written. A [writer] that
     * fails leaves the destination as it was. The default buffers in memory and calls
     * [writeAtomically], for in-memory/test implementations; production file systems stream.
     */
    public suspend fun writeAtomically(relativePath: String, writer: (BufferedSink) -> Unit): Long {
        val buffer = Buffer()
        writer(buffer)
        val size = buffer.size
        writeAtomically(relativePath, buffer.readByteArray())
        return size
    }

    public suspend fun read(relativePath: String): ByteArray?
    public suspend fun exists(relativePath: String): Boolean
    public suspend fun delete(relativePath: String): Boolean
    public suspend fun deleteTree(relativeDirectory: String): Boolean
    public suspend fun list(relativeDirectory: String): List<String>

    /**
     * Lists at most [maximumEntries], failing instead of materializing an unbounded directory.
     * Production file systems override this with an early-stopping traversal.
     */
    public suspend fun list(relativeDirectory: String, maximumEntries: Int): List<String> {
        require(maximumEntries >= 0) { "Maximum directory entry count cannot be negative" }
        return list(relativeDirectory).also { entries ->
            require(entries.size <= maximumEntries) { "Directory contains too many files" }
        }
    }

    public fun uri(relativePath: String): String

    /** Returns a sandboxed native path when a platform API needs direct file-system access. */
    public fun absolutePath(relativePath: String): String? = null
}

internal fun validatedRelativePath(value: String): String {
    val normalized = value.replace('\\', '/').trim('/')
    require(normalized.isNotBlank()) { "Path cannot be empty" }
    require(normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Unsafe relative path: $value"
    }
    require(!normalized.contains('\u0000') && !normalized.contains("://")) {
        "Unsafe relative path: $value"
    }
    return normalized
}
