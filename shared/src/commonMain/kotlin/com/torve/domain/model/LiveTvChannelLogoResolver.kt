package com.torve.domain.model

data class ChannelLogoRef(
    val url: String?,
    val source: ChannelLogoSource,
    val confidence: LogoConfidence,
    val fallbackText: String,
)

enum class ChannelLogoSource {
    PlaylistTvgLogo,
    EpgIcon,
    XtreamStreamIcon,
    CuratedChannelLogo,
    TmdbNetworkLogo,
    CachedLogo,
    Fallback,
}

enum class LogoConfidence {
    High,
    Medium,
    Low,
    Fallback,
}

object LiveTvChannelLogoResolver {
    fun resolveLogo(
        channel: Channel,
        epgChannel: EpgChannel? = null,
        cachedLogoUrl: String? = null,
    ): ChannelLogoRef {
        return resolveLogo(
            channelName = channel.name,
            playlistLogoUrl = channel.tvgLogo,
            epgIconUrl = epgChannel?.iconUrl,
            cachedLogoUrl = cachedLogoUrl,
        )
    }

    fun resolveLogo(
        channelName: String,
        playlistLogoUrl: String? = null,
        epgIconUrl: String? = null,
        xtreamStreamIconUrl: String? = null,
        cachedLogoUrl: String? = null,
    ): ChannelLogoRef {
        val fallbackText = fallbackTextFor(channelName)
        validRemoteLogoUrl(playlistLogoUrl)?.let {
            return ChannelLogoRef(it, ChannelLogoSource.PlaylistTvgLogo, LogoConfidence.High, fallbackText)
        }
        validRemoteLogoUrl(epgIconUrl)?.let {
            return ChannelLogoRef(it, ChannelLogoSource.EpgIcon, LogoConfidence.High, fallbackText)
        }
        validRemoteLogoUrl(xtreamStreamIconUrl)?.let {
            return ChannelLogoRef(it, ChannelLogoSource.XtreamStreamIcon, LogoConfidence.High, fallbackText)
        }
        validRemoteLogoUrl(cachedLogoUrl)?.let {
            return ChannelLogoRef(it, ChannelLogoSource.CachedLogo, LogoConfidence.Medium, fallbackText)
        }

        return ChannelLogoRef(
            url = null,
            source = ChannelLogoSource.Fallback,
            confidence = LogoConfidence.Fallback,
            fallbackText = fallbackText,
        )
    }

    fun normalizeChannelNameForLogo(name: String): String {
        val withoutPrefix = name.trim()
            .replace(Regex("^[A-Za-z]{2,3}\\s*:\\s*"), "")
        val separated = withoutPrefix
            .lowercase()
            .replace(Regex("[()\\[\\]{}._\\-+/|]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (separated.isBlank()) return ""

        val suffixTokens = setOf(
            "hd",
            "fhd",
            "uhd",
            "4k",
            "720p",
            "1080p",
            "2160p",
            "3840p",
            "low",
            "bit",
            "raw",
            "amz",
            "vip",
            "gold",
            "premium",
        )
        return separated
            .split(' ')
            .filter { it.isNotBlank() && it !in suffixTokens }
            .joinToString(" ")
            .trim()
    }

    fun validRemoteLogoUrl(rawUrl: String?): String? {
        val trimmed = rawUrl?.trim().orEmpty()
        if (trimmed.isBlank()) return null
        val lower = trimmed.lowercase()
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null

        val authority = trimmed.substringAfter("://", missingDelimiterValue = "")
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
        if (authority.isBlank() || authority.contains('@')) return null
        if (authority.startsWith("[")) return null

        val host = authority.substringBefore(':').lowercase()
        if (host.isBlank() || isBlockedHost(host)) return null

        return stripSensitiveQuery(trimmed)
    }

    fun fallbackTextFor(channelName: String): String {
        val normalized = normalizeChannelNameForLogo(channelName)
        curatedFallbackLabels[normalized]?.let { return it }
        val words = normalized.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return "TV"
        if (words.size == 1) {
            val compact = words.first().filter { it.isLetterOrDigit() }
            return when {
                compact.isBlank() -> "TV"
                compact.length <= 4 -> compact.uppercase()
                else -> compact.take(2).uppercase()
            }
        }
        return words.take(2)
            .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() }
            .joinToString("")
            .ifBlank { "TV" }
    }

    private fun isBlockedHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost")) return true
        if (host == "0.0.0.0" || host.startsWith("127.")) return true
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return true
        if (host.startsWith("172.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (host.startsWith("100.")) {
            val second = host.split('.').getOrNull(1)?.toIntOrNull()
            if (second != null && second in 64..127) return true
        }
        return false
    }

    private fun stripSensitiveQuery(url: String): String {
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return url

        val fragmentStart = url.indexOf('#', startIndex = queryStart + 1)
        val base = url.substring(0, queryStart)
        val query = if (fragmentStart >= 0) {
            url.substring(queryStart + 1, fragmentStart)
        } else {
            url.substring(queryStart + 1)
        }
        val fragment = if (fragmentStart >= 0) url.substring(fragmentStart) else ""
        val kept = query.split('&')
            .filter { it.isNotBlank() }
            .filterNot { param ->
                val key = param.substringBefore('=').lowercase()
                sensitiveQueryKeyFragments.any { it in key }
            }
        return if (kept.isEmpty()) {
            base + fragment
        } else {
            base + "?" + kept.joinToString("&") + fragment
        }
    }

    private val sensitiveQueryKeyFragments = listOf(
        "token",
        "auth",
        "key",
        "password",
        "passwd",
        "pass",
        "secret",
        "signature",
        "session",
        "credential",
        "user",
        "username",
    )

    private val curatedFallbackLabels = mapOf(
        "zdf" to "ZDF",
        "zdfneo" to "ZDF",
        "zdfinfo" to "ZDF",
        "das erste" to "1",
        "ard" to "ARD",
        "rtl" to "RTL",
        "rtl zwei" to "RTL",
        "vox" to "VOX",
        "prosieben" to "P7",
        "sat 1" to "SAT",
        "kabel eins" to "K1",
        "3sat" to "3S",
        "arte" to "ARTE",
        "wdr" to "WDR",
        "ndr" to "NDR",
        "swr" to "SWR",
        "br" to "BR",
        "mdr" to "MDR",
        "hr" to "HR",
        "rbb" to "RBB",
        "phoenix" to "PHX",
        "kika" to "KiKA",
        "sport1" to "S1",
        "eurosport" to "EU",
        "sky" to "SKY",
        "bbc" to "BBC",
        "itv" to "ITV",
        "channel 4" to "C4",
    )
}
