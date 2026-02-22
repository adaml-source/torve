package com.streamvault.data.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StremioStreamResponse(
    val streams: List<StremioStream> = emptyList(),
)

@Serializable
data class StremioStream(
    val name: String? = null,
    val title: String? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val behaviorHints: StemioBehaviorHints? = null,
)

@Serializable
data class StemioBehaviorHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
)

data class ParsedStream(
    val addonName: String,
    val quality: String,
    val title: String,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val directUrl: String? = null,
    val size: String? = null,
    val codec: String? = null,
    val seeds: Int? = null,
    val source: String? = null,
)

@Serializable
data class StremioManifest(
    val id: String,
    val name: String,
    val version: String = "",
    val description: String = "",
    val types: List<String> = emptyList(),
    val resources: List<StremioManifestResource> = emptyList(),
    val catalogs: List<StremioCatalog> = emptyList(),
    @SerialName("idPrefixes")
    val idPrefixes: List<String> = emptyList(),
)

@Serializable(with = StremioManifestResourceSerializer::class)
data class StremioManifestResource(
    val name: String,
    val types: List<String> = emptyList(),
    val idPrefixes: List<String> = emptyList(),
)

@Serializable
data class StremioCatalog(
    val type: String,
    val id: String,
    val name: String = "",
)
