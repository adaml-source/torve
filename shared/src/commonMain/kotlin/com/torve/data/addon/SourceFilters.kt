package com.torve.data.addon

data class SourceFilterOption(val key: String, val label: String)

/** Uses the same source classification as Android's source picker. */
object SourceFilters {
    // AIOStreams can decorate its short service badge with cached/waiting emoji.
    private val torBoxBadge = Regex("\\[tb(?:[+\\s⚡⌛⏳☁️✓✔✅-]*)]", RegexOption.IGNORE_CASE)

    fun supportsTorBoxUsenetProgress(stream: ParsedStream): Boolean {
        if (!stream.isUsenetStream()) return false
        // An explicit service wins over display text (which may name other services).
        stream.downloadService?.takeIf { it.isNotBlank() }?.let {
            return it.equals("torbox", ignoreCase = true) || it.equals("tb", ignoreCase = true)
        }
        val label = "${stream.addonName} ${stream.source.orEmpty()} ${stream.accelerationProviderType.orEmpty()}"
        return Regex("\\btorbox\\b", RegexOption.IGNORE_CASE).containsMatchIn(label) || torBoxBadge.containsMatchIn(label)
    }
    private val providers = listOf(
        SourceFilterOption("TORRENTIO", "Torrentio"),
        SourceFilterOption("REAL_DEBRID", "Real-Debrid"),
        SourceFilterOption("TORBOX", "TorBox"),
        SourceFilterOption("ALLDEBRID", "AllDebrid"),
        SourceFilterOption("PREMIUMIZE", "Premiumize"),
        SourceFilterOption("EASYNEWS", "Easynews"),
        SourceFilterOption("PANDA", "Panda"),
    )

    fun options(streams: List<ParsedStream>): List<SourceFilterOption> = listOf(
        SourceFilterOption("ALL", "All sources"),
        SourceFilterOption("TORRENT", "Torrents / debrid"),
        SourceFilterOption("USENET", "Usenet / NZB"),
    ) + providers.filter { option -> streams.any { matches(it, option.key) } }

    fun matches(stream: ParsedStream, filter: String): Boolean {
        val provider = "${stream.addonName} ${stream.source.orEmpty()} ${stream.accelerationProviderType.orEmpty()}"
            .lowercase()
        return when (filter) {
            "ALL", "" -> true
            "USENET" -> stream.isUsenetStream()
            "TORRENT" -> stream.isTorrentOrDebridStream()
            "TORRENTIO" -> "torrentio" in provider
            "REAL_DEBRID" -> "realdebrid" in provider || "real-debrid" in provider ||
                "real_debrid" in provider || Regex("\\[rd[+ ]?]").containsMatchIn(provider)
            "TORBOX" -> stream.downloadService.equals("torbox", ignoreCase = true) ||
                stream.downloadService.equals("tb", ignoreCase = true) || "torbox" in provider || torBoxBadge.containsMatchIn(provider)
            "ALLDEBRID" -> "alldebrid" in provider || Regex("\\[ad[+ ]?]").containsMatchIn(provider)
            "PREMIUMIZE" -> "premiumize" in provider || Regex("\\[pm[+ ]?]").containsMatchIn(provider)
            "EASYNEWS" -> "easynews" in provider || stream.directUrl?.contains("/easynews/") == true
            "PANDA" -> stream.isPandaStream()
            else -> false
        }
    }

    fun providerLabel(stream: ParsedStream): String = if (stream.isPandaStream()) {
        stream.source?.lineSequence()?.firstOrNull()
            ?.substringBefore(" · ")?.substringBefore("💾")?.substringBefore("🗣")
            ?.substringBefore("💬")?.substringBefore("🗨")
            ?.trim()?.trimEnd('·')?.trim()?.takeIf { it.isNotBlank() } ?: stream.addonName
    } else stream.addonName
}
