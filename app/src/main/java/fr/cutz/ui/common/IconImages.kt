package fr.cutz.ui.common

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import fr.cutz.AppContainer
import fr.cutz.data.icons.IconMeta
import fr.cutz.shortcut.IconGeometry
import fr.cutz.shortcut.IconSpec

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }

/** Glyph thumbnail for the picker grid; uses the renderer's LRU cache when warm. */
@Composable
fun IconThumb(icon: IconMeta, size: Dp, modifier: Modifier = Modifier) {
    val renderer = LocalContainer.current.svgRenderer
    val color = MaterialTheme.colorScheme.onSurface.toArgb()
    val px = with(LocalDensity.current) { size.roundToPx() }
    val bmp by produceState(renderer.cached(icon.id, px, color), icon.id, px, color) {
        if (value == null) value = renderer.render(icon, px, color)
    }
    Box(modifier.size(size)) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = icon.title, modifier = Modifier.size(size)) }
    }
}

/**
 * Shows an adaptive shortcut bitmap the way a circular launcher would: only the 72 dp
 * visible area, clipped by a circle. Good enough as a preview on GrapheneOS/Pixel.
 */
@Composable
fun AdaptivePreview(bitmap: Bitmap?, size: Dp, modifier: Modifier = Modifier, greyed: Boolean = false) {
    val zoom = IconGeometry.CANVAS_DP / IconGeometry.VISIBLE_DP
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            // Custom shapes have a transparent surround, like on a real home screen.
            .background(if (bitmap == null) MaterialTheme.colorScheme.surfaceVariant else androidx.compose.ui.graphics.Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (greyed) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) else null,
                alpha = if (greyed) 0.5f else 1f,
                // requiredSize lets the 108 dp bitmap overflow the 72 dp box, which the clip crops.
                modifier = Modifier.requiredSize(size * zoom),
            )
        }
    }
}

/** Renders a stored shortcut for list rows (re-rendered when [key] changes). */
@Composable
fun ShortcutIcon(spec: IconSpec, key: Any, size: Dp, greyed: Boolean = false) {
    val factory = LocalContainer.current.iconFactory
    val px = with(LocalDensity.current) { (size * (IconGeometry.CANVAS_DP / IconGeometry.VISIBLE_DP)).roundToPx() }
    val bmp by produceState<Bitmap?>(null, key, px) { value = runCatching { factory.render(spec, px) }.getOrNull() }
    AdaptivePreview(bmp, size, greyed = greyed)
}
