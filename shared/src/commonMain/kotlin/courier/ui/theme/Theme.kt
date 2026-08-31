package courier.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Base Backdrops
val BgDark = Color(0xFF090A10)
val GlassBackground = Color(0xB30B0D18) // 70% opacity balanced glass for wallpaper see-through
val GlassBackgroundDeep = Color(0xD9090A10) // 85% opacity for dialog scrims

// Elevated Glass Surfaces (86% - 90% opacity for guaranteed 7:1+ contrast)
val SurfaceDark = Color(0xEB131422)
val SurfaceCard = Color(0xDC1B1E32) // Frosted glass card
val SurfaceCardHover = Color(0xEE22263F)
val SurfaceVariantDark = Color(0xCC262A48) // Translucent chips and badges

// Glass Borders & Refractions
val CardBorderDark = Color(0x554E5680)
val CardBorderFocused = Color(0xFF00E5FF)
val GlassBorderGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x8000E5FF), // Electric Cyan highlight at top-left
        Color(0x356C5CE7), // Frosted Indigo mid-tone
        Color(0x18FFFFFF), // Soft frosted white highlight
        Color(0x0A000000)
    )
)

// Vibrant Solid Accents
val PrimaryIndigo = Color(0xFF6C5CE7)
val PrimaryIndigoLight = Color(0xFF8C7CFF)
val AccentCyan = Color(0xFF00E5FF)
val AccentPink = Color(0xFFFF5252)
val SuccessGreen = Color(0xFF00E676)
val WarningOrange = Color(0xFFFFAB00)

// High-Luminance Typography (Guaranteed crisp readability on any wallpaper)
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFFE2E8F0) // Light slate (90% luminance)
val TextMuted = Color(0xFFA0A6C8) // Frosted muted lavender

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryIndigo,
    onPrimary = Color.White,
    primaryContainer = SurfaceVariantDark,
    onPrimaryContainer = TextPrimary,
    secondary = AccentCyan,
    onSecondary = Color.Black,
    background = BgDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    outline = CardBorderDark,
    error = AccentPink,
    onError = Color.White
)

val CourierShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
fun CourierTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        shapes = CourierShapes,
        content = content
    )
}
