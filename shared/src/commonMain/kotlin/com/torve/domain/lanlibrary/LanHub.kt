package com.torve.domain.lanlibrary

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Hub metadata returned by the backend registry's listing endpoint
 * `GET /me/lan/hubs`. Used by TV / mobile to discover desktop hubs on
 * the same account.
 *
 * **Privacy invariants** (Prompt 9 / 9B):
 *   - LAN-shape only: host, port, protocol version, label.
 *   - No filesystem paths, no per-item ids, no auth secret. The secret
 *     lives behind a separate authenticated endpoint
 *     (`GET /me/lan/hubs/{id}/secret`) that returns it only to the
 *     same user that published it.
 *   - `lanHost` is the local-network IP the publisher believes peers
 *     can reach it on. The backend never relays bytes — it only stores
 *     the metadata.
 */
@Serializable
data class LanHub(
    @SerialName("publisher_id") val publisherId: String,
    @SerialName("device_label") val deviceLabel: String,
    @SerialName("lan_host") val lanHost: String,
    @SerialName("lan_port") val lanPort: Int,
    @SerialName("protocol_version") val protocolVersion: Int = MANIFEST_VERSION,
    @SerialName("published_at_epoch_ms") val publishedAtEpochMs: Long,
)

/**
 * Body the publisher posts to `POST /me/lan/hubs`. Carries the same
 * metadata as [LanHub] plus the per-restart auth secret. The backend
 * stores the secret (encrypted at rest in production) and returns it
 * via the secret endpoint when the same user asks.
 *
 * Distinct from [LanHub] so listings never accidentally include the
 * secret — the listing schema is publish-write-only for the secret
 * field.
 */
@Serializable
data class LanHubPublishRequest(
    @SerialName("publisher_id") val publisherId: String,
    @SerialName("device_label") val deviceLabel: String,
    @SerialName("lan_host") val lanHost: String,
    @SerialName("lan_port") val lanPort: Int,
    @SerialName("protocol_version") val protocolVersion: Int = MANIFEST_VERSION,
    /**
     * The exact value the LAN HTTP server expects in `X-Torve-Lan-Auth`.
     * Generated in-process on the publisher; rotates on every server
     * restart so a leaked stale secret can't grant indefinite access.
     */
    @SerialName("auth_secret") val authSecret: String,
)

/**
 * Auth payload returned by the registry's per-hub secret endpoint. The
 * secret is the same string the publisher passes in
 * `X-Torve-Lan-Auth`; without it, the LAN HTTP server rejects every
 * request even from the same account.
 *
 * Lifetime: server-side this is short-lived (rotates each time the
 * publisher restarts). The client never persists it — re-fetch on every
 * playback attempt.
 */
@Serializable
data class LanHubSecret(
    @SerialName("publisher_id") val publisherId: String,
    @SerialName("auth_secret") val authSecret: String,
)

/**
 * Outcome of a LAN-stream URL build attempt. The data layer keeps
 * results explicit so the UI can route to the right setup screen
 * instead of swallowing failures silently.
 */
sealed interface LanHubStreamUrlResult {
    data class Ready(val url: String, val authSecret: String) : LanHubStreamUrlResult

    /** Backend registry returned 404/410/disabled. */
    data object RegistryUnavailable : LanHubStreamUrlResult

    /** No hub matched the requested item. */
    data object NotPublished : LanHubStreamUrlResult

    /** Hub matched but the per-item access token couldn't be fetched. */
    data object NoAccessToken : LanHubStreamUrlResult
}
