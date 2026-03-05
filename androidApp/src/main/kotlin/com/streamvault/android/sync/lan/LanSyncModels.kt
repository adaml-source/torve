package com.streamvault.android.sync.lan

import com.streamvault.android.sync.model.SyncDeviceDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class LanHelloResponse(
    val status: String = "ok",
    val device: SyncDeviceDto,
)

@Serializable
data class LanPairClaimRequest(
    val code: String,
    @SerialName("source_device")
    val sourceDevice: SyncDeviceDto,
)

@Serializable
data class LanPairClaimResponse(
    val status: String,
    val device: SyncDeviceDto? = null,
    val message: String? = null,
)

@Serializable
data class LanEventEnvelope(
    @SerialName("event_id")
    val eventId: String,
    @SerialName("event_type")
    val eventType: String,
    @SerialName("source_device_id")
    val sourceDeviceId: String,
    @SerialName("target_device_id")
    val targetDeviceId: String,
    val payload: JsonElement,
)

@Serializable
data class LanStatusResponse(
    val status: String = "ok",
    val message: String? = null,
)

data class LanResolvedService(
    val serviceName: String,
    val host: String,
    val port: Int,
)
