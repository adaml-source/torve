package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class CardStylePresetResolutionTest {

    @Test
    fun resolveCardStyle_usesSectionPresetWhenPresent() {
        val defaultStyle = CardStyle(
            appearance = CardAppearancePrefs(cornerRadiusDp = 8),
        )
        val sectionStyle = CardStyle(
            appearance = CardAppearancePrefs(cornerRadiusDp = 20),
        )
        val presets = listOf(
            CardStylePreset(presetId = "default", name = "Default", cardStyle = defaultStyle),
            CardStylePreset(presetId = "hero", name = "Hero", cardStyle = sectionStyle),
        )

        val result = resolveCardStyle(
            presets = presets,
            presetId = "hero",
            globalDefaultPresetId = "default",
        )

        assertEquals(20, result.appearance.cornerRadiusDp)
    }

    @Test
    fun resolveCardStyle_inheritsGlobalDefaultWhenSectionPresetMissing() {
        val defaultStyle = CardStyle(
            appearance = CardAppearancePrefs(cornerRadiusDp = 12),
        )
        val altStyle = CardStyle(
            appearance = CardAppearancePrefs(cornerRadiusDp = 4),
        )
        val presets = listOf(
            CardStylePreset(presetId = "default", name = "Default", cardStyle = defaultStyle),
            CardStylePreset(presetId = "alt", name = "Alt", cardStyle = altStyle),
        )

        val result = resolveCardStyle(
            presets = presets,
            presetId = null,
            globalDefaultPresetId = "default",
        )

        assertEquals(12, result.appearance.cornerRadiusDp)
    }

    @Test
    fun resolveCardStyle_fallsBackToFirstPresetWhenDefaultMissing() {
        val first = CardStyle(appearance = CardAppearancePrefs(cornerRadiusDp = 6))
        val second = CardStyle(appearance = CardAppearancePrefs(cornerRadiusDp = 18))
        val presets = listOf(
            CardStylePreset(presetId = "first", name = "First", cardStyle = first),
            CardStylePreset(presetId = "second", name = "Second", cardStyle = second),
        )

        val result = resolveCardStyle(
            presets = presets,
            presetId = null,
            globalDefaultPresetId = "missing",
        )

        assertEquals(6, result.appearance.cornerRadiusDp)
    }

    @Test
    fun resolveCardStyle_reflectsPresetEditsForSectionsUsingThatPreset() {
        val before = CardStyle(appearance = CardAppearancePrefs(cornerRadiusDp = 10))
        val after = CardStyle(appearance = CardAppearancePrefs(cornerRadiusDp = 22))
        val initialPresets = listOf(
            CardStylePreset(presetId = "default", name = "Default", cardStyle = CardStyle()),
            CardStylePreset(presetId = "cinema", name = "Cinema", cardStyle = before),
        )
        val updatedPresets = listOf(
            CardStylePreset(presetId = "default", name = "Default", cardStyle = CardStyle()),
            CardStylePreset(presetId = "cinema", name = "Cinema", cardStyle = after),
        )

        val beforeResult = resolveCardStyle(
            presets = initialPresets,
            presetId = "cinema",
            globalDefaultPresetId = "default",
        )
        val afterResult = resolveCardStyle(
            presets = updatedPresets,
            presetId = "cinema",
            globalDefaultPresetId = "default",
        )

        assertEquals(10, beforeResult.appearance.cornerRadiusDp)
        assertEquals(22, afterResult.appearance.cornerRadiusDp)
    }
}
