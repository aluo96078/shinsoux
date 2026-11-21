package dev.shinsou.kmp.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A stable UUID namespace for one migration family.  The value is deliberately explicit and is
 * never derived from a platform UUID implementation or from Kotlin hash codes.
 */
@Serializable
public data class MigrationNamespaceId(
    val value: String,
) {
    init { validate() }

    public fun validate(): Unit {
        require(UUID_PATTERN.matches(value) && value != NIL_UUID) {
            "Migration namespace must be a lowercase non-NIL UUID"
        }
    }

    /** Derives an RFC 9562 UUIDv5 using the exact UTF-8 bytes of [name]. */
    public fun deriveUuidV5(name: String): String = Rfc9562UuidV5.derive(this, name)

    public val uuid: String get() = value

    public companion object {
        /** Namespace for the lossless Shinsou Manga/Chapter v1 projection. */
        public val LEGACY_MANGA_V1: MigrationNamespaceId =
            MigrationNamespaceId("0f5a6d16-3b71-5a8e-9e50-3ce6d6f6b7a1")
        public val DEFAULT: MigrationNamespaceId get() = LEGACY_MANGA_V1
    }
}

/**
 * RFC 9562 UUIDv5 deriver implemented entirely in common Kotlin.
 *
 * The algorithm is SHA-1(namespace UUID bytes || name UTF-8 bytes), followed by setting the UUID
 * version nibble to 5 and the RFC variant bits to 10xx.  It intentionally does not call
 * java.util.UUID, MessageDigest, platform byte-order helpers, or a platform random source, so
 * the same migration input produces the same identifier on JVM, Android and Kotlin/Native.
 */
public object Rfc9562UuidV5 {
    public fun derive(namespace: MigrationNamespaceId, name: String): String =
        derive(namespace, name.encodeToByteArray())

    /** Derives from exact caller-owned bytes; migration aliases never pass through text normalization. */
    public fun derive(namespace: MigrationNamespaceId, nameBytes: ByteArray): String {
        namespace.validate()
        require(nameBytes.size <= MAX_UUID_NAME_LENGTH) { "UUIDv5 name is too long" }
        val namespaceBytes = parseUuid(namespace.value)
        val input = ByteArray(namespaceBytes.size + nameBytes.size)
        namespaceBytes.copyInto(input, 0)
        nameBytes.copyInto(input, namespaceBytes.size)
        val digest = Sha1.digest(input)
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        return formatUuid(digest)
    }

    public fun derive(namespace: String, name: String): String =
        derive(MigrationNamespaceId(namespace), name)
}

/** Top-level convenience for adapters that do not need the object receiver. */
public fun deriveRfc9562UuidV5(namespace: MigrationNamespaceId, name: String): String =
    Rfc9562UuidV5.derive(namespace, name)

@Serializable
public enum class LegacyAliasKind {
    MANGA,
    ACQUISITION,
    CHAPTER,
    CATEGORY,
    MANGA_CATEGORY,
}

/**
 * Typed aliases keep legacy row identity distinct.  In particular, source and parent manga IDs
 * participate in chapter aliases; a chapter id alone is never allowed to merge two publications.
 */
@Serializable
public sealed interface LegacyAliasKey {
    public val kind: LegacyAliasKind
    public val canonicalId: String

    @Serializable
    @SerialName("manga")
    public data class Manga(
        val id: Long,
        val source: Long = -1L,
    ) : LegacyAliasKey {
        override val kind: LegacyAliasKind get() = LegacyAliasKind.MANGA
        override val canonicalId: String get() = "manga-v1/source=$source/id=$id"
        public val mangaId: Long get() = id
    }

    @Serializable
    @SerialName("acquisition")
    public data class Acquisition(
        val mangaId: Long,
        val source: Long = -1L,
    ) : LegacyAliasKey {
        override val kind: LegacyAliasKind get() = LegacyAliasKind.ACQUISITION
        override val canonicalId: String get() = "acquisition-v1/source=$source/manga=$mangaId"
    }

    @Serializable
    @SerialName("chapter")
    public data class Chapter(
        val mangaId: Long,
        val id: Long,
        val source: Long = -1L,
    ) : LegacyAliasKey {
        override val kind: LegacyAliasKind get() = LegacyAliasKind.CHAPTER
        override val canonicalId: String get() = "chapter-v1/source=$source/manga=$mangaId/id=$id"
        public val chapterId: Long get() = id
    }

