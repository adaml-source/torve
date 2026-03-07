package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class CardSizePreset(val label: String, val widthDp: Int) {
    XS("XS", 80),
    S("S", 110),
    M("M", 140),
    L("L", 170),
    XL("XL", 210),
    CUSTOM("Custom", 0),
}

@Serializable
enum class CardOrientation {
    PORTRAIT,
    LANDSCAPE,
    SQUARE,
}

@Serializable
data class CardSizePrefs(
    val preset: CardSizePreset = CardSizePreset.M,
    val orientation: CardOrientation = CardOrientation.PORTRAIT,
    val customWidthDp: Int = 140,
)

fun CardSizePrefs.resolvedWidthDp(): Int =
    if (preset == CardSizePreset.CUSTOM) customWidthDp else preset.widthDp

fun CardSizePrefs.resolvedAspectRatio(): Float = when (orientation) {
    CardOrientation.PORTRAIT -> 2f / 3f
    CardOrientation.LANDSCAPE -> 16f / 9f
    CardOrientation.SQUARE -> 1f
}

@Serializable
data class CardHoverPrefs(
    val enabled: Boolean = true,
    val scalePercent: Int = 115,
    val elevationOnHover: Boolean = true,
    val borderOnHover: Boolean = true,
    val animationDurationMs: Int = 200,
    val dimOtherCards: Boolean = false,
)

@Serializable
enum class WatchedIndicatorStyle(val label: String) {
    CHECKMARK_BADGE("Badge"),
    CHECKMARK_OVERLAY("Overlay"),
    EYE_ICON("Eye"),
    BANNER("Banner"),
    BORDER("Border"),
    DOT("Dot"),
    NONE("None"),
}

@Serializable
data class WatchedIndicatorPrefs(
    val enabled: Boolean = true,
    val style: WatchedIndicatorStyle = WatchedIndicatorStyle.CHECKMARK_BADGE,
    val dimWatched: Boolean = false,
    val dimAmount: Float = 0.5f,
    val progressBarForPartial: Boolean = true,
    val rewatchBadge: Boolean = true,
)

@Serializable
enum class CardTitlePosition {
    BELOW,
    OVERLAY_BOTTOM,
    HIDDEN,
}

@Serializable
enum class CardScrollAnimation {
    NONE,
    FADE_IN,
    SLIDE_UP,
    SCALE_IN,
}

@Serializable
enum class ShelfTitleStyle {
    DEFAULT,
    MINIMAL,
    BOLD,
}

@Serializable
data class CardAppearancePrefs(
    val cornerRadiusDp: Int = 8,
    val cardSpacingDp: Int = 12,
    val cardElevationDp: Int = 2,
    val showBottomGradient: Boolean = true,
    val titlePosition: CardTitlePosition = CardTitlePosition.BELOW,
    val showYear: Boolean = true,
    val showBorder: Boolean = false,
    val showGenreTags: Boolean = false,
    val showTypeBadge: Boolean = false,
    val showRuntime: Boolean = false,
    val scrollAnimation: CardScrollAnimation = CardScrollAnimation.NONE,
)

@Serializable
data class CardPrefs(
    val size: CardSizePrefs = CardSizePrefs(),
    val hover: CardHoverPrefs = CardHoverPrefs(),
    val watched: WatchedIndicatorPrefs = WatchedIndicatorPrefs(),
    val appearance: CardAppearancePrefs = CardAppearancePrefs(),
)

data class WatchState(
    val isStarted: Boolean = false,
    val isCompleted: Boolean = false,
    val progressPercent: Float = 0f,
    val rewatchCount: Int = 0,
)
