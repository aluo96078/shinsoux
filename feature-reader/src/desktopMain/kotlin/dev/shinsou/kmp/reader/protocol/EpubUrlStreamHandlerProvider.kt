package dev.shinsou.kmp.reader.protocol

import dev.shinsou.kmp.reader.EpubBrowserUrlPolicy
import dev.shinsou.kmp.reader.EpubBrowserResolverSlot
import dev.shinsou.kmp.reader.EpubPublicationResourceResolver
import dev.shinsou.kmp.reader.EpubRenderResponse
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.net.spi.URLStreamHandlerProvider
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/** Service-loaded handler used by JavaFX WebKit for the exact in-memory publication scheme. */
public class EpubUrlStreamHandlerProvider : URLStreamHandlerProvider() {
    override fun createURLStreamHandler(protocol: String): URLStreamHandler? =
        if (protocol == EPUB_SCHEME) EpubUrlStreamHandler else null
}

internal object EpubProtocolRegistry {
    private data class Entry(
        val owner: Any,
        val session: EpubProtocolSession,
    )

    private val sessions = ConcurrentHashMap<String, Entry>()
    private val random = SecureRandom()

    /**
     * A random origin is a capability for exactly one WebView surface. Two windows displaying the
     * same publication never share a resolver registration or a browser-visible origin.
     */
    fun register(owner: Any, resolver: EpubPublicationResourceResolver): EpubProtocolSession {
        while (true) {
            val session = EpubProtocolSession(
                canonicalPublicationRootUrl = resolver.request.publicationRootUrl,
                browserPublicationRootUrl = randomPublicationRoot(resolver.request.publicationRootUrl),
                resolverSlot = EpubBrowserResolverSlot(resolver),
            )
            val entry = Entry(owner, session)
            if (sessions.putIfAbsent(session.browserPublicationRootUrl, entry) == null) return session
        }
    }

    fun update(owner: Any, session: EpubProtocolSession, resolver: EpubPublicationResourceResolver) {
        require(resolver.request.publicationRootUrl == session.canonicalPublicationRootUrl) {
            "Desktop EPUB session cannot cross publication roots"
        }
        sessions.compute(session.browserPublicationRootUrl) { _, current ->
            require(current != null && current.owner === owner && current.session === session) {
                "Desktop EPUB session is not registered by this owner"
            }
            current.session.resolverSlot.stage(resolver)
            current
        }
    }

    fun commit(owner: Any, session: EpubProtocolSession) {
        registeredEntry(owner, session).session.resolverSlot.commit()
    }

    fun rollback(owner: Any, session: EpubProtocolSession) {
        registeredEntry(owner, session).session.resolverSlot.rollback()
    }

    fun unregister(owner: Any, session: EpubProtocolSession) {
        val current = sessions[session.browserPublicationRootUrl] ?: return
        if (current.owner === owner && current.session === session &&
            sessions.remove(session.browserPublicationRootUrl, current)
        ) {
            // Removal wins before close, so a new request cannot enter this resolver slot.
            session.resolverSlot.close()
        }
    }

    /** Native URLConnection request gate. Unknown origins and cross-publication paths fail closed. */
    fun resolve(url: String): EpubRenderResponse? = sessions.entries
        .asSequence()
        .filter { (root, _) -> url.startsWith(root) }
        .maxByOrNull { (root, _) -> root.length }
        ?.value
        ?.let { entry ->
            val canonical = entry.session.canonicalUrl(url) ?: return@let null
            entry.session.resolverSlot.read { resolver ->
                resolver.resolve(canonical)?.let { response ->
                    entry.session.scopeResponse(response, resolver)
                }
            }
        }

    private fun registeredEntry(owner: Any, session: EpubProtocolSession): Entry {
        val current = sessions[session.browserPublicationRootUrl]
        require(current != null && current.owner === owner && current.session === session) {
            "Desktop EPUB session is not registered by this owner"
        }
        return current
    }

