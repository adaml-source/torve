package com.torve.presentation.error

/**
 * Categorized, user-safe error representation.
 *
 * Every error surfaced in the UI MUST go through this type so that raw
 * exception messages, backend detail strings, SDK debug text, and object
 * references never reach end users.
 *
 * ViewModels store [UserFacingError] (or its [messageKey]) in UI state;
 * composables resolve the key to a localized string via resources.
 */
enum class UserFacingError(
    /** Machine-readable key used by the UI layer to resolve a localized string. */
    val messageKey: String,
) {
    // ── Device governance ──
    DEVICE_CAP_REACHED("error_device_cap_reached"),
    DEVICE_SWAP_LIMIT("error_device_swap_limit"),
    DEVICE_ALREADY_REMOVED("error_device_already_removed"),
    DEVICE_REMOVE_FAILED("error_device_remove_failed"),
    DEVICE_ACTIVATE_FAILED("error_device_activate_failed"),
    DEVICE_RENAME_FAILED("error_device_rename_failed"),
    DEVICE_FETCH_FAILED("error_device_fetch_failed"),
    DEVICE_REQUIRED("error_device_required"),
    DEVICE_NOT_AUTHORIZED("error_device_not_authorized"),

    // ── Auth / session ──
    NOT_LOGGED_IN("error_not_logged_in"),
    UNAUTHORIZED("error_unauthorized"),
    SESSION_EXPIRED("error_session_expired"),

    // ── Premium / entitlement ──
    NO_ENTITLEMENT("error_no_entitlement"),
    PREMIUM_REQUIRED("error_premium_required"),

    // ── Billing / purchase ──
    BILLING_UNAVAILABLE("error_billing_unavailable"),
    BILLING_INIT_FAILED("error_billing_init_failed"),
    PURCHASE_FAILED("error_purchase_failed"),
    PRODUCT_NOT_AVAILABLE("error_product_not_available"),
    ALREADY_OWNED("error_already_owned"),

    // ── Network ──
    NETWORK_FAILURE("error_network_failure"),
    TIMEOUT("error_timeout"),
    SERVER_ERROR("error_server_error"),
    RATE_LIMITED("error_rate_limited"),

    // ── Sync ──
    SYNC_FAILED("error_sync_failed"),

    // ── Integrations ──
    INTEGRATION_CONNECT_FAILED("error_integration_connect_failed"),
    INTEGRATION_AUTH_TIMEOUT("error_integration_auth_timeout"),
    INTEGRATION_AUTH_DENIED("error_integration_auth_denied"),
    INTEGRATION_AUTH_EXPIRED("error_integration_auth_expired"),
    INTEGRATION_AUTH_USED("error_integration_auth_used"),
    INTEGRATION_INVALID_KEY("error_integration_invalid_key"),
    INTEGRATION_SERVICE_UNAVAILABLE("error_integration_service_unavailable"),
    INTEGRATION_RATE_LIMITED("error_integration_rate_limited"),
    INTEGRATION_CONFIG_MISSING("error_integration_config_missing"),
    INTEGRATION_CONNECT_CHECK("error_integration_connect_check"),

    // ── Resource / duplicate ──
    ALREADY_REGISTERED("error_already_registered"),

    // ── Content / browsing ──
    CONTENT_LOAD_FAILED("error_content_load_failed"),
    SEARCH_FAILED("error_search_failed"),
    STREAMS_LOAD_FAILED("error_streams_load_failed"),
    STREAM_RESOLVE_FAILED("error_stream_resolve_failed"),
    STREAM_REFERENCE_UNAVAILABLE("error_stream_reference_unavailable"),
    STREAM_RESOLVE_TIMEOUT("error_stream_resolve_timeout"),
    STREAM_REAL_DEBRID_MISSING("error_stream_real_debrid_missing"),
    STREAM_REAL_DEBRID_RECONNECT("error_stream_real_debrid_reconnect"),
    STREAM_REAL_DEBRID_REFRESH_FAILED("error_stream_real_debrid_refresh_failed"),
    STREAM_REAL_DEBRID_SOURCE_BLOCKED("error_stream_real_debrid_source_blocked"),
    STREAM_NO_CACHED_SOURCE("error_stream_no_cached_source"),
    PLAYBACK_LINK_EXPIRED("error_playback_link_expired"),
    WATCHLIST_FAILED("error_watchlist_failed"),
    DOWNLOAD_FAILED("error_download_failed"),
    CHANNEL_LOAD_FAILED("error_channel_load_failed"),
    PROFILE_FAILED("error_profile_failed"),
    STATS_LOAD_FAILED("error_stats_load_failed"),
    ADDON_FAILED("error_addon_failed"),
    ADDON_INSTALL_FAILED("error_addon_install_failed"),
    CALENDAR_LOAD_FAILED("error_calendar_load_failed"),

    // ── Catch-all ──
    UNKNOWN("error_unknown"),
}