    @Serializable
    @SerialName("category")
    public data class Category(
        val id: Long,
    ) : LegacyAliasKey {
        override val kind: LegacyAliasKind get() = LegacyAliasKind.CATEGORY
        override val canonicalId: String get() = "category-v1/id=$id"
        public val categoryId: Long get() = id
    }

    @Serializable
    @SerialName("manga_category")
    public data class MangaCategory(
        val mangaId: Long,
        val categoryId: Long,
    ) : LegacyAliasKey {
        override val kind: LegacyAliasKind get() = LegacyAliasKind.MANGA_CATEGORY
        override val canonicalId: String get() = "manga-category-v1/manga=$mangaId/category=$categoryId"
    }

    public fun validate(): Unit = require(canonicalId.isNotBlank()) { "Legacy alias key is blank" }

    /**
     * Versioned, kind-tagged, fixed-width canonical bytes used only for deterministic migration
     * identity. They deliberately avoid delimiters, locale, trimming and Unicode normalization.
     */
    public fun canonicalBytes(): ByteArray {
        val values = when (this) {
            is Manga -> longArrayOf(source, id)
            is Acquisition -> longArrayOf(source, mangaId)
            is Chapter -> longArrayOf(source, mangaId, id)
            is Category -> longArrayOf(id)
            is MangaCategory -> longArrayOf(mangaId, categoryId)
        }
        val result = ByteArray(8 + values.size * Long.SIZE_BYTES)
        writeIntBigEndian(result, 0, LEGACY_ALIAS_CANONICAL_VERSION)
        writeIntBigEndian(result, 4, kind.ordinal + 1)
        values.forEachIndexed { index, value ->
            writeLongBigEndian(result, 8 + index * Long.SIZE_BYTES, value)
        }
        return result
    }
}

/** Exact, body-free v1 Manga record.  No URL or source value is normalized during migration. */
@Serializable
public data class LegacyMangaRecordV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: Long = -1L,
    val source: Long = -1L,
    val favorite: Boolean = false,
    val lastUpdate: Long = 0L,
    val nextUpdate: Long = 0L,
    val fetchInterval: Int = 0,
    val dateAdded: Long = 0L,
    val viewerFlags: Long = 0L,
    val chapterFlags: Long = 0L,
    val coverLastModified: Long = 0L,
    val url: String = "",
    val title: String = "",
    val artist: String? = null,
    val author: String? = null,
    val description: String? = null,
    val genre: List<String>? = null,
    val status: Long = 0L,
    val thumbnailUrl: String? = null,
    val updateStrategy: Int = 0,
    val initialized: Boolean = false,
    val lastModifiedAt: Long = 0L,
    val favoriteModifiedAt: Long? = null,
    val version: Long = 0L,
    val notes: String = "",
    val excludedScanlators: Set<String> = emptySet(),
    /** Position in the legacy list used by the v1 adapter; identity never depends on it. */
    val legacyListOrdinal: Int = 0,
) {
    init { validate() }

    public val wireVersion: Int get() = schemaVersion

    public fun validate(): Unit = require(schemaVersion == CURRENT_SCHEMA_VERSION) {
        "Unsupported legacy Manga record schema version $schemaVersion"
    }

    public fun toManga(): Manga = Manga(
        id = id,
        source = source,
        favorite = favorite,
        lastUpdate = lastUpdate,
        nextUpdate = nextUpdate,
        fetchInterval = fetchInterval,
        dateAdded = dateAdded,
        viewerFlags = viewerFlags,
        chapterFlags = chapterFlags,
        coverLastModified = coverLastModified,
        url = url,
        title = title,
        artist = artist,
        author = author,
        description = description,
        genre = genre,
        status = status,
        thumbnailUrl = thumbnailUrl,
        updateStrategy = updateStrategy,
        initialized = initialized,
        lastModifiedAt = lastModifiedAt,
        favoriteModifiedAt = favoriteModifiedAt,
        version = version,
        notes = notes,
        excludedScanlators = LinkedHashSet<String>().also { it.addAll(excludedScanlators) },
    )

    public fun libraryState(): LegacyMangaLibraryStateV1 = LegacyMangaLibraryStateV1.from(this)

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public fun fromManga(manga: Manga, legacyListOrdinal: Int = 0): LegacyMangaRecordV1 = LegacyMangaRecordV1(
            id = manga.id,
            source = manga.source,
            favorite = manga.favorite,
            lastUpdate = manga.lastUpdate,
            nextUpdate = manga.nextUpdate,
            fetchInterval = manga.fetchInterval,
            dateAdded = manga.dateAdded,
            viewerFlags = manga.viewerFlags,
            chapterFlags = manga.chapterFlags,
            coverLastModified = manga.coverLastModified,
            url = manga.url,
            title = manga.title,
            artist = manga.artist,
            author = manga.author,
            description = manga.description,
            genre = manga.genre,
            status = manga.status,
            thumbnailUrl = manga.thumbnailUrl,
            updateStrategy = manga.updateStrategy,
            initialized = manga.initialized,
            lastModifiedAt = manga.lastModifiedAt,
            favoriteModifiedAt = manga.favoriteModifiedAt,
            version = manga.version,
            notes = manga.notes,
            excludedScanlators = LinkedHashSet<String>().also { it.addAll(manga.excludedScanlators) },
            legacyListOrdinal = legacyListOrdinal,
        )
    }
}

