package com.torve.data.error

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Structured backend error parsed from API responses.
 *
 * The backend may return `detail` as:
 * - A plain string: `{"detail": "Invalid credentials"}`
 * - A structured object: `{"detail": {"code": "device_cap_reached", "message": "...", ...}}`
 * - A FastAPI validation array: `{"detail": [{"msg": "...", "type": "...", "loc": [...]}]}`
 *
 * This class normalizes all three forms into a [code] + [message] pair that
 * the client-side error mapper can process safely.
 */
data class BackendError(
    /** Machine-readable error code (e.g. "device_cap_reached", "premium_required"). Null for plain-string errors. */
    val code: String? = null,
    /** Human-readable message from the backend. Safe to log but must NOT be shown directly in UI. */
    val message: String? = null,
    /** The raw detail element, for structured payloads that include extra fields (e.g. active_devices list). */
    val rawDetail: JsonElement? = null,
)

private val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

/**
 * Parses a raw HTTP error response body into a [BackendError].
 *
 * Handles:
 * 1. `{"detail": "plain string"}` → code=null, message="plain string"
 * 2. `{"detail": {"code": "...", "message": "..."}}` → code + message extracted
 * 3. `{"detail": [{"msg": "..."}]}` → joined validation messages
 * 4. Malformed / unparseable → BackendError with message=null
 */
fun parseBackendError(responseBody: String): BackendError {
    val wrapper = runCatching {
        lenientJson.decodeFromString(ErrorWrapper.serializer(), responseBody)
    }.getOrNull() ?: return BackendError()

    return when (val detail = wrapper.detail) {
        is JsonPrimitive -> BackendError(
            message = detail.contentOrNull,
        )
        is JsonObject -> {
            val code = detail["code"]?.jsonPrimitive?.contentOrNull
                ?: detail["error_code"]?.jsonPrimitive?.contentOrNull
            val message = detail["message"]?.jsonPrimitive?.contentOrNull
                ?: detail["msg"]?.jsonPrimitive?.contentOrNull
            BackendError(code = code, message = message, rawDetail = detail)
        }
        is JsonArray -> {
            val messages = detail.mapNotNull { item ->
                (item as? JsonObject)?.get("msg")?.jsonPrimitive?.contentOrNull
            }
            BackendError(
                message = messages.joinToString("; ").ifEmpty { "Validation error" },
            )
        }
        null -> BackendError()
    }
}

/**
 * Extracts the machine-readable error code from a response body, or null.
 * Convenience for call sites that only need the code.
 */
fun extractBackendErrorCode(responseBody: String): String? =
    parseBackendError(responseBody).code

fun BackendError.maxDevices(): Int? {
    val detail = rawDetail as? JsonObject ?: return null
    return detail["max_devices"]?.jsonPrimitive?.intOrNull
        ?: detail["device_limit"]?.jsonPrimitive?.intOrNull
}

fun BackendError.activeDeviceCount(): Int? {
    val detail = rawDetail as? JsonObject ?: return null
    detail["active_device_count"]?.jsonPrimitive?.intOrNull?.let { return it }
    detail["active_devices"]?.let { active ->
        active.jsonPrimitiveOrNull()?.intOrNull?.let { return it }
        return runCatching { active.jsonArray.size }.getOrNull()
    }
    return null
}

fun BackendError.deviceLimitReachedMessage(): String? {
    if (code != "device_cap_reached") return null
    return maxDevices()?.let { max ->
        "You have reached your $max-device limit. Remove an existing device to continue."
    } ?: "Device limit reached. Remove an existing device to continue."
}

private fun JsonElement.jsonPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

@Serializable
private data class ErrorWrapper(
    val detail: JsonElement? = null,
)
