package courier.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Base Backdrops (Neutral dark steel background)
val BgDark = Color(0xFF0E1116)
val GlassBackground = Color(0xE60E1116) // 90% opacity (0xE6) default frosted glass
val GlassBackgroundHazy = Color(0xF20E1116) // 95% opacity (0xF2) solid / hazy glass
val GlassBackgroundSheer = Color(0xCC0E1116) // 80% opacity (0xCC) sheer glass
val GlassBackgroundDeep = Color(0xD90E1116) // 85% opacity for dialog scrims

// Elevated Surfaces (Steel dark neutral hierarchy)
val SurfaceDark = Color(0xEB161A21)
val SurfaceCard = Color(0xDC1D222B) // Card surface
val SurfaceCardHover = Color(0xEE262C37)
val SurfaceVariantDark = Color(0xCC262C37) // Translucent chips and badges
val TitleBarBg = Color(0x660E1116)
val CloseButtonHover = Color(0xFFD97066)
val PlayerOverlayBg = Color(0xBA000000)
val PlayerSurfaceBg = Color(0xF2161A21)

// Glass Borders & Refractions
val CardBorderDark = Color(0x338FA8B8) // Subtle slate border
val CardBorderFocused = Color(0xFF8FA8B8) // Focused slate border
val GlassBorderGradient = Brush.linearGradient(
    colors = listOf(
        Color(0x668FA8B8), // Slate highlight at top-left
        Color(0x227D9BB8), // Steel blue mid-tone
        Color(0x14FFFFFF), // Soft neutral white highlight
        Color(0x05000000)
    )
)

// Vibrant Solid Accents (Steel Blue & Slate)
val PrimaryIndigo = Color(0xFF7D9BB8) // Primary steel blue (named PrimaryIndigo for caller compatibility)
val PrimaryIndigoLight = Color(0xFF9CB5CD)
val PrimaryContainer = Color(0xFF2C3E52)
val AccentCyan = Color(0xFF8FA8B8) // Secondary slate (named AccentCyan for caller compatibility)
val AccentPink = Color(0xFFD97066) // Error red
val SuccessGreen = Color(0xFF6BBF8A)
val WarningOrange = Color(0xFFD4A857)

// High-Luminance Typography
val TextPrimary = Color(0xFFF1F4F8) // Off-white crisp text (95% luminance)
val TextSecondary = Color(0xFFC3CCD6) // Light slate (80% luminance)
val TextMuted = Color(0xFF8B96A3) // Muted slate gray

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryIndigo,
    onPrimary = Color.Black,
    primaryContainer = PrimaryContainer,
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