/**
 * Maps a raw [Throwable] to a safe [UserFacingError], inspecting the message
 * for well-known patterns.  The original exception is logged but NEVER
 * forwarded to UI state.
 */
fun Throwable.toUserFacingError(): UserFacingError {
    val msg = message?.lowercase().orEmpty()
    // Check for structured backend error codes first
    val fromCode = backendReasonToUserFacingError(msg)
    if (fromCode != UserFacingError.UNKNOWN) return fromCode
    return when {
        msg.contains("timeout") || msg.contains("timed out") -> UserFacingError.TIMEOUT
        msg.contains("unable to resolve host") ||
            msg.contains("no address associated") ||
            msg.contains("network") ||
            msg.contains("connect") && msg.contains("refused") -> UserFacingError.NETWORK_FAILURE
        msg.contains("401") || msg.contains("unauthorized") -> UserFacingError.UNAUTHORIZED
        msg.contains("403") || msg.contains("forbidden") -> UserFacingError.UNAUTHORIZED
        msg.contains("resource already registered") ||
            msg.contains("already registered") -> UserFacingError.ALREADY_REGISTERED
        msg.contains("premium_required") || msg.contains("premium required") -> UserFacingError.PREMIUM_REQUIRED
        msg.contains("device_required") || msg.contains("device_not_registered") -> UserFacingError.DEVICE_REQUIRED
        msg.contains("device_not_authorized") -> UserFacingError.DEVICE_NOT_AUTHORIZED
        msg.contains("stream_expired") || msg.contains("invalid_handoff") -> UserFacingError.PLAYBACK_LINK_EXPIRED
        msg.contains("device_cap_reached") || msg.contains("device cap reached") -> UserFacingError.DEVICE_CAP_REACHED
        msg.contains("429") || msg.contains("rate_limited") || msg.contains("rate") && msg.contains("limit") -> UserFacingError.RATE_LIMITED
        msg.contains("5") && msg.matches(Regex(".*\\b5\\d{2}\\b.*")) -> UserFacingError.SERVER_ERROR
        else -> UserFacingError.UNKNOWN
    }
}

/**
 * Maps a backend error detail / reason string to a [UserFacingError].
 */
fun backendReasonToUserFacingError(reason: String?): UserFacingError {
    return when (reason?.trim()?.lowercase()) {
        "device_cap_reached", "activation_slot_exhausted", "no_activation_slots" ->
            UserFacingError.DEVICE_CAP_REACHED
        "device_required", "device_not_registered" -> UserFacingError.DEVICE_REQUIRED
        "device_not_authorized" -> UserFacingError.DEVICE_NOT_AUTHORIZED
        "swap_limit_reached" -> UserFacingError.DEVICE_SWAP_LIMIT
        "already_removed" -> UserFacingError.DEVICE_ALREADY_REMOVED
        "not_found" -> UserFacingError.DEVICE_REMOVE_FAILED
        "no_entitlement" -> UserFacingError.NO_ENTITLEMENT
        "premium_required" -> UserFacingError.PREMIUM_REQUIRED
        "rate_limited" -> UserFacingError.RATE_LIMITED
        "stream_expired", "invalid_handoff" -> UserFacingError.PLAYBACK_LINK_EXPIRED
        "stream_reference_required", "stream_reference_not_found" -> UserFacingError.STREAM_REFERENCE_UNAVAILABLE
        "stream_handoff_unavailable" -> UserFacingError.STREAM_RESOLVE_FAILED
        "already_registered" -> UserFacingError.ALREADY_REGISTERED
        else -> UserFacingError.UNKNOWN
    }
}

/**
 * Default English fallback messages, keyed by [UserFacingError.messageKey].
 * The Android/iOS UI layer should prefer platform-localized string resources
 * but can fall back to these when a resource is missing.
 */
