package com.torve.android.tv.screens

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.stableChannelId
import com.torve.presentation.channels.ChannelsFilterType
import com.torve.util.DisplayTextDecoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

internal enum class TvChannelEventStatus(val label: String) {
    LIVE("Live"), UPCOMING("Upcoming"), FINISHED("Finished"), CHANNEL("")
}

internal enum class TvChannelEventFilter(val label: String) {
    ACTIVE("Live & upcoming"), ALL("All events"), FINISHED("Finished events")
}

internal enum class TvChannelBrowseSort(val label: String) {
    RECOMMENDED("Live first"), NAME("Name A–Z")
}

internal enum class TvChannelGuideMode { AUTO, LIST, GUIDE }

internal data class TvChannelPresentation(
    val title: String,
    val detail: String? = null,
    val status: TvChannelEventStatus = TvChannelEventStatus.CHANNEL,
    val startMs: Long? = null,
    val quality: String? = null,
)

private val providerDate = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b")
private val providerTime = Regex("^(\\d{1,2}:\\d{2})\\s*(?:\\((GMT|UTC|CET|CEST)\\))?$", RegexOption.IGNORE_CASE)
private val providerQuality = Regex("(?:^|[^\\p{L}\\p{N}])(8K|4K|UHD|2160P|FHD|1080P|HD|720P|SD)(?=$|[^\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
private val decorativeEdge = Regex("^[#*=~_\\-]{3,}|[#*=~_\\-]{3,}$")
private val superscriptHd = Regex("[ᴴʰ][ᴰᵈ]")
private val countryPrefix = Regex("^([A-Z]{2,3})\\s*[:|]\\s*")
private val qualityAtStart = Regex("^(8K|4K|UHD|2160P|FHD|1080P|HD|720P|SD)(?=$|[\\s|:·\\[\\]()\\-])", RegexOption.IGNORE_CASE)
private val qualityAtEnd = Regex("[\\s|:·\\[\\]()\\-](8K|4K|UHD|2160P|FHD|1080P|HD|720P|SD)[\\s|:·\\[\\]()\\-]*$", RegexOption.IGNORE_CASE)
private val countryCodes = Locale.getISOCountries().toSet() + setOf("UK", "XK")

private fun canonicalChannelQuality(name: String): String? =
    providerQuality.find(name.replace(superscriptHd, " HD "))?.groupValues?.get(1)?.uppercase(Locale.ROOT)?.let {
        when (it) { "2160P", "UHD" -> "4K"; "1080P" -> "FHD"; "720P" -> "HD"; else -> it }
    }

private fun cleanChannelTitle(name: String): String {
    fun String.trimDecoration() = trim().trim('•', '●', '○', '◉', '|', ':', '·', '[', ']', '(', ')').trim()
    var title = name.replace(superscriptHd, "").trimDecoration()
    countryPrefix.find(title)?.let { prefix ->
        if (tvNormalizeCountry(prefix.groupValues[1]) in countryCodes) title = title.removePrefix(prefix.value)
    }
    title = title.replace(qualityAtStart, "").trimDecoration()
    title = title.replace(qualityAtEnd, "").trimDecoration()
    return title.ifBlank { name }
}

internal fun tvShouldShowChannelGuide(
    mode: TvChannelGuideMode,
    channelCount: Int,
    guideCoverageCount: Int,
    isLoading: Boolean,
): Boolean = channelCount > 0 && when (mode) {
    TvChannelGuideMode.LIST -> false
    TvChannelGuideMode.GUIDE -> guideCoverageCount > 0 || isLoading
    TvChannelGuideMode.AUTO -> guideCoverageCount > 0 && guideCoverageCount * 5 >= channelCount
}

/** Display-only normalization: the provider name, URL and EPG identity stay intact. */
internal fun tvChannelPresentation(channel: Channel): TvChannelPresentation {
    val name = DisplayTextDecoder.decode(channel.name).trim()
    val parts = name.split('|').map(String::trim).filter(String::isNotBlank)
    val status = when (parts.firstOrNull()?.lowercase(Locale.ROOT)) {
        "live" -> TvChannelEventStatus.LIVE
        "upcoming", "soon", "scheduled" -> TvChannelEventStatus.UPCOMING
        "end", "ended", "finished" -> TvChannelEventStatus.FINISHED
        else -> TvChannelEventStatus.CHANNEL
    }
    val quality = canonicalChannelQuality(name) ?: canonicalChannelQuality(channel.groupTitle.orEmpty())
    if (status == TvChannelEventStatus.CHANNEL) return TvChannelPresentation(cleanChannelTitle(name), quality = quality)

    val content = parts.drop(1)
    val datePart = content.firstNotNullOfOrNull { providerDate.find(it)?.groupValues?.get(1) }
    val timePart = content.firstNotNullOfOrNull { providerTime.matchEntire(it) }
    val start = if (datePart != null && timePart != null) {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd H:mm", Locale.ROOT).apply {
                isLenient = false
                timeZone = when (timePart.groupValues[2].uppercase(Locale.ROOT)) {
                    "GMT", "UTC" -> TimeZone.getTimeZone("UTC")
                    "CET" -> TimeZone.getTimeZone("GMT+01:00")
                    "CEST" -> TimeZone.getTimeZone("GMT+02:00")
                    else -> TimeZone.getDefault()
                }
            }.parse("$datePart ${timePart.groupValues[1]}")?.time
        }.getOrNull()
    } else null
    val title = content.firstOrNull().orEmpty().ifBlank { name }
    val detail = content.drop(1).firstOrNull { part ->
        !providerDate.containsMatchIn(part) && providerTime.matchEntire(part) == null &&
            !providerQuality.containsMatchIn(part) && !part.contains("exclusive", ignoreCase = true) &&
            !part.matches(Regex("^[A-Z]{2,3}\\s*[:|].*"))
    }
    return TvChannelPresentation(title, detail, status, start, quality)
}

