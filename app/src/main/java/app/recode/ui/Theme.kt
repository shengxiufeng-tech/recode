package app.recode.ui

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

private val Light = lightColorScheme(
    primary = Color(0xFF3A5BD9),
    secondary = Color(0xFF5B5D72),
    tertiary = Color(0xFF8E4585),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFB8C4FF),
    secondary = Color(0xFFC4C5DD),
    tertiary = Color(0xFFFFADE8),
)

/** Material You colors from the wallpaper on Android 12+, a blue fallback otherwise. */
@Composable
fun RecodeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

val WarningColor = Color(0xFFB26A00)