val defaultUserFacingMessages: Map<String, String> = mapOf(
    // Device governance
    "error_device_cap_reached" to "Device limit reached. Remove a device to continue.",
    "error_device_swap_limit" to "You\u2019ve reached the device swap limit. Try again later.",
    "error_device_already_removed" to "This device was already removed.",
    "error_device_remove_failed" to "Could not remove device.",
    "error_device_activate_failed" to "Could not activate this device. Please try again.",
    "error_device_rename_failed" to "Could not rename device.",
    "error_device_fetch_failed" to "Could not load devices. Please try again.",
    "error_device_required" to "This device needs to be set up before playback.",
    "error_device_not_authorized" to "This device is not authorized for playback. Manage your devices in account settings.",
    // Auth
    "error_not_logged_in" to "Please sign in to continue.",
    "error_unauthorized" to "Your session has expired. Please sign in again.",
    "error_session_expired" to "Your session has expired. Please sign in again.",
    // Historical compatibility
    "error_no_entitlement" to "This feature is available to everyone.",
    "error_premium_required" to "This feature is available to everyone.",
    // Historical billing compatibility
    "error_billing_unavailable" to "Billing is deprecated and not required for access.",
    "error_billing_init_failed" to "Store billing is deprecated and not required for access.",
    "error_purchase_failed" to "Purchase state does not affect access.",
    "error_product_not_available" to "No paid product is required for access.",
    "error_already_owned" to "This feature is available to everyone.",
    // Network
    "error_network_failure" to "Could not connect. Please check your internet connection.",
    "error_timeout" to "The request timed out. Please try again.",
    "error_server_error" to "Something went wrong on our end. Please try again later.",
    "error_rate_limited" to "Too many requests. Please wait and try again.",
    // Sync
    "error_sync_failed" to "Settings sync failed. Please try again.",
    // Integrations
    "error_integration_connect_failed" to "Could not connect to the service. Please try again.",
    "error_integration_auth_timeout" to "Authorization timed out. Please try again.",
    "error_integration_auth_denied" to "Authorization was denied.",
    "error_integration_auth_expired" to "Authorization code expired. Please try again.",
    "error_integration_auth_used" to "Authorization code already used. Please try again.",
    "error_integration_invalid_key" to "Invalid credentials. Please check and try again.",
    "error_integration_service_unavailable" to "The service is not available right now.",
    "error_integration_rate_limited" to "Too many requests. Please wait and try again.",
    "error_integration_config_missing" to "Required configuration is missing.",
    "error_integration_connect_check" to "Could not connect. Please check your settings.",
    // Resource
    "error_already_registered" to "This resource is already set up.",
    // Content / browsing
    "error_content_load_failed" to "Could not load content. Please try again.",
    "error_search_failed" to "Search failed. Please try again.",
    "error_streams_load_failed" to "Could not load streams. Please try again.",
    "error_stream_resolve_failed" to "Could not resolve stream. Try another source.",
    "error_stream_reference_unavailable" to "This source is no longer available. Refresh sources or try another one.",
    "error_stream_resolve_timeout" to "Stream resolution timed out. Try another source.",
    "error_stream_real_debrid_missing" to "Connect Real-Debrid in Panda to use this stream.",
    "error_stream_real_debrid_reconnect" to "Real-Debrid needs reconnecting. Open Settings > Advanced > Panda.",
    "error_stream_real_debrid_refresh_failed" to "Real-Debrid could not refresh your session. Reconnect it in Settings > Advanced > Panda.",
    "error_stream_real_debrid_source_blocked" to "Real-Debrid blocked this source. Try another source.",
    "error_stream_no_cached_source" to "No cached stream is available for this title.",
    "error_playback_link_expired" to "Playback link expired. Try playing this source again.",
    "error_watchlist_failed" to "Watchlist update failed. Please try again.",
    "error_download_failed" to "Download action failed. Please try again.",
    "error_channel_load_failed" to "Could not load channels. Please try again.",
    "error_profile_failed" to "Profile action failed. Please try again.",
    "error_stats_load_failed" to "Could not load stats. Please try again.",
    "error_addon_failed" to "Add-on action failed. Please try again.",
    "error_addon_install_failed" to "Could not install add-on. Check the URL and try again.",
    "error_calendar_load_failed" to "Could not load calendar. Please try again.",
    // Catch-all
    "error_unknown" to "Something went wrong. Please try again.",
)

/**
 * Resolves a [UserFacingError] to a default English string.
 * UI layer should prefer localized resources but can use this as fallback.
 */
fun UserFacingError.defaultMessage(): String =
    defaultUserFacingMessages[messageKey] ?: "Something went wrong. Please try again."
