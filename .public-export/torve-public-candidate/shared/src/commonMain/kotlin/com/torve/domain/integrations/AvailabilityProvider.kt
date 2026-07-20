package com.torve.domain.integrations

import com.torve.domain.model.AvailabilityResult
import com.torve.domain.model.MediaType

interface AvailabilityProvider {
    suspend fun getAvailability(
        tmdbId: Int,
        mediaType: MediaType,
        region: String,
    ): AvailabilityResult
}