/** Exact, body-free v1 Chapter record. */
@Serializable
public data class LegacyChapterRecordV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: Long = -1L,
    val mangaId: Long = -1L,
    val url: String = "",
    val name: String = "",
    val scanlator: String? = null,
    val read: Boolean = false,
    val bookmark: Boolean = false,
    val lastPageRead: Int = 0,
    val chapterNumber: Double = -1.0,
    val sourceOrder: Int = 0,
    val dateFetch: Long = 0L,
    val dateUpload: Long = 0L,
    val lastModifiedAt: Long = 0L,
    val version: Long = 1L,
    /** Position in Manga.chapters as read from the legacy store. */
    val legacyListOrdinal: Int = 0,
) {
    init { validate() }

    public val wireVersion: Int get() = schemaVersion

    public fun validate(): Unit = require(schemaVersion == CURRENT_SCHEMA_VERSION) {
        "Unsupported legacy Chapter record schema version $schemaVersion"
    }

    public fun toChapter(): Chapter = Chapter(
        id = id,
        mangaId = mangaId,
        url = url,
        name = name,
        scanlator = scanlator,
        read = read,
        bookmark = bookmark,
        lastPageRead = lastPageRead,
        chapterNumber = chapterNumber,
        sourceOrder = sourceOrder,
        dateFetch = dateFetch,
        dateUpload = dateUpload,
        lastModifiedAt = lastModifiedAt,
        version = version,
    )

    public fun libraryState(): LegacyChapterLibraryStateV1 = LegacyChapterLibraryStateV1.from(this)

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public fun fromChapter(chapter: Chapter, legacyListOrdinal: Int = 0): LegacyChapterRecordV1 = LegacyChapterRecordV1(
            id = chapter.id,
            mangaId = chapter.mangaId,
            url = chapter.url,
            name = chapter.name,
            scanlator = chapter.scanlator,
            read = chapter.read,
            bookmark = chapter.bookmark,
            lastPageRead = chapter.lastPageRead,
            chapterNumber = chapter.chapterNumber,
            sourceOrder = chapter.sourceOrder,
            dateFetch = chapter.dateFetch,
            dateUpload = chapter.dateUpload,
            lastModifiedAt = chapter.lastModifiedAt,
            version = chapter.version,
            legacyListOrdinal = legacyListOrdinal,
        )
    }
}

@Serializable
public data class LegacyCategoryRecordV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val id: Long = 0L,
    val name: String = "",
    val sort: Int = 0,
    val flags: Long = 0L,
    /** Position in the legacy category list. */
    val legacyListOrdinal: Int = 0,
) {
    init { validate() }

    public val wireVersion: Int get() = schemaVersion
    public fun validate(): Unit = require(schemaVersion == CURRENT_SCHEMA_VERSION) {
        "Unsupported legacy Category record schema version $schemaVersion"
    }

    public fun toCategory(): Category = Category(id, name, sort, flags)

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public fun fromCategory(category: Category, legacyListOrdinal: Int = 0): LegacyCategoryRecordV1 =
            LegacyCategoryRecordV1(
                id = category.id,
                name = category.name,
                sort = category.sort,
                flags = category.flags,
                legacyListOrdinal = legacyListOrdinal,
            )
    }
}

