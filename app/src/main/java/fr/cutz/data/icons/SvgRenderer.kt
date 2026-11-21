package fr.cutz.data.icons

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders bundled SVGs to bitmaps with AndroidSVG (Apache-2.0, no transitive deps).
 *
 * Colouring: the import script guarantees every glyph paints with the literal
 * `currentColor`, so recolouring is a plain text substitution before parsing. This is
 * more reliable than CSS injection, which AndroidSVG only partially supports.
 *
 * Two caches: raw SVG text (avoids asset I/O) and an LRU of rendered bitmaps sized in
 * bytes for the picker grid.
 */
class SvgRenderer(private val context: Context) {

    private val textCache = LruCache<String, String>(512)

    private val bitmapCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    fun cached(id: String, sizePx: Int, color: Int): Bitmap? = bitmapCache.get(key(id, sizePx, color))

    suspend fun render(icon: IconMeta, sizePx: Int, color: Int): Bitmap? = withContext(Dispatchers.Default) {
        val k = key(icon.id, sizePx, color)
        bitmapCache.get(k) ?: renderUncached(icon.assetPath, sizePx, color)?.also { bitmapCache.put(k, it) }
    }

    /** Uncached rendering, used for the final shortcut icon (large, rendered once). */
    fun renderUncached(assetPath: String, sizePx: Int, color: Int): Bitmap? = runCatching {
        val raw = textCache.get(assetPath) ?: context.assets.open(assetPath).bufferedReader()
            .use { it.readText() }.also { textCache.put(assetPath, it) }
        val svg = SVG.getFromString(raw.replace("currentColor", toHex(color)))
        svg.setDocumentWidth(sizePx.toFloat())
        svg.setDocumentHeight(sizePx.toFloat())
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bmp))
        bmp
    }.getOrNull()

    private fun key(id: String, size: Int, color: Int) = "$id@$size#$color"

    companion object {
        /** SVG colour syntax (#RRGGBB); glyph alpha is not supported by design. */
        fun toHex(color: Int): String = String.format("#%06X", color and 0xFFFFFF)
    }
}
