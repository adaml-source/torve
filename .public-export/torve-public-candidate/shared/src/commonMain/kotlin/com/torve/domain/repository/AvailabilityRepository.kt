package com.torve.domain.repository

import com.torve.domain.model.AvailabilityResult
import com.torve.domain.model.MediaType

interface AvailabilityRepository {
    suspend fun getAvailability(
        tmdbId: Int,
        mediaType: MediaType,
        region: String,
    ): AvailabilityResult
}

