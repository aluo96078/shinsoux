package dev.shinsou.kmp.plugin

import io.ktor.http.Url

/** Small common HTML parser for viewer pages; it intentionally does not depend on JVM-only Jsoup. */
internal object ViewerImageParser {
    fun extractImageSource(html: String): String? {
        var cursor = 0
        while (cursor < html.length) {
            val start = html.indexOf("<img", startIndex = cursor, ignoreCase = true)
            if (start < 0) return null
            val boundary = html.getOrNull(start + 4)
            if (boundary != null && !boundary.isWhitespace() && boundary != '/' && boundary != '>') {
                cursor = start + 4
                continue
            }
            val end = findTagEnd(html, start + 4)
            if (end < 0) return null
            val attributes = parseAttributes(html.substring(start + 4, end))
            if (attributes["id"]?.equals("img", ignoreCase = true) == true) {
                return attributes["src"]
                    ?.takeIf(String::isNotBlank)
                    ?.let(::decodeHtmlEntities)
            }
            cursor = end + 1
        }
        return null
    }

    fun resolveUrl(viewerUrl: String, imageSource: String): String {
        val reference = decodeHtmlEntities(imageSource).trim()
        require(reference.isNotEmpty()) { "Viewer image source is empty" }
        if (SCHEME.matchesAt(reference, 0)) return reference

        val base = Url(viewerUrl)
        if (reference.startsWith("//")) return "${base.protocol.name}:$reference"
        if (reference.startsWith('#')) return viewerUrl.substringBefore('#') + reference
        if (reference.startsWith('?')) {
            return viewerUrl.substringBefore('#').substringBefore('?') + reference
        }

        val origin = viewerUrl.substringBefore("://") + "://" +
            viewerUrl.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        val suffixStart = reference.indexOfFirst { it == '?' || it == '#' }
        val referencePath = if (suffixStart < 0) reference else reference.substring(0, suffixStart)
        val suffix = if (suffixStart < 0) "" else reference.substring(suffixStart)
        val rawPath = if (referencePath.startsWith('/')) {
            referencePath
        } else {
            base.encodedPath.substringBeforeLast('/', "") + "/" + referencePath
        }
        return origin + normalizePath(rawPath) + suffix
    }

    private fun findTagEnd(html: String, from: Int): Int {
        var quote: Char? = null
        for (index in from until html.length) {
            val character = html[index]
            if (quote == null) {
                when (character) {
                    '\'', '"' -> quote = character
                    '>' -> return index
                }
            } else if (character == quote) {
                quote = null
            }
        }
        return -1
    }

    private fun parseAttributes(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < value.length) {
            while (index < value.length && (value[index].isWhitespace() || value[index] == '/')) index++
            val nameStart = index
            while (index < value.length &&
                !value[index].isWhitespace() && value[index] != '=' && value[index] != '/' && value[index] != '>'
            ) {
                index++
            }
            if (index == nameStart) {
                index++
                continue
            }
            val name = value.substring(nameStart, index).lowercase()
            while (index < value.length && value[index].isWhitespace()) index++
            if (index >= value.length || value[index] != '=') {
                if (name !in result) result[name] = ""
                continue
            }
            index++
            while (index < value.length && value[index].isWhitespace()) index++
            if (index >= value.length) {
                result[name] = ""
                break
            }
            val quote = value[index].takeIf { it == '\'' || it == '"' }
            if (quote != null) index++
            val valueStart = index
            if (quote != null) {
                while (index < value.length && value[index] != quote) index++
            } else {
                while (index < value.length && !value[index].isWhitespace() && value[index] != '/') index++
            }
            result[name] = value.substring(valueStart, index)
            if (quote != null && index < value.length) index++
        }
        return result
    }

    private fun normalizePath(value: String): String {
        val segments = mutableListOf<String>()
        value.split('/').forEach { segment ->
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments += segment
            }
        }
        val trailingSlash = value.endsWith('/') && segments.isNotEmpty()
        return "/" + segments.joinToString("/") + if (trailingSlash) "/" else ""
    }

    private fun decodeHtmlEntities(value: String): String {
        if ('&' !in value) return value
        val result = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            if (value[index] != '&') {
                result.append(value[index++])
                continue
            }
            val end = value.indexOf(';', startIndex = index + 1)
            if (end < 0 || end - index > 12) {
                result.append(value[index++])
                continue
            }
            val entity = value.substring(index + 1, end)
            val codePoint = when {
                entity.startsWith("#x", ignoreCase = true) -> entity.substring(2).toIntOrNull(16)
                entity.startsWith('#') -> entity.substring(1).toIntOrNull()
                else -> NAMED_ENTITIES[entity.lowercase()]
            }
            if (codePoint == null || codePoint !in 0..0x10ffff) {
                result.append(value, index, end + 1)
            } else {
                appendCodePoint(result, codePoint)
            }
            index = end + 1
        }
        return result.toString()
    }

    private fun appendCodePoint(target: StringBuilder, codePoint: Int) {
        if (codePoint <= 0xffff) {
            target.append(codePoint.toChar())
        } else {
            val normalized = codePoint - 0x10000
            target.append(((normalized ushr 10) + 0xd800).toChar())
            target.append(((normalized and 0x3ff) + 0xdc00).toChar())
        }
    }

    private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.-]*:")
    private val NAMED_ENTITIES = mapOf(
        "amp" to '&'.code,
        "quot" to '"'.code,
        "apos" to '\''.code,
        "lt" to '<'.code,
        "gt" to '>'.code,
        "nbsp" to 0x00a0,
        "colon" to ':'.code,
        "sol" to '/'.code,
    )
}