internal fun isPlayableTvBrowseChannel(channel: Channel): Boolean {
    val url = channel.url.trim()
    if (url.isBlank() || url == "#" || url.equals("about:blank", true)) return false
    if (channel.contentType == ChannelContentType.VOD_MOVIE || channel.contentType == ChannelContentType.VOD_SERIES) return false
    val name = DisplayTextDecoder.decode(channel.name)
        .filterNot { Character.getType(it) == Character.FORMAT.toInt() }
        .trim()
    if (name.isBlank() || name.none(Char::isLetterOrDigit)) return false
    // Some feeds append a country/group after a decorated header, or include
    // invisible formatting characters. Check each edge of its first segment.
    val firstSegment = name.substringBefore('|').trim()
    return !decorativeEdge.containsMatchIn(firstSegment)
}

internal fun tvChannelCountry(channel: Channel, categoryCountry: String? = null): String? =
    channel.tvgCountry?.split(',', ';')?.firstOrNull()?.let(::tvNormalizeCountry)
        ?.takeIf(String::isNotBlank)
        ?: categoryCountry?.takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.let(::tvNormalizeCountry)
        ?: Regex("^([A-Z]{2,3})\\s*[|:]").find(channel.groupTitle.orEmpty())?.groupValues?.get(1)

internal fun tvNormalizeCountry(country: String): String {
    val code = country.trim().uppercase(Locale.ROOT)
    return ISO3_TO_ISO2[code] ?: code
}

internal fun tvChannelMatchesCountry(channel: Channel, country: String, categoryCountry: String? = null): Boolean {
    val selected = tvNormalizeCountry(country)
    val declared = channel.tvgCountry?.split(',', ';')?.filter(String::isNotBlank).orEmpty()
    return if (declared.isNotEmpty()) declared.any { tvNormalizeCountry(it) == selected }
    else tvChannelCountry(channel, categoryCountry)?.let(::tvNormalizeCountry) == selected
}

/** EPG identifiers can be shared by HD/4K variants and event feeds. */
internal fun tvChannelBrowseKey(channel: Channel): String = "${channel.playlistId}|${channel.url}"

internal fun tvChannelMatchesQuality(channel: Channel, filter: ChannelsFilterType): Boolean {
    if (filter == ChannelsFilterType.ALL) return true
    if (filter == ChannelsFilterType.FAVORITES) return channel.isFavorite
    val quality = tvChannelPresentation(channel).quality
        ?: providerQuality.find(channel.groupTitle.orEmpty())?.groupValues?.get(1)?.uppercase(Locale.ROOT)
    return when (filter) {
        ChannelsFilterType.UHD -> quality in setOf("4K", "8K", "UHD", "2160P")
        ChannelsFilterType.FHD -> quality in setOf("FHD", "1080P")
        ChannelsFilterType.HD -> quality in setOf("HD", "720P")
        else -> true
    }
}

internal fun tvBrowseChannels(
    channels: List<EnrichedChannel>,
    eventFilter: TvChannelEventFilter = TvChannelEventFilter.ACTIVE,
    quality: ChannelsFilterType = ChannelsFilterType.ALL,
    country: String? = null,
    categoryCountry: String? = null,
    sort: TvChannelBrowseSort = TvChannelBrowseSort.RECOMMENDED,
    preserveOrder: Boolean = false,
): List<EnrichedChannel> {
    val entries = channels.asSequence()
        .filter { isPlayableTvBrowseChannel(it.channel) }
        .distinctBy { tvChannelBrowseKey(it.channel) }
        .filter { tvChannelMatchesQuality(it.channel, quality) }
        .filter { country == null || tvChannelMatchesCountry(it.channel, country, categoryCountry) }
        .map { it to tvChannelPresentation(it.channel) }
        .filter { (_, display) ->
            when (eventFilter) {
                TvChannelEventFilter.ACTIVE -> display.status != TvChannelEventStatus.FINISHED
                TvChannelEventFilter.FINISHED -> display.status == TvChannelEventStatus.FINISHED
                TvChannelEventFilter.ALL -> true
            }
        }.toList()
    val sorted = when {
        sort == TvChannelBrowseSort.NAME -> entries.sortedBy { it.second.title.lowercase(Locale.ROOT) }
        preserveOrder -> entries
        else -> entries.sortedWith(compareBy<Pair<EnrichedChannel, TvChannelPresentation>> {
            when (it.second.status) {
                TvChannelEventStatus.LIVE -> 0
                TvChannelEventStatus.CHANNEL -> 1
                TvChannelEventStatus.UPCOMING -> 2
                TvChannelEventStatus.FINISHED -> 3
            }
        }.thenBy { if (it.second.status == TvChannelEventStatus.UPCOMING) it.second.startMs ?: Long.MAX_VALUE else 0L })
    }
    return sorted.map { it.first }
}

internal fun tvProgrammeNeedsDetails(programme: EpgProgramme?, nowMs: Long): Boolean =
    programme != null && (programme.startTime > nowMs || programme.endTime <= nowMs)

internal fun tvGuideDayStart(nowMs: Long, dayOffset: Int, hour: Int = 18): Long =
    Calendar.getInstance().apply {
        timeInMillis = nowMs
        add(Calendar.DATE, dayOffset)
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

internal fun tvGuideTonightStart(nowMs: Long): Long =
    tvGuideDayStart(nowMs, if (Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY) >= 22) 1 else 0, 20)
