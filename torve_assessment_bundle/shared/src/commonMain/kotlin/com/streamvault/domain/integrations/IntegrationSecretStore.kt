package com.streamvault.domain.integrations

enum class IntegrationSecretKey {
    TRAKT_TOKENS,
    TRAKT_ACCESS_TOKEN,
    TRAKT_REFRESH_TOKEN,
    TRAKT_CLIENT_SECRET,
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
}

interface IntegrationSecretStore {
    suspend fun put(key: IntegrationSecretKey, value: String)
    suspend fun get(key: IntegrationSecretKey): String?
    suspend fun remove(key: IntegrationSecretKey)
}
