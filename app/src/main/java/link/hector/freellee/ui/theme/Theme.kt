package link.hector.freellee.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    background = AmoledBlack,
    surface = SurfaceDark,
    primary = AccentGreen,
    // Explicit white label on the green primary buttons; the Material default is a low-contrast
    // grey that is hard to read on AMOLED.
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = TextPrimary,
    onSurface = TextPrimary,
    secondary = AccentTeal,
    tertiary = AccentBlue,
)

/** Freellee is AMOLED dark-only and ignores the system light/dark setting. */
@Composable
fun FreelleeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        content = content,
    )
}
