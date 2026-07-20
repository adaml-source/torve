package com.torve.data.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NetworkErrorSanitizerTest {

    @Test
    fun redactsUrlsAndApiKeysFromDiagnostics() {
        val sanitized = sanitizeNetworkDiagnosticText(
            "Request timeout has expired [url=https://api.themoviedb.org/3/trending/movie/week?api_key=secret&page=1, request_timeout=30000 ms]",
        )

        assertTrue(sanitized?.contains("secret") == false)
        assertTrue(sanitized?.contains("api.themoviedb.org") == false)
        assertTrue(sanitized?.contains("<redacted-url>") == true)
    }

    @Test
    fun returnsSanitizedUiMessages() {
        assertEquals("Home content could not be loaded. Please try again.", homeContentLoadErrorMessage())
        assertEquals("Movies could not be loaded. Please try again.", catalogContentLoadErrorMessage("movie"))
        assertEquals("TV Shows could not be loaded. Please try again.", catalogContentLoadErrorMessage("tv"))
    }

    @Test
    fun homeMessageExplainsSecureConnectionFailures() {
        val cause = IllegalStateException("Home content is unavailable. Upstream failures: trending_movies=Chain validation failed")

        assertEquals(
            "Home could not connect securely. Check the device date and time, then try again.",
            homeContentLoadErrorMessage(cause),
        )
    }
}
