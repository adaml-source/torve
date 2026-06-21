package com.torve.android.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat
import androidx.compose.ui.unit.sp
import com.torve.android.R
import com.torve.presentation.settings.ThemeMode

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Typography
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

val JakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
    Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
)

val DmSans = FontFamily(
    Font(R.font.dm_sans_regular, FontWeight.Normal),
    Font(R.font.dm_sans_medium, FontWeight.Medium),
)

val TorveTypography = Typography(
    // Display — Hero titles, splash
    displayLarge = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 32.sp,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 28.sp,
    ),
    // Headlines — Screen titles, section headers
    headlineLarge = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    // Title — Card titles, shelf labels
    titleLarge = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    // Body — Descriptions, overviews
    bodyLarge = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DmSans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    // Label — Badges, chips, metadata
    labelLarge = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 16.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = JakartaSans,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        letterSpacing = 0.5.sp,
    ),
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Color Schemes
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

private val DarkColorScheme = darkColorScheme(
    primary = Amber,
    onPrimary = Obsidian,
    primaryContainer = AmberDark,
    onPrimaryContainer = Snow,
    secondary = Silver,
    onSecondary = Obsidian,
    secondaryContainer = Gunmetal,
    onSecondaryContainer = Silver,
    tertiary = Amethyst,
    onTertiary = Obsidian,
    background = Obsidian,
    onBackground = Snow,
    surface = Charcoal,
    onSurface = Snow,
    surfaceVariant = Graphite,
    onSurfaceVariant = Silver,
    surfaceContainerHigh = Gunmetal,
    surfaceContainerHighest = Steel,
    outline = Steel,
    outlineVariant = Pewter,
    error = Ruby,
    onError = Color.White,
    inverseSurface = Snow,
    inverseOnSurface = Obsidian,
)

private val LightColorScheme = lightColorScheme(
    primary = AmberOnLight,
    onPrimary = Color.White,
    primaryContainer = AmberSubtle,
    onPrimaryContainer = Ink,
    background = Ivory,
    onBackground = Ink,
    surface = Pearl,
    onSurface = Ink,
    surfaceVariant = Platinum,
    onSurfaceVariant = Slate,
    outline = Platinum,
    error = Ruby,
    onError = Color.White,
)

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Extended Theme Properties
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

data class TorveColors(
    val accent: Color = Amber,
    val accentSubtle: Color = AmberSubtle,
    val textPrimary: Color = Snow,
    val textSecondary: Color = Silver,
    val textTertiary: Color = Ash,
    val textHint: Color = Smoke,
    val cardBackground: Color = Charcoal,
    val elevatedSurface: Color = Graphite,
    val inputBackground: Color = Gunmetal,
    val border: Color = Steel,
    val success: Color = Emerald,
    val error: Color = Ruby,
    val info: Color = Sapphire,
    val live: Color = Coral,
    val badge4K: Color = Badge4K,
    val badge1080p: Color = Badge1080p,
    val badge720p: Color = Badge720p,
    val badgeHDR: Color = BadgeHDR,
    val badgeDV: Color = BadgeDV,
)

val LocalTorveColors = staticCompositionLocalOf { TorveColors() }

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Theme Composable
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

@Composable
fun TorveTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val isDark = true

    val colorScheme = if (isDark) DarkColorScheme else LightColorScheme

    val extendedColors = if (isDark) {
        TorveColors()
    } else {
        TorveColors(
            accent = AmberOnLight,
            accentSubtle = AmberSubtle,
            textPrimary = Ink,
            textSecondary = Slate,
            textTertiary = Slate.copy(alpha = 0.6f),
            textHint = Slate.copy(alpha = 0.4f),
            cardBackground = Pearl,
            elevatedSurface = Platinum,
            inputBackground = Platinum,
            border = Platinum,
        )
    }

    // Keep system bar icon contrast aligned with the active app theme.
    // Transparent system bar colors are provided by the theme; startup edge-to-edge
    // is configured by the app's own window helper.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(window, view)
            // isAppearanceLightStatusBars = true → dark icons (for light backgrounds)
            // isAppearanceLightStatusBars = false → light icons (for dark backgrounds)
            insetsController.isAppearanceLightStatusBars = !isDark
            insetsController.isAppearanceLightNavigationBars = !isDark
        }
    }

    CompositionLocalProvider(LocalTorveColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = TorveTypography,
            content = content,
        )
    }
}

// Easy access extension
object Torve {
    val colors: TorveColors
        @Composable get() = LocalTorveColors.current
}
