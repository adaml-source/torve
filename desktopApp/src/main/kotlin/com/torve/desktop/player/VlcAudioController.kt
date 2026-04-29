package com.torve.desktop.player

import uk.co.caprica.vlcj.player.base.MediaPlayer

/**
 * Audio track selection and delay adjustment for the VLC player.
 */
class VlcAudioController(private val mediaPlayerProvider: () -> MediaPlayer?) {

    companion object {
        const val DELAY_STEP_MS = 50L
        const val MAX_DELAY_MS = 10_000L
    }

    fun getTracks(): List<DesktopTrackInfo> {
        val mp = mediaPlayerProvider() ?: return emptyList()
        return VlcTrackModel.audioTracks(mp)
    }

    fun getSelectedTrackId(): Int = mediaPlayerProvider()?.audio()?.track() ?: -1

    fun selectTrack(id: Int) {
        mediaPlayerProvider()?.audio()?.setTrack(id)
    }

    /** Get audio delay in milliseconds. Positive = audio plays later. */
    fun getDelay(): Long {
        val microseconds = mediaPlayerProvider()?.audio()?.delay() ?: 0
        return microseconds / 1000
    }

    /** Set audio delay in milliseconds. */
    fun setDelay(delayMs: Long) {
        val clamped = delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        mediaPlayerProvider()?.audio()?.setDelay(clamped * 1000) // vlcj uses microseconds
    }

    fun increaseDelay(stepMs: Long = DELAY_STEP_MS) {
        setDelay(getDelay() + stepMs)
    }

    fun decreaseDelay(stepMs: Long = DELAY_STEP_MS) {
        setDelay(getDelay() - stepMs)
    }
}
