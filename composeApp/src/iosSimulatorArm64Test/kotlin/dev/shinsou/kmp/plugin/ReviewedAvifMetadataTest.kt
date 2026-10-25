package dev.shinsou.kmp.plugin

import platform.JavaScriptCore.JSContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReviewedAvifMetadataTest {
    @Test fun acceptsStructuralAvifAndRejectsAdversarialBoxes() {
        assertEquals("1350x1920", parse(valid(1350, 1920)))
        listOf(
            valid(8192, 8192), valid(9000, 1),
            box("ftyp", ascii("avif") + u32(0)) + box("free", ascii("ispe") + u32(1) + u32(1)),
            box("ftyp", ascii("xxxx") + ascii("avif")) + metadata(1, 1),
            box("ftyp", ascii("avif") + u32(0)) + box("free", metadata(1, 1)),
            box("ftyp", ascii("avif") + u32(0)) + box("ipco", ispe(1, 1)),
            box("ftyp", ascii("avif") + u32(0)) + metadata(1, 1) + metadata(2, 1),
            valid(1, 1).copyOf().also { it[3] = 7 },
        ).forEach { assertTrue(parse(it).startsWith("error:")) }
    }

    private fun parse(bytes: ByteArray): String {
        val literal = bytes.joinToString(prefix = "[", postfix = "]") { (it.toInt() and 255).toString() }
        val context = JSContext()!!
        val script = """
            (() => { try {
              const bytes = new Uint8Array($literal);
              const extent = (${reviewedAvifDimensionParserScript()})(bytes);
              return extent.width + 'x' + extent.height;
            } catch (_) { return 'error:avif_metadata'; } })()
        """.trimIndent()
        return context.evaluateScript(script)?.toString() ?: "error:nil"
    }

    private fun valid(width: Int, height: Int) = box("ftyp", ascii("avif") + u32(0) + ascii("mif1")) + metadata(width, height)
    private fun metadata(width: Int, height: Int) = box("meta", u32(0) + box("iprp", box("ipco", ispe(width, height))))
    private fun ispe(width: Int, height: Int) = box("ispe", u32(0) + u32(width) + u32(height))
    private fun box(type: String, body: ByteArray) = u32(body.size + 8) + ascii(type) + body
    private fun ascii(value: String) = value.encodeToByteArray()
    private fun u32(value: Int) = byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())
}
