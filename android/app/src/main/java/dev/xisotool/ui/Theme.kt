package dev.xisotool.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val XisoColors = darkColorScheme(
    primary = Color(0xFF8FB4D9),
    onPrimary = Color(0xFF0A1A2A),
    primaryContainer = Color(0xFF243B54),
    onPrimaryContainer = Color(0xFFD5E6F8),
    secondary = Color(0xFFA7B6C6),
    onSecondary = Color(0xFF14202C),
    secondaryContainer = Color(0xFF2A3541),
    onSecondaryContainer = Color(0xFFDCE6F0),
    tertiary = Color(0xFF9DBFB4),
    onTertiary = Color(0xFF0E211B),
    tertiaryContainer = Color(0xFF254238),
    onTertiaryContainer = Color(0xFFD0EBE0),
    background = Color(0xFF0E1116),
    onBackground = Color(0xFFE3E7EC),
    surface = Color(0xFF12161C),
    onSurface = Color(0xFFE3E7EC),
    surfaceVariant = Color(0xFF1D242D),
    onSurfaceVariant = Color(0xFFA6B1BD),
    surfaceTint = Color(0xFF8FB4D9),
    inverseSurface = Color(0xFFE3E7EC),
    inverseOnSurface = Color(0xFF1B2027),
    outline = Color(0xFF48515C),
    outlineVariant = Color(0xFF2A323B),
    error = Color(0xFFFF8F85),
    onError = Color(0xFF3B0A06),
    errorContainer = Color(0xFF5A211D),
    onErrorContainer = Color(0xFFFFDAD5),
    surfaceBright = Color(0xFF2A323B),
    surfaceDim = Color(0xFF0E1116),
    surfaceContainerLowest = Color(0xFF0B0E12),
    surfaceContainerLow = Color(0xFF14181F),
    surfaceContainer = Color(0xFF181D24),
    surfaceContainerHigh = Color(0xFF1E252D),
    surfaceContainerHighest = Color(0xFF252D37),
)

val SuccessColor = Color(0xFF8FD0A9)
val WarnColor = Color(0xFFE8C170)

@Composable
fun XisoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = XisoColors, content = content)
}
