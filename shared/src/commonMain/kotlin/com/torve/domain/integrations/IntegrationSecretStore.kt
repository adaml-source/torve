package com.torve.domain.integrations

enum class IntegrationSecretKey {
    TRAKT_TOKENS,
    TRAKT_ACCESS_TOKEN,
    TRAKT_REFRESH_TOKEN,
    TRAKT_CLIENT_SECRET,
    TRAKT_CONNECTION_SCOPE,
    DEBRID_API_KEY,              // legacy single-key (migration only)
    DEBRID_API_KEY_REAL_DEBRID,
    DEBRID_API_KEY_ALL_DEBRID,
    DEBRID_API_KEY_PREMIUMIZE,
    DEBRID_API_KEY_TORBOX,
    DEBRID_RD_REFRESH_TOKEN,
    DEBRID_RD_CLIENT_ID,
    DEBRID_RD_CLIENT_SECRET,
    SIMKL_ACCESS_TOKEN,
    JELLYFIN_API_KEY,
    PLEX_ACCESS_TOKEN,
    CLAUDE_API_KEY,
    CHATGPT_API_KEY,
    GEMINI_API_KEY,
    PERPLEXITY_API_KEY,
    DEEPSEEK_API_KEY,
    MDBLIST_API_KEY,
    OMDB_API_KEY,
    OPENSUBTITLES_API_KEY,
    PANDA_TOKEN,
    /**
     * Panda per-config management token. Always persisted as DEVICE_ONLY: a
     * compromised Torve account must not leak write access to Panda configs.
     * Keyed by the server-issued `config_id` via the [IntegrationSecretStore]
     * subKey parameter so multiple Panda configs can coexist per install.
     */
    PANDA_MANAGEMENT_TOKEN,
    /**
     * Base URL of the user's NzbDAV instance, e.g. `https://nzbdav.example.com`.
     * Forwarded to the Torve backend — the app never speaks NzbDAV directly.
     */
    NZBDAV_BASE_URL,
    /**
     * API key for the user's NzbDAV instance. Forwarded to the backend on
     * save; after that the backend owns the credential and the app only
     * reads a sanitized status.
     */
    NZBDAV_API_KEY,
    /**
     * Local cache of Panda's NZB indexer API keys. Panda's read API
     * always returns indexer apiKeys as `[redacted]`, so the desktop's
     * Adult / Sports / Movies-via-Usenet tabs (which talk to the
     * Newznab indexer directly, not via Panda) lose access to the key
     * across restarts. We mirror the user-typed value here on save and
     * prefer it over the redacted server response on hydrate. SubKey is
     * `<indexerType>|<indexerUrl>` so multiple indexers coexist.
     */
    PANDA_INDEXER_API_KEY,
    /**
     * Same role as [PANDA_INDEXER_API_KEY] for the Panda download
     * client (e.g. TorBox API key). SubKey is `<downloadClientType>`.
     */
    PANDA_DOWNLOAD_CLIENT_API_KEY,
    /** Local cache of the Panda download client password (NZBget /
     *  SABnzbd self-hosted creds). SubKey is `<downloadClientType>`. */
    PANDA_DOWNLOAD_CLIENT_PASSWORD,
    /** Local cache of the Panda usenet provider password (Easynews /
     *  generic NNTP). SubKey is `<usenetProviderType>`. */
    PANDA_USENET_PASSWORD,
}

/**
 * Where an integration credential is persisted.
 * Users choose this when saving a credential — the app must clearly disclose
 * the behavior of each mode.
 */
enum class IntegrationStorageMode {
    /** Saved to Torve account; restored when signing in on another device. */
    ACCOUNT,
    /** Stored only on this device; never synced to the backend. */
    DEVICE_ONLY,
}

/**
 * Runtime state of an integration, derived from storage mode + local secret presence
 * + restore result. Separate from [IntegrationStorageMode] to avoid conflating
 * "where it's stored" with "is it usable right now".
 */
