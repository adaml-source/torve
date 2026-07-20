package com.torve.desktop.player

import uk.co.caprica.vlcj.player.base.MediaPlayer

/**
 * Volume and mute policy for the VLC player.
 * Volume range: 0-200 (VLC allows up to 200% amplification).
 * Default: 100 (100%).
 */
class VlcVolumeController(private val mediaPlayerProvider: () -> MediaPlayer?) {

    companion object {
        const val MIN_VOLUME = 0
        const val MAX_VOLUME = 200
        const val DEFAULT_VOLUME = 100
        const val VOLUME_STEP = 5
    }

    private var lastVolume: Int = DEFAULT_VOLUME

    fun getVolume(): Int = mediaPlayerProvider()?.audio()?.volume() ?: lastVolume

    fun setVolume(volume: Int) {
        val clamped = volume.coerceIn(MIN_VOLUME, MAX_VOLUME)
        mediaPlayerProvider()?.audio()?.setVolume(clamped)
        lastVolume = clamped
    }

    fun increaseVolume(step: Int = VOLUME_STEP) {
        setVolume(getVolume() + step)
    }

    fun decreaseVolume(step: Int = VOLUME_STEP) {
        setVolume(getVolume() - step)
    }

    fun isMuted(): Boolean = mediaPlayerProvider()?.audio()?.isMute ?: false

    fun setMute(muted: Boolean) {
        mediaPlayerProvider()?.audio()?.setMute(muted)
    }

    fun toggleMute() {
        setMute(!isMuted())
    }
}
