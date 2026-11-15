package dev.shinsou.kmp.manga

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/** One translation to draw: its layout and the style taken from the erased source. */
public class PlacedText(public val layout: TextLayout, public val erased: Erased)

/**
 * Draws translations onto a page with Compose's text stack, so all three platforms share it. One
 * pixel is one sp (density 1), so glyph sizes are in page pixels.
 */
public class MangaRenderer(private val platform: MangaPlatform, fontPath: String, weight: Int = 600) {
    private val family: FontFamily = platform.fontFamily(fontPath, weight)
    private val measurer = TextMeasurer(platform.fontResolver(), Density(1f, 1f), LayoutDirection.Ltr, cacheSize = 64)

    private fun style(size: Int, color: Color, outline: Float? = null) = TextStyle(
        fontFamily = family, fontSize = size.sp, color = color, lineHeight = size.sp,
        // always set: on Android a pass without a draw style keeps the previous pass's stroke
        drawStyle = outline?.let { Stroke(width = it) } ?: Fill,
    )

    /** Text widths in the translation font, for [MangaLayout]. */
    public val measure: TextMeasure = TextMeasure { text, size -> measurer.measure(text, style(size, Color.Black)).size.width.toFloat() }

    /** Draws [texts] onto [page] and returns the result. */
    public fun draw(page: RgbImage, texts: List<PlacedText>): RgbImage {
        if (texts.isEmpty()) return page
        val bitmap = platform.codec.toImageBitmap(page)
        CanvasDrawScope().draw(Density(1f, 1f), LayoutDirection.Ltr, Canvas(bitmap), Size(page.width.toFloat(), page.height.toFloat())) {
            for (t in texts) {
                val ink = Color(t.erased.ink[0], t.erased.ink[1], t.erased.ink[2])
                val outline = if (t.erased.flat) null else Color(t.erased.background[0], t.erased.background[1], t.erased.background[2])
                val size = t.layout.size
                for (g in t.layout.glyphs) {
                    val passes = listOfNotNull(outline?.let { style(size, it, max(2f, size / 7f) * 2) }, style(size, ink))
                    for (st in passes) {
                        val measured: TextLayoutResult = measurer.measure(g.text, st)
                        val w = measured.size.width.toFloat(); val h = measured.size.height.toFloat()
                        val topLeft = Offset(g.x - w / 2, g.y - h / 2)
                        when (g.kind) {
                            // upright across the column: squeeze to one cell when wider
                            GlyphKind.TATE_CHU_YOKO -> scale(min(1f, size * 0.95f / max(1f, w)), 1f, pivot = Offset(g.x, g.y)) { drawText(measured, topLeft = topLeft) }
                            GlyphKind.SIDEWAYS -> rotate(90f, pivot = Offset(g.x, g.y)) { drawText(measured, topLeft = topLeft) }
                            else -> drawText(measured, topLeft = topLeft)
                        }
                    }
                }
            }
        }
        return platform.codec.fromImageBitmap(bitmap)
    }
}