@Serializable
public data class LegacyMangaCategoryLinkV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val mangaId: Long = -1L,
    val categoryId: Long = 0L,
    /** Position in the legacy MangaCategory relation list. */
    val legacyListOrdinal: Int = 0,
) {
    init { validate() }

    public val wireVersion: Int get() = schemaVersion
    public fun validate(): Unit = require(schemaVersion == CURRENT_SCHEMA_VERSION) {
        "Unsupported legacy MangaCategory link schema version $schemaVersion"
    }

    public fun toMangaCategory(): MangaCategory = MangaCategory(mangaId, categoryId)

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public fun fromMangaCategory(link: MangaCategory, legacyListOrdinal: Int = 0): LegacyMangaCategoryLinkV1 =
            LegacyMangaCategoryLinkV1(
                mangaId = link.mangaId,
                categoryId = link.categoryId,
                legacyListOrdinal = legacyListOrdinal,
            )
    }
}

/** Versioned aggregate preserving record and relationship order exactly as supplied. */
@Serializable
public data class LegacyMangaAggregateV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val record: LegacyMangaRecordV1,
    val chapters: List<LegacyChapterRecordV1> = emptyList(),
    val categories: List<LegacyCategoryRecordV1> = emptyList(),
    val links: List<LegacyMangaCategoryLinkV1> = emptyList(),
) {
    init { validate() }

    public val wireVersion: Int get() = schemaVersion
    public val manga: LegacyMangaRecordV1 get() = record
    public val chapterRecords: List<LegacyChapterRecordV1> get() = chapters
    public val categoryRecords: List<LegacyCategoryRecordV1> get() = categories
    public val mangaCategoryLinks: List<LegacyMangaCategoryLinkV1> get() = links

    public fun validate(): Unit {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) {
            "Unsupported legacy Manga aggregate schema version $schemaVersion"
        }
        record.validate()
        require(record.legacyListOrdinal >= 0) { "Legacy Manga list ordinal must be non-negative" }
        chapters.forEach {
            it.validate()
            require(it.legacyListOrdinal >= 0) { "Legacy Chapter list ordinal must be non-negative" }
            require(it.mangaId == record.id) { "Legacy Chapter refers to another Manga" }
        }
        categories.forEach {
            it.validate()
            require(it.legacyListOrdinal >= 0) { "Legacy Category list ordinal must be non-negative" }
        }
        links.forEach {
            it.validate()
            require(it.legacyListOrdinal >= 0) { "Legacy MangaCategory list ordinal must be non-negative" }
        }
        require(chapters.map { it.id }.distinct().size == chapters.size) {
            "Legacy Chapter IDs must be unique within an aggregate"
        }
        require(categories.map { it.id }.distinct().size == categories.size) {
            "Legacy Category IDs must be unique within an aggregate"
        }
        require(links.map { it.mangaId to it.categoryId }.distinct().size == links.size) {
            "Legacy MangaCategory links must be unique"
        }
        val categoryIds = categories.mapTo(hashSetOf()) { it.id }
        links.forEach {
            require(it.mangaId == record.id) { "Legacy MangaCategory link refers to another Manga" }
            require(it.categoryId in categoryIds) { "Legacy MangaCategory link refers to a missing Category" }
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
        public fun fromLegacy(
            manga: Manga,
            chapters: List<Chapter> = emptyList(),
            categories: List<Category> = emptyList(),
            links: List<MangaCategory> = emptyList(),
        ): LegacyMangaAggregateV1 = LegacyMangaAggregateV1(
            record = LegacyMangaRecordV1.fromManga(manga),
            chapters = chapters.mapIndexed { index, chapter ->
                LegacyChapterRecordV1.fromChapter(chapter, legacyListOrdinal = index)
            },
            categories = categories.mapIndexed { index, category ->
                LegacyCategoryRecordV1.fromCategory(category, legacyListOrdinal = index)
            },
            links = links.mapIndexed { index, link ->
                LegacyMangaCategoryLinkV1.fromMangaCategory(link, legacyListOrdinal = index)
            },
        )
    }
}

/** Body-free library state kept beside the compatibility facet. */
@Serializable
public data class LegacyMangaLibraryStateV1(
    val favorite: Boolean,
    val lastUpdate: Long,
    val nextUpdate: Long,
    val fetchInterval: Int,
    val dateAdded: Long,
    val viewerFlags: Long,
    val chapterFlags: Long,
    val coverLastModified: Long,
    val status: Long,
    val updateStrategy: Int,
    val initialized: Boolean,
    val lastModifiedAt: Long,
    val favoriteModifiedAt: Long?,
    val version: Long,
    val notes: String,
    val excludedScanlators: Set<String>,
) {
    public companion object {
        public fun from(record: LegacyMangaRecordV1): LegacyMangaLibraryStateV1 = LegacyMangaLibraryStateV1(
            favorite = record.favorite,
            lastUpdate = record.lastUpdate,
            nextUpdate = record.nextUpdate,
            fetchInterval = record.fetchInterval,
            dateAdded = record.dateAdded,
            viewerFlags = record.viewerFlags,
            chapterFlags = record.chapterFlags,
            coverLastModified = record.coverLastModified,
            status = record.status,
            updateStrategy = record.updateStrategy,
            initialized = record.initialized,
            lastModifiedAt = record.lastModifiedAt,
            favoriteModifiedAt = record.favoriteModifiedAt,
            version = record.version,
            notes = record.notes,
            excludedScanlators = record.excludedScanlators,
        )
    }
}

