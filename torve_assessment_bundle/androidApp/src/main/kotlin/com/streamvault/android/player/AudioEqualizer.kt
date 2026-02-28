package com.streamvault.android.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer

/**
 * Wraps Android AudioEffect APIs to provide a VLC-style 10-band equalizer
 * with bass boost, virtualizer, and presets.
 */
class AudioEqualizer(audioSessionId: Int) {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    val bandCount: Int
    val bandFrequencies: List<Int> // center freq in mHz
    val minLevel: Int // millibels
    val maxLevel: Int // millibels

    private var _enabled: Boolean = false
    val enabled: Boolean get() = _enabled

    private var _bandLevels: IntArray
    val bandLevels: IntArray get() = _bandLevels.copyOf()

    private var _bassBoostStrength: Int = 0
    val bassBoostStrength: Int get() = _bassBoostStrength

    private var _virtualizerStrength: Int = 0
    val virtualizerStrength: Int get() = _virtualizerStrength

    private var _loudnessGain: Int = 0
    val loudnessGain: Int get() = _loudnessGain

    init {
        val eq = try {
            Equalizer(0, audioSessionId).also { it.enabled = false }
        } catch (_: Exception) {
            null
        }
        equalizer = eq

        bandCount = eq?.numberOfBands?.toInt() ?: 0
        val freqs = mutableListOf<Int>()
        for (i in 0 until bandCount) {
            freqs.add(eq?.getCenterFreq(i.toShort()) ?: 0)
        }
        bandFrequencies = freqs

        val range = eq?.bandLevelRange
        minLevel = range?.get(0)?.toInt() ?: -1500
        maxLevel = range?.get(1)?.toInt() ?: 1500

        _bandLevels = IntArray(bandCount) { 0 }

        bassBoost = try {
            BassBoost(0, audioSessionId).also { it.enabled = false }
        } catch (_: Exception) {
            null
        }

        virtualizer = try {
            Virtualizer(0, audioSessionId).also { it.enabled = false }
        } catch (_: Exception) {
            null
        }

        loudnessEnhancer = try {
            LoudnessEnhancer(audioSessionId).also { it.enabled = false }
        } catch (_: Exception) {
            null
        }
    }

    fun setEnabled(on: Boolean) {
        _enabled = on
        equalizer?.enabled = on
        bassBoost?.enabled = on && _bassBoostStrength > 0
        virtualizer?.enabled = on && _virtualizerStrength > 0
        loudnessEnhancer?.enabled = on && _loudnessGain > 0
    }

    fun setBandLevel(band: Int, level: Int) {
        if (band < 0 || band >= bandCount) return
        val clamped = level.coerceIn(minLevel, maxLevel)
        _bandLevels[band] = clamped
        try {
            equalizer?.setBandLevel(band.toShort(), clamped.toShort())
        } catch (_: Exception) { }
    }

    fun setBassBoostStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _bassBoostStrength = clamped
        try {
            bassBoost?.setStrength(clamped.toShort())
            bassBoost?.enabled = _enabled && clamped > 0
        } catch (_: Exception) { }
    }

    fun setVirtualizerStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _virtualizerStrength = clamped
        try {
            virtualizer?.setStrength(clamped.toShort())
            virtualizer?.enabled = _enabled && clamped > 0
        } catch (_: Exception) { }
    }

    fun setLoudnessGain(gainMb: Int) {
        val clamped = gainMb.coerceIn(0, 1000)
        _loudnessGain = clamped
        try {
            loudnessEnhancer?.setTargetGain(clamped)
            loudnessEnhancer?.enabled = _enabled && clamped > 0
        } catch (_: Exception) { }
    }

    fun applyPreset(preset: EqPreset) {
        if (preset == EqPreset.FLAT) {
            for (i in 0 until bandCount) setBandLevel(i, 0)
            setBassBoostStrength(0)
            setVirtualizerStrength(0)
            setLoudnessGain(0)
            return
        }

        val gains = preset.gains
        for (i in gains.indices) {
            if (i < bandCount) {
                // Convert dB-ish values to millibels (x100)
                setBandLevel(i, gains[i] * 100)
            }
        }
        setBassBoostStrength(preset.bassBoost)
        setVirtualizerStrength(preset.virtualizer)
        setLoudnessGain(preset.loudness)
    }

    fun release() {
        equalizer?.release()
        bassBoost?.release()
        virtualizer?.release()
        loudnessEnhancer?.release()
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
    }

    /** Serialize current state for persistence. */
    fun toStateString(): String {
        val levels = _bandLevels.joinToString(",")
        return "$_enabled|$levels|$_bassBoostStrength|$_virtualizerStrength|$_loudnessGain"
    }

    /** Restore state from a previously serialized string. */
    fun restoreFromState(state: String) {
        try {
            val parts = state.split("|")
            if (parts.size < 5) return
            val on = parts[0].toBooleanStrictOrNull() ?: false
            val levels = parts[1].split(",").map { it.toInt() }
            val bass = parts[2].toInt()
            val virt = parts[3].toInt()
            val loud = parts[4].toInt()

            for (i in levels.indices) {
                if (i < bandCount) setBandLevel(i, levels[i])
            }
            setBassBoostStrength(bass)
            setVirtualizerStrength(virt)
            setLoudnessGain(loud)
            setEnabled(on)
        } catch (_: Exception) { }
    }
}

/**
 * VLC-style EQ presets.
 * Gains are in dB (will be multiplied by 100 for millibels).
 * Values tuned for 10-band EQ: 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz.
 * If the device has fewer bands, extra values are ignored.
 */
enum class EqPreset(
    val label: String,
    val gains: IntArray,
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val loudness: Int = 0,
) {
    FLAT("Flat", intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
    ROCK("Rock", intArrayOf(5, 4, -5, -8, -3, 4, 8, 11, 11, 11)),
    POP("Pop", intArrayOf(-1, 4, 7, 8, 5, 0, -2, -2, -1, -1)),
    JAZZ("Jazz", intArrayOf(4, 3, 1, 2, -1, -1, 0, 1, 3, 4)),
    CLASSICAL("Classical", intArrayOf(5, 3, 0, 0, 0, 0, 0, -3, -3, -5)),
    BASS("Bass Boost", intArrayOf(10, 8, 5, 1, 0, 0, 0, 0, 0, 0), bassBoost = 600),
    TREBLE("Treble Boost", intArrayOf(0, 0, 0, 0, 0, 1, 3, 6, 9, 11)),
    VOICE("Voice", intArrayOf(-3, -1, 0, 3, 6, 6, 5, 3, 0, -2)),
    LOUDNESS("Loudness", intArrayOf(6, 4, 0, 0, -2, 0, -1, -5, 5, 2), loudness = 500),
    HEADPHONES("Headphones", intArrayOf(4, 3, 0, -3, -2, 1, 4, 6, 8, 9), virtualizer = 500),
    LATE_NIGHT("Late Night", intArrayOf(3, 3, 2, 0, -2, -3, -2, 0, 2, 3), loudness = 300),
}
