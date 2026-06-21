package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class CustomSection(
    val id: String,
    val title: String,
    val mediaType: String = "movie", // "movie", "tv", or "both"
    val filters: CustomSectionFilters = CustomSectionFilters(),
    val order: Int = 0,
    val enabled: Boolean = true,
)

@Serializable
data class CustomSectionFilters(
    val genreIds: List<Int> = emptyList(),
    val sortBy: String = "popularity.desc",
    val minRating: Float? = null,
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val originCountries: List<String> = emptyList(),
    val originalLanguage: String? = null,
    val runtimeGte: Int? = null,
    val runtimeLte: Int? = null,
    val certification: String? = null,
    val certificationGte: String? = null,
    val certificationLte: String? = null,
    val certificationCountry: String? = null,
    val withCast: List<SavedPerson> = emptyList(),
    val withCrew: List<SavedPerson> = emptyList(),
    val withWatchProviders: List<Int> = emptyList(),
    val watchRegion: String? = null,
    val withKeywords: List<Int> = emptyList(),
    val specificTmdbIds: List<SpecificTmdbItem> = emptyList(),
)

@Serializable
data class SpecificTmdbItem(
    val tmdbId: Int,
    val title: String,
    val mediaType: String, // "movie" or "tv"
)

@Serializable
data class SavedPerson(
    val id: Int,
    val name: String,
)
