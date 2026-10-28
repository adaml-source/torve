package com.torve.data.trakt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TraktPersonalList(
    val name: String = "",
    val description: String? = null,
    val privacy: String = "private",
    val ids: TraktIds = TraktIds(),
    @SerialName("item_count") val itemCount: Int = 0,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class TraktListWrite(val name: String, val description: String? = null, val privacy: String = "private")

@Serializable
data class TraktListItemsWrite(
    val movies: List<TraktHistoryMovie>? = null,
    val shows: List<TraktHistoryMovie>? = null,
)

@Serializable
data class TraktListMutationResponse(
    val added: TraktListMutationCounts? = null,
    val existing: TraktListMutationCounts? = null,
    val deleted: TraktListMutationCounts? = null,
    @SerialName("not_found") val notFound: TraktListItemsWrite? = null,
) {
    fun requireItemMatched() {
        check(notFound?.movies.isNullOrEmpty() && notFound?.shows.isNullOrEmpty()) {
            "Trakt could not match this title."
        }
    }
}

@Serializable
data class TraktListMutationCounts(val movies: Int = 0, val shows: Int = 0)
