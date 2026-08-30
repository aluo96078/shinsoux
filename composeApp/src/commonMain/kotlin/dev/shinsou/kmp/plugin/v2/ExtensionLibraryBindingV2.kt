package dev.shinsou.kmp.plugin.v2

import dev.shinsou.kmp.domain.model.PublicationKey
import dev.shinsou.kmp.domain.model.SourceKey

/**
 * Reversible authority stored by an app-local extension library row.
 *
 * The portable publication UUID remains the stable typed-content identity, while the exact source
 * and remote id let a fresh process reopen the source detail screen before any chapter body has
 * been materialized. This is local library metadata only; it never implies a source-side favorite.
 */
public data class ExtensionLibraryBindingV2(
    val publicationKey: PublicationKey,
    val sourceKey: SourceKey,
    val remotePublicationId: String,
) {
    init {
        sourceKey.validate()
        require(remotePublicationId.isNotBlank()) { "Remote publication id cannot be blank" }
        require(publicationKey == extensionPublicationKey(sourceKey, remotePublicationId)) {
            "Extension library binding does not match its publication identity"
        }
    }
}

/** Encodes one source-backed title without granting or invoking the source favorite capability. */
internal fun encodeExtensionLibraryPublicationUrl(
    sourceKey: SourceKey,
    remotePublicationId: String,
): String {
    val binding = ExtensionLibraryBindingV2(
        publicationKey = extensionPublicationKey(sourceKey, remotePublicationId),
        sourceKey = sourceKey,
        remotePublicationId = remotePublicationId,
    )
    return buildString {
        append(EXTENSION_LIBRARY_URL_PREFIX)
        append(binding.publicationKey.value)
        append('/')
        append(binding.sourceKey.contractVersion)
        append('/')
        append(binding.sourceKey.packageId.encodeLibraryField())
        append('/')
        append(binding.sourceKey.sourceId.encodeLibraryField())
        append('/')
        append(binding.remotePublicationId.encodeLibraryField())
    }.also { encoded ->
        require(encoded.length <= MAX_EXTENSION_LIBRARY_URL_CHARS) {
            "Extension library binding is too large"
        }
    }
}

/** Strictly decodes only the reversible extension-library URL format. */
internal fun decodeExtensionLibraryPublicationUrl(value: String): ExtensionLibraryBindingV2? {
    if (!value.startsWith(EXTENSION_LIBRARY_URL_PREFIX) || value.length > MAX_EXTENSION_LIBRARY_URL_CHARS) {
        return null
    }
    val fields = value.removePrefix(EXTENSION_LIBRARY_URL_PREFIX).split('/')
    if (fields.size != 5) return null
    return runCatching {
        val publicationKey = PublicationKey(fields[0])
        val contractVersion = fields[1].toInt().also { require(it > 0) }
        val packageId = fields[2].decodeLibraryField()
        val sourceId = fields[3].decodeLibraryField()
        val remotePublicationId = fields[4].decodeLibraryField()
        ExtensionLibraryBindingV2(
            publicationKey = publicationKey,
            sourceKey = SourceKey(
                contractVersion = contractVersion,
                packageId = packageId,
                sourceId = sourceId,
            ),
            remotePublicationId = remotePublicationId,
        )
    }.getOrNull()
}

private fun String.encodeLibraryField(): String {
    val bytes = encodeToByteArray()
    require(bytes.size <= MAX_EXTENSION_LIBRARY_FIELD_BYTES) {
        "Extension library identity field is too large"
    }
    return buildString(bytes.size * 2) {
        bytes.forEach { byte ->
            val unsigned = byte.toInt() and 0xff
            append(LOWER_HEX[unsigned ushr 4])
            append(LOWER_HEX[unsigned and 0x0f])
        }
    }
}

private fun String.decodeLibraryField(): String {
    require(length % 2 == 0 && length <= MAX_EXTENSION_LIBRARY_FIELD_BYTES * 2) {
        "Extension library identity field is malformed"
    }
    val bytes = ByteArray(length / 2)
    var index = 0
    while (index < length) {
        val high = this[index].lowerHexValue()
        val low = this[index + 1].lowerHexValue()
        bytes[index / 2] = ((high shl 4) or low).toByte()
        index += 2
    }
    return bytes.decodeToString(throwOnInvalidSequence = true)
}

private fun Char.lowerHexValue(): Int = when (this) {
    in '0'..'9' -> code - '0'.code
    in 'a'..'f' -> code - 'a'.code + 10
    else -> throw IllegalArgumentException("Extension library identity is not canonical lowercase hex")
}

private const val EXTENSION_LIBRARY_URL_PREFIX: String = "local://extension/v1/"
private const val MAX_EXTENSION_LIBRARY_URL_CHARS: Int = 32 * 1024
private const val MAX_EXTENSION_LIBRARY_FIELD_BYTES: Int = 8 * 1024
private const val LOWER_HEX: String = "0123456789abcdef"