@Serializable
public data class LegacyChapterLibraryStateV1(
    val read: Boolean,
    val bookmark: Boolean,
    val lastPageRead: Int,
    val chapterNumber: Double,
    val sourceOrder: Int,
    val dateFetch: Long,
    val dateUpload: Long,
    val lastModifiedAt: Long,
    val version: Long,
) {
    public companion object {
        public fun from(record: LegacyChapterRecordV1): LegacyChapterLibraryStateV1 = LegacyChapterLibraryStateV1(
            read = record.read,
            bookmark = record.bookmark,
            lastPageRead = record.lastPageRead,
            chapterNumber = record.chapterNumber,
            sourceOrder = record.sourceOrder,
            dateFetch = record.dateFetch,
            dateUpload = record.dateUpload,
            lastModifiedAt = record.lastModifiedAt,
            version = record.version,
        )
    }
}

/** Compatibility facet attached to one Acquisition; it contains no body bytes. */
@Serializable
public data class LegacyMangaCompatibilityFacetV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val namespace: MigrationNamespaceId,
    val record: LegacyMangaRecordV1,
    val sourcePackageId: String = "legacy.manga.v1",
    val alias: LegacyAliasKey.Manga = LegacyAliasKey.Manga(record.id, record.source),
    val libraryState: LegacyMangaLibraryStateV1 = LegacyMangaLibraryStateV1.from(record),
    val categories: List<LegacyCategoryRecordV1> = emptyList(),
    val links: List<LegacyMangaCategoryLinkV1> = emptyList(),
) {
    init { validate() }

    public val aggregateRecord: LegacyMangaRecordV1 get() = record
    public fun validate(): Unit {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported legacy Manga facet version" }
        namespace.validate()
        record.validate()
        require(sourcePackageId.isNotBlank()) { "Legacy source package id must not be blank" }
        alias.validate()
        require(alias == LegacyAliasKey.Manga(record.id, record.source)) {
            "Legacy Manga facet alias does not match its record"
        }
        require(libraryState == LegacyMangaLibraryStateV1.from(record)) {
            "Legacy Manga library state does not match its record"
        }
        require(record.legacyListOrdinal >= 0) { "Legacy Manga list ordinal must be non-negative" }
        categories.forEach {
            it.validate()
            require(it.legacyListOrdinal >= 0) { "Legacy Category list ordinal must be non-negative" }
        }
        links.forEach {
            it.validate()
            require(it.legacyListOrdinal >= 0) { "Legacy MangaCategory list ordinal must be non-negative" }
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

/** Compatibility facet attached to one PublicationUnit; it contains no chapter body. */
@Serializable
public data class LegacyChapterCompatibilityFacetV1(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val namespace: MigrationNamespaceId,
    val record: LegacyChapterRecordV1,
    val parentSource: Long = -1L,
    val alias: LegacyAliasKey.Chapter = LegacyAliasKey.Chapter(record.mangaId, record.id, parentSource),
    val libraryState: LegacyChapterLibraryStateV1 = LegacyChapterLibraryStateV1.from(record),
) {
    init { validate() }

    public val aggregateRecord: LegacyChapterRecordV1 get() = record
    public fun validate(): Unit {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Unsupported legacy Chapter facet version" }
        namespace.validate()
        record.validate()
        require(record.legacyListOrdinal >= 0) { "Legacy Chapter list ordinal must be non-negative" }
        alias.validate()
        require(alias == LegacyAliasKey.Chapter(record.mangaId, record.id, parentSource)) {
            "Legacy Chapter facet alias does not match its record"
        }
        require(libraryState == LegacyChapterLibraryStateV1.from(record)) {
            "Legacy Chapter library state does not match its record"
        }
    }

    public companion object {
        public const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}

public typealias LegacyMangaRecord = LegacyMangaRecordV1
public typealias LegacyChapterRecord = LegacyChapterRecordV1
public typealias LegacyCategoryRecord = LegacyCategoryRecordV1
public typealias LegacyCategoryV1 = LegacyCategoryRecordV1
public typealias LegacyLinkV1 = LegacyMangaCategoryLinkV1
public typealias LegacyMangaCompatibilityFacet = LegacyMangaCompatibilityFacetV1
public typealias LegacyChapterCompatibilityFacet = LegacyChapterCompatibilityFacetV1

private object Sha1 {
    fun digest(input: ByteArray): ByteArray {
        val bitLength = input.size.toLong() * 8L
        val paddedLength = ((input.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedLength)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (index in 0 until 8) {
            padded[padded.size - 1 - index] = (bitLength ushr (index * 8)).toByte()
        }
        var h0 = 0x67452301
        var h1 = 0xefcdab89.toInt()
        var h2 = 0x98badcfe.toInt()
        var h3 = 0x10325476
        var h4 = 0xc3d2e1f0.toInt()
        val words = IntArray(80)
        var offset = 0
        while (offset < padded.size) {
            for (index in 0 until 16) {
                val base = offset + index * 4
                words[index] = ((padded[base].toInt() and 0xff) shl 24) or
                    ((padded[base + 1].toInt() and 0xff) shl 16) or
                    ((padded[base + 2].toInt() and 0xff) shl 8) or
                    (padded[base + 3].toInt() and 0xff)
            }
            for (index in 16 until 80) words[index] = rotateLeft(words[index - 3] xor words[index - 8] xor words[index - 14] xor words[index - 16], 1)
            var a = h0
            var b = h1
            var c = h2
            var d = h3
            var e = h4
            for (index in 0 until 80) {
                val (function, constant) = when (index) {
                    in 0..19 -> ((b and c) or (b.inv() and d)) to 0x5a827999
                    in 20..39 -> (b xor c xor d) to 0x6ed9eba1
                    in 40..59 -> ((b and c) or (b and d) or (c and d)) to 0x8f1bbcdc.toInt()
                    else -> (b xor c xor d) to 0xca62c1d6.toInt()
                }
                val temp = rotateLeft(a, 5) + function + e + constant + words[index]
                e = d
                d = c
                c = rotateLeft(b, 30)
                b = a
                a = temp
            }
            h0 += a
            h1 += b
            h2 += c
            h3 += d
            h4 += e
            offset += 64
        }
        val result = ByteArray(20)
        val values = intArrayOf(h0, h1, h2, h3, h4)
        values.forEachIndexed { valueIndex, value ->
            repeat(4) { byteIndex ->
                result[valueIndex * 4 + byteIndex] = (value ushr (24 - byteIndex * 8)).toByte()
            }
        }
        return result
    }

    private fun rotateLeft(value: Int, distance: Int): Int = (value shl distance) or (value ushr (32 - distance))
}

private fun parseUuid(value: String): ByteArray {
    val compact = value.replace("-", "")
    val bytes = ByteArray(16)
    repeat(16) { index -> bytes[index] = compact.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    return bytes
}

private fun formatUuid(bytes: ByteArray): String {
    val hex = "0123456789abcdef"
    val compact = buildString(32) {
        bytes.take(16).forEach { byte ->
            val value = byte.toInt() and 0xff
            append(hex[value ushr 4])
            append(hex[value and 0x0f])
        }
    }
    return buildString(36) {
        append(compact, 0, 8)
        append('-')
        append(compact, 8, 12)
        append('-')
        append(compact, 12, 16)
        append('-')
        append(compact, 16, 20)
        append('-')
        append(compact, 20, 32)
    }
}

private const val MAX_UUID_NAME_LENGTH: Int = 1_048_576
private const val LEGACY_ALIAS_CANONICAL_VERSION: Int = 1
private const val NIL_UUID = "00000000-0000-0000-0000-000000000000"
private val UUID_PATTERN = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

private fun writeIntBigEndian(target: ByteArray, offset: Int, value: Int) {
    repeat(Int.SIZE_BYTES) { index ->
        target[offset + index] = (value ushr (24 - index * 8)).toByte()
    }
}

private fun writeLongBigEndian(target: ByteArray, offset: Int, value: Long) {
    repeat(Long.SIZE_BYTES) { index ->
        target[offset + index] = (value ushr (56 - index * 8)).toByte()
    }
}
