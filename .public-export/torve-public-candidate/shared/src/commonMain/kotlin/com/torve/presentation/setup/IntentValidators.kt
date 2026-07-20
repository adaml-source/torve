package com.torve.presentation.setup

import com.torve.data.debrid.DebridClient
import com.torve.domain.integrations.LibraryOverlayService
import com.torve.domain.model.DebridServiceType
import com.torve.presentation.channels.ChannelsUiState
import com.torve.presentation.channels.EpgState
import com.torve.presentation.panda.PandaSetupUiState

/**
 * Concrete [IntentValidator]s — one per [SetupIntent]. Each one delegates
 * to an existing client or live UI state so the wizard's "validated"
 * verdict matches what the rest of the app sees.
 *
 * **No new network calls** for IPTV / Usenet — those are state-derived.
 * Debrid and Plex/Jellyfin already have lightweight test-connection APIs.
 *
 * **No secrets in returned strings** — every validator scrubs credential
 * material before returning. Same contract as `ProviderHealthChecker`.
 */

class DebridIntentValidator(
    private val providerSource: suspend () -> DebridServiceType?,
    private val apiKeySource: suspend () -> String?,
    private val debridClient: DebridClient,
) : IntentValidator {

    override val intent: SetupIntent = SetupIntent.DEBRID

    override suspend fun validate(): SetupIntentValidation {
        val provider = providerSource() ?: return SetupIntentValidation.notStarted(
            message = "No debrid provider selected.",
            nextAction = "Pick a debrid provider",
        )
        val key = apiKeySource()?.takeIf { it.isNotBlank() }
            ?: return SetupIntentValidation.notStarted(
                message = "No API key on file for ${provider.label()}.",
                nextAction = "Enter API key",
            )
        val result = runCatching { debridClient.verifyApiKey(provider, key) }.getOrElse { t ->
            return SetupIntentValidation.invalid(
                message = "Couldn't reach ${provider.label()}: ${t.message ?: t::class.simpleName}",
                nextAction = "Retry",
            )
        }
        return if (result.success) {
            SetupIntentValidation.ready(message = "${provider.label()} is connected.")
        } else {
            SetupIntentValidation.invalid(
                message = result.error?.takeIf { it.isNotBlank() }
                    ?: "${provider.label()} rejected the API key.",
                nextAction = "Re-enter API key",
            )
        }
    }

    private fun DebridServiceType.label(): String = when (this) {
        DebridServiceType.REAL_DEBRID -> "Real-Debrid"
        DebridServiceType.ALL_DEBRID -> "AllDebrid"
        DebridServiceType.PREMIUMIZE -> "Premiumize"
        DebridServiceType.TORBOX -> "TorBox"
    }
}

/**
 * IPTV path: projects the live channels state. The user can save a
 * playlist URL but `ChannelsViewModel` validates the parse — we just
 * read the result.
 *
 * Status mapping:
 *   - No playlists       → NOT_STARTED
 *   - Loading            → IN_PROGRESS
 *   - Parse error        → INVALID
 *   - 0 channels parsed  → NEEDS_ATTENTION (parse OK but empty)
 *   - Channels OK + EPG  → READY (or NEEDS_ATTENTION if EPG match < 50%)
 */