    private fun randomPublicationRoot(canonicalRoot: String): String {
        require(canonicalRoot.startsWith("$EPUB_SCHEME://")) {
            "Desktop EPUB protocol only accepts the private publication scheme"
        }
        val tokenBytes = ByteArray(SESSION_TOKEN_BYTES).also(random::nextBytes)
        val token = buildString(tokenBytes.size * 2) {
            tokenBytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
        val canonicalRoute = canonicalRoot.removePrefix("$EPUB_SCHEME://")
        return "$EPUB_SCHEME://s-$token.invalid/$canonicalRoute"
    }
}

internal class EpubProtocolSession internal constructor(
    val canonicalPublicationRootUrl: String,
    val browserPublicationRootUrl: String,
    internal val resolverSlot: EpubBrowserResolverSlot,
) {
    private val policy = EpubBrowserUrlPolicy(
        canonicalPublicationRootUrl = canonicalPublicationRootUrl,
        browserPublicationRootUrl = browserPublicationRootUrl,
    )

    fun browserUrl(canonicalUrl: String): String? = policy.browserUrl(canonicalUrl)

    fun canonicalUrl(browserUrl: String): String? = policy.canonicalUrl(browserUrl)

    /** Re-scope only host-generated absolute publication URLs in the transient HTML response. */
    fun scopeResponse(
        response: EpubRenderResponse,
        resolver: EpubPublicationResourceResolver,
    ): EpubRenderResponse {
        val html = response.mediaType.equals("application/xhtml+xml", ignoreCase = true) ||
            response.mediaType.equals("text/html", ignoreCase = true)
        if (!html || !response.textEncoding.equals("UTF-8", ignoreCase = true)) return response
        val styleMappings = resolver.userStyleUrls.associateWith { canonicalStyleUrl ->
            requireNotNull(browserUrl(canonicalStyleUrl))
        }
        val scoped = scopeDesktopEpubHtml(response.bytes.decodeToString(), styleMappings).encodeToByteArray()
        return EpubRenderResponse(
            mediaType = response.mediaType,
            textEncoding = response.textEncoding,
            bytes = scoped,
            headers = response.headers,
        )
    }
}

/**
 * Applies only Desktop-host security transformations to an already validated transient document.
 * Publisher text and arbitrary canonical-root strings remain byte-for-byte unchanged.
 */
internal fun scopeDesktopEpubHtml(
    html: String,
    hostUserStyleUrls: Map<String, String>,
): String {
    if ('<' !in html) return html
    val output = StringBuilder(html.length)
    var cursor = 0
    while (cursor < html.length) {
        val tagStart = html.indexOf('<', cursor)
        if (tagStart < 0) {
            output.append(html, cursor, html.length)
            break
        }
        output.append(html, cursor, tagStart)
        val protectedEnd = when {
            html.regionMatches(tagStart, "<!--", 0, 4) -> html.indexOf("-->", tagStart + 4)
                .takeIf { it >= 0 }
                ?.plus(3)
            html.regionMatches(tagStart, "<![CDATA[", 0, 9) -> html.indexOf("]]>", tagStart + 9)
                .takeIf { it >= 0 }
                ?.plus(3)
            else -> null
        }
        if (protectedEnd != null) {
            output.append(html, tagStart, protectedEnd)
            cursor = protectedEnd
            continue
        }
        val tagEnd = findTagEnd(html, tagStart)
        require(tagEnd >= 0) { "Validated EPUB HTML contains an unterminated tag" }
        val tag = html.substring(tagStart, tagEnd + 1)
        val parsed = parseHtmlStartTag(tag)
        when {
            parsed == null -> output.append(tag)
            parsed.name == "meta" && parsed.attributes.any { attribute ->
                attribute.name == "http-equiv" &&
                    attribute.value.decodeAsciiCharacterReferences().trim().equals("refresh", ignoreCase = true)
            } -> Unit
            parsed.name == "link" -> {
                val rel = parsed.attributes.firstOrNull { it.name == "rel" }?.value.orEmpty()
                val href = parsed.attributes.firstOrNull { it.name == "href" }
                val scopedHref = href?.value?.let(hostUserStyleUrls::get)
                val isStyleSheet = rel.split(ASCII_WHITESPACE)
                    .any { it.equals("stylesheet", ignoreCase = true) }
                if (scopedHref != null && isStyleSheet) {
                    output.append(tag.replaceRange(href.valueStart, href.valueEnd, scopedHref))
                } else {
                    output.append(tag)
                }
            }
            else -> output.append(tag)
        }
        if (parsed != null && parsed.name in RAW_TEXT_ELEMENTS && !parsed.selfClosing) {
            val closingTagStart = findRawTextClosingTag(html, tagEnd + 1, parsed.name)
            if (closingTagStart < 0) {
                output.append(html, tagEnd + 1, html.length)
                return output.toString()
            }
            output.append(html, tagEnd + 1, closingTagStart)
            val closingTagEnd = findTagEnd(html, closingTagStart)
            require(closingTagEnd >= 0) { "Validated EPUB HTML contains an unterminated raw-text close tag" }
            output.append(html, closingTagStart, closingTagEnd + 1)
            cursor = closingTagEnd + 1
            continue
        }
        cursor = tagEnd + 1
    }
    return output.toString()
}

private data class ParsedHtmlStartTag(
    val name: String,
    val attributes: List<ParsedHtmlAttribute>,
    val selfClosing: Boolean,
)

private data class ParsedHtmlAttribute(
    val name: String,
    val value: String,
    val valueStart: Int,
    val valueEnd: Int,
)

private fun findTagEnd(html: String, tagStart: Int): Int {
    var quote: Char? = null
    var index = tagStart + 1
    while (index < html.length) {
        val character = html[index]
        when {
            quote != null && character == quote -> quote = null
            quote == null && (character == '\'' || character == '"') -> quote = character
            quote == null && character == '>' -> return index
        }
        index++
    }
    return -1
}

private fun parseHtmlStartTag(tag: String): ParsedHtmlStartTag? {
    var index = 1
    while (index < tag.length && tag[index].isWhitespace()) index++
    if (index >= tag.length || tag[index] == '/' || tag[index] == '!' || tag[index] == '?') return null
    val nameStart = index
    while (index < tag.length && tag[index].isHtmlNameCharacter()) index++
    if (index == nameStart) return null
    val name = tag.substring(nameStart, index).lowercase()
    val attributes = ArrayList<ParsedHtmlAttribute>()
    while (index < tag.length) {
        while (index < tag.length && (tag[index].isWhitespace() || tag[index] == '/')) index++
        if (index >= tag.length || tag[index] == '>') break
        val attributeStart = index
        while (index < tag.length && tag[index].isHtmlNameCharacter()) index++
        if (index == attributeStart) return null
        val attributeName = tag.substring(attributeStart, index).lowercase()
        while (index < tag.length && tag[index].isWhitespace()) index++
        if (index >= tag.length || tag[index] != '=') {
            attributes += ParsedHtmlAttribute(attributeName, "", index, index)
            continue
        }
        index++
        while (index < tag.length && tag[index].isWhitespace()) index++
        if (index >= tag.length) return null
        val quote = tag[index].takeIf { it == '\'' || it == '"' }
        if (quote != null) index++
        val valueStart = index
        if (quote != null) {
            while (index < tag.length && tag[index] != quote) index++
            if (index >= tag.length) return null
        } else {
            while (index < tag.length && !tag[index].isWhitespace() && tag[index] != '>' &&
                !(tag[index] == '/' && index + 1 < tag.length && tag[index + 1] == '>')
            ) {
                index++
            }
        }
        val valueEnd = index
        attributes += ParsedHtmlAttribute(
            name = attributeName,
            value = tag.substring(valueStart, valueEnd),
            valueStart = valueStart,
            valueEnd = valueEnd,
        )
        if (quote != null) index++
    }
    var closingMarker = tag.length - 2
    while (closingMarker > 0 && tag[closingMarker].isWhitespace()) closingMarker--
    return ParsedHtmlStartTag(
        name = name,
        attributes = attributes,
        selfClosing = closingMarker > 0 && tag[closingMarker] == '/',
    )
}

private fun findRawTextClosingTag(html: String, startIndex: Int, elementName: String): Int {
    var cursor = startIndex
    while (cursor < html.length) {
        val candidate = html.indexOf("</", cursor)
        if (candidate < 0) return -1
        val nameStart = candidate + 2
        val nameEnd = nameStart + elementName.length
        if (nameEnd < html.length &&
            html.regionMatches(nameStart, elementName, 0, elementName.length, ignoreCase = true) &&
            (html[nameEnd].isWhitespace() || html[nameEnd] == '>')
        ) {
            return candidate
        }
        cursor = nameStart
    }
    return -1
}

private fun Char.isHtmlNameCharacter(): Boolean =
    isLetterOrDigit() || this == '-' || this == '_' || this == ':'

private fun String.decodeAsciiCharacterReferences(): String {
    if ("&#" !in this) return this
    val output = StringBuilder(length)
    var cursor = 0
    while (cursor < length) {
        if (this[cursor] != '&' || cursor + 2 >= length || this[cursor + 1] != '#') {
            output.append(this[cursor++])
            continue
        }
        val semicolon = indexOf(';', cursor + 2)
        if (semicolon < 0 || semicolon - cursor > MAX_NUMERIC_CHARACTER_REFERENCE_LENGTH) {
            output.append(this[cursor++])
            continue
        }
        val encoded = substring(cursor + 2, semicolon)
        val value = if (encoded.startsWith('x', ignoreCase = true)) {
            encoded.drop(1).toIntOrNull(16)
        } else {
            encoded.toIntOrNull()
        }
        if (value == null || value !in 0..0x7f) {
            output.append(this, cursor, semicolon + 1)
        } else {
            output.append(value.toChar())
        }
        cursor = semicolon + 1
    }
    return output.toString()
}

private object EpubUrlStreamHandler : URLStreamHandler() {
    override fun openConnection(url: URL): URLConnection = EpubUrlConnection(url)
}

private class EpubUrlConnection(url: URL) : URLConnection(url) {
    private var response: EpubRenderResponse? = null

