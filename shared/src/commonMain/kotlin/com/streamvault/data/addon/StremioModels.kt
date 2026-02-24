package com.streamvault.data.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ── Stream Models ──

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
    val externalUrl: String? = null,
    val ytId: String? = null,
    val behaviorHints: StemioBehaviorHints? = null,
)

@Serializable
data class StemioBehaviorHints(
    val bingeGroup: String? = null,
    val filename: String? = null,
    val countryWhitelist: List<String>? = null,
    val notWebReady: Boolean? = null,
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
    val isCached: Boolean = false,
    val score: Int = 0,
    val hdr: String? = null,
    val audioCodec: String? = null,
)

// ── Manifest Models ──

@Serializable
data class StremioManifest(
    val id: String = "",
    val name: String = "",
    val version: String = "",
    val description: String = "",
    val logo: String? = null,
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
    val extra: List<StremioCatalogExtra> = emptyList(),
    val genres: List<String> = emptyList(),
    val extraSupported: List<String> = emptyList(),
    val extraRequired: List<String> = emptyList(),
)

@Serializable
data class StremioCatalogExtra(
    val name: String,
    val isRequired: Boolean = false,
    val options: List<String> = emptyList(),
)

// ── Catalog Response Models ──

@Serializable
data class StremioCatalogResponse(
    val metas: List<StremioMeta> = emptyList(),
)

@Serializable
data class StremioMetaResponse(
    val meta: StremioMeta? = null,
)

@Serializable
data class StremioMeta(
    val id: String,
    val type: String = "",
    val name: String = "",
    val poster: String? = null,
    val posterShape: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val year: String? = null,
    val genres: List<String> = emptyList(),
    val runtime: String? = null,
    val cast: List<String> = emptyList(),
    val director: List<String> = emptyList(),
    val videos: List<StremioVideo> = emptyList(),
    val links: List<StremioLink> = emptyList(),
    val trailers: List<StremioTrailer> = emptyList(),
)

@Serializable
data class StremioVideo(
    val id: String,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    val released: String? = null,
    val overview: String? = null,
    val thumbnail: String? = null,
)

@Serializable
data class StremioLink(
    val name: String = "",
    val category: String = "",
    val url: String = "",
)

@Serializable
data class StremioTrailer(
    val source: String = "",
    val type: String = "",
)

// ── Subtitle Models ──

@Serializable
data class StremioSubtitleResponse(
    val subtitles: List<StremioSubtitle> = emptyList(),
)

@Serializable
data class StremioSubtitle(
    val id: String? = null,
    val url: String,
    val lang: String = "",
    val label: String? = null,
)
