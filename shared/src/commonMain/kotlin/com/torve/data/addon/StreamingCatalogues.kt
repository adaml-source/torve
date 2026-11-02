package com.torve.data.addon

import com.torve.domain.model.*
import io.ktor.http.Url

/** Adapter for the installed Streaming Catalogs protocol; never installs or substitutes a host. */
data class StreamingCatalogueService(val id: Int, val name: String, val logoUrl: String)

data class StreamingCatalogueSource(val addon: InstalledAddon, val catalog: AddonCatalog,
    val service: StreamingCatalogueService) {
    val key: String get() = com.torve.domain.player.MediaIdentityFactory.stableHash(addon.manifestUrl) + ":${catalog.type}:${catalog.id}"
}

fun streamingCatalogueSources(addons: List<InstalledAddon>): List<StreamingCatalogueSource> = addons
    .filter { it.isEnabled && it.manifest.id == "pw.ers.netflix-catalog" && it.policyFlags?.shelfEligible != false }
    .sortedBy { it.priority }
    .flatMap { addon ->
        val url = runCatching { Url(addon.manifestUrl) }.getOrNull() ?: return@flatMap emptyList()
        if (url.protocol.name !in setOf("https", "http")) return@flatMap emptyList()
        val origin = "${url.protocol.name}://${url.host}" +
            if (url.specifiedPort > 0 && url.specifiedPort != url.protocol.defaultPort) ":${url.specifiedPort}" else ""
        addon.manifest.catalogs.mapNotNull { catalog ->
            val definition = streamingCatalogDefinitions[catalog.id] ?: return@mapNotNull null
            if (catalog.type !in setOf("movie", "series") || catalog.extra.any { it.isRequired }) return@mapNotNull null
            StreamingCatalogueSource(addon, catalog, StreamingCatalogueService(definition.first,
                catalog.name?.takeIf { it.isNotBlank() } ?: definition.second.first,
                "$origin/${definition.second.second}.webp"))
        }
    }
    // One configured source per provider/type, respecting the user's extension priority.
    .distinctBy { it.service.id to it.catalog.type }.take(64)

// Catalogue IDs and static logo paths are declared by Streaming Catalogs' configuration UI.
// Positive IDs preserve existing Torve favourites; negative IDs belong only to this adapter.
private val streamingCatalogDefinitions = mapOf(
    "nfx" to (8 to ("Netflix" to "netflix")), "nfk" to (-1 to ("Netflix Kids" to "netflixkids")),
    "hbm" to (1899 to ("HBO Max" to "hbo")), "dnp" to (337 to ("Disney+" to "disney")),
    "amp" to (9 to ("Prime Video" to "prime")), "atp" to (350 to ("Apple TV+" to "apple")),
    "pmp" to (531 to ("Paramount+" to "paramount")), "pcp" to (386 to ("Peacock" to "peacock")),
    "hlu" to (15 to ("Hulu" to "hulu")), "cts" to (190 to ("Curiosity Stream" to "curiositystream")),
    "cru" to (283 to ("Crunchyroll" to "crunchyroll")), "mbi" to (11 to ("Mubi" to "mubi")),
    "stz" to (43 to ("Starz" to "starz")), "shd" to (439 to ("Shudder" to "shudder")),
    "bbo" to (380 to ("BritBox" to "britbox")),
    "mgl" to (-2 to ("MagellanTV" to "magellan")), "hay" to (-3 to ("Hayu" to "hayu")),
    "clv" to (-4 to ("Clarovideo" to "claro")), "gop" to (-5 to ("Globoplay" to "globo")),
    "jhs" to (-6 to ("JioHotstar" to "jiohotstar")), "zee" to (-7 to ("Zee5" to "zee5")),
    "nlz" to (-8 to ("NLZIET" to "nlziet")), "vil" to (-9 to ("Videoland" to "videoland")),
    "sst" to (-10 to ("SkyShowtime" to "skyshowtime")), "cpd" to (-11 to ("Canal+" to "canal-plus")),
    "dpe" to (-12 to ("Discovery+" to "discovery-plus")), "vik" to (-13 to ("Rakuten Viki" to "viki")),
    "sgo" to (-14 to ("Sky Go" to "skygo")), "sonyliv" to (-15 to ("SonyLIV" to "sonyliv")),
    "mp9" to (-16 to ("Movistar Plus+" to "movistar")), "act" to (-17 to ("Acorn TV" to "acorntv")),
    "itv" to (-18 to ("ITVX" to "itvx")), "bbc" to (-19 to ("BBC iPlayer" to "bbciplayer")),
    "al4" to (-20 to ("Channel 4" to "channel4")), "crc" to (258 to ("Criterion" to "criterion")),
    "iqi" to (-22 to ("iQIYI" to "iqiyi")), "sha" to (-23 to ("Shahid" to "shahid")),
)
