package dev.shinsou.kmp.ui.markdown

/**
 * Parser for the small Markdown subset of reviews and replies (server spec `docs/spec/reviews.md`
 * section 4). This is a port of the reference implementation `internal/markdown/markdown.go`; the
 * shared vectors (`api/vectors/markdown.json`) pin both to the same structure. The app draws this
 * model natively and never uses the server's `bodyHtml`.
 *
 * Blocks are separated by blank lines (a change of kind needs none): consecutive plain lines form
 * a paragraph, `>` lines a quote, `- ` / `* ` lines a bullet list. Inline: `code`, **bold**, *italic*.
 */
internal sealed interface MarkdownBlock {
    /** Plain lines; a line break separates them. */
    data class Paragraph(val lines: List<List<MarkdownInline>>) : MarkdownBlock

    data class Quote(val lines: List<List<MarkdownInline>>) : MarkdownBlock

    /** One entry per bullet. */
    data class BulletList(val items: List<List<MarkdownInline>>) : MarkdownBlock
}

/** Inline spans. Italic cannot contain bold or italic; bold may contain italic and code. */
internal sealed interface MarkdownInline {
    data class Text(val value: String) : MarkdownInline

    data class Code(val value: String) : MarkdownInline

    data class Bold(val children: List<MarkdownInline>) : MarkdownInline

    data class Italic(val children: List<MarkdownInline>) : MarkdownInline
}

internal fun parseReviewMarkdown(source: String): List<MarkdownBlock> {
    val src = source.replace("\r\n", "\n").replace("\r", "\n")
    val blocks = mutableListOf<MarkdownBlock>()
    var kind = ' ' // ' ' none, 'p' paragraph, 'q' quote, 'l' list
    val lines = mutableListOf<String>()

    fun flush() {
        when (kind) {
            'p' -> blocks += MarkdownBlock.Paragraph(lines.map(::parseInline))
            'q' -> blocks += MarkdownBlock.Quote(lines.map(::parseInline))
            'l' -> blocks += MarkdownBlock.BulletList(lines.map(::parseInline))
        }
        kind = ' '
        lines.clear()
    }

    for (raw in src.split('\n')) {
        val line = raw.trimEnd(::isBlank)
        val trimmed = line.trimStart(::isBlank)
        val nextKind: Char
        val text: String
        when {
            trimmed.isEmpty() -> {
                flush()
                continue
            }
            trimmed.startsWith(">") -> {
                nextKind = 'q'
                text = trimmed.substring(1).trimStart(::isBlank)
            }
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                nextKind = 'l'
                text = trimmed.substring(2).trimStart(::isBlank)
            }
            else -> {
                nextKind = 'p'
                text = trimmed
            }
        }
        if (nextKind != kind) {
            flush()
            kind = nextKind
        }
        lines += text
    }
    flush()
    return blocks
}

private fun isBlank(c: Char): Boolean = c == ' ' || c == '\t'

/** Parses the inline markup of one line. */
internal fun parseInline(line: String): List<MarkdownInline> = inline(line, emphasis = true)

/**
 * A marker without a matching closing marker is literal text. Emphasis must hug its text (no
 * space after the opening or before the closing marker) and cannot be empty.
 */
private fun inline(s: String, emphasis: Boolean): List<MarkdownInline> {
    val out = mutableListOf<MarkdownInline>()
    var lit = 0 // start of pending literal text
    fun emit(end: Int) {
        if (end > lit) out += MarkdownInline.Text(s.substring(lit, end))
    }
    var i = 0
    while (i < s.length) {
        if (s[i] == '`') {
            val end = s.indexOf('`', i + 1).let { if (it < 0) -1 else it - (i + 1) }
            if (end > 0) {
                emit(i)
                out += MarkdownInline.Code(s.substring(i + 1, i + 1 + end))
                i += end + 2
                lit = i
                continue
            }
        } else if (emphasis && s.startsWith("**", i)) {
            val end = closing(s.substring(i + 2), "**")
            if (end > 0) {
                emit(i)
                out += MarkdownInline.Bold(inline(s.substring(i + 2, i + 2 + end), true))
                i += end + 4
                lit = i
                continue
            }
            i += 2 // literal "**": do not read its second star as an italic marker
            continue
        } else if (emphasis && s[i] == '*') {
            val end = closing(s.substring(i + 1), "*")
            if (end > 0) {
                emit(i)
                out += MarkdownInline.Italic(inline(s.substring(i + 1, i + 1 + end), false))
                i += end + 2
                lit = i
                continue
            }
        }
        i++
    }
    emit(s.length)
    return out
}

/** Index in [s] of the marker that closes an emphasis starting at s[0], or -1. */
private fun closing(s: String, marker: String): Int {
    if (s.isEmpty() || s[0] == ' ' || s[0] == '\t') return -1
    var from = 0
    while (true) {
        val found = s.indexOf(marker, from)
        if (found < 0) return -1
        if (found > 0 && s[found - 1] != ' ' && s[found - 1] != '\t') {
            // A single star must not be the first half of "**".
            if (marker == "*" && s.startsWith("**", found)) {
                from = found + 2
                continue
            }
            return found
        }
        from = found + marker.length
    }
}

/** Serialises the model the way the server renders it; used by the shared-vector test. */
internal fun List<MarkdownBlock>.toHtml(): String = buildString {
    for (block in this@toHtml) {
        when (block) {
            is MarkdownBlock.Paragraph -> {
                append("<p>")
                appendLines(block.lines)
                append("</p>")
            }
            is MarkdownBlock.Quote -> {
                append("<blockquote><p>")
                appendLines(block.lines)
                append("</p></blockquote>")
            }
            is MarkdownBlock.BulletList -> {
                append("<ul>")
                block.items.forEach { append("<li>"); appendInline(it); append("</li>") }
                append("</ul>")
            }
        }
    }
}

private fun StringBuilder.appendLines(lines: List<List<MarkdownInline>>) {
    lines.forEachIndexed { index, line ->
        if (index > 0) append("<br>")
        appendInline(line)
    }
}

private fun StringBuilder.appendInline(spans: List<MarkdownInline>) {
    for (span in spans) {
        when (span) {
            is MarkdownInline.Text -> append(escapeHtml(span.value))
            is MarkdownInline.Code -> append("<code>").append(escapeHtml(span.value)).append("</code>")
            is MarkdownInline.Bold -> { append("<strong>"); appendInline(span.children); append("</strong>") }
            is MarkdownInline.Italic -> { append("<em>"); appendInline(span.children); append("</em>") }
        }
    }
}

private fun escapeHtml(s: String): String = buildString {
    for (c in s) {
        when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&#34;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }
}
