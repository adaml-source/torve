package com.torve.data.availability

import com.torve.data.metadata.TmdbApiClient
import com.torve.data.metadata.TmdbWatchProvider
import com.torve.domain.integrations.AvailabilityProvider
import com.torve.domain.model.AvailabilityOffer
import com.torve.domain.model.AvailabilityOfferType
import com.torve.domain.model.AvailabilityResult
import com.torve.domain.model.MediaType
import kotlinx.datetime.Clock

class TmdbAvailabilityProvider(
    private val tmdbApi: TmdbApiClient,
) : AvailabilityProvider {
    override suspend fun getAvailability(
        tmdbId: Int,
        mediaType: MediaType,
        region: String,
    ): AvailabilityResult {
        val type = if (mediaType == MediaType.MOVIE) "movie" else "tv"
        val response = tmdbApi.getTitleWatchProviders(type, tmdbId)
        val regionProviders = response.results[region.uppercase()] ?: response.results["US"]
        val offers = if (regionProviders == null) {
            emptyList()
        } else {
            buildList {
                addAll(regionProviders.flatrate.map { it.toOffer(AvailabilityOfferType.SUBSCRIPTION, regionProviders.link) })
                addAll(regionProviders.free.map { it.toOffer(AvailabilityOfferType.FREE, regionProviders.link) })
                addAll(regionProviders.ads.map { it.toOffer(AvailabilityOfferType.ADS, regionProviders.link) })
                addAll(regionProviders.rent.map { it.toOffer(AvailabilityOfferType.RENT, regionProviders.link) })
                addAll(regionProviders.buy.map { it.toOffer(AvailabilityOfferType.BUY, regionProviders.link) })
            }
        }
        return AvailabilityResult(
            tmdbId = tmdbId,
            mediaType = mediaType,
            region = region.uppercase(),
            offers = offers,
            fetchedAt = Clock.System.now().toEpochMilliseconds(),
        )
    }
}

private fun TmdbWatchProvider.toOffer(
    offerType: AvailabilityOfferType,
    fallbackLink: String?,
): AvailabilityOffer {
    val logo = logoPath?.let { "${TmdbApiClient.IMAGE_BASE}/w154$it" }
    return AvailabilityOffer(
        providerName = providerName,
        offerType = offerType,
        deeplinkUrl = null,
        webUrl = fallbackLink,
        logoUrl = logo,
    )
}

