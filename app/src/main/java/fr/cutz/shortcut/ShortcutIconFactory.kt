package fr.cutz.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.core.graphics.drawable.toBitmap
import fr.cutz.data.GalleryStore
import fr.cutz.data.apps.AppsRepository
import fr.cutz.data.db.IconShape
import fr.cutz.data.db.IconSourceType
import fr.cutz.data.db.ShortcutEntity
import fr.cutz.data.icons.IconCatalog
import fr.cutz.data.icons.SvgRenderer
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Everything needed to draw an icon; shared by the live preview and the pinned shortcut. */
data class IconSpec(
    val source: IconSourceType,
    val ref: String,
    val backgroundColor: Int,
    val glyphColor: Int,
    val shape: IconShape,
    val scale: Float,
    val margin: Float,
)

fun ShortcutEntity.iconSpec() = IconSpec(iconSource, iconRef, backgroundColor, glyphColor, shape, scale, margin)

/**
 * Composes the 108 dp adaptive bitmap handed to IconCompat.createWithAdaptiveBitmap.
 *
 * Size: 108 dp at the device density, clamped between xxhdpi (3x) and xxxhdpi (4x).
 * A Pixel 9a (2.625x) therefore gets 324 px; going above 4x only grows the Binder
 * transaction (ARGB bitmaps travel to system_server) since ShortcutService downsizes
 * icons to its own max dimension anyway.
 */
class ShortcutIconFactory(
    private val context: Context,
    private val svg: SvgRenderer,
    private val catalog: IconCatalog,
    private val gallery: GalleryStore,
    private val apps: AppsRepository,
) {
    val canvasPx: Int
        get() {
            val density = context.resources.displayMetrics.density.coerceIn(3f, 4f)
            return ceil(IconGeometry.CANVAS_DP * density).toInt()
        }

    suspend fun render(spec: IconSpec, sizePx: Int = canvasPx): Bitmap {
        val s = sizePx.toFloat()
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val shapeBox = IconGeometry.shapeBox(spec.shape, s)
        val shapePath = IconGeometry.path(spec.shape, shapeBox)
        paint.color = spec.backgroundColor
        if (spec.shape == IconShape.SYSTEM) canvas.drawRect(0f, 0f, s, s, paint)
        else canvas.drawPath(shapePath, paint)

        val glyphBox = IconGeometry.glyphBox(spec.shape, s, spec.scale, spec.margin)
        val glyph = loadGlyph(spec, glyphBox.width().roundToInt().coerceAtLeast(1)) ?: return out

        canvas.save()
        // Photos are clipped to the chosen shape so a scale of 1.0 gives a full-bleed picture.
        if (spec.source == IconSourceType.GALLERY && spec.shape != IconShape.SYSTEM) canvas.clipPath(shapePath)
        canvas.drawBitmap(glyph, Rect(0, 0, glyph.width, glyph.height), glyphBox.toRect(), paint)
        canvas.restore()
        return out
    }

    private suspend fun loadGlyph(spec: IconSpec, px: Int): Bitmap? = when (spec.source) {
        IconSourceType.BUNDLED -> catalog.index().get(spec.ref)?.let { svg.renderUncached(it.assetPath, px, spec.glyphColor) }
        IconSourceType.GALLERY -> gallery.load(spec.ref)
        IconSourceType.APP_ICON -> apps.loadIcon(spec.ref)?.toBitmap(px, px)
    }

    private fun RectF.toRect() = Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
}
