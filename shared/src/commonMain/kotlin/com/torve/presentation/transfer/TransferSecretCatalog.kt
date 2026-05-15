package com.torve.presentation.transfer

import com.torve.domain.integrations.IntegrationSecretKey
import com.torve.domain.transfer.SecretCategory

/**
 * Static catalog of which [IntegrationSecretKey]s belong to which
 * [SecretCategory]. Shared between desktop and mobile/TV so both ends of
 * a transfer enumerate the same surface area.
 *
 * Adding a new category or key requires touching both [SecretCategory]
 * (shared enum) and this catalog. The receiver-side applier silently
 * skips key names it doesn't recognize, so a sender on a newer build
 * stays compatible with an older receiver.
 */
object TransferSecretCatalog {
    val specs: List<TransferCategorySpec> = listOf(
        TransferCategorySpec(
            category = SecretCategory.DEBRID,
            title = "Debrid",
            description = "Real-Debrid, AllDebrid, Premiumize, TorBox, and RD OAuth tokens.",
            keys = listOf(
                IntegrationSecretKey.DEBRID_API_KEY,
                IntegrationSecretKey.DEBRID_API_KEY_REAL_DEBRID,
                IntegrationSecretKey.DEBRID_API_KEY_ALL_DEBRID,
                IntegrationSecretKey.DEBRID_API_KEY_PREMIUMIZE,
                IntegrationSecretKey.DEBRID_API_KEY_TORBOX,
                IntegrationSecretKey.DEBRID_RD_REFRESH_TOKEN,
                IntegrationSecretKey.DEBRID_RD_CLIENT_ID,
                IntegrationSecretKey.DEBRID_RD_CLIENT_SECRET,
            ),
        ),
        TransferCategorySpec(
            category = SecretCategory.IPTV,
            title = "IPTV",
            // IPTV credentials live per-playlist in SQLite, not as
            // enum-keyed singletons. The sender ships the playlist rows
            // themselves via [SecretsTransferPayload.playlists]; this
            // category therefore reports zero enum keys but still
            // participates in transfers when selected.
            description = "Xtream + M3U playlists with their credentials.",
            keys = emptyList(),
        ),
        TransferCategorySpec(
            category = SecretCategory.PLEX_JELLYFIN,
            title = "Plex / Jellyfin",
            description = "Plex token and Jellyfin API key, plus their server URLs.",
            keys = listOf(
                IntegrationSecretKey.PLEX_ACCESS_TOKEN,
                IntegrationSecretKey.JELLYFIN_API_KEY,
            ),
        ),
        TransferCategorySpec(
            category = SecretCategory.TRAKT_SIMKL,
            title = "Trakt / SIMKL",
            description = "OAuth access, refresh, and client-secret tokens.",
            keys = listOf(
                IntegrationSecretKey.TRAKT_TOKENS,
                IntegrationSecretKey.TRAKT_ACCESS_TOKEN,
                IntegrationSecretKey.TRAKT_REFRESH_TOKEN,
                IntegrationSecretKey.TRAKT_CLIENT_SECRET,
                IntegrationSecretKey.SIMKL_ACCESS_TOKEN,
            ),
        ),
        TransferCategorySpec(
            category = SecretCategory.AI_KEYS,
            title = "AI and metadata keys",
            description = "OpenAI, Claude, Gemini, Perplexity, DeepSeek, MDBList, and OMDB keys.",
            keys = listOf(
                IntegrationSecretKey.CHATGPT_API_KEY,
                IntegrationSecretKey.CLAUDE_API_KEY,
                IntegrationSecretKey.GEMINI_API_KEY,
                IntegrationSecretKey.PERPLEXITY_API_KEY,
                IntegrationSecretKey.DEEPSEEK_API_KEY,
                IntegrationSecretKey.MDBLIST_API_KEY,
                IntegrationSecretKey.OMDB_API_KEY,
            ),
        ),
        TransferCategorySpec(
            category = SecretCategory.PANDA,
            title = "Panda / Usenet",
            description = "Panda token, NzbDAV credentials, and locally cached Panda provider keys.",
            keys = listOf(
                IntegrationSecretKey.PANDA_TOKEN,
                IntegrationSecretKey.NZBDAV_BASE_URL,
                IntegrationSecretKey.NZBDAV_API_KEY,
                IntegrationSecretKey.PANDA_INDEXER_API_KEY,
                IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_API_KEY,
                IntegrationSecretKey.PANDA_DOWNLOAD_CLIENT_PASSWORD,
                IntegrationSecretKey.PANDA_USENET_PASSWORD,
            ),
        ),
    )

    /**
     * Categories that ship at least one transferable item by default.
     * Includes IPTV — it carries playlists alongside the secret bag,
     * so the empty `keys` list shouldn't drop it from the default
     * selection.
     */
    val defaultSelectedCategories: Set<SecretCategory> =
        specs.filter { it.keys.isNotEmpty() || it.category == SecretCategory.IPTV }
            .map { it.category }
            .toSet()

    fun keysFor(category: SecretCategory): List<IntegrationSecretKey> =
        specs.firstOrNull { it.category == category }?.keys.orEmpty()

    fun titleFor(category: SecretCategory): String =
        specs.firstOrNull { it.category == category }?.title ?: category.name
}

data class TransferCategorySpec(
    val category: SecretCategory,
    val title: String,
    val description: String,
    val keys: List<IntegrationSecretKey>,
)
