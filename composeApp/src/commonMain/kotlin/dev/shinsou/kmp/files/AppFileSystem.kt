package dev.shinsou.kmp.files

/** Sandboxed application files used for downloaded pages, local sources and backups. */
interface AppFileSystem {
    suspend fun write(relativePath: String, bytes: ByteArray)

    /**
     * Replaces [relativePath] without exposing a partially-written destination.
     *
     * Production file systems override this with their platform's atomic replace primitive. The
     * default keeps in-memory/test implementations source-compatible.
     */
    suspend fun writeAtomically(relativePath: String, bytes: ByteArray) = write(relativePath, bytes)

    suspend fun read(relativePath: String): ByteArray?
    suspend fun exists(relativePath: String): Boolean
    suspend fun delete(relativePath: String): Boolean
    suspend fun deleteTree(relativeDirectory: String): Boolean
    suspend fun list(relativeDirectory: String): List<String>
    fun uri(relativePath: String): String

    /** Returns a sandboxed native path when a platform API needs direct file-system access. */
    fun absolutePath(relativePath: String): String? = null
}

fun validatedRelativePath(value: String): String {
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
