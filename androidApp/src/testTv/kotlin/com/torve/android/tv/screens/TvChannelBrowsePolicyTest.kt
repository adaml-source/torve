package com.torve.android.tv.screens

import com.torve.domain.model.Channel
import com.torve.domain.model.ChannelContentType
import com.torve.domain.model.EnrichedChannel
import com.torve.domain.model.EpgProgramme
import com.torve.domain.model.stableChannelId
import com.torve.presentation.channels.ChannelsFilterType
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TvChannelBrowsePolicyTest {
    private fun channel(name: String, id: String = name) = Channel(name,
        "https://example.test/live/$id", playlistId = "source", contentType = ChannelContentType.LIVE)
    private fun browse(vararg channels: Channel, eventFilter: TvChannelEventFilter = TvChannelEventFilter.ACTIVE) =
        tvBrowseChannels(channels.map { EnrichedChannel(it) }, eventFilter = eventFilter)

    @Test fun cachedAndDirectHeadersAreExcludedIncludingInvisibleAndTrailingGroupText() {
        listOf("######## DAZN PPV AT ########", "\u200e######## DAZN ######## | AT: DAZN",
            "--- Sports ---", "***", " ").forEach { assertFalse(it, isPlayableTvBrowseChannel(channel(it))) }
        assertTrue(isPlayableTvBrowseChannel(channel("#1 Sports")))
        assertTrue(isPlayableTvBrowseChannel(channel("C++ TV")))
    }

    @Test fun invalidStreamsAndVodNeverEnterLiveBrowser() {
        assertFalse(isPlayableTvBrowseChannel(channel("News").copy(url = "#")))
        assertFalse(isPlayableTvBrowseChannel(channel("News").copy(url = "about:blank")))
        assertFalse(isPlayableTvBrowseChannel(channel("Movie").copy(contentType = ChannelContentType.VOD_MOVIE)))
        assertFalse(isPlayableTvBrowseChannel(channel("Series").copy(contentType = ChannelContentType.VOD_SERIES)))
    }

    @Test fun eventPresentationPreservesUnicodeAndDoesNotAlterPlaybackOrEpgIdentity() {
        val original = channel("Live | Bayern M%C3%BCnchen vs. Žalgiris | EuroLeague | 2026-10-06 | 18:30 (GMT) | 4K EXCLUSIVE | DE: DAZN")
        val display = tvChannelPresentation(original)
        assertEquals("Bayern München vs. Žalgiris", display.title)
        assertEquals("EuroLeague", display.detail)
        assertEquals("4K", display.quality)
        assertEquals(TvChannelEventStatus.LIVE, display.status)
        val result = browse(original).single().channel
        assertSame(original, result)
        assertEquals(stableChannelId(original), stableChannelId(result))
    }

    @Test fun eventUtcTimeConvertsToTheCorrectInstant() {
        val display = tvChannelPresentation(channel("Upcoming | Match | EuroLeague | 2026-10-06 | 18:30 (GMT)"))
        val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear(); set(2026, Calendar.OCTOBER, 6, 18, 30)
        }
        assertEquals(utc.timeInMillis, display.startMs)
    }

    @Test fun invalidProviderDatesDoNotProduceInventedStartTimes() {
        assertNull(tvChannelPresentation(channel("Live | Match | 2026-99-06 | 18:30 (GMT)")).startMs)
        assertNull(tvChannelPresentation(channel("Live | Match | 2026-10-06 | 99:30 (GMT)")).startMs)
    }

    @Test fun finishedEventsAreHiddenByDefaultButRemainAvailableInFilters() {
        val ended = channel("End | Finished match")
        val live = channel("Live | Current match")
        assertEquals(listOf(live), browse(ended, live).map { it.channel })
        assertEquals(listOf(live, ended), browse(ended, live, eventFilter = TvChannelEventFilter.ALL).map { it.channel })
        assertEquals(listOf(ended), browse(ended, live, eventFilter = TvChannelEventFilter.FINISHED).map { it.channel })
    }

    @Test fun recommendedOrderPromotesLiveAndKeepsUpcomingEventsChronological() {
        val late = channel("Upcoming | Late | 2026-10-07 | 20:00 (GMT)")
        val early = channel("Upcoming | Early | 2026-10-07 | 12:00 (GMT)")
        val normal = channel("News One")
        val live = channel("Live | Current match")
        assertEquals(listOf(live, normal, early, late), browse(late, normal, early, live).map { it.channel })
    }

    @Test fun recentAndFavoriteOrderIsPreservedAndDuplicateStreamIdentitiesAreRemoved() {
        val first = channel("News One")
        val second = channel("Live | Current match")
        val result = tvBrowseChannels(listOf(first, second, first).map { EnrichedChannel(it) }, preserveOrder = true)
        assertEquals(listOf(first, second), result.map { it.channel })
    }

    @Test fun qualityFilteringDoesNotConfuseHdWithFhdAndSupportsProviderVariants() {
        assertTrue(tvChannelMatchesQuality(channel("News FHD"), ChannelsFilterType.FHD))
        assertFalse(tvChannelMatchesQuality(channel("News FHD"), ChannelsFilterType.HD))
        assertTrue(tvChannelMatchesQuality(channel("News 1080p"), ChannelsFilterType.FHD))
        assertTrue(tvChannelMatchesQuality(channel("News 2160p"), ChannelsFilterType.UHD))
        assertTrue(tvChannelMatchesQuality(channel("News 8K"), ChannelsFilterType.UHD))
        assertTrue(tvChannelMatchesQuality(channel("News").copy(groupTitle = "DE | News HD"), ChannelsFilterType.HD))
        assertFalse(tvChannelMatchesQuality(channel("HDNet"), ChannelsFilterType.HD))
    }

    @Test fun regularTitlesSeparateCountryQualityAndDecorationsWithoutChangingTheStream() {
        val original = channel("DE: Bayern M%C3%BCnchen HD ◉").copy(tvgId = "original.epg")
        val display = tvChannelPresentation(original)
        assertEquals("Bayern München", display.title)
        assertEquals("HD", display.quality)
        assertSame(original, browse(original).single().channel)
        assertEquals("original.epg", original.tvgId)
        assertEquals("WOW: SKY SPORT F1", tvChannelPresentation(channel("WOW: SKY SPORT F1ᴴᴰ ◉")).title)
        assertEquals("HD", tvChannelPresentation(channel("WOW: SKY SPORT F1ᴴᴰ ◉")).quality)
        assertEquals("News", tvChannelPresentation(channel("USA | News [FHD]")).title)
        assertEquals("FHD", tvChannelPresentation(channel("USA | News [FHD]")).quality)
    }

    @Test fun channelBrandNamesAndAccentedCharactersArePreserved() {
        listOf("HDNet", "NOW: Cinema", "WOW: Serien", "Rai Südtirol", "ČT 1", "C++ TV").forEach {
            assertEquals(it, tvChannelPresentation(channel(it)).title)
        }
        assertEquals("4K", tvChannelPresentation(channel("News").copy(groupTitle = "DE | News UHD")).quality)
        assertTrue(tvChannelMatchesQuality(channel("Sportᴴᴰ"), ChannelsFilterType.HD))
    }

    @Test fun guideSelectionUsesRealCoverageAndDoesNotCreateEmptyTimetables() {
        assertFalse(tvShouldShowChannelGuide(TvChannelGuideMode.AUTO, 20, 0, false))
        assertFalse(tvShouldShowChannelGuide(TvChannelGuideMode.AUTO, 20, 3, false))
        assertTrue(tvShouldShowChannelGuide(TvChannelGuideMode.AUTO, 20, 4, false))
        assertFalse(tvShouldShowChannelGuide(TvChannelGuideMode.GUIDE, 20, 0, false))
        assertTrue(tvShouldShowChannelGuide(TvChannelGuideMode.GUIDE, 20, 1, false))
        assertTrue(tvShouldShowChannelGuide(TvChannelGuideMode.GUIDE, 20, 0, true))
        assertFalse(tvShouldShowChannelGuide(TvChannelGuideMode.GUIDE, 0, 0, true))
        assertFalse(tvShouldShowChannelGuide(TvChannelGuideMode.LIST, 20, 20, true))
    }

    @Test fun streamsSharingAnEpgIdRemainSeparatePlayableChoices() {
        val hd = channel("News HD", "hd").copy(tvgId = "news.one")
        val uhd = channel("News 4K", "uhd").copy(tvgId = "news.one")
        assertEquals(2, browse(hd, uhd).size)
        assertNotEquals(tvChannelBrowseKey(hd), tvChannelBrowseKey(uhd))
    }

    @Test fun countryFilteringFallsBackToCategoryAndProviderGroup() {
        assertEquals("DE", tvChannelCountry(channel("News"), "DE"))
        assertEquals("AT", tvChannelCountry(channel("News").copy(groupTitle = "AT| DAZN PPV")))
        assertEquals("GB", tvChannelCountry(channel("News").copy(tvgCountry = "GB;IE"), "DE"))
        assertTrue(tvChannelMatchesCountry(channel("News").copy(tvgCountry = "GB;IE"), "IE"))
        assertTrue(tvChannelMatchesCountry(channel("News").copy(tvgCountry = "DEU"), "DE"))
        val result = tvBrowseChannels(listOf(EnrichedChannel(channel("News"))), country = "DE", categoryCountry = "DE")
        assertEquals(1, result.size)
    }

    @Test fun onlyCurrentProgrammesPlayImmediately() {
        fun programme(start: Long, end: Long) = EpgProgramme("channel", start, end, "Programme")
        assertTrue(tvProgrammeNeedsDetails(programme(2_000, 3_000), 1_000))
        assertTrue(tvProgrammeNeedsDetails(programme(0, 1_000), 1_000))
        assertFalse(tvProgrammeNeedsDetails(programme(0, 2_000), 1_000))
        assertFalse(tvProgrammeNeedsDetails(null, 1_000))
    }

    @Test fun dayJumpsKeepLocalEveningTimesAcrossDaylightSaving() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            val beforeChange = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 24, 21, 0) }
            val target = Calendar.getInstance().apply { timeInMillis = tvGuideDayStart(beforeChange.timeInMillis, 1) }
            assertEquals(25, target.get(Calendar.DAY_OF_MONTH))
            assertEquals(18, target.get(Calendar.HOUR_OF_DAY))
            assertEquals(0, target.get(Calendar.MINUTE))
        } finally { TimeZone.setDefault(previousZone) }
    }

    @Test fun lateNightTonightShortcutUsesTheNextEvening() {
        val now = Calendar.getInstance().apply { clear(); set(2026, Calendar.OCTOBER, 6, 23, 0) }
        val target = Calendar.getInstance().apply { timeInMillis = tvGuideTonightStart(now.timeInMillis) }
        assertEquals(7, target.get(Calendar.DAY_OF_MONTH))
        assertEquals(20, target.get(Calendar.HOUR_OF_DAY))
    }
}
