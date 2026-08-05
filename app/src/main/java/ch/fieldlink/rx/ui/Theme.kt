package ch.fieldlink.rx.ui

import android.app.Activity
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

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF45DEC0),
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFF9CCAC0),
    background = Color(0xFF081F25),
    surface = Color(0xFF0D2931),
    surfaceVariant = Color(0xFF173A43),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF006B5B),
    onPrimary = Color.White,
    secondary = Color(0xFF46665F),
    background = Color(0xFFF5FBF8),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFD8E9E4),
)

@Composable
fun FieldLinkRxTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && context is Activity) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) {
        DarkScheme
    } else {
        LightScheme
    }
    MaterialTheme(colorScheme = colors, content = content)
}

