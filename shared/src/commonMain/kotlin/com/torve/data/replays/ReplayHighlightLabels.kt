package com.torve.data.replays

/** Never copy arbitrary uploader prose into the spoiler-free highlight picker. */
internal object ReplayHighlightLabels {
    fun coverage(title: String, seconds: Int): String {
        val text = ReplayHighlightIdentity.normalize(title)
        val segment = segment(text)
        val kind = when {
            Regex("\\bcondensed (?:game|match|race|replay)\\b").containsMatchIn(text) -> "Condensed game"
            Regex("\\b(?:player|career) highlights\\b").containsMatchIn(text) -> "Player highlights"
            Regex("\\brecap\\b").containsMatchIn(text) -> "Game recap"
            segment != null -> "$segment highlights"
            Regex("\\bfull (?:game|match|race) highlights\\b").containsMatchIn(text) -> "Full ${if ("race" in text.split(' ')) "race" else "game"} highlights"
            Regex("\\bextended highlights\\b").containsMatchIn(text) -> "Extended highlights"
            seconds < 300 -> "Short highlights"
            seconds >= 600 -> "Extended highlights"
            else -> "Highlights"
        }
        val part = Regex("\\b(?:part|pt|p) ?([1-9]|1[0-9]|20)\\b").find(text)?.groupValues?.get(1)
        return kind + if (part != null) " · Part $part" else ""
    }

    private fun segment(text: String): String? {
        val ordinal = mapOf("first" to "1st", "second" to "2nd", "third" to "3rd", "fourth" to "4th")
        Regex("\\b(1st|2nd|3rd|4th|first|second|third|fourth) (quarter|qtr|half|period)\\b").find(text)?.let {
            val number = ordinal[it.groupValues[1]] ?: it.groupValues[1]
            val unit = if (it.groupValues[2] == "qtr") "quarter" else it.groupValues[2]
            return "$number $unit"
        }
        Regex("\\bq([1-4])\\b").find(text)?.let {
            return "${listOf("1st", "2nd", "3rd", "4th")[it.groupValues[1].toInt() - 1]} quarter"
        }
        return null
    }

    fun isSimulation(title: String, uploader: String, description: String, official: Boolean): Boolean {
        val evidence = ReplayHighlightIdentity.normalize("$title ${description.take(800)}")
        if (Regex("\\b(?:nba ?2k(?:[0-9]{2})?|madden|gameplay|simulation|simulator)\\b").containsMatchIn(evidence)) return true
        // A plausible matchup headline alone cannot establish that a gaming upload is the real event.
        return !official && Regex("\\b(?:gaming|gamer|gameplay|simulation|simulations|simulator|2k(?:[0-9]{2})?|madden)\\b")
            .containsMatchIn(ReplayHighlightIdentity.normalize(uploader))
    }
}
