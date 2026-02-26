package com.streamvault.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CardStyle(
    val size: CardSizePrefs = CardSizePrefs(),
    val hover: CardHoverPrefs = CardHoverPrefs(),
    val watched: WatchedIndicatorPrefs = WatchedIndicatorPrefs(),
    val appearance: CardAppearancePrefs = CardAppearancePrefs(),
    val ratingPrefs: RatingDisplayPrefs = RatingDisplayPrefs(),
)

@Serializable
data class CardStylePreset(
    val presetId: String,
    val name: String,
    val cardStyle: CardStyle,
    val isBuiltIn: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

fun resolveCardStyle(
    presets: List<CardStylePreset>,
    presetId: String?,
    globalDefaultPresetId: String?,
): CardStyle {
    if (presets.isEmpty()) return CardStyle()
    val byId = presets.associateBy { it.presetId }
    val explicitId = presetId?.takeIf { byId.containsKey(it) }
    val resolvedId = explicitId ?: globalDefaultPresetId
    return byId[resolvedId]?.cardStyle ?: presets.first().cardStyle
}
