package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class AvailabilityOfferType {
    SUBSCRIPTION,
    RENT,
    BUY,
    FREE,
    ADS,
}

@Serializable
data class AvailabilityOffer(
    val providerName: String,
    val offerType: AvailabilityOfferType,
    val deeplinkUrl: String? = null,
    val webUrl: String? = null,
    val logoUrl: String? = null,
)

@Serializable
data class AvailabilityResult(
    val tmdbId: Int,
    val mediaType: MediaType,
    val region: String,
    val offers: List<AvailabilityOffer>,
    val fetchedAt: Long,
)

