package com.torve.domain.transfer

/**
 * Receiver-side gate on which `PreferencesRepository` keys an envelope
 * is allowed to overwrite. The applier rejects any [ConfigEntry] whose
 * key is not on this list — a paste of a hostile envelope cannot
 * silently overwrite arbitrary preferences.
 *
 * The allowlist is intentionally tiny in v1: only the handful of
 * non-secret values that a transferred secret *requires* in order to
 * function (e.g. a Plex token without its server URL is useless). It
 * grows as new credential categories show that they need companion
 * config.
 */
interface ConfigKeyAllowlist {
    /** True iff [key] is permitted to be written by an inbound transfer. */
    fun allows(key: String): Boolean

    /** Stable list — used by the sender to know what's worth shipping. */
    val keys: Set<String>
}

/**
 * Default allowlist: Plex + Jellyfin server URLs. Both are stored as
 * `PreferencesRepository` strings and required for their associated
 * tokens to authenticate against an actual server.
 */
class DefaultConfigKeyAllowlist : ConfigKeyAllowlist {
    override val keys: Set<String> = ALLOWLIST_V1
    override fun allows(key: String): Boolean = key in ALLOWLIST_V1

    companion object {
        const val PLEX_SERVER_URL = "plex_server_url"
        const val JELLYFIN_SERVER_URL = "jellyfin_server_url"

        val ALLOWLIST_V1: Set<String> = setOf(
            PLEX_SERVER_URL,
            JELLYFIN_SERVER_URL,
        )
    }
}
