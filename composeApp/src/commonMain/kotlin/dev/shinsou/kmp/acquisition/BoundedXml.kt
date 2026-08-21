package dev.shinsou.kmp.acquisition

internal class XmlElement(
    val qualifiedName: String,
    val namespaceUri: String?,
    val attributes: Map<String, String>,
    private val attributeNamespaceUris: Map<String, String?>,
    val content: List<XmlContentNode>,
) {
    val localName: String get() = qualifiedName.substringAfter(':')
    val children: List<XmlElement> = content.mapNotNull { node ->
        (node as? XmlContentNode.Element)?.value
    }
    val ownText: String = buildString {
        content.forEach { node -> if (node is XmlContentNode.Text) append(node.value) }
    }

    /** Unqualified XML attributes never inherit the element's default namespace. */
    fun attribute(localName: String): String? {
        require(':' !in localName) { "XML unqualified attribute lookup must use a local name" }
        val candidates = attributes.keys.filter { name ->
            !name.isNamespaceDeclaration() && name.substringAfter(':') == localName
        }
        require(candidates.all { it == localName }) {
            "EPUB XML attribute $localName is shadowed by a namespaced attribute"
        }
        return attributes[localName]
    }

    /** Resolves one attribute by expanded name and rejects local-name namespace collisions. */
    fun attribute(namespaceUri: String, localName: String): String? {
        require(namespaceUri.isNotBlank() && ':' !in localName) {
            "XML namespaced attribute lookup is invalid"
        }
        val candidates = attributes.keys.filter { name ->
            !name.isNamespaceDeclaration() && name.substringAfter(':') == localName
        }
        if (candidates.isEmpty()) return null
        val matching = candidates.filter { name -> attributeNamespaceUris[name] == namespaceUri }
        require(matching.size == 1 && candidates.size == 1) {
            "EPUB XML attribute $localName has an ambiguous or incorrect namespace"
        }
        return attributes.getValue(matching.single())
    }

    fun hasExpandedName(namespaceUri: String, localName: String): Boolean {
        require(namespaceUri.isNotBlank() && XML_NCNAME.matches(localName)) {
            "XML expanded element name is invalid"
        }
        return this.namespaceUri == namespaceUri && this.localName == localName
    }

    fun children(namespaceUri: String, localName: String): List<XmlElement> =
        children.filter { child -> child.hasExpandedName(namespaceUri, localName) }

    fun child(namespaceUri: String, localName: String): XmlElement? =
        children.firstOrNull { child -> child.hasExpandedName(namespaceUri, localName) }

    fun descendants(namespaceUri: String, localName: String): List<XmlElement> = buildList {
        fun visit(element: XmlElement) {
            element.children.forEach { child ->
                if (child.hasExpandedName(namespaceUri, localName)) add(child)
                visit(child)
            }
        }
        visit(this@XmlElement)
    }

    /** Preserves mixed-content order (`text <em>child</em> tail`) for locator-safe extraction. */
    fun textContent(): String = buildString {
        content.forEach { node ->
            when (node) {
                is XmlContentNode.Text -> append(node.value)
                is XmlContentNode.Element -> append(node.value.textContent())
            }
        }
    }
}

/** Ordered XML content retained by the bounded parser; no entity or external-node capability. */
internal sealed interface XmlContentNode {
    data class Text(val value: String) : XmlContentNode
    data class Element(val value: XmlElement) : XmlContentNode
}

internal object BoundedXmlParser {
    fun parse(
        bytes: ByteArray,
        maximumBytes: Long,
        cancellationCheckpoint: () -> Unit = {},
    ): XmlElement {
        cancellationCheckpoint()
        require(bytes.size.toLong() <= maximumBytes) { "EPUB XML exceeds the configured size limit" }
        val decoded = StrictTextDecoder.decode(
            bytes,
            normalizeLineEndings = true,
            cancellationCheckpoint = cancellationCheckpoint,
        )
        validateDeclarationEncoding(decoded)
        return Parser(decoded.text, cancellationCheckpoint).parse()
    }

