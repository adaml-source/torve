package com.torve.data.contentpolicy

/**
 * Single-point-of-change constants for the content policy backend API contract.
 *
 * ASSUMPTION: These field names match the live backend at
 *   {baseUrl}/me/content-policy  (GET, PATCH /dob, POST /enable-sensitive, POST /disable-sensitive)
 *
 * If the backend renames any field, update ONLY this object — the DTOs reference
 * these constants via @SerialName so the rest of the codebase is unaffected.
 *
 * Last verified: 2026-04-07 (naming convention alignment, not live-tested).
 */
object ContentPolicyContract {

    // ── GET /me/content-policy response fields ──
    const val FIELD_AGE_BAND = "age_band"
    const val FIELD_ADULT_ELIGIBLE = "adult_eligible"
    const val FIELD_SENSITIVE_MATERIAL_ENABLED = "sensitive_material_enabled"
    const val FIELD_SENSITIVE_MATERIAL_POLICY_VERSION = "sensitive_material_policy_version"
    const val FIELD_CURRENT_POLICY_VERSION = "current_policy_version"
    const val FIELD_POLICY_STATE_VERSION = "policy_state_version"

    // ── PATCH /me/content-policy/dob request field ──
    const val FIELD_DATE_OF_BIRTH = "date_of_birth"

    // ── Endpoint paths (relative to baseUrl) ──
    const val PATH_GET_POLICY = "/me/content-policy"
    const val PATH_SUBMIT_DOB = "/me/content-policy/dob"
    const val PATH_ENABLE_SENSITIVE = "/me/content-policy/enable-sensitive"
    const val PATH_DISABLE_SENSITIVE = "/me/content-policy/disable-sensitive"

    // ── Header ──
    const val HEADER_CHANNEL = "X-Torve-Channel"
    const val CHANNEL_GOOGLE_PLAY = "google_play"
}
