package com.torve.data.entitlement

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parsing contract for the production backend's `VerifyResponse.error_code`.
 *
 * The field is optional so:
 * - Older backends without the contract still parse.
 * - Success responses that omit the field still parse.
 *
 * And when present, each of the six documented values round-trips as the
 * raw string — the client never converts it into an enum at deserialization
 * time, so a future backend can add new codes without breaking this client.
 */
class PurchaseVerifyDtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val basePurchase = """
        {
          "status": "ok",
          "purchase": {
            "id": "p1",
            "store": "google_play",
            "product_id": "com.torve.pro.lifetime",
            "purchase_type": "lifetime",
            "verification_status": "verified"
          },
          "entitlements": [],
          "premium_access": true
    """.trimIndent()

    @Test
    fun parses_response_without_error_code_field() {
        val body = "$basePurchase\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertNull(dto.error_code)
        assertEquals(true, dto.premium_access)
    }

    @Test
    fun parses_error_code_already_verified() {
        val body = "$basePurchase,\n  \"error_code\": \"already_verified\"\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals(PurchaseVerifyErrorCode.ALREADY_VERIFIED, dto.error_code)
    }

    @Test
    fun parses_error_code_config_missing() {
        val body = "$basePurchase,\n  \"error_code\": \"config_missing\"\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals(PurchaseVerifyErrorCode.CONFIG_MISSING, dto.error_code)
    }

    @Test
    fun parses_error_code_product_mismatch() {
        val body = "$basePurchase,\n  \"error_code\": \"product_mismatch\"\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals(PurchaseVerifyErrorCode.PRODUCT_MISMATCH, dto.error_code)
    }

    @Test
    fun parses_error_code_service_account_failure() {
        val body = "$basePurchase,\n  \"error_code\": \"service_account_failure\"\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals(PurchaseVerifyErrorCode.SERVICE_ACCOUNT_FAILURE, dto.error_code)
    }

    @Test
    fun parses_error_code_upstream_unreachable() {
        val body = "$basePurchase,\n  \"error_code\": \"upstream_unreachable\"\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals(PurchaseVerifyErrorCode.UPSTREAM_UNREACHABLE, dto.error_code)
    }

    @Test
    fun parses_error_code_not_verified() {
        val body = "$basePurchase,\n  \"error_code\": \"not_verified\"\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals(PurchaseVerifyErrorCode.NOT_VERIFIED, dto.error_code)
    }

    @Test
    fun unknown_error_code_preserved_for_future_compatibility() {
        // If the backend adds a new code, the client must still deserialize
        // cleanly. The ViewModel's mapping falls through to the generic
        // message for any code it doesn't recognize.
        val body = "$basePurchase,\n  \"error_code\": \"rate_limited\"\n}"
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals("rate_limited", dto.error_code)
    }

    @Test
    fun ignores_extra_unknown_fields() {
        val body = """
            {
              "status": "ok",
              "purchase": {
                "id": "p1",
                "store": "google_play",
                "product_id": "com.torve.pro.lifetime",
                "purchase_type": "lifetime",
                "verification_status": "verified"
              },
              "entitlements": [],
              "premium_access": false,
              "error_code": "config_missing",
              "diagnostic_trace_id": "abc-123",
              "debug_detail": "some internal thing we must NOT render"
            }
        """.trimIndent()
        val dto = json.decodeFromString<PurchaseVerifyDto>(body)
        assertEquals(PurchaseVerifyErrorCode.CONFIG_MISSING, dto.error_code)
        // Extra fields are dropped; there's no leakage channel for raw
        // backend internals via the DTO itself.
    }
}
