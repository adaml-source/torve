package com.torve.data.contentpolicy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ContentPolicyApiContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // ── Response DTO ──

    @Test
    fun responseDecodesCurrentBackendFields() {
        val dto = json.decodeFromString<ContentPolicyResponseDto>(
            """
            {
              "age_band": "ADULT",
              "adult_eligible": true,
              "sensitive_material_enabled": false,
              "sensitive_material_policy_version": 4,
              "current_policy_version": "8",
              "policy_state_version": 12
            }
            """.trimIndent(),
        )

        assertEquals("ADULT", dto.age_band)
        assertEquals(true, dto.adult_eligible)
        assertEquals(false, dto.sensitive_material_enabled)
        assertEquals("4", dto.sensitive_material_policy_version)
        assertEquals("8", dto.current_policy_version)
        assertEquals("12", dto.policy_state_version)
    }

    @Test
    fun responseHandlesAllFieldsMissing() {
        val dto = json.decodeFromString<ContentPolicyResponseDto>("{}")
        assertNull(dto.age_band)
        assertFalse(dto.adult_eligible)
        assertFalse(dto.sensitive_material_enabled)
        assertNull(dto.sensitive_material_policy_version)
        assertNull(dto.current_policy_version)
        assertNull(dto.policy_state_version)
    }

    @Test
    fun responseHandlesNullValues() {
        val dto = json.decodeFromString<ContentPolicyResponseDto>(
            """
            {
              "age_band": null,
              "adult_eligible": false,
              "sensitive_material_enabled": false,
              "sensitive_material_policy_version": null,
              "current_policy_version": null,
              "policy_state_version": null
            }
            """.trimIndent(),
        )
        assertNull(dto.age_band)
        assertNull(dto.sensitive_material_policy_version)
        assertNull(dto.current_policy_version)
        assertNull(dto.policy_state_version)
    }

    @Test
    fun responseIgnoresUnknownFields() {
        val dto = json.decodeFromString<ContentPolicyResponseDto>(
            """
            {
              "age_band": "ADULT",
              "adult_eligible": true,
              "sensitive_material_enabled": true,
              "some_future_field": "unexpected"
            }
            """.trimIndent(),
        )
        assertEquals("ADULT", dto.age_band)
        assertTrue(dto.adult_eligible)
        assertTrue(dto.sensitive_material_enabled)
    }

    @Test
    fun responseVersionFieldsAcceptNumericAndStringTypes() {
        // Numeric values
        val numericDto = json.decodeFromString<ContentPolicyResponseDto>(
            """{"policy_state_version": 42}""",
        )
        assertEquals("42", numericDto.policy_state_version)

        // String values
        val stringDto = json.decodeFromString<ContentPolicyResponseDto>(
            """{"policy_state_version": "42"}""",
        )
        assertEquals("42", stringDto.policy_state_version)
    }

    @Test
    fun responseVersionFieldsTreatBlankAsNull() {
        val dto = json.decodeFromString<ContentPolicyResponseDto>(
            """{"policy_state_version": "", "current_policy_version": "  "}""",
        )
        assertNull(dto.policy_state_version)
        assertNull(dto.current_policy_version)
    }

    // ── DOB Request DTO ──

    @Test
    fun dobRequestUsesSnakeCaseDateOfBirthField() {
        val encoded = json.encodeToString(
            ContentPolicyDobRequestDto.serializer(),
            ContentPolicyDobRequestDto(dateOfBirth = "1990-04-07"),
        )

        assertEquals("""{"date_of_birth":"1990-04-07"}""", encoded)
    }

    @Test
    fun dobRequestFieldNameMatchesContract() {
        val encoded = json.encodeToString(
            ContentPolicyDobRequestDto.serializer(),
            ContentPolicyDobRequestDto(dateOfBirth = "2000-01-15"),
        )
        val parsed = json.decodeFromString<JsonObject>(encoded)
        assertTrue(
            ContentPolicyContract.FIELD_DATE_OF_BIRTH in parsed,
            "DOB request must use field name '${ContentPolicyContract.FIELD_DATE_OF_BIRTH}'",
        )
        assertEquals("2000-01-15", parsed[ContentPolicyContract.FIELD_DATE_OF_BIRTH]?.jsonPrimitive?.content)
    }

    @Test
    fun dobRequestDecodesFromSnakeCaseJson() {
        val dto = json.decodeFromString<ContentPolicyDobRequestDto>(
            """{"date_of_birth":"1995-12-25"}""",
        )
        assertEquals("1995-12-25", dto.dateOfBirth)
    }

    // ── Response field names match contract constants ──

    @Test
    fun responseFieldNamesMatchContractConstants() {
        val fullJson = """
            {
              "${ContentPolicyContract.FIELD_AGE_BAND}": "ADULT",
              "${ContentPolicyContract.FIELD_ADULT_ELIGIBLE}": true,
              "${ContentPolicyContract.FIELD_SENSITIVE_MATERIAL_ENABLED}": true,
              "${ContentPolicyContract.FIELD_SENSITIVE_MATERIAL_POLICY_VERSION}": "1",
              "${ContentPolicyContract.FIELD_CURRENT_POLICY_VERSION}": "2",
              "${ContentPolicyContract.FIELD_POLICY_STATE_VERSION}": "3"
            }
        """.trimIndent()

        val dto = json.decodeFromString<ContentPolicyResponseDto>(fullJson)
        assertEquals("ADULT", dto.age_band)
        assertTrue(dto.adult_eligible)
        assertTrue(dto.sensitive_material_enabled)
        assertEquals("1", dto.sensitive_material_policy_version)
        assertEquals("2", dto.current_policy_version)
        assertEquals("3", dto.policy_state_version)
    }
}
