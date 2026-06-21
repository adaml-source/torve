package com.torve.domain.player

enum class LiveAudioOutputMode(val storageValue: String) {
    AUTO("auto"),
    PREFER_COMPATIBLE("prefer_compatible"),
    FORCE_STEREO_PCM("force_stereo_pcm"),
    ;

    companion object {
        fun fromStorage(value: String?): LiveAudioOutputMode = entries.firstOrNull {
            it.storageValue.equals(value, ignoreCase = true)
        } ?: PREFER_COMPATIBLE
    }
}
