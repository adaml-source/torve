package com.torve.desktop.adult

/**
 * Single-shot per-tmdbId hint registry. Populated when the user clicks
 * a "Latest on Usenet" poster from the Movies tab so that the next
 * [V2App.launchPlayback] for the same tmdbId routes through the
 * TorBox-backed openPreResolvedStream path instead of the standard
 * Stremio-addon source resolver - which is the addressed user
 * complaint: "it shows movie posters without playable sources".
 *
 * Movie hints persist while the app is running so repeated Play clicks
 * from the same detail page keep using the NZB the user selected instead
 * of falling into the generic IMDb-based source resolver.
 */
object NzbPlaybackHints {
    private val byTmdbId = mutableMapOf<Int, NewznabItem>()

    /**
     * TV-shaped registry: a TMDB show id maps to ALL the NZB releases
     * we know about for that show. Populated when the user clicks a
     * Latest-on-Usenet TV poster. launchPlayback consumes the
     * matching episode by parsing each release's title and looking
     * for the requested S/E. Unlike the movies map, TV hints are NOT
     * single-shot - multiple episodes of the same show may be played
     * before the user navigates away.
     */
    private val tvByTmdbId = mutableMapOf<Int, List<NewznabItem>>()

    fun set(tmdbId: Int, nzb: NewznabItem) {
        synchronized(byTmdbId) { byTmdbId[tmdbId] = nzb }
    }

    fun consume(tmdbId: Int): NewznabItem? =
        synchronized(byTmdbId) { byTmdbId.remove(tmdbId) }

    fun peek(tmdbId: Int): NewznabItem? =
        synchronized(byTmdbId) { byTmdbId[tmdbId] }

    fun setTv(tmdbId: Int, releases: List<NewznabItem>) {
        synchronized(tvByTmdbId) { tvByTmdbId[tmdbId] = releases }
    }

    /**
     * Find the EXACT NZB for a given (tmdbId, season, episode). Returns
     * null if no exact match is found OR if the caller didn't supply
     * S/E - both situations should fall through to the standard source
     * picker rather than play a different episode than the one the
     * user clicked. (Earlier this method returned "most recent" as a
     * fallback, which played S1E9 when the user wanted S1E1 - never
     * what users expect.)
     *
     * If S/E aren't supplied (e.g. the user clicked the show poster
     * directly without picking an episode first), we DO fall back to
     * the most recent release - that's the only way the click can
     * resolve to anything at all.
     */
    fun findTvEpisode(tmdbId: Int, season: Int?, episode: Int?): NewznabItem? {
        val releases = synchronized(tvByTmdbId) { tvByTmdbId[tmdbId] }.orEmpty()
        if (releases.isEmpty()) return null
        if (season == null || episode == null) {
            return releases.firstOrNull()
        }
        return releases.firstOrNull { rel ->
            val parsed = NzbTvReleaseTitleParser.parse(rel.title) ?: return@firstOrNull false
            parsed.seasonNumber == season && parsed.episodeNumber == episode
        }
    }
}
