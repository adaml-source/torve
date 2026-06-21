package com.torve.data.device

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessStateDtoTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    @Test
    fun parsesNeedsVerificationWhenPresent() {
        val access = json.decodeFromString(
            AccessStateDto.serializer(),
            """
            {
              "has_premium_access": false,
              "access_tier": "lifetime",
              "is_device_activated": false,
              "device_block_reason": null,
              "needs_verification": true
            }
            """.trimIndent(),
        )

        assertTrue(access.needs_verification)
    }

    @Test
    fun defaultsNeedsVerificationFalseWhenAbsent() {
        val access = json.decodeFromString(
            AccessStateDto.serializer(),
            """
            {
              "has_premium_access": true,
              "access_tier": "monthly",
              "is_device_activated": true,
              "device_block_reason": null
            }
            """.trimIndent(),
        )

        assertFalse(access.needs_verification)
    }
}
