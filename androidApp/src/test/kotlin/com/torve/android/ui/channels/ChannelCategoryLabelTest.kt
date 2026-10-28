package com.torve.android.ui.channels

import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelCategoryLabelTest {
    @Test fun stripsOnlyTheCountryAlreadyShownByTheBadge() {
        assertEquals("Sport", channelCategoryLabel("Albania Sport", "AL"))
        assertEquals("News", channelCategoryLabel("Germany News", "DE"))
        assertEquals("Documentaries", channelCategoryLabel("United Kingdom Documentaries", "GB"))
    }
    @Test fun handlesCountryAliasesCodesAndSeparators() {
        assertEquals("Doku", channelCategoryLabel("Deutschland | Doku", "DE"))
        assertEquals("News", channelCategoryLabel("[GB] - News", "UK"))
        assertEquals("Sports", channelCategoryLabel("USA: Sports", "US"))
    }
    @Test fun preservesMeaningfulNamesWithoutAMatchingCountryPrefix() {
        assertEquals("Albania Sport", channelCategoryLabel("Albania Sport", "DE"))
        assertEquals("US Open", channelCategoryLabel("US Open", null))
        assertEquals("Design", channelCategoryLabel("Design", "DE"))
    }
    @Test fun neverLeavesAnEmptyGroupLabel() {
        assertEquals("Germany", channelCategoryLabel("Germany", "DE"))
        assertEquals("DE |", channelCategoryLabel("DE |", "DE"))
    }
}
