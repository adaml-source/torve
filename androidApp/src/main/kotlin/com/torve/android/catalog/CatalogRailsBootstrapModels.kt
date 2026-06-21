package com.torve.android.catalog

import com.torve.domain.model.MediaItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val CATALOG_RAILS_BOOTSTRAP_PREFIX = "catalog_rails_bootstrap_v1_"
internal const val PUBLIC_CATALOG_RAILS_USER_ID = "__public_catalog__"

internal val CatalogRailsBootstrapJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

@Serializable
internal data class CatalogRailsBootstrapPayload(
    val savedAtMs: Long,
    val mediaType: String,
    val rails: List<CatalogRailsBootstrapRail> = emptyList(),
)

@Serializable
internal data class CatalogRailsBootstrapRail(
    val key: String,
    val items: List<MediaItem> = emptyList(),
)

internal fun catalogRailsBootstrapKey(userId: String, mediaType: String): String {
    return "$CATALOG_RAILS_BOOTSTRAP_PREFIX${userId.hashCode()}_$mediaType"
}