    private fun validateDeclarationEncoding(decoded: DecodedText) {
        val declaration = XML_DECLARATION.find(decoded.text.take(MAX_DECLARATION_SCAN)) ?: return
        val encoding = XML_ENCODING.find(declaration.value)?.groupValues?.get(2) ?: return
        val compatible = when (encoding.uppercase()) {
            "UTF-8", "UTF8" -> decoded.sourceEncoding == SourceTextEncoding.UTF_8
            "UTF-16" -> decoded.sourceEncoding == SourceTextEncoding.UTF_16_LE ||
                decoded.sourceEncoding == SourceTextEncoding.UTF_16_BE
            "UTF-16LE" -> decoded.sourceEncoding == SourceTextEncoding.UTF_16_LE
            "UTF-16BE" -> decoded.sourceEncoding == SourceTextEncoding.UTF_16_BE
            else -> false
        }
        require(compatible) { "EPUB XML declares an unsupported or conflicting encoding" }
    }

    private class Parser(
        private val source: String,
        private val cancellationCheckpoint: () -> Unit,
    ) {
        private val stack = ArrayList<MutableElement>()
        private var root: MutableElement? = null
        private var cursor = 0
        private var nodeCount = 0
        private var doctypeSeen = false
        private var doctypeRootName: String? = null

        fun parse(): XmlElement {
            while (cursor < source.length) {
                cancellationCheckpoint()
                val opening = source.indexOfCancellable('<', cursor, cancellationCheckpoint)
                if (opening < 0) {
                    appendText(source.substring(cursor))
                    cursor = source.length
                    break
                }
                appendText(source.substring(cursor, opening))
                cursor = opening
                when {
                    source.startsWith("<!--", cursor) -> skipComment()
                    source.startsWith("<![CDATA[", cursor) -> consumeCdata()
                    source.startsWith("<?", cursor) -> skipProcessingInstruction()
                    source.startsWith("</", cursor) -> consumeEndTag()
                    source.startsWith("<!DOCTYPE", cursor) -> consumeDoctype()
                    source.startsWith("<!", cursor) -> throw IllegalArgumentException(
                        "EPUB XML declarations and external entities are not allowed",
                    )
                    else -> consumeStartTag()
                }
            }
            require(stack.isEmpty()) { "EPUB XML contains an unclosed element" }
            cancellationCheckpoint()
            return requireNotNull(root) { "EPUB XML has no document element" }.freeze(cancellationCheckpoint)
        }

        private fun skipComment() {
            val end = source.indexOfCancellable("-->", cursor + 4, cancellationCheckpoint)
            require(end >= 0) { "EPUB XML contains an unterminated comment" }
            cursor = end + 3
        }

        private fun consumeCdata() {
            require(stack.isNotEmpty()) { "EPUB XML CDATA must be inside the document element" }
            val start = cursor + "<![CDATA[".length
            val end = source.indexOfCancellable("]]>", start, cancellationCheckpoint)
            require(end >= 0) { "EPUB XML contains unterminated CDATA" }
            val text = source.substring(start, end)
            requireValidXmlText(text, cancellationCheckpoint)
            stack.last().appendText(text)
            cursor = end + 3
        }

        private fun skipProcessingInstruction() {
            val end = source.indexOfCancellable("?>", cursor + 2, cancellationCheckpoint)
            require(end >= 0) { "EPUB XML contains an unterminated processing instruction" }
            cursor = end + 2
        }

        /**
         * EPUB 2 NCX documents commonly retain a public XHTML/NCX DOCTYPE and EPUB 3 navigation
         * documents often retain `<!DOCTYPE html>`. The identifiers are accepted only as bounded
         * opaque syntax: this parser has no DTD/entity resolver, rejects internal subsets, and
         * still permits only the five predefined XML entities plus numeric character references.
         */
        private fun consumeDoctype() {
            require(stack.isEmpty() && root == null) {
                "EPUB XML DOCTYPE must precede the document element"
            }
            require(!doctypeSeen) { "EPUB XML contains more than one DOCTYPE" }
            val bodyStart = cursor + XML_DOCTYPE_PREFIX.length
            require(bodyStart < source.length && source[bodyStart].isWhitespace()) {
                "EPUB XML DOCTYPE is malformed"
            }
            var quote: Char? = null
            var index = bodyStart
            var end = -1
            while (index < source.length) {
                if ((index - bodyStart) % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
                require(index - cursor <= MAX_XML_DOCTYPE_CHARS) {
                    "EPUB XML DOCTYPE is too large"
                }
                val value = source[index]
                when {
                    quote != null && value == quote -> quote = null
                    quote == null && (value == '\'' || value == '"') -> quote = value
                    quote == null && value == '>' -> {
                        end = index
                        break
                    }
                    quote == null && (value == '[' || value == '<') -> throw IllegalArgumentException(
                        "EPUB XML DOCTYPE internal subsets and declarations are not allowed",
                    )
                    value.isISOControl() && !value.isWhitespace() -> throw IllegalArgumentException(
                        "EPUB XML DOCTYPE contains an invalid character",
                    )
                }
                index++
            }
            require(end >= 0 && quote == null) { "EPUB XML contains an unterminated DOCTYPE" }
            require(end - cursor + 1 <= MAX_XML_DOCTYPE_CHARS) { "EPUB XML DOCTYPE is too large" }
            doctypeRootName = validateDoctypeBody(source.substring(bodyStart, end))
            doctypeSeen = true
            cursor = end + 1
        }

        private fun validateDoctypeBody(body: String): String {
            var index = body.skipXmlWhitespace(0, cancellationCheckpoint)
            val nameStart = index
            while (index < body.length && !body[index].isWhitespace()) index++
            val rootName = body.substring(nameStart, index)
            require(XML_NAME.matches(rootName)) { "EPUB XML DOCTYPE root name is invalid" }
            index = body.skipXmlWhitespace(index, cancellationCheckpoint)
            if (index == body.length) return rootName

            fun consumeKeyword(keyword: String) {
                require(body.regionMatches(index, keyword, 0, keyword.length)) {
                    "EPUB XML DOCTYPE external identifier is invalid"
                }
                index += keyword.length
                require(index < body.length && body[index].isWhitespace()) {
                    "EPUB XML DOCTYPE external identifier is malformed"
                }
                index = body.skipXmlWhitespace(index, cancellationCheckpoint)
            }

            fun consumeQuotedIdentifier() {
                require(index < body.length && (body[index] == '\'' || body[index] == '"')) {
                    "EPUB XML DOCTYPE external identifier must be quoted"
                }
                val quote = body[index++]
                val valueStart = index
                while (index < body.length && body[index] != quote) {
                    require(index - valueStart <= MAX_XML_DOCTYPE_IDENTIFIER_CHARS) {
                        "EPUB XML DOCTYPE external identifier is too large"
                    }
                    require(body[index] != '<' && !body[index].isISOControl()) {
                        "EPUB XML DOCTYPE external identifier is invalid"
                    }
                    index++
                }
                require(index < body.length && index > valueStart) {
                    "EPUB XML DOCTYPE external identifier is unterminated or empty"
                }
                index++
                index = body.skipXmlWhitespace(index, cancellationCheckpoint)
            }

            when {
                body.startsWith("SYSTEM", index) -> {
                    consumeKeyword("SYSTEM")
                    consumeQuotedIdentifier()
                }
                body.startsWith("PUBLIC", index) -> {
                    consumeKeyword("PUBLIC")
                    consumeQuotedIdentifier()
                    consumeQuotedIdentifier()
                }
                else -> throw IllegalArgumentException("EPUB XML DOCTYPE declaration is not allowed")
            }
            require(index == body.length) {
                "EPUB XML DOCTYPE internal subsets and trailing declarations are not allowed"
            }
            return rootName
        }

        private fun consumeEndTag() {
            val end = source.indexOfCancellable('>', cursor + 2, cancellationCheckpoint)
            require(end >= 0) { "EPUB XML contains an unterminated end tag" }
            require(end - cursor <= MAX_XML_NAME_CHARS + 4) { "EPUB XML end tag is too large" }
            val name = source.substring(cursor + 2, end).trim()
            require(XML_NAME.matches(name)) { "EPUB XML end tag name is invalid" }
            val element = stack.removeLastOrNull()
                ?: throw IllegalArgumentException("EPUB XML closes an element that was not opened")
            require(element.qualifiedName == name) { "EPUB XML element nesting is invalid" }
            cursor = end + 1
        }

        private fun consumeStartTag() {
            val end = findTagEnd(cursor + 1)
            require(end - cursor <= MAX_XML_TAG_CHARS) { "EPUB XML start tag is too large" }
            var body = source.substring(cursor + 1, end)
            val trimmedEnd = body.trimEndIndexCancellable(cancellationCheckpoint)
            val selfClosing = trimmedEnd > 0 && body[trimmedEnd - 1] == '/'
            if (selfClosing) body = body.substring(0, trimmedEnd - 1)
            val parsed = parseTagBody(body)
            nodeCount++
            require(nodeCount <= MAX_XML_NODES) { "EPUB XML contains too many elements" }
            require(stack.size < MAX_XML_DEPTH) { "EPUB XML nesting is too deep" }
            val namespaceScope = NamespaceScope.child(
                parent = stack.lastOrNull()?.namespaceScope,
                attributes = parsed.second,
            )
            val namespaceInfo = namespaceScope.resolveElement(parsed.first, parsed.second)
            val element = MutableElement(
                qualifiedName = parsed.first,
                namespaceUri = namespaceInfo.elementNamespaceUri,
                attributes = parsed.second,
                attributeNamespaceUris = namespaceInfo.attributeNamespaceUris,
                namespaceScope = namespaceScope,
            )
            if (stack.isEmpty()) {
                require(root == null) { "EPUB XML contains more than one document element" }
                require(doctypeRootName == null || doctypeRootName == parsed.first) {
                    "EPUB XML DOCTYPE root does not match the document element"
                }
                root = element
            } else {
                stack.last().children += element
                stack.last().content += MutableContentNode.Element(element)
            }
            if (!selfClosing) stack += element
            cursor = end + 1
        }

        private fun findTagEnd(start: Int): Int {
            var quote: Char? = null
            var index = start
            while (index < source.length) {
                if ((index - start) % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
                val value = source[index]
                when {
                    quote != null && value == quote -> quote = null
                    quote == null && (value == '\'' || value == '"') -> quote = value
                    quote == null && value == '>' -> return index
                }
                index++
            }
            throw IllegalArgumentException("EPUB XML contains an unterminated start tag")
        }

        private fun parseTagBody(body: String): Pair<String, Map<String, String>> {
            var index = 0
            index = body.skipXmlWhitespace(index, cancellationCheckpoint)
            val nameStart = index
            while (index < body.length && !body[index].isWhitespace()) {
                if (index % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
                index++
            }
            val name = body.substring(nameStart, index)
            require(name.length <= MAX_XML_NAME_CHARS) { "EPUB XML element name is too large" }
            require(XML_NAME.matches(name)) { "EPUB XML start tag name is invalid" }
            val attributes = LinkedHashMap<String, String>()
            while (true) {
                cancellationCheckpoint()
                index = body.skipXmlWhitespace(index, cancellationCheckpoint)
                if (index == body.length) break
                val attributeStart = index
                while (index < body.length && !body[index].isWhitespace() && body[index] != '=') {
                    if (index % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
                    index++
                }
                val attributeName = body.substring(attributeStart, index)
                require(attributeName.length <= MAX_XML_NAME_CHARS) {
                    "EPUB XML attribute name is too large"
                }
                require(XML_NAME.matches(attributeName)) { "EPUB XML attribute name is invalid" }
                index = body.skipXmlWhitespace(index, cancellationCheckpoint)
                require(index < body.length && body[index] == '=') { "EPUB XML attribute is missing '='" }
                index = body.skipXmlWhitespace(index + 1, cancellationCheckpoint)
                require(index < body.length && (body[index] == '\'' || body[index] == '"')) {
                    "EPUB XML attribute value must be quoted"
                }
                val quote = body[index++]
                val valueStart = index
                while (index < body.length && body[index] != quote) {
                    if (index % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
                    index++
                }
                require(index < body.length) { "EPUB XML attribute value is unterminated" }
                require(index - valueStart <= MAX_XML_ATTRIBUTE_VALUE_CHARS) {
                    "EPUB XML attribute value is too large"
                }
                val rawValue = body.substring(valueStart, index)
                require('<' !in rawValue) { "EPUB XML attribute contains a literal '<'" }
                val value = decodeXmlEntities(rawValue, cancellationCheckpoint)
                require(attributes.put(attributeName, value) == null) { "EPUB XML attribute is duplicated" }
                require(attributes.size <= MAX_XML_ATTRIBUTES_PER_ELEMENT) {
                    "EPUB XML element has too many attributes"
                }
                index++
            }
            return name to attributes
        }

        private fun appendText(raw: String) {
            if (raw.isEmpty()) return
            val decoded = decodeXmlEntities(raw, cancellationCheckpoint)
            if (stack.isEmpty()) {
                require(decoded.isBlank()) { "EPUB XML contains text outside its document element" }
            } else {
                stack.last().appendText(decoded)
            }
        }
    }

    private class MutableElement(
        val qualifiedName: String,
        val namespaceUri: String?,
        val attributes: Map<String, String>,
        val attributeNamespaceUris: Map<String, String?>,
        val namespaceScope: NamespaceScope,
    ) {
        val children = ArrayList<MutableElement>()
        val content = ArrayList<MutableContentNode>()

        fun appendText(value: String) {
            if (value.isEmpty()) return
            val previous = content.lastOrNull() as? MutableContentNode.Text
            if (previous == null) {
                content += MutableContentNode.Text(StringBuilder(value))
            } else {
                previous.value.append(value)
            }
        }

        fun freeze(cancellationCheckpoint: () -> Unit): XmlElement {
            cancellationCheckpoint()
            return XmlElement(
                qualifiedName = qualifiedName,
                namespaceUri = namespaceUri,
                attributes = attributes.toMap(),
                attributeNamespaceUris = attributeNamespaceUris.toMap(),
                content = content.map { node ->
                    cancellationCheckpoint()
                    when (node) {
                        is MutableContentNode.Text -> XmlContentNode.Text(node.value.toString())
                        is MutableContentNode.Element ->
                            XmlContentNode.Element(node.value.freeze(cancellationCheckpoint))
                    }
                },
            )
        }
    }

    private sealed interface MutableContentNode {
        class Text(val value: StringBuilder) : MutableContentNode
        class Element(val value: MutableElement) : MutableContentNode
    }

    private data class ResolvedNamespaceInfo(
        val elementNamespaceUri: String?,
        val attributeNamespaceUris: Map<String, String?>,
    )

    /** Persistent namespace scope: siblings share parents instead of copying every inherited map. */
    private class NamespaceScope private constructor(
        private val parent: NamespaceScope?,
        private val declarations: Map<String, String?>,
    ) {
        fun resolveElement(
            qualifiedName: String,
            attributes: Map<String, String>,
        ): ResolvedNamespaceInfo {
            val (elementPrefix, _) = splitQualifiedName(qualifiedName)
            val elementNamespace = when (elementPrefix) {
                null -> resolve("")
                "xml" -> XML_NAMESPACE_URI
                else -> requireNotNull(resolve(elementPrefix)) {
                    "EPUB XML element uses an undeclared namespace prefix"
                }
            }
            val expandedNames = HashSet<Pair<String?, String>>()
            val attributeNamespaces = LinkedHashMap<String, String?>()
            attributes.keys.filterNot(String::isNamespaceDeclaration).forEach { name ->
                val (prefix, localName) = splitQualifiedName(name)
                val namespace = when (prefix) {
                    null -> null
                    "xml" -> XML_NAMESPACE_URI
                    else -> requireNotNull(resolve(prefix)) {
                        "EPUB XML attribute uses an undeclared namespace prefix"
                    }
                }
                require(expandedNames.add(namespace to localName)) {
                    "EPUB XML contains duplicate expanded attribute names"
                }
                attributeNamespaces[name] = namespace
            }
            return ResolvedNamespaceInfo(elementNamespace, attributeNamespaces)
        }

        private fun resolve(prefix: String): String? = when {
            prefix == "xml" -> XML_NAMESPACE_URI
            prefix in declarations -> declarations[prefix]
            else -> parent?.resolve(prefix)
        }

        companion object {
            fun child(parent: NamespaceScope?, attributes: Map<String, String>): NamespaceScope {
                val declarations = LinkedHashMap<String, String?>()
                attributes.forEach { (name, uri) ->
                    val prefix = when {
                        name == "xmlns" -> ""
                        name.startsWith("xmlns:") -> name.substringAfter(':').also { declaredPrefix ->
                            require(declaredPrefix.isNotEmpty() && ':' !in declaredPrefix) {
                                "EPUB XML namespace declaration is invalid"
                            }
                        }
                        else -> return@forEach
                    }
                    require(prefix != "xmlns" && ':' !in prefix &&
                        (prefix.isEmpty() || XML_NCNAME.matches(prefix))
                    ) { "EPUB XML namespace prefix is invalid" }
                    require(prefix.isEmpty() || prefix == "xml" ||
                        !prefix.startsWith("xml", ignoreCase = true)
                    ) { "EPUB XML namespace prefix is reserved" }
                    require(uri.length <= MAX_XML_NAMESPACE_URI_CHARS && uri.none(Char::isISOControl) &&
                        (uri.isEmpty() || uri.none(Char::isWhitespace))
                    ) {
                        "EPUB XML namespace URI is invalid"
                    }
                    if (prefix == "xml") {
                        require(uri == XML_NAMESPACE_URI) { "EPUB XML xml prefix binding is invalid" }
                    } else {
                        require(uri != XML_NAMESPACE_URI && uri != XMLNS_NAMESPACE_URI) {
                            "EPUB XML namespace binding is reserved"
                        }
                    }
                    require(prefix.isEmpty() || uri.isNotEmpty()) {
                        "EPUB XML prefixed namespace cannot be undeclared"
                    }
                    declarations[prefix] = uri.takeIf(String::isNotEmpty)
                }
                return NamespaceScope(parent, declarations)
            }
        }
    }
}

private fun splitQualifiedName(name: String): Pair<String?, String> {
    val separator = name.indexOf(':')
    if (separator < 0) {
        require(XML_NCNAME.matches(name)) { "EPUB XML qualified name is invalid" }
        return null to name
    }
    require(separator > 0 && separator == name.lastIndexOf(':') && separator < name.lastIndex) {
        "EPUB XML qualified name is invalid"
    }
    val prefix = name.substring(0, separator)
    val localName = name.substring(separator + 1)
    require(XML_NCNAME.matches(prefix) && XML_NCNAME.matches(localName)) {
        "EPUB XML qualified name is invalid"
    }
    return prefix to localName
}

private fun String.isNamespaceDeclaration(): Boolean = this == "xmlns" || startsWith("xmlns:")

private fun String.skipXmlWhitespace(start: Int, cancellationCheckpoint: () -> Unit): Int {
    var index = start
    while (index < length && this[index].isWhitespace()) {
        if ((index - start) % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
        index++
    }
    return index
}

private fun String.trimEndIndexCancellable(cancellationCheckpoint: () -> Unit): Int {
    var index = length
    while (index > 0 && this[index - 1].isWhitespace()) {
        if ((length - index) % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
        index--
    }
    return index
}

private fun decodeXmlEntities(value: String, cancellationCheckpoint: () -> Unit): String {
    if ('&' !in value) {
        requireValidXmlText(value, cancellationCheckpoint)
        return value
    }
    val output = StringBuilder(value.length)
    var cursor = 0
    while (cursor < value.length) {
        cancellationCheckpoint()
        val ampersand = value.indexOfCancellable('&', cursor, cancellationCheckpoint)
        if (ampersand < 0) {
            val suffix = value.substring(cursor)
            requireValidXmlText(suffix, cancellationCheckpoint)
            output.append(suffix)
            break
        }
        val prefix = value.substring(cursor, ampersand)
        requireValidXmlText(prefix, cancellationCheckpoint)
        output.append(prefix)
        val semicolon = value.indexOfCancellable(';', ampersand + 1, cancellationCheckpoint)
        require(semicolon >= 0 && semicolon - ampersand <= MAX_ENTITY_LENGTH) {
            "EPUB XML contains an invalid entity"
        }
        val entity = value.substring(ampersand + 1, semicolon)
        val codePoint = when (entity) {
            "amp" -> '&'.code
            "lt" -> '<'.code
            "gt" -> '>'.code
            "quot" -> '"'.code
            "apos" -> '\''.code
            else -> when {
                entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
                entity.startsWith('#') -> entity.drop(1).toIntOrNull()
                else -> null
            } ?: throw IllegalArgumentException("EPUB XML uses an undeclared entity")
        }
        require(isXmlCodePoint(codePoint)) { "EPUB XML entity resolves to an invalid character" }
        output.appendCodePointCommon(codePoint)
        cursor = semicolon + 1
    }
    return output.toString()
}

private fun requireValidXmlText(value: String, cancellationCheckpoint: () -> Unit) {
    var index = 0
    while (index < value.length) {
        if (index % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
        val character = value[index]
        when {
            character == '\t' || character == '\n' || character == '\r' ||
                character.code in 0x20..0xd7ff || character.code in 0xe000..0xfffd -> index++
            character.isHighSurrogate() && index + 1 < value.length && value[index + 1].isLowSurrogate() -> {
                index += 2
            }
            else -> throw IllegalArgumentException("EPUB XML contains an invalid character")
        }
    }
}

private fun String.indexOfCancellable(
    target: Char,
    startIndex: Int,
    cancellationCheckpoint: () -> Unit,
): Int {
    var index = startIndex.coerceAtLeast(0)
    while (index < length) {
        if ((index - startIndex) % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
        if (this[index] == target) return index
        index++
    }
    cancellationCheckpoint()
    return -1
}

private fun String.indexOfCancellable(
    target: String,
    startIndex: Int,
    cancellationCheckpoint: () -> Unit,
): Int {
    require(target.isNotEmpty())
    var index = startIndex.coerceAtLeast(0)
    val last = length - target.length
    while (index <= last) {
        if ((index - startIndex) % XML_CANCELLATION_INTERVAL_CHARS == 0) cancellationCheckpoint()
        if (regionMatches(index, target, 0, target.length)) return index
        index++
    }
    cancellationCheckpoint()
    return -1
}

private fun isXmlCodePoint(value: Int): Boolean =
    value == 0x9 || value == 0xa || value == 0xd || value in 0x20..0xd7ff ||
        value in 0xe000..0xfffd || value in 0x10000..0x10ffff

private fun StringBuilder.appendCodePointCommon(codePoint: Int) {
    if (codePoint <= 0xffff) {
        append(codePoint.toChar())
    } else {
        val adjusted = codePoint - 0x10000
        append(((adjusted ushr 10) + 0xd800).toChar())
        append(((adjusted and 0x3ff) + 0xdc00).toChar())
    }
}

private val XML_NAME = Regex("[A-Za-z_:][A-Za-z0-9_.:-]*")
private val XML_NCNAME = Regex("[A-Za-z_][A-Za-z0-9_.-]*")
private val XML_DECLARATION = Regex("^\\s*<\\?xml\\s+[^?]*\\?>", RegexOption.IGNORE_CASE)
private val XML_ENCODING = Regex("\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1", RegexOption.IGNORE_CASE)
private const val MAX_DECLARATION_SCAN: Int = 1024
private const val MAX_ENTITY_LENGTH: Int = 32
private const val MAX_XML_NODES: Int = 200_000
private const val MAX_XML_DEPTH: Int = 256
private const val MAX_XML_ATTRIBUTES_PER_ELEMENT: Int = 256
private const val XML_CANCELLATION_INTERVAL_CHARS: Int = 4_096
private const val MAX_XML_NAME_CHARS: Int = 512
private const val MAX_XML_TAG_CHARS: Int = 1_048_576
private const val MAX_XML_ATTRIBUTE_VALUE_CHARS: Int = 1_048_576
private const val XML_DOCTYPE_PREFIX: String = "<!DOCTYPE"
private const val MAX_XML_DOCTYPE_CHARS: Int = 2_048
private const val MAX_XML_DOCTYPE_IDENTIFIER_CHARS: Int = 1_024
private const val MAX_XML_NAMESPACE_URI_CHARS: Int = 4_096
private const val XML_NAMESPACE_URI: String = "http://www.w3.org/XML/1998/namespace"
private const val XMLNS_NAMESPACE_URI: String = "http://www.w3.org/2000/xmlns/"
