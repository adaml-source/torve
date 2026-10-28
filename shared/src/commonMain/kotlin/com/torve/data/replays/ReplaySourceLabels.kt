package com.torve.data.replays

import io.ktor.http.Url

/** Only identify qualities/commentary actually supplied by the replay website. */
object ReplaySourceLabels {
    fun title(option: ReplaySourceOption, options: List<ReplaySourceOption>): String {
        val group = options.filter { it.gameTitle == option.gameTitle }
        val number = group.indexOfFirst { it.id == option.id }.takeIf { it >= 0 }?.plus(1) ?: 1
        return "Source $number · ${host(option)}"
    }

    fun detail(option: ReplaySourceOption): String {
        val label = option.title.trim()
        return if (label.equals(host(option), true) || label in listOf("OK", "FM", "Video") ||
            label == runCatching { Url(option.url).host }.getOrNull()) {
            "Full replay · Alternative video host"
        } else label
    }

    private fun host(option: ReplaySourceOption): String {
        val host = runCatching { Url(option.url).host.lowercase() }.getOrDefault("")
        val label = option.title
        return when {
            host == "ok.ru" || host.endsWith(".ok.ru") || Regex("\\bOK(?:\\.ru)?\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) -> "OK.ru"
            host == "dai.ly" || host.endsWith("dailymotion.com") || label.contains("Dailymotion", true) -> "Dailymotion"
            host.contains("filemoon") || host.startsWith("byse") || Regex("\\bFM\\b|filemoon", RegexOption.IGNORE_CASE).containsMatchIn(label) -> "Filemoon"
            label.contains("Vidara", true) -> "Vidara"
            WebReplayUrls.media(option.url) -> "Direct video"
            else -> host.ifBlank { "Video host" }
        }
    }
}
