package com.torve.data.addon

import com.torve.domain.model.CandidateProvenanceKind
import com.torve.domain.model.StartupConfidenceReasonCode
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
    @SerialName("info_hash") val infoHashSnake: String? = null,
    val hash: String? = null,
    val fileIdx: Int? = null,
    val url: String? = null,
    val externalUrl: String? = null,
    val magnet: String? = null,
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
    val magnetUrl: String? = null,
    val directUrl: String? = null,
    val size: String? = null,
    val codec: String? = null,
    val seeds: Int? = null,
    val source: String? = null,
    val isCached: Boolean = false,
    val score: Int = 0,
    val hdr: String? = null,
    val audioCodec: String? = null,
    /** Language codes parsed from Panda's 🗣️ tag (e.g. ["DE", "EN"]). Empty if none. */
    val languages: List<String> = emptyList(),
    val recentSuccessCount: Int = 0,
    val lastSuccessfulResolveAt: Long? = null,
    val accelerationMemoryId: String? = null,
    val accelerationSourceKey: String? = null,
    val accelerationProviderType: String? = null,
    val accelerationProvenanceKind: CandidateProvenanceKind? = null,
    val accelerationConfidenceReasons: List<StartupConfidenceReasonCode> = emptyList(),
    val accelerationScore: Double? = null,
    val accelerationScoreBreakdown: Map<String, Double> = emptyMap(),
    /**
     * Origin of the addon that produced this stream (e.g. "https://panda.torve.app").
     * Used to recognise addon-hosted direct URLs (Panda's /u/<token>/easynews/… or
     * /u/<token>/nzb/…) so the resolver can skip debrid unrestriction — those URLs
     * are already playable or 302-redirect to a playable URL. Null for streams
     * produced by code paths that don't know the origin (acceleration backend).
     */
    val addonBaseUrl: String? = null,
    /**
     * Full backend Usenet-candidate payload for rows whose
     * `accelerationProvenanceKind == USENET_NZBDAV`. Null for every
     * other provenance — non-NzbDAV rows render and resolve exactly as
     * before.
     *
     * Required so the warm/resolve coordinators can rebuild the live
     * backend's full `UsenetCandidate` request body (`candidate_id` +
     * `hash_key` + optional `nzb_url`). The mapper that constructs
     * USENET_NZBDAV `ParsedStream` instances must populate this field.
     */
    val usenetCandidate: com.torve.data.usenet.model.UsenetCandidatePayload? = null,
)

enum class ResolvableStreamKind {
    DIRECT_URL,
    MAGNET,
    INFO_HASH_ONLY,
    INVALID,
}

fun ParsedStream.resolvableKind(): ResolvableStreamKind = when {
    directUrl != null -> ResolvableStreamKind.DIRECT_URL
    magnetUrl != null -> ResolvableStreamKind.MAGNET
    infoHash != null -> ResolvableStreamKind.INFO_HASH_ONLY
    else -> ResolvableStreamKind.INVALID
}

fun ParsedStream.isPandaStream(): Boolean =
    addonName.equals("Panda", ignoreCase = true) ||
        addonBaseUrl?.contains("panda.torve.app", ignoreCase = true) == true

fun ParsedStream.isUsenetStream(): Boolean =
    accelerationProvenanceKind == CandidateProvenanceKind.USENET_NZBDAV ||
        accelerationProviderType?.contains("usenet", ignoreCase = true) == true ||
        source.containsUsenetMarker() ||
        title.containsUsenetMarker() ||
        directUrl?.contains("/nzb/", ignoreCase = true) == true ||
        directUrl?.contains("/easynews/", ignoreCase = true) == true ||
        directUrl?.endsWith(".nzb", ignoreCase = true) == true

fun ParsedStream.isTorrentOrDebridStream(): Boolean =
    !isUsenetStream() && (
        infoHash != null ||
            magnetUrl != null ||
            addonName.containsKnownTorrentProviderMarker() ||
            source.containsKnownTorrentProviderMarker() ||
            title.containsKnownTorrentProviderMarker() ||
            (isPandaStream() && directUrl != null)
        )

fun ParsedStream.canUseGenericStreamHandoff(): Boolean =
    !accelerationMemoryId.isNullOrBlank() && !isUsenetStream()

object LegacyStreamFallbackCompatibility {
    var allowDirectFallbackWithoutMemoryId: Boolean = true
}

fun ParsedStream.canUseLegacyDirectFallback(): Boolean =
    accelerationMemoryId.isNullOrBlank() &&
        !isUsenetStream() &&
        (directUrl != null || magnetUrl != null || infoHash != null)

fun String?.containsUsenetMarker(): Boolean {
    val value = this ?: return false
    return listOf(
        "usenet",
        "easynews",
        "nzb",
        "newznab",
        "scenenzb",
    ).any { marker -> value.contains(marker, ignoreCase = true) }
}

fun String?.containsKnownTorrentProviderMarker(): Boolean {
    val value = this ?: return false
    return listOf(
        "yts",
        "eztv",
        "1337",
        "pirate",
        "torrent",
        "nyaa",
        "kickass",
        "rutor",
        "rutracker",
        "rarbg",
        "magnet",
        "rd+",
        "rd download",
        "real-debrid",
        "realdebrid",
        "alldebrid",
        "premiumize",
        "torbox",
    ).any { marker -> value.contains(marker, ignoreCase = true) }
}

/**
 * True when this stream's [directUrl] is served by the addon that produced it
 * — not a hoster URL that needs debrid unrestriction. Compared by host (not
 * full URL) so Panda's per-user manifest path /u/<token>/... still matches
 * the plain panda.torve.app origin.
 */
fun ParsedStream.isAddonHostedUrl(): Boolean {
    val addonHost = addonBaseUrl?.let { extractHost(it) } ?: return false
    val streamHost = directUrl?.let { extractHost(it) } ?: return false
    return addonHost.equals(streamHost, ignoreCase = true)
}

internal fun String.isMagnetUri(): Boolean =
    trimStart().startsWith("magnet:", ignoreCase = true)

internal fun String.extractBtihInfoHash(): String? {
    if (!isMagnetUri()) return null
    val xtValue = split('&')
        .firstOrNull { part -> part.substringBefore('=').equals("magnet:?xt", ignoreCase = true) || part.substringBefore('=').equals("xt", ignoreCase = true) }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.substringAfterLast(':')
        ?.substringBefore('&')
        ?.trim()
    return xtValue
        ?.replace("%3A", ":", ignoreCase = true)
        ?.normalizeBtihInfoHash()
}

internal fun String.normalizeBtihInfoHash(): String? {
    val normalized = trim()
        .removePrefix("urn:btih:")
        .removePrefix("btih:")
        .substringBefore('&')
        .substringBefore('?')
        .trim()
        .lowercase()
    return normalized.takeIf { it.matches(Regex("^[a-f0-9]{40}$")) }
}

// Multiplatform URL.host isn't available in commonMain; pull the host out by
// hand. Handles http/https, ignores port, case-insensitive. Returns null for
// malformed input so callers fall through to the existing paths.
private fun extractHost(url: String): String? {
    val afterScheme = url.substringAfter("://", missingDelimiterValue = "")
        .takeIf { it.isNotEmpty() } ?: return null
    val hostWithPort = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    if (hostWithPort.isEmpty()) return null
    // Strip userinfo and port. Port may also be absent entirely.
    val noUserinfo = hostWithPort.substringAfterLast('@')
    return noUserinfo.substringBefore(':').takeIf { it.isNotEmpty() }
}

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
