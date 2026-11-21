package fr.cutz.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightFallback = lightColorScheme(
    primary = Color(0xFF3949AB),
    secondary = Color(0xFF5C6BC0),
    tertiary = Color(0xFF00897B),
)
private val DarkFallback = darkColorScheme(
    primary = Color(0xFF9FA8DA),
    secondary = Color(0xFFB0BEC5),
    tertiary = Color(0xFF80CBC4),
)

/** Material You on Android 12+ (GrapheneOS supports it), static indigo palette below. */
@Composable
fun CutzTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkFallback
        else -> LightFallback
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
