package com.torve.android.ui.community

import org.junit.Assert.*
import org.junit.Test

class UiTextPatternTest {
    @Test fun indexedArgumentsKeepResourceOrderWhenTheSentenceReordersThem() {
        assertArrayEquals(arrayOf<Any>(7, "Source"),
            UiTextPattern("%2\$s checked %1\$03d items", 1).arguments("Source checked 007 items"))
    }

    @Test fun literalRegexCharactersAndMultilineArgumentsStayExact() {
        val entry = UiTextPattern("[%1\$s] (total: %2\$d).", 1)
        assertArrayEquals(arrayOf<Any>("First\nSecond", 12), entry.arguments("[First\nSecond] (total: 12)."))
        assertNull(entry.arguments("First\nSecond (total: 12)."))
        assertNull(entry.arguments("[First] (total: twelve)."))
    }

    @Test fun anchorCanBeBeforeBetweenOrAfterArguments() {
        assertArrayEquals(arrayOf("item"), UiTextPattern("Start %s", 1).arguments("Start item"))
        assertArrayEquals(arrayOf("item"), UiTextPattern("%s finished", 1).arguments("item finished"))
        assertArrayEquals(arrayOf<Any>("item", 1.5), UiTextPattern("%s · %f", 1).arguments("item · 1.5"))
        assertNull(UiTextPattern("Start %s", 1).arguments("A provider title"))
    }

    @Test fun literalPercentAndMissingArgumentPositionsPreserveMatchingRules() {
        assertArrayEquals(emptyArray<Any>(), UiTextPattern("100%% complete", 1).arguments("100% complete"))
        assertNull(UiTextPattern("100%% complete", 1).arguments("99% complete"))
        assertNull(UiTextPattern("%2\$s only", 1).arguments("item only"))
    }
}
