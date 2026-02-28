package com.streamvault.domain.repository

import com.streamvault.domain.model.AvailabilityResult
import com.streamvault.domain.model.MediaType

interface AvailabilityRepository {
    suspend fun getAvailability(
        tmdbId: Int,
        mediaType: MediaType,
        region: String,
    ): AvailabilityResult
}

