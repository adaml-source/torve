package com.torve.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals

class StreamingProviderDiscoveryTest {
    @Test
    fun resolvesUsParamountTiersAndExcludesChannelAddOns() {
        val ids = resolveStreamingProviderIds(
            requestedName = "Paramount+",
            configuredProviderId = 531,
            region = "US",
            availableProviders = listOf(
                StreamingProviderCandidate(2303, "Paramount Plus Premium"),
                StreamingProviderCandidate(2616, "Paramount Plus Essential"),
                StreamingProviderCandidate(582, "Paramount+ Amazon Channel"),
                StreamingProviderCandidate(633, "Paramount+ Roku Premium Channel"),
            ),
        )
        assertEquals(listOf(2303, 2616), ids)
    }

    @Test
    fun usesRegionalParamountFallbackWhenProviderLookupFails() {
        assertEquals(listOf(2303, 2616), resolveStreamingProviderIds("Paramount+", 531, "US", emptyList()))
        assertEquals(listOf(531), resolveStreamingProviderIds("Paramount+", 531, "DE", emptyList()))
    }

    @Test
    fun resolvesGermanParamountAndExcludesPicturesAndChannels() {
        val ids = resolveStreamingProviderIds(
            requestedName = "Paramount+",
            configuredProviderId = 531,
            region = "DE",
            availableProviders = listOf(
                StreamingProviderCandidate(531, "Paramount Plus"),
                StreamingProviderCandidate(582, "Paramount+ Amazon Channel"),
                StreamingProviderCandidate(187, "Paramount Pictures"),
            ),
        )
        assertEquals(listOf(531), ids)
    }

    @Test
    fun resolvesPrimeVideoBrandAlias() {
        val ids = resolveStreamingProviderIds(
            requestedName = "Prime Video",
            configuredProviderId = 9,
            region = "US",
            availableProviders = listOf(
                StreamingProviderCandidate(9, "Amazon Prime Video"),
                StreamingProviderCandidate(119, "Amazon Prime Video with Ads"),
            ),
        )
        assertEquals(listOf(9, 119), ids)
    }
}