enum class IntegrationRuntimeState {
    /** Local secret present and usable. */
    CONNECTED,
    /** Account-mode integration whose credential restore failed or hasn't completed.
     *  The user may need to re-authenticate or re-enter the credential. */
    NEEDS_REAUTH,
    /** Device-only integration without a local secret on this device.
     *  The user must enter credentials manually. */
    NEEDS_CREDENTIALS,
    /** No integration metadata exists — never configured. */
    NOT_CONFIGURED,
}

/** Derive the runtime state from storage mode, local secret presence, and backend metadata. */
fun resolveRuntimeState(
    mode: IntegrationStorageMode?,
    hasLocalSecret: Boolean,
    hasBackendCredentials: Boolean = false,
): IntegrationRuntimeState = when {
    mode == null -> IntegrationRuntimeState.NOT_CONFIGURED
    hasLocalSecret -> IntegrationRuntimeState.CONNECTED
    mode == IntegrationStorageMode.ACCOUNT && hasBackendCredentials -> IntegrationRuntimeState.NEEDS_REAUTH
    mode == IntegrationStorageMode.DEVICE_ONLY -> IntegrationRuntimeState.NEEDS_CREDENTIALS
    mode == IntegrationStorageMode.ACCOUNT -> IntegrationRuntimeState.NEEDS_REAUTH
    else -> IntegrationRuntimeState.NOT_CONFIGURED
}

interface IntegrationSecretStore {
    /**
     * Persist [value] under [key]. When [subKey] is non-null the entry is
     * scoped — distinct subKeys under the same key coexist without collision.
     * Pass `null` to use the single-value slot (legacy behavior). Implementors
     * must never log the raw [value]. Use subKey for per-owner secrets such
     * as `PANDA_MANAGEMENT_TOKEN` keyed by `config_id`.
     */
    suspend fun put(key: IntegrationSecretKey, value: String, subKey: String? = null)
    suspend fun get(key: IntegrationSecretKey, subKey: String? = null): String?
    suspend fun remove(key: IntegrationSecretKey, subKey: String? = null)

    /** Persist the storage mode chosen by the user for [key]. */
    suspend fun setStorageMode(key: IntegrationSecretKey, mode: IntegrationStorageMode)

    /** Read the storage mode for [key]. Defaults to [IntegrationStorageMode.DEVICE_ONLY]. */
    suspend fun getStorageMode(key: IntegrationSecretKey): IntegrationStorageMode

    /** Remove ALL locally stored integration secrets and their mode flags.
     *  Called on logout/account-switch to prevent cross-user credential bleed.
     *  Account-mode secrets can be restored from the backend on next sign-in.
     *  Device-only secrets are gone — the user must re-enter them. */
    suspend fun clearAllSecrets()

    /** Check if a usable local secret exists for [key] and optional [subKey]. */
    suspend fun hasSecret(key: IntegrationSecretKey, subKey: String? = null): Boolean =
        get(key, subKey)?.isNotBlank() == true

    /**
     * Returns all subKeys stored under [key]. Useful when the caller needs to
     * enumerate scoped entries without knowing the subKeys in advance (e.g.
     * finding indexer entries whose subKey encodes a URL that isn't stored
     * separately). Implementations that don't support enumeration return an
     * empty list — callers must treat this as "unknown, not absent".
     */
    suspend fun getSubKeys(key: IntegrationSecretKey): List<String> = emptyList()

    /** Keys in the preferences DB that may contain legacy secret fallbacks.
     *  Must be cleared on logout alongside the secure store. */
    val legacyPreferenceSecretKeys: List<String>
        get() = listOf(
            "debrid_api_key",
            "trakt_access_token",
            "trakt_refresh_token",
            "simkl_access_token",
            "claude_api_key",
            "chatgpt_api_key",
            "gemini_api_key",
            "perplexity_api_key",
            "deepseek_api_key",
            "omdb_api_key",
            "mdblist_api_key",
            "jellyfin_api_key",
            "jellyfin_server_url",
            "plex_server_url",
        )
}
