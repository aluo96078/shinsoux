package dev.shinsou.kmp.contract

import dev.shinsou.kmp.domain.model.ReaderImageTransform

// R4-1 (docs/architecture/0006): host contracts shared by the plugin, local and UI layers.

/** Immutable random-access picker result; large files need not live in the Kotlin heap. */
public interface ImportedDocumentSource {
    public val byteSize: Long
    /** Returns exactly [byteCount] bytes or throws when the provider changed/truncated. */
    public fun read(offset: Long, byteCount: Int): ByteArray

    /**
     * Releases what [read] kept open (a file handle or descriptor) once the import is over. A
     * source may keep one open for the whole import instead of reopening the file for every read.
     */
    public fun close(): Unit = Unit
}

public class ImportedDocument(
    public val name: String,
    public val source: ImportedDocumentSource,
) {
    public constructor(name: String, contents: ByteArray) : this(name, ByteArrayImportedDocumentSource(contents))

    public val byteSize: Long get() = source.byteSize

    /** See [ImportedDocumentSource.close]; never throws. */
    public fun close() {
        try {
            source.close()
        } catch (_: Exception) {
            // Closing a read-only source loses nothing.
        }
    }

    /** Compatibility materialization for bounded images, text and legacy callers. */
    public val contents: ByteArray
        get() {
            require(byteSize <= Int.MAX_VALUE.toLong()) { "Imported document cannot fit in memory" }
            val output = ByteArray(byteSize.toInt())
            var offset = 0
            while (offset < output.size) {
                val count = minOf(IMPORTED_DOCUMENT_READ_CHUNK_BYTES, output.size - offset)
                val chunk = source.read(offset.toLong(), count)
                require(chunk.size == count) { "Imported document source returned a truncated read" }
                chunk.copyInto(output, offset)
                offset += count
            }
            return output
        }
}

private class ByteArrayImportedDocumentSource(
    private val bytes: ByteArray,
) : ImportedDocumentSource {
    override val byteSize: Long get() = bytes.size.toLong()

    override fun read(offset: Long, byteCount: Int): ByteArray {
        require(offset in 0..bytes.size.toLong() && byteCount >= 0 &&
            byteCount.toLong() <= bytes.size.toLong() - offset) {
            "Imported document source read is out of bounds"
        }
        return bytes.copyOfRange(offset.toInt(), offset.toInt() + byteCount)
    }
}

private const val IMPORTED_DOCUMENT_READ_CHUNK_BYTES: Int = 64 * 1024

/** One title [ContentCallbacks.importLocalDocuments] added to the built-in local source. */
public data class LocalImportResult(
    val mangaId: Long,
    val chapterId: Long,
    val title: String,
    val pageCount: Int,
)

/** An encoding the user can choose for a TXT import. */
public enum class LocalTextEncoding {
    /** Simplified Chinese (GB18030, a superset of GBK and GB2312). */
    GB18030,

    /** Traditional Chinese. */
    BIG5,

    /** Unicode: a file that is valid UTF-8 but may also be legacy Chinese text. */
    UTF_8,
}

/** One reading of a TXT import: its encoding and the start of the text it gives. */
public data class LocalTextEncodingCandidate(
    val encoding: LocalTextEncoding,
    val preview: String,
)

/**
 * A TXT file reads as valid text in more than one encoding and the import cannot tell which is
 * right, the file is already in the library, or the user asked to choose: the user chooses. The message quotes
 * the user's file name and text, so it is shown, never logged.
 */
public data class LocalTextEncodingQuestion(
    val documentName: String,
    val candidates: List<LocalTextEncodingCandidate>,
    val recommended: LocalTextEncoding,
    /** Encodings this file is already imported in. Choosing another replaces those titles. */
    val imported: List<LocalTextEncoding>,
)

/** Keeps a reading already in the library, else takes the likelier one: never replaces a title. */
public val KeepImportedTextEncoding: suspend (LocalTextEncodingQuestion) -> LocalTextEncoding? =
    { question -> question.imported.firstOrNull() ?: question.recommended }

