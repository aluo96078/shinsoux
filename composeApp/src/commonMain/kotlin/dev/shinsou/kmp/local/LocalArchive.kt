package dev.shinsou.kmp.local

internal val LOCAL_IMAGE_EXTENSIONS: Set<String> = setOf(
    "jpg",
    "jpeg",
    "png",
    "webp",
    "gif",
    "avif",
    "heic",
    "bmp",
)

// EPUB is ZIP-based. Matching the Swift source, its image payload is handled like a comic archive.
/** Comic archives only. EPUB is parsed into the typed publication graph, never as image ZIP. */
internal val LOCAL_ARCHIVE_EXTENSIONS: Set<String> = setOf("zip", "cbz")

internal fun fileExtension(name: String): String = name.substringAfterLast('.', "").lowercase()

internal fun isSupportedLocalImage(name: String): Boolean = fileExtension(name) in LOCAL_IMAGE_EXTENSIONS

internal fun isSafeArchiveEntryName(name: String): Boolean {
    if (name.isBlank() || '\u0000' in name) return false
    val normalized = name.replace('\\', '/')
    if (normalized.startsWith('/') || WINDOWS_DRIVE_PATH.containsMatchIn(normalized)) return false
    val withoutDirectoryMarker = normalized.trimEnd('/')
    if (withoutDirectoryMarker.isBlank()) return false
    return withoutDirectoryMarker.split('/').none { it.isBlank() || it == "." || it == ".." }
}

/** Reads classic ZIP central-directory names without trusting them as output paths. */
internal fun validateZipEntryNames(bytes: ByteArray): List<String> {
    val end = findEndOfCentralDirectory(bytes)
    require(end >= 0) { "The selected archive is not a valid ZIP/CBZ file." }
    val disk = bytes.readUInt16(end + 4)
    val centralDisk = bytes.readUInt16(end + 6)
    val entriesOnDisk = bytes.readUInt16(end + 8)
    val entryCount = bytes.readUInt16(end + 10)
    val centralSize = bytes.readUInt32(end + 12)
    val centralOffset = bytes.readUInt32(end + 16)
    require(disk == 0 && centralDisk == 0 && entriesOnDisk == entryCount) {
        "Multi-volume ZIP archives are not supported."
    }
    require(entryCount != ZIP64_UINT16 && centralSize != ZIP64_UINT32 && centralOffset != ZIP64_UINT32) {
        "ZIP64 archives are not supported for local import."
    }
    require(entryCount <= MAX_ARCHIVE_ENTRIES) { "Archive contains too many entries." }
    require(centralOffset <= Int.MAX_VALUE.toLong() && centralSize <= Int.MAX_VALUE.toLong()) {
        "Archive central directory is invalid."
    }
    require(centralOffset + centralSize <= end.toLong()) { "Archive central directory is invalid." }

    var cursor = centralOffset.toInt()
    return buildList(entryCount) {
        repeat(entryCount) {
            require(bytes.hasSignature(cursor, CENTRAL_DIRECTORY_SIGNATURE)) {
                "Archive central directory is invalid."
            }
            val flags = bytes.readUInt16(cursor + 8)
            require(flags and ENCRYPTED_FLAG == 0) { "Encrypted local archives are not supported." }
            val compressedSize = bytes.readUInt32(cursor + 20)
            val uncompressedSize = bytes.readUInt32(cursor + 24)
            val nameLength = bytes.readUInt16(cursor + 28)
            val extraLength = bytes.readUInt16(cursor + 30)
            val commentLength = bytes.readUInt16(cursor + 32)
            val startDisk = bytes.readUInt16(cursor + 34)
            val localHeaderOffset = bytes.readUInt32(cursor + 42)
            require(
                compressedSize != ZIP64_UINT32 &&
                    uncompressedSize != ZIP64_UINT32 &&
                    startDisk != ZIP64_UINT16 &&
                    localHeaderOffset != ZIP64_UINT32,
            ) { "ZIP64 archives are not supported for local import." }
            val nameStart = cursor + CENTRAL_DIRECTORY_HEADER_SIZE
            val nameEnd = nameStart.checkedAdd(nameLength, bytes.size)
            val entryName = bytes.copyOfRange(nameStart, nameEnd).decodeToString()
            require(isSafeArchiveEntryName(entryName)) { "Archive contains an unsafe path: $entryName" }
            add(entryName)
            cursor = nameEnd.checkedAdd(extraLength, bytes.size).checkedAdd(commentLength, bytes.size)
        }
    }
}

internal val naturalPathComparator: Comparator<String> = Comparator(::compareNaturalPaths)

internal fun naturalSortedPaths(paths: List<String>): List<String> = paths.sortedWith(naturalPathComparator)

