package com.torve.android.ui.theme

import androidx.compose.ui.graphics.Color

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Torve Design System — "Cinematic Dark"
// A deep, immersive dark theme inspired by movie theater
// experiences. Rich blacks with warm amber accents that
// feel premium without copying any specific streaming app.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

// ── Core Palette ──
// Deep blacks for backgrounds — not pure black (#000) which
// feels cheap on OLED. These have subtle warm undertones.
val Obsidian = Color(0xFF0A0A0F)       // Deepest background
val Charcoal = Color(0xFF12121A)       // Card/surface background
val Graphite = Color(0xFF1A1A25)       // Elevated surfaces
val Gunmetal = Color(0xFF242432)       // Input fields, chips
val Steel = Color(0xFF2E2E40)          // Borders, dividers
val Pewter = Color(0xFF3D3D54)         // Subtle interactive elements

// ── Text Colors ──
val Snow = Color(0xFFF5F5F7)           // Primary text (not pure white — easier on eyes)
val Silver = Color(0xFFB8B8C7)         // Secondary text
val Ash = Color(0xFF7C7C92)            // Tertiary/disabled text
val Smoke = Color(0xFF52526A)          // Hint text, timestamps

// ── Accent: Amber Gold ──
// Warm amber that feels premium and cinematic.
// Used for CTAs, highlights, selected states.
val Amber = Color(0xFFE8A838)          // Primary accent
val AmberLight = Color(0xFFF0C060)     // Hover/pressed state
val AmberDark = Color(0xFFBF8A28)      // Darker variant
val AmberSubtle = Color(0x33E8A838)    // 20% opacity — backgrounds
val AmberGlow = Color(0x1AE8A838)      // 10% opacity — very subtle

// ── Semantic Colors ──
val Emerald = Color(0xFF34D399)        // Success, cached, online
val Ruby = Color(0xFFEF4444)           // Error, delete, live badge
val Sapphire = Color(0xFF60A5FA)       // Links, info, 720p badge
val Amethyst = Color(0xFFA78BFA)       // 4K badge, premium features
val Coral = Color(0xFFFF6B6B)          // Live indicator dot

// ── Quality Badge Colors ──
val Badge4K = Color(0xFFD4AF37)        // Gold
val Badge1080p = Color(0xFF34D399)     // Emerald
val Badge720p = Color(0xFF60A5FA)      // Sapphire
val BadgeSD = Color(0xFF7C7C92)        // Ash
val BadgeHDR = Color(0xFFE8A838)       // Amber
val BadgeDV = Color(0xFFA78BFA)        // Amethyst (Dolby Vision)

// ── Gradients (defined as lists for Brush usage) ──
// Hero overlay gradient — cinematic bottom fade
val HeroGradient = listOf(
    Color.Transparent,
    Color.Transparent,
    Obsidian.copy(alpha = 0.3f),
    Obsidian.copy(alpha = 0.7f),
    Obsidian.copy(alpha = 0.95f),
    Obsidian,
)

// Card hover/focus gradient
val CardGradient = listOf(
    Color.Transparent,
    Obsidian.copy(alpha = 0.85f),
)

// Scrim for overlays
val ScrimGradient = listOf(
    Color.Black.copy(alpha = 0.0f),
    Color.Black.copy(alpha = 0.6f),
)

// ── Light Theme Palette ──
// Minimal light theme — most users will use dark for a media app,
// but we support it for accessibility.
val Ivory = Color(0xFFFAFAFC)
val Pearl = Color(0xFFF0F0F5)
val Platinum = Color(0xFFE8E8F0)
val Slate = Color(0xFF6B6B80)
val Ink = Color(0xFF1A1A2E)
val AmberOnLight = Color(0xFFBF8A28)
