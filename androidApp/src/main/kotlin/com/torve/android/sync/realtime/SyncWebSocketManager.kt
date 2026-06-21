package com.torve.android.sync.realtime

import android.util.Log
import com.torve.android.BuildConfig
import com.torve.data.network.HttpClientFactory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.URLBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

class SyncWebSocketManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json: Json = HttpClientFactory.json
    private val client = HttpClient {
        install(WebSockets)
    }

    private val _events = MutableSharedFlow<SyncRealtimeEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SyncRealtimeEvent> = _events

    private var running = false

    fun start(accessTokenProvider: () -> String?, deviceIdProvider: () -> String?) {
        if (running) return
        running = true
        scope.launch {
            var backoffMs = 1_000L
            while (isActive && running) {
                val accessToken = accessTokenProvider()
                val deviceId = deviceIdProvider()
                if (accessToken.isNullOrBlank() || deviceId.isNullOrBlank()) {
                    delay(1_500L)
                    continue
                }

                _events.tryEmit(SyncRealtimeEvent.Connecting)
                try {
                    val wsUrl = URLBuilder(BuildConfig.SYNC_WS_URL).apply {
                        parameters.append("token", accessToken)
                    }.buildString()

                    val session = client.webSocketSession(urlString = wsUrl)
                    val registerPayload = json.encodeToString(
                        SyncWsRegisterMessage.serializer(),
                        SyncWsRegisterMessage(deviceId = deviceId),
                    )
                    session.send(Frame.Text(registerPayload))
                    _events.tryEmit(SyncRealtimeEvent.Connected)
                    backoffMs = 1_000L

                    while (isActive && running) {
                        val frame = session.incoming.receive()
                        if (frame is Frame.Text) {
                            val envelope = json.decodeFromString(
                                SyncWsEventEnvelope.serializer(),
                                frame.readText(),
                            )
                            when (envelope.type) {
                                "event" -> {
                                    envelope.event?.let { evt ->
                                        _events.tryEmit(
                                            SyncRealtimeEvent.Message(
                                                eventId = evt.id,
                                                eventType = evt.type,
                                                payload = evt.payload,
                                            ),
                                        )
                                        val ackPayload = json.encodeToString(
                                            SyncWsAckMessage.serializer(),
                                            SyncWsAckMessage(eventId = evt.id),
                                        )
                                        session.send(Frame.Text(ackPayload))
                                    }
                                }
                                "ready" -> _events.tryEmit(SyncRealtimeEvent.Connected)
                                "error" -> _events.tryEmit(
                                    SyncRealtimeEvent.Error(envelope.message ?: "Realtime error"),
                                )
                                else -> Unit
                            }
                        }
                    }
                    session.close()
                } catch (_: ClosedReceiveChannelException) {
                    _events.tryEmit(SyncRealtimeEvent.Disconnected)
                } catch (t: Throwable) {
                    Log.w(TAG, "WebSocket reconnect", t)
                    _events.tryEmit(SyncRealtimeEvent.Error(t.message ?: "WebSocket error"))
                    _events.tryEmit(SyncRealtimeEvent.Disconnected)
                }

                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            }
        }
    }

    fun stop() {
        running = false
    }

    private companion object {
        const val TAG = "SyncWebSocketManager"
    }
}