internal fun deriveLocalTitle(names: List<String>): String {
    val stems = naturalSortedPaths(names).map { it.substringBeforeLast('.', it).substringAfterLast('/') }
    if (stems.isEmpty()) return "Local Manga"
    if (stems.size == 1) return stems.single().trim().ifBlank { "Local Manga" }.take(MAX_TITLE_LENGTH)
    var prefix = stems.first()
    stems.drop(1).forEach { value ->
        val limit = minOf(prefix.length, value.length)
        var matching = 0
        while (matching < limit && prefix[matching].lowercaseChar() == value[matching].lowercaseChar()) matching++
        prefix = prefix.take(matching)
    }
    val cleaned = prefix.trim().trimEnd { it.isDigit() || it == '-' || it == '_' || it == '.' || it.isWhitespace() }
    return cleaned.takeUnless { it.isBlank() || it.equals("page", true) || it.equals("image", true) }
        ?.take(MAX_TITLE_LENGTH)
        ?: "Local Manga"
}

private fun compareNaturalPaths(left: String, right: String): Int {
    var leftIndex = 0
    var rightIndex = 0
    while (leftIndex < left.length && rightIndex < right.length) {
        val leftChar = left[leftIndex]
        val rightChar = right[rightIndex]
        if (leftChar.isDigit() && rightChar.isDigit()) {
            val leftStart = leftIndex
            val rightStart = rightIndex
            while (leftIndex < left.length && left[leftIndex].isDigit()) leftIndex++
            while (rightIndex < right.length && right[rightIndex].isDigit()) rightIndex++
            val leftDigits = left.substring(leftStart, leftIndex)
            val rightDigits = right.substring(rightStart, rightIndex)
            val leftNumber = leftDigits.trimStart('0').ifEmpty { "0" }
            val rightNumber = rightDigits.trimStart('0').ifEmpty { "0" }
            if (leftNumber.length != rightNumber.length) return leftNumber.length.compareTo(rightNumber.length)
            val numberComparison = leftNumber.compareTo(rightNumber)
            if (numberComparison != 0) return numberComparison
            if (leftDigits.length != rightDigits.length) return leftDigits.length.compareTo(rightDigits.length)
        } else {
            val characterComparison = leftChar.lowercaseChar().compareTo(rightChar.lowercaseChar())
            if (characterComparison != 0) return characterComparison
            leftIndex++
            rightIndex++
        }
    }
    if (leftIndex != left.length || rightIndex != right.length) {
        return (left.length - leftIndex).compareTo(right.length - rightIndex)
    }
    return left.compareTo(right)
}

private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
    if (bytes.size < END_OF_CENTRAL_DIRECTORY_SIZE) return -1
    val lowerBound = (bytes.size - END_OF_CENTRAL_DIRECTORY_SIZE - MAX_ZIP_COMMENT_LENGTH).coerceAtLeast(0)
    for (index in bytes.size - END_OF_CENTRAL_DIRECTORY_SIZE downTo lowerBound) {
        if (bytes.hasSignature(index, END_OF_CENTRAL_DIRECTORY_SIGNATURE)) return index
    }
    return -1
}

private fun ByteArray.hasSignature(offset: Int, signature: Long): Boolean =
    offset >= 0 && offset + 4 <= size && readUInt32(offset) == signature

private fun ByteArray.readUInt16(offset: Int): Int {
    require(offset >= 0 && offset + 2 <= size) { "Archive is truncated." }
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.readUInt32(offset: Int): Long {
    require(offset >= 0 && offset + 4 <= size) { "Archive is truncated." }
    return (this[offset].toLong() and 0xffL) or
        ((this[offset + 1].toLong() and 0xffL) shl 8) or
        ((this[offset + 2].toLong() and 0xffL) shl 16) or
        ((this[offset + 3].toLong() and 0xffL) shl 24)
}

private fun Int.checkedAdd(value: Int, size: Int): Int {
    require(value >= 0 && this <= size - value) { "Archive central directory is truncated." }
    return this + value
}

private val WINDOWS_DRIVE_PATH = Regex("^[A-Za-z]:/")
private const val ENCRYPTED_FLAG = 0x1
private const val ZIP64_UINT16 = 0xffff
private const val ZIP64_UINT32 = 0xffffffffL
private const val CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50L
private const val END_OF_CENTRAL_DIRECTORY_SIGNATURE = 0x06054b50L
private const val CENTRAL_DIRECTORY_HEADER_SIZE = 46
private const val END_OF_CENTRAL_DIRECTORY_SIZE = 22
private const val MAX_ZIP_COMMENT_LENGTH = 65_535
internal const val MAX_ARCHIVE_ENTRIES = 10_000
private const val MAX_TITLE_LENGTH = 200
