package dev.shinsou.kmp.plugin

import dev.shinsou.kmp.domain.model.SourceKey

/**
 * Exact host-issued authority retained only while rendering remote extension content.
 *
 * The constructor is host-internal and this is deliberately not a data class: UI and extension
 * code may consume a scope, but cannot manufacture one through a generated `copy` function.
 */
public class TypedReaderRemoteAssetScope internal constructor(
    public val sourceKey: SourceKey,
    public val sourceId: Long,
    public val network: PluginContentNetworkClient,
) {
    init {
        sourceKey.validate()
    }
}

/**
 * Returns a response body only when it is safe to hand to a raster image decoder.
 *
 * Missing media types and generic binary responses remain compatible with older image hosts,
 * where the decoder identifies the format from the bytes. Explicit document types, SVG, malformed
 * values, and ambiguous duplicate Content-Type headers fail closed before reaching Coil.
 */
internal fun PluginHttpResponse.imageBodyForDecoderOrNull(): ByteArray? {
    if (status !in 200..299 || body.isEmpty()) return null
    val declaredTypes = contentTypeHeaderValues()
    if (declaredTypes.isEmpty()) return body
    if (declaredTypes.size != 1) return null
    val mediaType = declaredTypes.single().normalizedPluginMediaTypeOrNull() ?: return null
    return body.takeIf {
        mediaType == "application/octet-stream" || mediaType == "binary/octet-stream" ||
            mediaType.isDecoderCompatibleImageMediaType() ||
            // Some object-storage CDNs (e.g. img.czbooks.net) label real JPEGs as text/plain.
            // Accept that mislabel only when the bytes carry a raster image signature, so an
            // actual text, HTML or SVG document is still rejected before decoding.
            (mediaType == "text/plain" && body.hasRasterImageSignature())
    }
}

/** True when [this] starts with a JPEG, PNG, GIF, WebP or AVIF/HEIF file signature. */
internal fun ByteArray.hasRasterImageSignature(): Boolean {
    fun at(offset: Int, ascii: String): Boolean =
        size >= offset + ascii.length && ascii.indices.all { this[offset + it] == ascii[it].code.toByte() }
    fun bytes(vararg values: Int): Boolean =
        size >= values.size && values.indices.all { this[it] == values[it].toByte() }
    return bytes(0xFF, 0xD8, 0xFF) ||
        bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) ||
        at(0, "GIF87a") || at(0, "GIF89a") ||
        (at(0, "RIFF") && at(8, "WEBP")) ||
        (at(4, "ftyp") && listOf("avif", "avis", "heic", "heix", "mif1").any { at(8, it) })
}

/** Normalized single response media type, or null when it is absent or ambiguous. */
internal fun PluginHttpResponse.normalizedPluginMediaType(): String? =
    contentTypeHeaderValues()
        .singleOrNull()
        ?.normalizedPluginMediaTypeOrNull()

private fun PluginHttpResponse.contentTypeHeaderValues(): List<String> =
    headers.entries
        .filter { (name, _) -> name.equals("Content-Type", ignoreCase = true) }
        .flatMap(Map.Entry<String, List<String>>::value)

private fun String.normalizedPluginMediaTypeOrNull(): String? {
    if (isBlank() || ',' in this) return null
    val mediaType = substringBefore(';').trim().lowercase()
    val slash = mediaType.indexOf('/')
    if (slash <= 0 || slash == mediaType.lastIndex || slash != mediaType.lastIndexOf('/')) return null
    return mediaType.takeIf { value -> value.all(::isPluginMediaTypeCharacter) }
}

private fun isPluginMediaTypeCharacter(character: Char): Boolean =
    character in 'a'..'z' || character in '0'..'9' || character == '/' ||
        character in "!#$%&'*+-.^_`|~"

private fun String.isDecoderCompatibleImageMediaType(): Boolean =
    startsWith("image/") &&
        length > "image/".length &&
        substringAfter('/').let { subtype -> subtype != "svg" && !subtype.endsWith("+xml") }