class IptvIntentValidator(
    private val stateSource: () -> ChannelsUiState,
) : IntentValidator {

    override val intent: SetupIntent = SetupIntent.IPTV

    override suspend fun validate(): SetupIntentValidation {
        val state = stateSource()
        if (state.playlists.isEmpty()) {
            return SetupIntentValidation.notStarted(
                message = "No IPTV playlist added.",
                nextAction = "Add a playlist",
            )
        }
        val activeName = state.selectedPlaylistId
            ?.let { id -> state.playlists.firstOrNull { it.id == id }?.name }
            ?: state.playlists.first().name
        val storedChannelCount = state.selectedPlaylistId
            ?.let { id -> state.playlists.firstOrNull { it.id == id }?.channelCount }
            ?: state.playlists.firstOrNull()?.channelCount
            ?: 0
        val channelCount = maxOf(state.channels.size, storedChannelCount)
        val hasLoadedContent = channelCount > 0 ||
            state.categories.isNotEmpty() ||
            state.groupedChannels.isNotEmpty()
        if (state.error != null && !hasLoadedContent) {
            return SetupIntentValidation.invalid(
                message = "Couldn't load \"$activeName\": ${state.error}",
                nextAction = "Re-add or refresh playlist",
            )
        }
        if (!hasLoadedContent && state.isLoadingChannels) {
            return SetupIntentValidation.inProgress(
                message = "Loading \"$activeName\"…",
            )
        }
        if (!hasLoadedContent) {
            return SetupIntentValidation.needsAttention(
                message = "\"$activeName\" loaded with 0 channels.",
                nextAction = "Refresh playlist",
            )
        }
        // Playlist OK; let EPG state degrade us to YELLOW if matching is poor.
        return when (val epg = state.epgState) {
            EpgState.NotConfigured -> SetupIntentValidation.ready(
                message = "\"$activeName\" — $channelCount channels (no EPG).",
            )
            EpgState.Loading -> SetupIntentValidation.ready(
                message = "\"$activeName\" — $channelCount channels (loading EPG).",
            )
            is EpgState.Error -> SetupIntentValidation.needsAttention(
                message = "Channels load OK but EPG failed: ${epg.message}",
                nextAction = "Check EPG URL",
            )
            is EpgState.Loaded -> {
                val matched = epg.matchedChannelCount
                val total = matched + epg.unmatchedChannelCount
                when {
                    // Placeholder state: ChannelsViewModel sets
                    // EpgState.Loaded with all counts zero on
                    // cache-first startup — the EPG grid builds match
                    // data lazily per category, so a full guide hasn't
                    // run yet. Reading this as "loaded but no channels
                    // matched" produced a false-negative on the hub
                    // (the actual player matches by name and works
                    // fine). Treat zero-total as "ready, counts not
                    // built yet" rather than an attention warning.
                    total == 0 -> SetupIntentValidation.ready(
                        message = "\"$activeName\" — $channelCount channels (EPG ready).",
                    )
                    matched == 0 -> SetupIntentValidation.invalid(
                        message = "0 of $total channels matched the EPG.",
                        nextAction = "Check tvg-id mapping",
                    )
                    else -> {
                        val pct = (matched * 100.0 / total).toInt()
                        if (pct < 50) {
                            SetupIntentValidation.needsAttention(
                                message = "Only $matched of $total channels matched the EPG ($pct%).",
                                nextAction = "Improve tvg-id mapping",
                            )
                        } else {
                            SetupIntentValidation.ready(
                                message = "\"$activeName\" — ${state.channels.size} channels, $matched/$total EPG matched.",
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Plex / Jellyfin path. Wraps [LibraryOverlayService.testConnection]. The
 * composite implementation tries both products, so this validator stays
 * product-neutral.
 */
class PlexJellyfinIntentValidator(
    private val serverUrlSource: suspend () -> String?,
    private val tokenSource: suspend () -> String?,
    private val service: LibraryOverlayService,
    private val productLabel: String = "Plex / Jellyfin",
) : IntentValidator {

    override val intent: SetupIntent = SetupIntent.PLEX_JELLYFIN

    override suspend fun validate(): SetupIntentValidation {
        val url = serverUrlSource()?.trim().orEmpty()
        val token = tokenSource()?.trim().orEmpty()
        if (url.isBlank() && token.isBlank()) {
            return SetupIntentValidation.notStarted(
                message = "Not connected. Add your $productLabel server.",
                nextAction = "Connect $productLabel",
            )
        }
        if (url.isBlank() || token.isBlank()) {
            return SetupIntentValidation.needsAttention(
                message = "Server URL and access token are both required.",
                nextAction = "Complete connection details",
            )
        }
        val ok = runCatching { service.testConnection(url, token) }.getOrElse { t ->
            return SetupIntentValidation.invalid(
                message = "Couldn't reach the server: ${t.message ?: t::class.simpleName}",
                nextAction = "Check server URL",
            )
        }
        return if (ok) {
            SetupIntentValidation.ready(message = "$productLabel is reachable.")
        } else {
            SetupIntentValidation.invalid(
                message = "Server rejected the credentials.",
                nextAction = "Re-enter token",
            )
        }
    }
}

/**
 * Usenet path — projection of [PandaSetupUiState]. Mirrors the per-row
 * logic in `PandaUsenetProviderHealthChecker` but rolled up into a single
 * intent verdict because the wizard UI presents Usenet as one concept.
 *
 * Three legs to cover:
 *   - At least one indexer with a usable API key.
 *   - Usenet provider host + password (or skip leg if `enableUsenet` is off).
 *   - Download client credential.
 */
class UsenetIntentValidator(
    private val stateSource: () -> PandaSetupUiState,
) : IntentValidator {

    override val intent: SetupIntent = SetupIntent.USENET

    override suspend fun validate(): SetupIntentValidation {
        val state = stateSource()
        if (!state.isEditMode) {
            return SetupIntentValidation.notStarted(
                message = "Panda not set up yet.",
                nextAction = "Run Panda setup",
            )
        }

        val configuredIndexers = state.nzbIndexers.filter { it.type != "none" }
        val indexersWithKey = configuredIndexers.filter {
            it.apiKey.isNotBlank() && !isRedacted(it.apiKey)
        }
        val downloadClientOk = downloadClientReady(state)
        // Special case: when the user has a debrid-style download
        // client (TorBox today) wired up with credentials AND at least
        // one indexer with an API key, the direct Usenet provider
        // password is unused — TorBox resolves NZBs server-side over
        // HTTP. The "missing password for generic" warning was
        // sending users to fix something they don't need. Mirrors the
        // same short-circuit in PandaUsenetProviderProviderHealthChecker.
        // SABnzbd / NZBGet still need direct NNTP creds.
        val debridNzbClientCoversIt = state.downloadClient.equals("torbox", ignoreCase = true) &&
            downloadClientOk &&
            indexersWithKey.isNotEmpty()
        val providerOk = !state.enableUsenet ||
            debridNzbClientCoversIt ||
            (state.usenetProvider != "none" &&
                state.usenetPassword.isNotBlank() &&
                !isRedacted(state.usenetPassword))

        if (configuredIndexers.isEmpty() && !state.enableUsenet && state.downloadClient == "none") {
            return SetupIntentValidation.notStarted(
                message = "No usenet pieces configured in Panda yet.",
                nextAction = "Add an indexer or provider",
            )
        }

        val problems = mutableListOf<String>()
        if (configuredIndexers.isEmpty()) {
            problems += "no NZB indexer"
        } else if (indexersWithKey.isEmpty()) {
            problems += "indexer API keys missing"
        } else if (indexersWithKey.size < configuredIndexers.size) {
            problems += "${configuredIndexers.size - indexersWithKey.size} indexer key(s) missing"
        }
        if (state.enableUsenet && !providerOk) {
            problems += "usenet provider password missing"
        }
        if (state.downloadClient != "none" && !downloadClientOk) {
            problems += "download client credentials missing"
        }
        if (state.downloadClient == "none") {
            problems += "no download client selected"
        }

        // Hard fails — won't function at all.
        if (configuredIndexers.isEmpty() && (state.downloadClient == "none" || !downloadClientOk)) {
            return SetupIntentValidation.invalid(
                message = "Usenet not usable yet: ${problems.joinToString(", ")}.",
                nextAction = "Continue Panda setup",
            )
        }
        if (configuredIndexers.isNotEmpty() && indexersWithKey.isEmpty()) {
            return SetupIntentValidation.invalid(
                message = "Indexer API keys are missing on this device.",
                nextAction = "Re-enter indexer API keys",
            )
        }

        return if (problems.isEmpty()) {
            SetupIntentValidation.ready(
                message = "${configuredIndexers.size} indexer(s) ready, " +
                    "download client: ${state.downloadClient}.",
            )
        } else {
            SetupIntentValidation.needsAttention(
                message = "Usenet partially set up: ${problems.joinToString(", ")}.",
                nextAction = "Continue Panda setup",
            )
        }
    }

    private fun downloadClientReady(state: PandaSetupUiState): Boolean {
        if (state.downloadClient == "none") return false
        val keyOk = state.downloadClientApiKey.isNotBlank() && !isRedacted(state.downloadClientApiKey)
        val pwOk = state.downloadClientPassword.isNotBlank() && !isRedacted(state.downloadClientPassword)
        return keyOk || pwOk
    }

    private fun isRedacted(value: String): Boolean =
        value.contains("redact", ignoreCase = true)
}
