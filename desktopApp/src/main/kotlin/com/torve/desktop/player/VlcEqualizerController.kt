package com.torve.desktop.player

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.Equalizer
import uk.co.caprica.vlcj.player.base.MediaPlayer
import kotlin.math.roundToInt

class VlcEqualizerController(
    private val factoryProvider: () -> MediaPlayerFactory?,
    private val mediaPlayerProvider: () -> MediaPlayer?,
) {
    companion object {
        const val MIN_LEVEL = -20f
        const val MAX_LEVEL = 20f
    }

    private var enabled = false
    private var selectedPreset: String? = null
    private var equalizer: Equalizer? = null

    fun snapshot(): DesktopEqualizerSnapshot {
        val factory = factoryProvider()
        val presetNames = factory?.equalizer()?.presets().orEmpty()
        val bandLabels = factory?.equalizer()?.bands().orEmpty().map(::formatBandLabel)
        val currentEqualizer = equalizer
        val bands = bandLabels.mapIndexed { index, label ->
            DesktopEqualizerBand(
                index = index,
                label = label,
                level = currentEqualizer?.amp(index) ?: 0f,
            )
        }
        return DesktopEqualizerSnapshot(
            available = factory != null,
            enabled = enabled,
            presetName = selectedPreset,
            presets = presetNames,
            preamp = currentEqualizer?.preamp() ?: 0f,
            minLevel = MIN_LEVEL,
            maxLevel = MAX_LEVEL,
            bands = bands,
        )
    }

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        applyToPlayer()
    }

    fun applyPreset(presetName: String) {
        val factory = factoryProvider() ?: return
        equalizer = factory.equalizer().newEqualizer(presetName)
        selectedPreset = presetName
        if (enabled) {
            applyToPlayer()
        }
    }

    fun setPreamp(level: Float) {
        val currentEqualizer = ensureEqualizer() ?: return
        currentEqualizer.setPreamp(level.coerceIn(MIN_LEVEL, MAX_LEVEL))
        selectedPreset = null
        if (enabled) {
            applyToPlayer()
        }
    }

    fun setBandLevel(index: Int, level: Float) {
        val currentEqualizer = ensureEqualizer() ?: return
        if (index !in 0 until currentEqualizer.bandCount()) return
        currentEqualizer.setAmp(index, level.coerceIn(MIN_LEVEL, MAX_LEVEL))
        selectedPreset = null
        if (enabled) {
            applyToPlayer()
        }
    }

    fun reset() {
        equalizer = factoryProvider()?.equalizer()?.newEqualizer()
        selectedPreset = null
        if (enabled) {
            applyToPlayer()
        }
    }

    private fun ensureEqualizer(): Equalizer? {
        val factory = factoryProvider() ?: return null
        val current = equalizer
        if (current != null) return current
        return factory.equalizer().newEqualizer().also { equalizer = it }
    }

    private fun applyToPlayer() {
        mediaPlayerProvider()?.audio()?.setEqualizer(if (enabled) ensureEqualizer() else null)
    }

    private fun formatBandLabel(frequency: Float): String {
        return if (frequency >= 1000f) {
            "${((frequency / 100f).roundToInt()) / 10f} kHz"
        } else {
            "${frequency.roundToInt()} Hz"
        }
    }
}
