package dev.shinsou.kmp.ui.markdown

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs the Markdown vectors shared with the sync server. [MARKDOWN_VECTORS] is a verbatim copy of
 * `shinsou_sync_service/api/vectors/markdown.json` (that repository is the source of truth; update
 * the copy whenever the server's vectors change). Embedded as a constant so the test runs on every
 * target without resource loading.
 */
class ReviewMarkdownVectorTest {
    @Test
    fun everyVectorSerialisesToTheServersHtml() {
        val vectors = Json.parseToJsonElement(MARKDOWN_VECTORS).jsonObject["vectors"]!!.jsonArray
        assertTrue(vectors.size >= 20)
        vectors.forEach { element ->
            val case = element.jsonObject
            val name = case["name"]!!.jsonPrimitive.content
            val input = case["input"]!!.jsonPrimitive.content
            assertEquals(case["html"]!!.jsonPrimitive.content, parseReviewMarkdown(input).toHtml(), name)
        }
    }

    @Test
    fun boldKeepsNestedItalicAsStructure() {
        val blocks = parseReviewMarkdown("**a *b* c**")
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        val bold = paragraph.lines.single().single() as MarkdownInline.Bold
        assertEquals(
            listOf(
                MarkdownInline.Text("a "),
                MarkdownInline.Italic(listOf(MarkdownInline.Text("b"))),
                MarkdownInline.Text(" c"),
            ),
            bold.children,
        )
    }

    @Test
    fun blocksChangeWithoutBlankLines() {
        val blocks = parseReviewMarkdown("intro\n- item\n> quote\noutro")
        assertEquals(
            listOf(
                MarkdownBlock.Paragraph::class,
                MarkdownBlock.BulletList::class,
                MarkdownBlock.Quote::class,
                MarkdownBlock.Paragraph::class,
            ),
            blocks.map { it::class },
        )
    }
}

private const val MARKDOWN_VECTORS = """{
  "description": "Review Markdown subset (docs/spec/reviews.md section 4). html is what the server renders; clients that render natively must produce the same structure.",
  "vectors": [
    { "name": "plain paragraph", "input": "Hello world", "html": "<p>Hello world</p>" },
    { "name": "line break inside a paragraph", "input": "one\ntwo", "html": "<p>one<br>two</p>" },
    { "name": "blank line separates paragraphs", "input": "one\n\n\ntwo", "html": "<p>one</p><p>two</p>" },
    { "name": "windows line endings", "input": "one\r\ntwo", "html": "<p>one<br>two</p>" },
    { "name": "bold", "input": "a **b** c", "html": "<p>a <strong>b</strong> c</p>" },
    { "name": "italic", "input": "a *b* c", "html": "<p>a <em>b</em> c</p>" },
    { "name": "italic inside bold", "input": "**a *b* c**", "html": "<p><strong>a <em>b</em> c</strong></p>" },
    { "name": "inline code is literal", "input": "use `**x**` here", "html": "<p>use <code>**x**</code> here</p>" },
    { "name": "unclosed markers are text", "input": "2 * 3 ** 4 ` 5", "html": "<p>2 * 3 ** 4 ` 5</p>" },
    { "name": "emphasis must hug its text", "input": "a * b * c ** d ** e", "html": "<p>a * b * c ** d ** e</p>" },
    { "name": "empty emphasis is text", "input": "**** and ** and ``", "html": "<p>**** and ** and ``</p>" },
    { "name": "html is escaped", "input": "<b>x</b> & \"y\" 'z'", "html": "<p>&lt;b&gt;x&lt;/b&gt; &amp; &#34;y&#34; &#39;z&#39;</p>" },
    { "name": "html inside code is escaped", "input": "`<a href=x>`", "html": "<p><code>&lt;a href=x&gt;</code></p>" },
    { "name": "links are not rendered", "input": "[x](https://example.com) https://example.com", "html": "<p>[x](https://example.com) https://example.com</p>" },
    { "name": "quote", "input": "> one\n> **two**", "html": "<blockquote><p>one<br><strong>two</strong></p></blockquote>" },
    { "name": "quote without space", "input": ">one", "html": "<blockquote><p>one</p></blockquote>" },
    { "name": "list with both markers", "input": "- one\n* *two*", "html": "<ul><li>one</li><li><em>two</em></li></ul>" },
    { "name": "blocks change without a blank line", "input": "intro\n- item\n> quote\noutro", "html": "<p>intro</p><ul><li>item</li></ul><blockquote><p>quote</p></blockquote><p>outro</p>" },
    { "name": "dash without space is text", "input": "-one\n*two*", "html": "<p>-one<br><em>two</em></p>" },
    { "name": "headings are text", "input": "# title", "html": "<p># title</p>" },
    { "name": "leading and trailing spaces on a line", "input": "  one  \n\t- two", "html": "<p>one</p><ul><li>two</li></ul>" },
    { "name": "cjk", "input": "**很好看**，推薦", "html": "<p><strong>很好看</strong>，推薦</p>" },
    { "name": "empty", "input": "", "html": "" },
    { "name": "only blank lines", "input": "\n \n", "html": "" }
  ]
}
"""
