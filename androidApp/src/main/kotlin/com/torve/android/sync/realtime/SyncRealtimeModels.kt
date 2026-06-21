package com.torve.android.sync.realtime

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SyncWsRegisterMessage(
    val type: String = "register",
    @SerialName("device_id")
    val deviceId: String,
)

@Serializable
data class SyncWsPingMessage(
    val type: String = "ping",
)

@Serializable
data class SyncWsAckMessage(
    val type: String = "ack",
    @SerialName("event_id")
    val eventId: String,
)

@Serializable
data class SyncWsEventEnvelope(
    val type: String,
    val event: SyncWsEventPayload? = null,
    val message: String? = null,
)

@Serializable
data class SyncWsEventPayload(
    val id: String,
    val type: String,
    val payload: JsonElement,
    @SerialName("created_at")
    val createdAt: String,
)

sealed class SyncRealtimeEvent {
    data object Connecting : SyncRealtimeEvent()
    data object Connected : SyncRealtimeEvent()
    data object Disconnected : SyncRealtimeEvent()
    data class Error(val message: String) : SyncRealtimeEvent()
    data class Message(
        val eventId: String,
        val eventType: String,
        val payload: JsonElement,
    ) : SyncRealtimeEvent()
}
