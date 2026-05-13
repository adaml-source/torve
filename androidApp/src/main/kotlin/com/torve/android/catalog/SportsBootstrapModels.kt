package com.torve.android.catalog

import com.torve.data.usenet.NewznabItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal val SportsBootstrapJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

@Serializable
internal data class SportsBootstrapPayload(
    val savedAtMs: Long,
    val query: String = "",
    val selectedSportBucket: String? = null,
    val items: List<NewznabItem> = emptyList(),
)

internal fun sportsBootstrapKey(userId: String, indexerUrl: String, indexerKey: String): String {
    return "tv_sports_bootstrap:${userId.hashCode()}:${indexerUrl.hashCode()}:${indexerKey.hashCode()}"
}
