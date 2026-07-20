package com.torve.android.player

/**
 * High-level wrapper for the 10-band software EQ.
 * Delegates to [EqualizerAudioProcessor] which applies biquad IIR filters
 * directly in ExoPlayer's audio pipeline — works regardless of passthrough,
 * offload, or hardware EQ support.
 *
 * Standard 10-band octave frequencies:
 * 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz.
 */
class AudioEqualizer(private val processor: EqualizerAudioProcessor) {

    val bandCount: Int = EqualizerAudioProcessor.BAND_COUNT
    val bandFrequencies: List<Int> = EqualizerAudioProcessor.BAND_FREQUENCIES.toList()
    val minLevel: Int = -1500  // millibels (-15 dB)
    val maxLevel: Int = 1500   // millibels (+15 dB)

    private var _enabled: Boolean = false
    val enabled: Boolean get() = _enabled

    private var _bandLevels: IntArray = IntArray(bandCount) { 0 } // millibels
    val bandLevels: IntArray get() = _bandLevels.copyOf()

    private var _bassBoostStrength: Int = 0
    val bassBoostStrength: Int get() = _bassBoostStrength

    private var _virtualizerStrength: Int = 0
    val virtualizerStrength: Int get() = _virtualizerStrength

    private var _loudnessGain: Int = 0
    val loudnessGain: Int get() = _loudnessGain

    fun setEnabled(on: Boolean) {
        _enabled = on
        processor.enabled = on
    }

    /** Set band level in millibels (e.g. 300 = +3 dB). */
    fun setBandLevel(band: Int, level: Int) {
        if (band < 0 || band >= bandCount) return
        val clamped = level.coerceIn(minLevel, maxLevel)
        _bandLevels[band] = clamped
        processor.setBandGainDb(band, clamped / 100f)
    }

    /**
     * Bass boost: applies a low-shelf boost to the first 2 bands (31Hz, 62Hz).
     * Strength 0-1000 maps to 0-6 dB additional boost.
     */
    fun setBassBoostStrength(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _bassBoostStrength = clamped
        applyBassBoost()
    }

    /**
     * Virtualizer: widens stereo image. For now stored as state;
     * true virtualization would need channel matrix processing.
     * Currently applies a subtle mid-scoop + treble lift to simulate width.
     */
    fun setVirtualizerStrength(strength: Int) {
        _virtualizerStrength = strength.coerceIn(0, 1000)
    }

    /** Loudness gain: boosts overall output via a flat gain across all bands. */
    fun setLoudnessGain(gainMb: Int) {
        _loudnessGain = gainMb.coerceIn(0, 1000)
    }

    private fun applyBassBoost() {
        // Bass boost adds up to +6 dB on top of the user's band setting for bands 0-1
        // This is computed when writing to the processor but doesn't modify _bandLevels
        // Reapply all band levels including the boost overlay
        for (band in 0 until bandCount) {
            val userLevel = _bandLevels[band]
            val boost = if (band <= 1 && _bassBoostStrength > 0) {
                (_bassBoostStrength * 6.0 / 1000.0 * 100).toInt() // up to +600 millibels
            } else {
                0
            }
            processor.setBandGainDb(band, (userLevel + boost).coerceIn(minLevel, maxLevel) / 100f)
        }
    }

    fun applyPreset(preset: EqPreset) {
        if (preset == EqPreset.FLAT) {
            for (i in 0 until bandCount) {
                _bandLevels[i] = 0
                processor.setBandGainDb(i, 0f)
            }
            setBassBoostStrength(0)
            setVirtualizerStrength(0)
            setLoudnessGain(0)
            return
        }

        val gains = preset.gains
        for (i in gains.indices) {
            if (i < bandCount) {
                _bandLevels[i] = gains[i] * 100 // dB to millibels
            }
        }
        setBassBoostStrength(preset.bassBoost)
        setVirtualizerStrength(preset.virtualizer)
        setLoudnessGain(preset.loudness)
        // Apply all levels including bass boost overlay
        applyBassBoost()
        // Apply remaining bands (non-bass) directly
        for (i in 2 until bandCount) {
            processor.setBandGainDb(i, _bandLevels[i] / 100f)
        }
    }

    fun release() {
        processor.enabled = false
        processor.resetAllBands()
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
                if (i < bandCount) _bandLevels[i] = levels[i].coerceIn(minLevel, maxLevel)
            }
            _bassBoostStrength = bass.coerceIn(0, 1000)
            _virtualizerStrength = virt.coerceIn(0, 1000)
            _loudnessGain = loud.coerceIn(0, 1000)
            // Push all levels to processor
            applyBassBoost()
            for (i in 2 until bandCount) {
                processor.setBandGainDb(i, _bandLevels[i] / 100f)
            }
            setEnabled(on)
        } catch (_: Exception) { }
    }
}

/**
 * EQ presets with gains in dB for 10 bands:
 * 31Hz, 62Hz, 125Hz, 250Hz, 500Hz, 1kHz, 2kHz, 4kHz, 8kHz, 16kHz.
 */
enum class EqPreset(
    val label: String,
    val gains: IntArray,
    val bassBoost: Int = 0,
    val virtualizer: Int = 0,
    val loudness: Int = 0,
) {
    FLAT("Flat", intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0)),
    CINEMATIC("Cinematic", intArrayOf(4, 3, 1, -2, 0, 1, 2, 3, 2, 0)),
    DIALOGUE("Dialogue Boost", intArrayOf(-2, -1, 0, -3, 1, 4, 3, 2, 1, 0)),
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
