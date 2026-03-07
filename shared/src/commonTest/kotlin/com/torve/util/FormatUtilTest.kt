package com.torve.util

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatUtilTest {

    @Test
    fun formatRuntime_hoursAndMinutes() {
        assertEquals("2h 15m", FormatUtil.formatRuntime(135))
    }

    @Test
    fun formatRuntime_hoursOnly() {
        assertEquals("2h", FormatUtil.formatRuntime(120))
    }

    @Test
    fun formatRuntime_minutesOnly() {
        assertEquals("45m", FormatUtil.formatRuntime(45))
    }

    @Test
    fun formatRuntime_nullOrZero() {
        assertEquals("", FormatUtil.formatRuntime(null))
        assertEquals("", FormatUtil.formatRuntime(0))
        assertEquals("", FormatUtil.formatRuntime(-1))
    }

    @Test
    fun formatRating_valid() {
        assertEquals("7.8", FormatUtil.formatRating(7.8))
    }

    @Test
    fun formatRating_nullOrZero() {
        assertEquals("", FormatUtil.formatRating(null))
        assertEquals("", FormatUtil.formatRating(0.0))
    }

    @Test
    fun formatBytes_gigabytes() {
        assertEquals("1.5 GB", FormatUtil.formatBytes(1610612736L))
    }

    @Test
    fun formatBytes_megabytes() {
        assertEquals("500.0 MB", FormatUtil.formatBytes(524288000L))
    }

    @Test
    fun formatBytes_nullOrZero() {
        assertEquals("", FormatUtil.formatBytes(null))
        assertEquals("", FormatUtil.formatBytes(0))
    }

    @Test
    fun formatYear_valid() {
        assertEquals("2024", FormatUtil.formatYear("2024-01-15"))
    }

    @Test
    fun formatYear_nullOrEmpty() {
        assertEquals("", FormatUtil.formatYear(null))
        assertEquals("", FormatUtil.formatYear(""))
    }

    @Test
    fun formatDate_valid() {
        assertEquals("Jan 15, 2024", FormatUtil.formatDate("2024-01-15"))
    }

    @Test
    fun formatDate_december() {
        assertEquals("Dec 25, 2023", FormatUtil.formatDate("2023-12-25"))
    }

    @Test
    fun formatDate_nullOrEmpty() {
        assertEquals("", FormatUtil.formatDate(null))
        assertEquals("", FormatUtil.formatDate(""))
    }
}
