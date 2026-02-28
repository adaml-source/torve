package com.streamvault.domain.integrations

import com.streamvault.domain.model.AvailabilityResult
import com.streamvault.domain.model.MediaType

interface AvailabilityProvider {
    suspend fun getAvailability(
        tmdbId: Int,
        mediaType: MediaType,
        region: String,
    ): AvailabilityResult
}

