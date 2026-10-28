package com.torve.domain.integrations

/** Evidence from a read-only, authenticated connection check. Never carries secrets. */
enum class ConnectionProbeResult {
    CONNECTED, AUTH_REJECTED, UNREACHABLE, SETUP_INCOMPLETE, UNKNOWN_FAILURE,
}
