package com.torve.android.sync.lan

import android.util.Log
import com.torve.android.sync.model.SyncDeviceDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.Locale

class LanSyncServer(
    private val json: Json,
    private val selfDeviceProvider: () -> SyncDeviceDto,
    private val onPairClaim: (LanPairClaimRequest) -> LanPairClaimResponse,
    private val onPairConfirm: (LanPairConfirmRequest) -> LanStatusResponse,
    private val onInboundEvent: (LanEventEnvelope) -> LanStatusResponse,
    private val onChannelInitiate: (LanChannelInitiateRequest) -> LanChannelInitiateResponse,
    private val onChannelConfirm: (LanChannelConfirmRequest) -> LanStatusResponse,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    val port: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun startIfNeeded() {
        if (serverSocket != null) return
        val socket = ServerSocket(0)
        socket.reuseAddress = true
        serverSocket = socket
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (error: Exception) {
                    Log.w(TAG, "Accept error", error)
                    continue
                }
                launch { handleClient(client) }
            }
        }
    }

    @Synchronized
    fun stop() {
        runCatching { acceptJob?.cancel() }
        runCatching { serverSocket?.close() }
        acceptJob = null
        serverSocket = null
        scope.cancel()
    }

    private fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = SOCKET_TIMEOUT_MS
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val requestLine = readLine(input) ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) {
                writeJsonResponse(output, 400, LanStatusResponse(status = "bad_request", message = "Invalid request line."))
                return
            }
            val method = requestParts[0].uppercase(Locale.US)
            val path = requestParts[1]
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) {
                    headers[line.substring(0, idx).trim().lowercase(Locale.US)] = line.substring(idx + 1).trim()
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val body = if (contentLength > 0) {
                val bytes = ByteArray(contentLength)
                var offset = 0
                while (offset < contentLength) {
                    val read = input.read(bytes, offset, contentLength - offset)
                    if (read <= 0) break
                    offset += read
                }
                String(bytes, 0, offset, Charsets.UTF_8)
            } else {
                ""
            }

            try {
                when {
                    method == "GET" && path == PATH_HELLO -> {
                        val payload = LanHelloResponse(device = selfDeviceProvider())
                        writeJsonResponse(output, 200, payload)
                    }

                    method == "POST" && path == PATH_PAIR_CLAIM -> {
                        val request = json.decodeFromString(LanPairClaimRequest.serializer(), body)
                        val response = onPairClaim(request)
                        val status = if (response.status == "paired") 200 else 401
                        writeJsonResponse(output, status, response)
                    }

                    method == "POST" && path == PATH_PAIR_CONFIRM -> {
                        val request = json.decodeFromString(LanPairConfirmRequest.serializer(), body)
                        val response = onPairConfirm(request)
                        val status = if (response.status == "ok") 200 else 401
                        writeJsonResponse(output, status, response)
                    }

                    method == "POST" && path == PATH_EVENT -> {
                        val event = json.decodeFromString(LanEventEnvelope.serializer(), body)
                        val response = onInboundEvent(event)
                        val status = if (response.status == "ok") 200 else 400
                        writeJsonResponse(output, status, response)
                    }

                    method == "POST" && path == PATH_CHANNEL_INITIATE -> {
                        val request = json.decodeFromString(LanChannelInitiateRequest.serializer(), body)
                        val response = onChannelInitiate(request)
                        val status = if (response.status == "ok") 200 else 401
                        writeJsonResponse(output, status, response)
                    }

                    method == "POST" && path == PATH_CHANNEL_CONFIRM -> {
                        val request = json.decodeFromString(LanChannelConfirmRequest.serializer(), body)
                        val response = onChannelConfirm(request)
                        val status = if (response.status == "ok") 200 else 401
                        writeJsonResponse(output, status, response)
                    }

                    else -> {
                        writeJsonResponse(
                            output,
                            404,
                            LanStatusResponse(status = "not_found", message = "Unknown endpoint."),
                        )
                    }
                }
            } catch (error: SerializationException) {
                writeJsonResponse(
                    output,
                    400,
                    LanStatusResponse(status = "bad_request", message = error.message ?: "Invalid payload."),
                )
            } catch (error: Exception) {
                writeJsonResponse(
                    output,
                    500,
                    LanStatusResponse(status = "error", message = error.message ?: "Internal error."),
                )
            }
        }
    }

    private fun writeJsonResponse(
        output: java.io.OutputStream,
        statusCode: Int,
        payload: Any,
    ) {
        val body = when (payload) {
            is LanHelloResponse -> json.encodeToString(LanHelloResponse.serializer(), payload)
            is LanPairClaimResponse -> json.encodeToString(LanPairClaimResponse.serializer(), payload)
            is LanChannelInitiateResponse -> json.encodeToString(LanChannelInitiateResponse.serializer(), payload)
            is LanStatusResponse -> json.encodeToString(LanStatusResponse.serializer(), payload)
            else -> json.encodeToString(
                LanStatusResponse.serializer(),
                LanStatusResponse(status = "error", message = "Unsupported response payload."),
            )
        }
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val headers = buildString {
            append("HTTP/1.1 ")
            append(statusCode)
            append(' ')
            append(statusText(statusCode))
            append("\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ")
            append(bodyBytes.size)
            append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bodyBytes)
        output.flush()
    }

    private fun readLine(input: BufferedInputStream): String? {
        val buffer = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte == -1) {
                return if (buffer.isEmpty()) null else buffer.toString()
            }
            if (byte == '\n'.code) {
                break
            }
            if (byte != '\r'.code) {
                buffer.append(byte.toChar())
            }
        }
        return buffer.toString()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        else -> "Internal Server Error"
    }

    private companion object {
        const val TAG = "LanSyncServer"
        const val SOCKET_TIMEOUT_MS = 5_000
        const val PATH_HELLO = "/sync/hello"
        const val PATH_PAIR_CLAIM = "/sync/pair/claim"
        const val PATH_PAIR_CONFIRM = "/sync/pair/confirm"
        const val PATH_EVENT = "/sync/event"
        const val PATH_CHANNEL_INITIATE = "/sync/channel/initiate"
        const val PATH_CHANNEL_CONFIRM = "/sync/channel/confirm"
    }
}
