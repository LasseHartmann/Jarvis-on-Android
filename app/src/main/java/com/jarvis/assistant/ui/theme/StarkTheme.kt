package com.jarvis.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.agent.HudColorTheme

object JarvisPalette {
    val Obsidian = Color(0xFF000000)
    val Graphite = Color(0xFF101114)
    val Elevated = Color(0xFF17191E)
    val PillSurface = Color(0xFF15171C)
    val Hairline = Color(0x14FFFFFF)
    val HairlineStrong = Color(0x24FFFFFF)

    val TextPrimary = Color(0xFFF4F5F7)
    val TextSecondary = Color(0xFF8B909A)
    val TextTertiary = Color(0xFF565B65)

    val Success = Color(0xFF34D399)
    val Warning = Color(0xFFF59E0B)
    val Danger = Color(0xFFEF4444)
}

private val JarvisTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
        color = JarvisPalette.TextPrimary
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        letterSpacing = (-0.2).sp,
        color = JarvisPalette.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 21.sp,
        color = JarvisPalette.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.5.sp,
        lineHeight = 19.sp,
        color = JarvisPalette.TextSecondary
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.5.sp,
        letterSpacing = 0.2.sp,
        color = JarvisPalette.TextSecondary
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.5.sp,
        color = JarvisPalette.TextTertiary
    )
)

@Composable
fun StarkTheme(
    finish: HudColorTheme = HudColorTheme.JARVIS_AMBER,
    content: @Composable () -> Unit
) {
    val accent = Color(finish.primaryArgb)
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = Color(0xFF0A0A0C),
        primaryContainer = accent.copy(alpha = 0.16f),
        onPrimaryContainer = accent,
        secondary = Color(finish.secondaryArgb),
        onSecondary = Color(0xFF0A0A0C),
        background = JarvisPalette.Obsidian,
        onBackground = JarvisPalette.TextPrimary,
        surface = JarvisPalette.Graphite,
        onSurface = JarvisPalette.TextPrimary,
        surfaceVariant = JarvisPalette.Elevated,
        onSurfaceVariant = JarvisPalette.TextSecondary,
        outline = JarvisPalette.HairlineStrong,
        error = JarvisPalette.Danger,
        onError = Color.White
    )
    MaterialTheme(
        colorScheme = scheme,
        typography = JarvisTypography,
        content = content
    )
}
