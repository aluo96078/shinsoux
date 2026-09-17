package dev.shinsou.kmp.plugin

/** Strict HTTP/1.1 CONNECT admission for the ephemeral reviewed-image loopback proxy. */
internal fun isValidReviewedImageConnectHeader(
    header: String,
    expectedAuthorization: String,
): Boolean {
    if (header.encodeToByteArray().size > REVIEWED_IMAGE_CONNECT_MAX_HEADER_BYTES) return false
    // Validate raw input before splitting or trimming so whitespace normalization cannot hide
    // controls, obs-fold, or DEL. CR and LF are admitted only as exact CRLF pairs below.
    if (header.isEmpty() || header.any {
            (it.code < 0x20 && it != '\r' && it != '\n') || it.code > 0x7e
        }
    ) return false
    if ('\n' in header.replace("\r\n", "") || '\r' in header.replace("\r\n", "")) return false
    val lines = header.split("\r\n")
    if (lines.firstOrNull() != "CONNECT i.motiezw.com:443 HTTP/1.1") return false
    if (lines.drop(1).any { it.isEmpty() || it.first().isWhitespace() }) return false
    val fields = lines.drop(1).map { line ->
        val separator = line.indexOf(':')
        if (separator <= 0) return false
        val name = line.substring(0, separator)
        if (!REVIEWED_CONNECT_HEADER_NAME.matches(name)) return false
        val value = line.substring(separator + 1).trim()
        if (value.any { it.code !in 0x20..0x7e }) return false
        name.lowercase() to value
    }
    if (fields.any { it.first == "content-length" || it.first == "transfer-encoding" }) return false
    val hosts = fields.filter { it.first == "host" }
    val authorizations = fields.filter { it.first == "proxy-authorization" }
    return hosts.size == 1 && hosts.single().second in setOf("i.motiezw.com", "i.motiezw.com:443") &&
        authorizations.size == 1 && authorizations.single().second == expectedAuthorization
}

internal const val REVIEWED_IMAGE_CONNECT_MAX_HEADER_BYTES: Int = 8 * 1_024
private val REVIEWED_CONNECT_HEADER_NAME = Regex("^[A-Za-z0-9!#$%&'*+.^_`|~-]+$")