    override fun connect() {
        if (connected) return
        response = EpubProtocolRegistry.resolve(url.toExternalForm())
            ?: throw FileNotFoundException("Unknown EPUB publication resource")
        connected = true
    }

    override fun getInputStream(): InputStream {
        connect()
        return ByteArrayInputStream(requireNotNull(response).bytes)
    }

    override fun getContentType(): String {
        connect()
        val value = requireNotNull(response)
        return if (value.textEncoding == null) value.mediaType else {
            "${value.mediaType}; charset=${value.textEncoding}"
        }
    }

    override fun getContentLengthLong(): Long {
        connect()
        return requireNotNull(response).byteSize.toLong()
    }

    override fun getContentLength(): Int {
        connect()
        return requireNotNull(response).byteSize
    }

    override fun getHeaderField(name: String?): String? {
        connect()
        if (name == null) return null
        if (name.equals("Content-Type", ignoreCase = true)) return contentType
        if (name.equals("Content-Length", ignoreCase = true)) return contentLengthLong.toString()
        return requireNotNull(response).headers.entries.firstOrNull { (key, _) ->
            key.equals(name, ignoreCase = true)
        }?.value
    }

    override fun getHeaderFields(): Map<String, List<String>> {
        connect()
        val fields = LinkedHashMap<String, List<String>>()
        requireNotNull(response).headers.forEach { (name, value) -> fields[name] = listOf(value) }
        fields["Content-Type"] = listOf(contentType)
        fields["Content-Length"] = listOf(contentLengthLong.toString())
        return fields
    }

    override fun getUseCaches(): Boolean = false

    private companion object {
        const val EPUB_SCHEME: String = "shinsou-epub"
    }
}

private const val EPUB_SCHEME: String = "shinsou-epub"
private const val SESSION_TOKEN_BYTES: Int = 24
private const val HEX: String = "0123456789abcdef"
private const val MAX_NUMERIC_CHARACTER_REFERENCE_LENGTH: Int = 12
private val ASCII_WHITESPACE: Regex = Regex("[\\t\\n\\u000c\\r ]+")
private val RAW_TEXT_ELEMENTS: Set<String> = setOf("script", "style")
