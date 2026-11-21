package fr.cutz.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.cutz.R

/** Curated palette: Material 500/700 tones plus neutrals, all readable under white or black glyphs. */
val PALETTE: List<Int> = listOf(
    0xFFFFFFFF, 0xFF1B1B1F, 0xFF607D8B, 0xFF9E9E9E,
    0xFFD32F2F, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7,
    0xFF3949AB, 0xFF1E88E5, 0xFF039BE5, 0xFF00ACC1,
    0xFF00897B, 0xFF43A047, 0xFF7CB342, 0xFFC0CA33,
    0xFFFDD835, 0xFFFFB300, 0xFFFB8C00, 0xFFF4511E,
    0xFF6D4C41,
).map { it.toInt() }

@Composable
fun ColorRow(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    var showCustom by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    // Wallpaper-derived colours first: they match the user's Material You theme.
    val colors = remember(scheme) {
        listOf(scheme.primary, scheme.primaryContainer, scheme.tertiary, scheme.secondaryContainer).map { it.toArgb() } + PALETTE
    }
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { showCustom = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Palette, stringResource(R.string.color_custom)) }
        }
        items(colors.distinct()) { c -> Swatch(c, c == selected) { onSelect(c) } }
    }
    if (showCustom) ColorPickerDialog(selected, onDismiss = { showCustom = false }) { onSelect(it); showCustom = false }
}

@Composable
private fun Swatch(color: Int, selected: Boolean, onClick: () -> Unit) {
    val hex = hex(color)
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(Color(color))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = hex },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Default.Check, null, tint = Color(fr.cutz.ui.create.CreateViewModel.contrastingGlyph(color)))
    }
}

fun hex(color: Int) = String.format("#%06X", color and 0xFFFFFF)

fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return (0xFF000000 or t.toLong(16)).toInt()
}

/** Free colour selection: HSV sliders plus hex input (no extra dependency). */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var h by remember { mutableFloatStateOf(hsv[0]) }
    var s by remember { mutableFloatStateOf(hsv[1]) }
    var v by remember { mutableFloatStateOf(hsv[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
    var hexText by remember(color) { mutableStateOf(hex(color)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.color_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp)).background(Color(color)))
                Text(stringResource(R.string.color_hue))
                Slider(h, { h = it }, valueRange = 0f..360f)
                Text(stringResource(R.string.color_saturation))
                Slider(s, { s = it })
                Text(stringResource(R.string.color_brightness))
                Slider(v, { v = it })
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { txt ->
                        hexText = txt
                        parseHex(txt)?.let { c ->
                            val out = FloatArray(3); android.graphics.Color.colorToHSV(c, out)
                            h = out[0]; s = out[1]; v = out[2]
                        }
                    },
                    label = { Text(stringResource(R.string.color_hex)) },
                    singleLine = true,
                    isError = parseHex(hexText) == null,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text(stringResource(R.string.ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
