package com.torve.android.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Software 10-band parametric EQ implemented as an ExoPlayer AudioProcessor.
 * Uses cascaded biquad peaking-EQ filters at the standard octave frequencies:
 * 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000 Hz.
 *
 * This works regardless of audio passthrough, offload, or device hardware EQ support.
 */
@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    companion object {
        val BAND_FREQUENCIES = intArrayOf(31, 62, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        const val BAND_COUNT = 10
        private const val Q = 1.4 // bandwidth ~1 octave
    }

    @Volatile
    var enabled: Boolean = false

    // Gains in dB per band — index matches BAND_FREQUENCIES
    private val bandGainsDb = DoubleArray(BAND_COUNT) { 0.0 }

    // Biquad coefficients per band
    private val b0 = DoubleArray(BAND_COUNT)
    private val b1 = DoubleArray(BAND_COUNT)
    private val b2 = DoubleArray(BAND_COUNT)
    private val a1 = DoubleArray(BAND_COUNT)
    private val a2 = DoubleArray(BAND_COUNT)

    // Filter state per channel per band: [channel][band][0=x1, 1=x2, 2=y1, 3=y2]
    private var filterState: Array<Array<DoubleArray>> = emptyArray()
    private var sampleRate: Int = 44100
    private var channelCount: Int = 2
    private var needsRecalc: Boolean = true

    fun setBandGainDb(band: Int, gainDb: Float) {
        if (band < 0 || band >= BAND_COUNT) return
        bandGainsDb[band] = gainDb.toDouble().coerceIn(-15.0, 15.0)
        needsRecalc = true
    }

    fun getBandGainDb(band: Int): Float {
        if (band < 0 || band >= BAND_COUNT) return 0f
        return bandGainsDb[band].toFloat()
    }

    fun setAllBandGains(gainsDb: FloatArray) {
        for (i in 0 until minOf(gainsDb.size, BAND_COUNT)) {
            bandGainsDb[i] = gainsDb[i].toDouble().coerceIn(-15.0, 15.0)
        }
        needsRecalc = true
    }

    fun resetAllBands() {
        bandGainsDb.fill(0.0)
        needsRecalc = true
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        allocateState()
        recalcCoefficients()
        return inputAudioFormat
    }

    private fun allocateState() {
        filterState = Array(channelCount) {
            Array(BAND_COUNT) { DoubleArray(4) } // x1, x2, y1, y2
        }
    }

    private fun recalcCoefficients() {
        for (band in 0 until BAND_COUNT) {
            computePeakingEQ(band, BAND_FREQUENCIES[band].toDouble(), bandGainsDb[band], Q)
        }
        needsRecalc = false
    }

    /**
     * Peaking EQ biquad filter design (Audio EQ Cookbook by Robert Bristow-Johnson).
     */
    private fun computePeakingEQ(band: Int, freq: Double, gainDb: Double, q: Double) {
        if (gainDb == 0.0) {
            // Unity pass-through for this band
            b0[band] = 1.0; b1[band] = 0.0; b2[band] = 0.0
            a1[band] = 0.0; a2[band] = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0) // sqrt of linear gain
        val w0 = 2.0 * PI * freq / sampleRate
        val sinW0 = sin(w0)
        val cosW0 = cos(w0)
        val alpha = sinW0 / (2.0 * q)

        val b0v = 1.0 + alpha * a
        val b1v = -2.0 * cosW0
        val b2v = 1.0 - alpha * a
        val a0v = 1.0 + alpha / a
        val a1v = -2.0 * cosW0
        val a2v = 1.0 - alpha / a

        // Normalize
        b0[band] = b0v / a0v
        b1[band] = b1v / a0v
        b2[band] = b2v / a0v
        a1[band] = a1v / a0v
        a2[band] = a2v / a0v
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return

        val output = replaceOutputBuffer(size)
        output.order(ByteOrder.nativeOrder())

        if (!enabled) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (needsRecalc) recalcCoefficients()

        // Process 16-bit PCM interleaved
        val sampleCount = size / 2
        val frameCount = sampleCount / channelCount

        for (frame in 0 until frameCount) {
            for (ch in 0 until channelCount) {
                val raw = inputBuffer.short.toDouble() / 32768.0
                var sample = raw

                val state = if (ch < filterState.size) filterState[ch] else continue

                // Cascade all 10 biquad filters
                for (band in 0 until BAND_COUNT) {
                    if (bandGainsDb[band] == 0.0) continue // skip unity bands

                    val s = state[band]
                    val x0 = sample
                    val y0 = b0[band] * x0 + b1[band] * s[0] + b2[band] * s[1] -
                            a1[band] * s[2] - a2[band] * s[3]

                    s[1] = s[0] // x2 = x1
                    s[0] = x0   // x1 = x0
                    s[3] = s[2] // y2 = y1
                    s[2] = y0   // y1 = y0

                    sample = y0
                }

                // Soft-clip to prevent harsh digital distortion
                val clipped = if (sample > 1.0) 1.0
                else if (sample < -1.0) -1.0
                else sample

                output.putShort((clipped * 32767.0).toInt().coerceIn(-32768, 32767).toShort())
            }
        }

        output.flip()
    }

    override fun onFlush() {
        for (ch in filterState) {
            for (band in ch) {
                band.fill(0.0)
            }
        }
    }

    override fun onReset() {
        filterState = emptyArray()
        needsRecalc = true
    }
}
