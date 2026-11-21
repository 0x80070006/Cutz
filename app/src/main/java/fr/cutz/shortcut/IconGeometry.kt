package fr.cutz.shortcut

import android.graphics.Path
import android.graphics.RectF
import fr.cutz.data.db.IconShape
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Adaptive icon geometry (all values in dp of the 108 dp canvas).
 *
 *  - 108 dp: full adaptive layer.
 *  - 72 dp: area a launcher may show (the mask lives inside it).
 *  - 66 dp: safe zone, a circle every launcher mask is guaranteed to contain.
 *
 * Custom shapes are sized so their farthest point sits on the 66 dp circle: whatever
 * mask the launcher applies (circle, squircle, teardrop...), it never cuts them, and
 * the user sees the shape they picked. The price is that a square looks smaller than
 * a circle; that is the only way to honour the shape on a circular launcher.
 */
object IconGeometry {
    const val CANVAS_DP = 108f
    const val VISIBLE_DP = 72f
    const val SAFE_DP = 66f
    private const val ROUNDED_RATIO = 0.22f

    /** Max distance from centre of each unit shape (half-size = 1). */
    fun maxRadius(shape: IconShape): Float = when (shape) {
        IconShape.SYSTEM, IconShape.CIRCLE -> 1f
        IconShape.SQUARE -> sqrt(2f)
        IconShape.ROUNDED_SQUARE -> (1f - ROUNDED_RATIO) * sqrt(2f) + ROUNDED_RATIO
        IconShape.SQUIRCLE -> 2f.pow(-0.25f) * sqrt(2f) * 1f // superellipse n=4 at 45 degrees
    }

    /**
     * Box (in px) where the background shape is drawn and inside which the glyph is placed.
     * SYSTEM: the 72 dp visible area (the background itself fills the full canvas).
     */
    fun shapeBox(shape: IconShape, canvasPx: Float): RectF {
        val c = canvasPx / 2f
        val dp = canvasPx / CANVAS_DP
        val half = if (shape == IconShape.SYSTEM) VISIBLE_DP / 2f * dp
        else SAFE_DP / 2f * dp / maxRadius(shape)
        return RectF(c - half, c - half, c + half, c + half)
    }

    fun glyphBox(shape: IconShape, canvasPx: Float, scale: Float, margin: Float): RectF {
        val box = shapeBox(shape, canvasPx)
        val size = box.width() * scale.coerceIn(0.1f, 1f) * (1f - 2f * margin.coerceIn(0f, 0.4f))
        val c = canvasPx / 2f
        return RectF(c - size / 2f, c - size / 2f, c + size / 2f, c + size / 2f)
    }

    fun path(shape: IconShape, r: RectF): Path = Path().apply {
        when (shape) {
            IconShape.SYSTEM, IconShape.SQUARE -> addRect(r, Path.Direction.CW)
            IconShape.CIRCLE -> addOval(r, Path.Direction.CW)
            IconShape.ROUNDED_SQUARE -> {
                val rad = r.width() * ROUNDED_RATIO
                addRoundRect(r, rad, rad, Path.Direction.CW)
            }
            IconShape.SQUIRCLE -> {
                // |x|^4 + |y|^4 = 1, sampled; 128 points are visually perfect at 432 px.
                val cx = r.centerX(); val cy = r.centerY(); val h = r.width() / 2f
                val steps = 128
                for (i in 0..steps) {
                    val t = 2.0 * Math.PI * i / steps
                    val ct = cos(t); val st = sin(t)
                    val x = (cx + h * sign(ct) * abs(ct).pow(0.5)).toFloat()
                    val y = (cy + h * sign(st) * abs(st).pow(0.5)).toFloat()
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
        }
    }
}
