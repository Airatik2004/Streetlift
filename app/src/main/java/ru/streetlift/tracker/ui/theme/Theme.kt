package ru.streetlift.tracker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AccentBlue = Color(0xFF2196F3)
val DeloadGreen = Color(0xFF66BB6A)
val WarnOrange = Color(0xFFFFA726)

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF0D3B66),
    onPrimaryContainer = Color(0xFFD3E6FF),
    secondary = Color(0xFF64B5F6),
    onSecondary = Color(0xFF00213D),
    secondaryContainer = Color(0xFF1C2B3A),
    onSecondaryContainer = Color(0xFFCFE3F7),
    tertiary = DeloadGreen,
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFE3E5E8),
    surface = Color(0xFF0E1013),
    onSurface = Color(0xFFE3E5E8),
    surfaceVariant = Color(0xFF1E2329),
    onSurfaceVariant = Color(0xFFB4BCC6),
    outline = Color(0xFF3A424C),
    error = Color(0xFFEF5350),
)

/** Крупные цифры таймера: моноширинный шрифт + табличные цифры. */
val TimerDigits = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontWeight = FontWeight.Medium,
    fontFeatureSettings = "tnum",
    fontSize = 64.sp,
    letterSpacing = 0.sp,
)

@Composable
fun StreetLiftTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = Typography(),
        content = content,
    )
}