/** Network/source operations needed by detail, updates, downloads, and the reader. */
public interface ContentCallbacks {
    /** Copies selected image or ZIP-based comic documents into the built-in source 0. */
    public suspend fun importLocalDocuments(
        documents: List<ImportedDocument>,
        /** Explicit opt-in for rights-allowed TXT/EPUB bodies; ordinary import remains device-only. */
        syncContentBodies: Boolean = false,
        /**
         * Runs each library write of the import (a title's short content commit together with its
         * Manga/Chapter rows, or their rollback) where the app orders its store writes. Parsing
         * and hashing stay outside, so a large import does not hold up the writes queued behind it.
         */
        writeLibrary: suspend (suspend () -> Unit) -> Unit = { it() },
        /**
         * Asks which encoding a TXT is in ([LocalTextEncodingQuestion]); null skips that file.
         * Called outside [writeLibrary], and it may wait for the user.
         */
        chooseTextEncoding: suspend (LocalTextEncodingQuestion) -> LocalTextEncoding? = KeepImportedTextEncoding,
        /**
         * The user asked to choose the encoding: [chooseTextEncoding] is asked for every TXT
         * without a byte order mark that has more than one valid reading (UTF-8, GB18030, Big5),
         * also where the import would be sure. Detection cannot tell every legacy text from valid
         * UTF-8, and the picked file is not kept, so this is how a wrong reading is corrected:
         * importing the file again and choosing replaces the title it has.
         */
        alwaysChooseTextEncoding: Boolean = false,
    ): List<LocalImportResult> = emptyList()

    public suspend fun refreshLibrary(mangaIds: Set<Long>)

    public suspend fun refreshManga(mangaId: Long)

    /** Resolves a persisted source-relative manga URL into the canonical external URL. */
    public suspend fun resolveMangaOriginalUrl(mangaId: Long): String?

    /** Resolves a persisted chapter URL into a safe HTTP(S) URL suitable for an external browser. */
    public suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String?

    public suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter

    public suspend fun enqueueDownload(mangaId: Long, chapterId: Long)

    public suspend fun retryDownload(itemId: String)

    public suspend fun removeDownload(itemId: String)

    /** Removes a queue entry unless it finished downloading by the time the removal runs. */
    public suspend fun removeUnfinishedDownload(itemId: String): Unit = removeDownload(itemId)

    public suspend fun reorderDownloads(orderedIds: List<String>)

    public suspend fun clearCompletedDownloads()

    public suspend fun pauseDownloads(paused: Boolean)

    public companion object {
        public val None: ContentCallbacks = EmptyContentCallbacks()
    }
}

/**
 * A host without network sources: nothing to refresh, read or download. The interface members
 * carry no defaults (R4-2, docs/architecture/0006) so a production implementation cannot leave
 * one out by accident; [ContentCallbacks.None] and test fakes extend this class instead.
 */
public open class EmptyContentCallbacks : ContentCallbacks {
    override suspend fun refreshLibrary(mangaIds: Set<Long>): Unit = Unit
    override suspend fun refreshManga(mangaId: Long): Unit = Unit
    override suspend fun resolveMangaOriginalUrl(mangaId: Long): String? = null
    override suspend fun resolveChapterOriginalUrl(mangaId: Long, chapterId: Long): String? = null
    override suspend fun loadReaderChapter(mangaId: Long, chapterId: Long): ReaderChapter = ReaderChapter()
    override suspend fun enqueueDownload(mangaId: Long, chapterId: Long): Unit = Unit
    override suspend fun retryDownload(itemId: String): Unit = Unit
    override suspend fun removeDownload(itemId: String): Unit = Unit
    override suspend fun reorderDownloads(orderedIds: List<String>): Unit = Unit
    override suspend fun clearCompletedDownloads(): Unit = Unit
    override suspend fun pauseDownloads(paused: Boolean): Unit = Unit
}

public data class ReaderChapter(
    val pages: List<ReaderPage> = emptyList(),
    val referer: String? = null,
    val sourceHeaders: Map<String, String> = emptyMap(),
    /** Typed text/EPUB/image session; protected sessions must never be downgraded to legacy pages. */
    val typedSession: TypedReaderContentSession? = null,
)

public data class ReaderPage(
    val index: Int,
    val imageUrl: String = "",
    /** Host-fetched bytes for plugin-controlled remote content; Coil never sees the remote URL. */
    val imageBytes: ByteArray? = null,
    val headers: Map<String, String> = emptyMap(),
    val local: Boolean = false,
    val imageTransform: ReaderImageTransform? = null,
    /** Encoded height of a local [imageUrl] page, read once so [imageTransform] needs no full-size decode. */
    val imageNaturalHeight: Int? = null,
    /**
     * Resolves viewer HTML only when this page is about to be displayed. Large galleries can
     * therefore open without fetching hundreds of viewer pages up front. The returned page must
     * contain a final image URL and no further resolver.
     */
    val imageResolver: (suspend () -> ReaderPage)? = null,
    /** Display-only request URL (fragment stripped) for developer-mode diagnostics; never fetched. */
    val diagnosticUrl: String? = null,
)

/** Display-only URL for diagnostics: drops the `#...` fragment, which can carry request headers. */
public fun diagnosticUrl(url: String?): String? =
    url?.substringBefore('#')?.trim()?.takeIf(String::isNotEmpty)
