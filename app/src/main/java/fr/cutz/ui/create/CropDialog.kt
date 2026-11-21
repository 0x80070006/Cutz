package fr.cutz.ui.create

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fr.cutz.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val MAX_DECODE = 2048
private const val OUTPUT_PX = 512

/** Downsampled decode; ImageDecoder (API 28+) also applies EXIF rotation. */
suspend fun decodeForCrop(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { dec, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_DECODE) dec.setTargetSampleSize(longest / MAX_DECODE)
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // needed to draw it on a software Canvas
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE) sample *= 2
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }.getOrNull()
}

/**
 * Square crop with pan and pinch-zoom. Written by hand (about 100 lines) instead of
 * pulling a cropper library: fewer dependencies to audit for F-Droid/Accrescent.
 */
@Composable
fun CropDialog(uri: Uri, onDismiss: () -> Unit, onCropped: (Bitmap) -> Unit) {
    val context = LocalContext.current
    // null = still decoding; failure = unreadable image.
    val decoded by produceState<Result<Bitmap>?>(null, uri) {
        value = decodeForCrop(context, uri)?.let { Result.success(it) } ?: Result.failure(IllegalStateException())
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.crop_title), color = Color.White, style = MaterialTheme.typography.titleLarge)
                val bmp = decoded?.getOrNull()
                val failed = decoded?.isFailure == true
                var zoom by remember { mutableFloatStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                var viewport by remember { mutableStateOf(IntSize.Zero) }

                Box(
                    Modifier.fillMaxWidth().aspectRatio(1f).clipToBounds().background(Color.DarkGray)
                        .onSizeChanged { viewport = it },
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp == null) {
                        if (failed) Text(stringResource(R.string.crop_error), color = Color.White) else CircularProgressIndicator()
                    } else {
                        val image = remember(bmp) { bmp.asImageBitmap() }
                        val vp = viewport.width.toFloat()
                        val base = if (vp == 0f) 1f else max(vp / bmp.width, vp / bmp.height)
                        fun clamp(o: Offset, z: Float): Offset {
                            val maxX = max(0f, (bmp.width * base * z - vp) / 2f)
                            val maxY = max(0f, (bmp.height * base * z - vp) / 2f)
                            return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
                        }
                        Canvas(
                            Modifier.fillMaxSize().pointerInput(bmp, vp) {
                                detectTransformGestures { _, pan, gestureZoom, _ ->
                                    zoom = (zoom * gestureZoom).coerceIn(1f, 8f)
                                    offset = clamp(offset + pan, zoom)
                                }
                            },
                        ) {
                            val w = bmp.width * base * zoom
                            val h = bmp.height * base * zoom
                            drawImage(
                                image,
                                dstOffset = IntOffset((size.width / 2 - w / 2 + offset.x).roundToInt(), (size.height / 2 - h / 2 + offset.y).roundToInt()),
                                dstSize = IntSize(w.roundToInt(), h.roundToInt()),
                            )
                            // Circle guide = what a round launcher mask will keep at scale 1.
                            drawCircle(Color.White.copy(alpha = 0.8f), radius = size.minDimension / 2f - 2f, style = Stroke(2.dp.toPx()))
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = Color.White) }
                    Button(enabled = bmp != null && viewport.width > 0, onClick = {
                        val b = bmp ?: return@Button
                        val vp = viewport.width.toFloat()
                        val scale = max(vp / b.width, vp / b.height) * zoom
                        val srcSize = vp / scale
                        val cx = b.width / 2f - offset.x / scale
                        val cy = b.height / 2f - offset.y / scale
                        val left = (cx - srcSize / 2).roundToInt().coerceIn(0, b.width - 1)
                        val top = (cy - srcSize / 2).roundToInt().coerceIn(0, b.height - 1)
                        val side = min(srcSize.roundToInt(), min(b.width - left, b.height - top))
                        val out = Bitmap.createBitmap(OUTPUT_PX, OUTPUT_PX, Bitmap.Config.ARGB_8888)
                        android.graphics.Canvas(out).drawBitmap(
                            b, Rect(left, top, left + side, top + side), Rect(0, 0, OUTPUT_PX, OUTPUT_PX),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
                        )
                        onCropped(out)
                    }) { Text(stringResource(R.string.crop_confirm)) }
                }
            }
        }
    }
}
