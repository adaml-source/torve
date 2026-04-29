package com.torve.desktop.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import com.torve.presentation.settings.ThemeMode

data class TorveDesktopColors(
    val shellBackground: Color = Color(0xFF090A10),
    val shellGradientTop: Color = Color(0xFF121521),
    val shellGradientBottom: Color = Color(0xFF090A10),
    val sidebarSurface: Color = Color(0xFF111420),
    val headerSurface: Color = Color(0xFF121624),
    val stageSurface: Color = Color(0xFF151A27),
    val drawerSurface: Color = Color(0xFF171E2D),
    val dockSurface: Color = Color(0xFF121724),
    val cardSurface: Color = Color(0xFF1A2030),
    val elevatedSurface: Color = Color(0xFF222A3C),
    val fieldSurface: Color = Color(0xFF202838),
    val borderSubtle: Color = Color(0xFF2B3448),
    val borderStrong: Color = Color(0xFF39445D),
    val textPrimary: Color = Color(0xFFF4F6FB),
    val textSecondary: Color = Color(0xFFBAC2D6),
    val textMuted: Color = Color(0xFF8790A6),
    val textDisabled: Color = Color(0xFF61697D),
    val accent: Color = Color(0xFFE8A838),
    val accentHover: Color = Color(0xFFF0C060),
    val accentContainer: Color = Color(0x33E8A838),
    val accentContainerStrong: Color = Color(0x4DE8A838),
    val success: Color = Color(0xFF34D399),
    val info: Color = Color(0xFF60A5FA),
    val warning: Color = Color(0xFFF59E0B),
    val error: Color = Color(0xFFEF4444),
    val live: Color = Color(0xFFFF6B6B),
)

data class TorveDesktopSpacing(
    val xxs: Int = 4,
    val xs: Int = 8,
    val sm: Int = 12,
    val md: Int = 16,
    val lg: Int = 20,
    val xl: Int = 24,
    val xxl: Int = 32,
    val xxxl: Int = 40,
)

data class TorveDesktopRadii(
    val sm: androidx.compose.ui.unit.Dp = 10.dp,
    val md: androidx.compose.ui.unit.Dp = 14.dp,
    val lg: androidx.compose.ui.unit.Dp = 18.dp,
    val xl: androidx.compose.ui.unit.Dp = 22.dp,
    val xxl: androidx.compose.ui.unit.Dp = 28.dp,
)

data class TorveDesktopElevations(
    val card: androidx.compose.ui.unit.Dp = 0.dp,
    val drawer: androidx.compose.ui.unit.Dp = 6.dp,
    val overlay: androidx.compose.ui.unit.Dp = 10.dp,
)

private val DesktopColorScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFE8A838),
    onPrimary = Color(0xFF090A10),
    primaryContainer = Color(0xFFBF8A28),
    onPrimaryContainer = Color(0xFFF4F6FB),
    secondary = Color(0xFFBAC2D6),
    onSecondary = Color(0xFF090A10),
    background = Color(0xFF090A10),
    onBackground = Color(0xFFF4F6FB),
    surface = Color(0xFF151A27),
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = Color(0xFF202838),
    onSurfaceVariant = Color(0xFFBAC2D6),
    error = Color(0xFFEF4444),
    onError = Color.White,
    outline = Color(0xFF2B3448),
    outlineVariant = Color(0xFF39445D),
)

private val DesktopLightColorScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFFC77D12),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6D8A9),
    onPrimaryContainer = Color(0xFF2A1B05),
    secondary = Color(0xFF506077),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF5F1E9),
    onBackground = Color(0xFF16181F),
    surface = Color(0xFFFFFBF5),
    onSurface = Color(0xFF16181F),
    surfaceVariant = Color(0xFFE7DFD0),
    onSurfaceVariant = Color(0xFF4B5568),
    error = Color(0xFFB42318),
    onError = Color.White,
    outline = Color(0xFFD2C6B2),
    outlineVariant = Color(0xFFC4B8A5),
)

private val DesktopTypography = androidx.compose.material3.Typography(
    displayLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 40.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp,
    ),
    displayMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = InterFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.sp,
    ),
)

val LocalTorveDesktopColors = staticCompositionLocalOf { TorveDesktopColors() }
val LocalTorveDesktopSpacing = staticCompositionLocalOf { TorveDesktopSpacing() }
val LocalTorveDesktopRadii = staticCompositionLocalOf { TorveDesktopRadii() }
val LocalTorveDesktopElevations = staticCompositionLocalOf { TorveDesktopElevations() }

@Composable
fun TorveDesktopTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    val colors = if (isDark) {
        TorveDesktopColors()
    } else {
        TorveDesktopColors(
            shellBackground = Color(0xFFF5F1E9),
            shellGradientTop = Color(0xFFFFFCF6),
            shellGradientBottom = Color(0xFFEDE4D8),
            sidebarSurface = Color(0xFFF0E6D8),
            headerSurface = Color(0xFFFAF4EA),
            stageSurface = Color(0xFFFFFBF5),
            drawerSurface = Color(0xFFF6EDDF),
            dockSurface = Color(0xFFF2E8DA),
            cardSurface = Color(0xFFFFFBF5),
            elevatedSurface = Color(0xFFF6EDDF),
            fieldSurface = Color(0xFFF3EBDD),
            borderSubtle = Color(0xFFD8CDBD),
            borderStrong = Color(0xFFBDAF99),
            textPrimary = Color(0xFF16181F),
            textSecondary = Color(0xFF536073),
            textMuted = Color(0xFF7D8898),
            textDisabled = Color(0xFFA0A8B5),
            accent = Color(0xFFC77D12),
            accentHover = Color(0xFFD8912C),
            accentContainer = Color(0x33C77D12),
            accentContainerStrong = Color(0x4DC77D12),
            success = Color(0xFF15803D),
            info = Color(0xFF2563EB),
            warning = Color(0xFFB45309),
            error = Color(0xFFB42318),
            live = Color(0xFFDC2626),
        )
    }
    val spacing = TorveDesktopSpacing()
    val radii = TorveDesktopRadii()
    val elevations = TorveDesktopElevations()

    CompositionLocalProvider(
        LocalTorveDesktopColors provides colors,
        LocalTorveDesktopSpacing provides spacing,
        LocalTorveDesktopRadii provides radii,
        LocalTorveDesktopElevations provides elevations,
    ) {
        MaterialTheme(
            colorScheme = if (isDark) DesktopColorScheme else DesktopLightColorScheme,
            typography = DesktopTypography,
            content = content,
        )
    }
}

object TorveDesktopThemeTokens {
    val colors: TorveDesktopColors
        @Composable get() = LocalTorveDesktopColors.current

    val spacing: TorveDesktopSpacing
        @Composable get() = LocalTorveDesktopSpacing.current

    val radii: TorveDesktopRadii
        @Composable get() = LocalTorveDesktopRadii.current

    val elevations: TorveDesktopElevations
        @Composable get() = LocalTorveDesktopElevations.current
}
