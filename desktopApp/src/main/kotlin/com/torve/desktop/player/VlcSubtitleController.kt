package com.torve.desktop.player

import uk.co.caprica.vlcj.player.base.MediaPlayer

/**
 * Subtitle track selection, delay adjustment, and enable/disable controls.
 */
class VlcSubtitleController(private val mediaPlayerProvider: () -> MediaPlayer?) {

    companion object {
        const val DELAY_STEP_MS = 50L
        const val MAX_DELAY_MS = 10_000L
    }

    fun getTracks(): List<DesktopTrackInfo> {
        val mp = mediaPlayerProvider() ?: return emptyList()
        return VlcTrackModel.subtitleTracks(mp)
    }

    fun getSelectedTrackId(): Int = mediaPlayerProvider()?.subpictures()?.track() ?: -1

    fun selectTrack(id: Int) {
        mediaPlayerProvider()?.subpictures()?.setTrack(id)
    }

    fun disable() {
        mediaPlayerProvider()?.subpictures()?.setTrack(-1)
    }

    fun isEnabled(): Boolean = getSelectedTrackId() != -1

    /** Load an external subtitle file/URL. */
    fun addSubtitleFile(uri: String): Boolean {
        return mediaPlayerProvider()?.subpictures()?.setSubTitleFile(uri) ?: false
    }

    /** Get subtitle delay in milliseconds. Positive = subtitles appear later. */
    fun getDelay(): Long {
        val microseconds = mediaPlayerProvider()?.subpictures()?.delay() ?: 0
        return microseconds / 1000
    }

    /** Set subtitle delay in milliseconds. */
    fun setDelay(delayMs: Long) {
        val clamped = delayMs.coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)
        mediaPlayerProvider()?.subpictures()?.setDelay(clamped * 1000) // vlcj uses microseconds
    }

    fun increaseDelay(stepMs: Long = DELAY_STEP_MS) {
        setDelay(getDelay() + stepMs)
    }

    fun decreaseDelay(stepMs: Long = DELAY_STEP_MS) {
        setDelay(getDelay() - stepMs)
    }
}
