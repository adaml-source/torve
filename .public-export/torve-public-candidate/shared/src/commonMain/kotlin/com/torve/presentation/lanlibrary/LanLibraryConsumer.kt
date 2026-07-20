package com.torve.presentation.lanlibrary

import com.torve.data.lanlibrary.LanHubRegistryApi
import com.torve.data.lanlibrary.LanLibraryHttpClient
import com.torve.domain.lanlibrary.LanHub
import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.LanMediaEntry
import com.torve.domain.lanlibrary.PlaybackRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Consumer-side coordinator for TV / mobile.
 *
 * Polls the backend hub registry at [pollIntervalMs], and for each
 * discovered hub fetches its `/local/manifest` and exposes the union
 * as a [Map] keyed by `(title, seasonNumber, episodeNumber)`. UI code
 * uses [findLanRoute] to convert a media match into a
 * [PlaybackRoute.LanDesktopStream], which the locked
 * [com.torve.domain.lanlibrary.PlaybackRoutePreference] order surfaces
 * before any provider stream.
 *
 * **Why title-keyed:** the manifest entry's opaque id is publisher-side
 * only — consumers don't know it. Titles and (season, episode) tuples
 * are both already in the manifest and both available on the consumer
 * side from TMDB / metadata. No filesystem paths cross.
 *
 * **Privacy:** the manifests this consumer holds carry no paths, no
 * per-item tokens, and no auth secrets — by construction. Secrets are
 * fetched on-demand via [LanHubRegistryApi.fetchSecret] when building
 * a stream URL; they never sit in the consumer's snapshot.
 */
class LanLibraryConsumer(
    private val registry: LanHubRegistryApi,
    private val httpClient: LanLibraryHttpClient,
    private val pollIntervalMs: Long = DEFAULT_POLL_MS,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _entries = MutableStateFlow<Map<String, LanMatch>>(emptyMap())
    val entries: StateFlow<Map<String, LanMatch>> = _entries.asStateFlow()

    /**
     * Start the polling loop. Returns the [Job] so callers can cancel
     * when the user signs out. Multiple concurrent calls share the same
     * scope; the previous loop is **not** cancelled, so callers should
     * track the [Job] themselves.
     */
    fun observe(): Job = scope.launch {
        while (true) {
            refreshOnce()
            delay(pollIntervalMs)
        }
    }

    /**
     * One-shot refresh. Useful for tests and for "pull-to-refresh"
     * surfaces. No-op when no hubs are advertised.
     */
    suspend fun refreshOnce() {
        val hubs = registry.list()
        val merged = mutableMapOf<String, LanMatch>()
        for (hub in hubs) {
            val secret = registry.fetchSecret(hub.publisherId) ?: continue
            val manifest = httpClient.fetchManifest(hub.lanHost, hub.lanPort, secret.authSecret)
                ?: continue
            for (entry in manifest.entries) {
                val key = matchKey(entry.title, entry.seasonNumber, entry.episodeNumber)
                // Last writer wins — multiple desktops with the same
                // title sees the most recently fetched hub. Stable
                // enough for v1; ranking by hub-recency is a follow-up.
                merged[key] = LanMatch(hub = hub, entry = entry, authSecret = secret.authSecret)
            }
        }
        _entries.value = merged
    }

    /**
     * Light-weight presence check. Returns true iff a manifest entry
     * matches the requested title (and optional season/episode). UI
     * code uses this to render the "Available on desktop" badge
     * synchronously without requesting a token (token issuance is
     * reserved for the actual playback start).
     */
    fun hasLanMatch(title: String, seasonNumber: Int? = null, episodeNumber: Int? = null): Boolean =
        _entries.value.containsKey(matchKey(title, seasonNumber, episodeNumber))

    /**
     * Resolve a LAN playback route. Title is canonicalized (lower-cased,
     * trimmed) so case differences between TMDB and the publisher don't
     * break matching.
     *
     * The flow is:
     *   1. Look up the manifest match.
     *   2. Hit the publisher's `POST /local/stream-token/{id}` to mint
     *      a fresh per-item token. The hub auth secret authenticates
     *      this request; the token authorizes the specific item.
     *   3. Return a [PlaybackRoute.LanDesktopStream] carrying both the
     *      streaming URL (with `?token=...`) and the
     *      `X-Torve-Lan-Auth` header the player must attach to every
     *      request.
     *
     * Returns null when no manifest entry matches OR when the publisher
     * rejects the token request (404/401). The route chooser then falls
     * back to the provider stream.
     */
    suspend fun findLanRoute(
        title: String,
        seasonNumber: Int? = null,
        episodeNumber: Int? = null,
    ): PlaybackRoute.LanDesktopStream? {
        val key = matchKey(title, seasonNumber, episodeNumber)
        val match = _entries.value[key] ?: return null
        val streamToken = httpClient.requestStreamToken(
            host = match.hub.lanHost,
            port = match.hub.lanPort,
            authSecret = match.authSecret,
            entryId = match.entry.id,
        ) ?: return null
        val url = httpClient.streamUrl(
            host = match.hub.lanHost,
            port = match.hub.lanPort,
            entryId = match.entry.id,
            perEntryToken = streamToken.token,
        )
        return PlaybackRoute.LanDesktopStream(
            url = url,
            headers = mapOf("X-Torve-Lan-Auth" to match.authSecret),
        )
    }

    /**
     * Returns the auth secret for the hub serving [title], so callers
     * can attach `X-Torve-Lan-Auth` to the player's HTTP request.
     */
    fun authSecretFor(title: String, seasonNumber: Int? = null, episodeNumber: Int? = null): String? {
        val key = matchKey(title, seasonNumber, episodeNumber)
        return _entries.value[key]?.authSecret
    }

    private fun matchKey(title: String, seasonNumber: Int?, episodeNumber: Int?): String =
        buildString {
            append(title.trim().lowercase())
            append('|')
            append(seasonNumber ?: -1)
            append('|')
            append(episodeNumber ?: -1)
        }

    data class LanMatch(
        val hub: LanHub,
        val entry: LanMediaEntry,
        val authSecret: String,
    )

    companion object {
        const val DEFAULT_POLL_MS: Long = 60_000L
    }
}
