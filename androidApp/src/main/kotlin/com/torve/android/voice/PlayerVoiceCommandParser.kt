package com.torve.android.voice

sealed interface PlayerVoiceCommand {
    data object Play : PlayerVoiceCommand
    data object Pause : PlayerVoiceCommand
    data class Seek(val deltaMs: Long) : PlayerVoiceCommand
    data class Search(val query: String) : PlayerVoiceCommand
}

object PlayerVoiceCommandParser {
    private const val SEEK_DELTA_MS = 10_000L

    fun parse(rawText: String): PlayerVoiceCommand? {
        val source = rawText.trim()
        val normalized = source.lowercase()
        if (normalized.isBlank()) return null

        val searchQuery = extractSearchQuery(source, normalized)
        if (searchQuery != null) {
            return PlayerVoiceCommand.Search(searchQuery)
        }

        if (
            normalized == "pause" ||
            normalized == "stop" ||
            normalized.contains("pause playback")
        ) {
            return PlayerVoiceCommand.Pause
        }

        if (
            normalized == "play" ||
            normalized == "resume" ||
            normalized.contains("resume playback")
        ) {
            return PlayerVoiceCommand.Play
        }

        if (
            normalized.contains("forward") ||
            normalized.contains("skip ahead") ||
            normalized.contains("fast forward")
        ) {
            return PlayerVoiceCommand.Seek(deltaMs = SEEK_DELTA_MS)
        }

        if (
            normalized.contains("rewind") ||
            normalized.contains("go back") ||
            normalized.contains("backward")
        ) {
            return PlayerVoiceCommand.Seek(deltaMs = -SEEK_DELTA_MS)
        }

        return null
    }

    private fun extractSearchQuery(source: String, normalized: String): String? {
        val direct = "search for "
        if (normalized.startsWith(direct)) {
            return source.drop(direct.length).trim().takeIf { it.isNotBlank() }
        }
        val generic = "search "
        if (normalized.startsWith(generic)) {
            return source.drop(generic.length).trim().takeIf { it.isNotBlank() }
        }
        return null
    }
}
