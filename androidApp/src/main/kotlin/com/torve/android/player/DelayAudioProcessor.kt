package com.torve.android.player

import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * AudioProcessor that delays audio output by a configurable number of milliseconds.
 * Positive delay = audio plays later (use when audio is ahead of video).
 * Negative delay = audio plays earlier (use when audio is behind video).
 *
 * Works by maintaining a circular buffer of PCM samples.
 */
@UnstableApi
class DelayAudioProcessor : BaseAudioProcessor() {

    @Volatile
    private var targetDelayMs: Int = 0
    private var activeDelayMs: Int = 0
    private var bytesPerMs: Int = 0

    // Circular buffer
    private var ringBuffer: ByteArray = ByteArray(0)
    private var ringSize: Int = 0
    private var writePos: Int = 0
    private var filled: Int = 0

    fun setDelayMs(ms: Int) {
        targetDelayMs = ms.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
    }

    fun getDelayMs(): Int = targetDelayMs

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat,
    ): AudioProcessor.AudioFormat {
        val sampleRate = inputAudioFormat.sampleRate
        val channelCount = inputAudioFormat.channelCount
        val bytesPerSample = 2 // 16-bit PCM

        bytesPerMs = (sampleRate * channelCount * bytesPerSample) / 1000
        reallocateBuffer()
        return inputAudioFormat
    }

    private fun reallocateBuffer() {
        val delayMs = abs(targetDelayMs)
        activeDelayMs = targetDelayMs
        val needed = delayMs * bytesPerMs
        if (needed <= 0) {
            ringBuffer = ByteArray(0)
            ringSize = 0
            writePos = 0
            filled = 0
            return
        }
        ringSize = needed
        ringBuffer = ByteArray(ringSize)
        writePos = 0
        filled = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        // Check if delay changed
        if (targetDelayMs != activeDelayMs) {
            reallocateBuffer()
        }

        val inputSize = inputBuffer.remaining()
        if (inputSize == 0) return

        // No delay — pass through
        if (ringSize <= 0) {
            val output = replaceOutputBuffer(inputSize)
            output.put(inputBuffer)
            output.flip()
            return
        }

        val inputBytes = ByteArray(inputSize)
        inputBuffer.get(inputBytes)

        val output = replaceOutputBuffer(inputSize)
        output.order(ByteOrder.nativeOrder())

        if (activeDelayMs > 0) {
            processPositiveDelay(inputBytes, output)
        } else {
            processNegativeDelay(inputBytes, output)
        }

        output.flip()
    }

    /**
     * Positive delay: buffer incoming audio, output delayed audio.
     * Before buffer is full, output silence.
     */
    private fun processPositiveDelay(input: ByteArray, output: ByteBuffer) {
        var inPos = 0
        var remaining = input.size

        while (remaining > 0) {
            val chunk = minOf(remaining, ringSize)

            if (filled >= ringSize) {
                // Buffer is full — read old data first (this is the delayed output)
                val readPos = writePos
                var toRead = chunk
                var readIdx = readPos
                while (toRead > 0) {
                    val readable = minOf(toRead, ringSize - readIdx)
                    output.put(ringBuffer, readIdx, readable)
                    toRead -= readable
                    readIdx = (readIdx + readable) % ringSize
                }
            } else {
                // Buffer not yet full — output silence
                val silence = minOf(chunk, ringSize - filled)
                for (i in 0 until silence) {
                    output.put(0)
                }
                if (chunk > silence) {
                    // Part of this chunk can now be read from ring
                    val readPos = writePos
                    var toRead = chunk - silence
                    var readIdx = readPos
                    while (toRead > 0) {
                        val readable = minOf(toRead, ringSize - readIdx)
                        output.put(ringBuffer, readIdx, readable)
                        toRead -= readable
                        readIdx = (readIdx + readable) % ringSize
                    }
                }
            }

            // Write new data into ring buffer
            var toWrite = chunk
            var wIdx = writePos
            var srcIdx = inPos
            while (toWrite > 0) {
                val writable = minOf(toWrite, ringSize - wIdx)
                System.arraycopy(input, srcIdx, ringBuffer, wIdx, writable)
                toWrite -= writable
                srcIdx += writable
                wIdx = (wIdx + writable) % ringSize
            }
            writePos = wIdx
            filled = minOf(filled + chunk, ringSize)
            inPos += chunk
            remaining -= chunk
        }
    }

    /**
     * Negative delay: skip initial audio samples to make audio play earlier.
     * We accumulate [ringSize] bytes and then start outputting, effectively
     * advancing the audio timeline.
     */
    private fun processNegativeDelay(input: ByteArray, output: ByteBuffer) {
        if (filled < ringSize) {
            // Still absorbing initial samples to skip
            val canAbsorb = minOf(input.size, ringSize - filled)
            filled += canAbsorb
            if (canAbsorb < input.size) {
                // Output the rest after absorbing
                output.put(input, canAbsorb, input.size - canAbsorb)
            } else {
                // All absorbed — output silence to maintain timing
                for (i in 0 until input.size) {
                    output.put(0)
                }
            }
        } else {
            // Already skipped enough — pass through
            output.put(input)
        }
    }

    override fun onFlush() {
        writePos = 0
        filled = 0
        if (ringSize > 0) {
            ringBuffer.fill(0)
        }
    }

    override fun onReset() {
        ringBuffer = ByteArray(0)
        ringSize = 0
        writePos = 0
        filled = 0
        activeDelayMs = 0
    }

    companion object {
        const val MAX_DELAY_MS = 2000
    }
}
