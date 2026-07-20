package com.torve.android.sync.lan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class LanSyncHttpClient(
    private val json: Json,
) {
    suspend fun fetchHello(service: LanResolvedService): Result<LanHelloResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val (_, body) = request(
                url = endpointUrl(service, PATH_HELLO),
                method = "GET",
            )
            json.decodeFromString(LanHelloResponse.serializer(), body)
        }
    }

    suspend fun claimPairingCode(
        service: LanResolvedService,
        request: LanPairClaimRequest,
    ): Result<LanPairClaimResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(LanPairClaimRequest.serializer(), request)
            val (_, body) = request(
                url = endpointUrl(service, PATH_PAIR_CLAIM),
                method = "POST",
                body = payload,
            )
            json.decodeFromString(LanPairClaimResponse.serializer(), body)
        }
    }

    suspend fun sendPairConfirm(
        service: LanResolvedService,
        confirmRequest: LanPairConfirmRequest,
    ): Result<LanStatusResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(LanPairConfirmRequest.serializer(), confirmRequest)
            val (_, body) = request(
                url = endpointUrl(service, PATH_PAIR_CONFIRM),
                method = "POST",
                body = payload,
            )
            json.decodeFromString(LanStatusResponse.serializer(), body)
        }
    }

    suspend fun sendEvent(
        service: LanResolvedService,
        event: LanEventEnvelope,
    ): Result<LanStatusResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(LanEventEnvelope.serializer(), event)
            val (_, body) = request(
                url = endpointUrl(service, PATH_EVENT),
                method = "POST",
                body = payload,
            )
            json.decodeFromString(LanStatusResponse.serializer(), body)
        }
    }

    private fun request(
        url: String,
        method: String,
        body: String? = null,
    ): Pair<Int, String> {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doInput = true
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) {
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else (connection.errorStream ?: connection.inputStream)
            val responseBody = stream?.use { input ->
                BufferedReader(InputStreamReader(input)).readText()
            }.orEmpty()
            if (status !in 200..299) {
                throw IllegalStateException("HTTP $status ${connection.responseMessage}: $responseBody")
            }
            status to responseBody
        } finally {
            connection.disconnect()
        }
    }

    private fun endpointUrl(service: LanResolvedService, path: String): String {
        val host = if (service.host.contains(":")) "[${service.host}]" else service.host
        return "http://$host:${service.port}$path"
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 3_000
        const val PATH_HELLO = "/sync/hello"
        const val PATH_PAIR_CLAIM = "/sync/pair/claim"
        const val PATH_PAIR_CONFIRM = "/sync/pair/confirm"
        const val PATH_EVENT = "/sync/event"
        const val PATH_CHANNEL_INITIATE = "/sync/channel/initiate"
        const val PATH_CHANNEL_CONFIRM = "/sync/channel/confirm"
    }

    suspend fun sendChannelInitiate(
        service: LanResolvedService,
        request: LanChannelInitiateRequest,
    ): Result<LanChannelInitiateResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(LanChannelInitiateRequest.serializer(), request)
            val (_, body) = request(
                url = endpointUrl(service, PATH_CHANNEL_INITIATE),
                method = "POST",
                body = payload,
            )
            json.decodeFromString(LanChannelInitiateResponse.serializer(), body)
        }
    }

    suspend fun sendChannelConfirm(
        service: LanResolvedService,
        confirmRequest: LanChannelConfirmRequest,
    ): Result<LanStatusResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(LanChannelConfirmRequest.serializer(), confirmRequest)
            val (_, body) = request(
                url = endpointUrl(service, PATH_CHANNEL_CONFIRM),
                method = "POST",
                body = payload,
            )
            json.decodeFromString(LanStatusResponse.serializer(), body)
        }
    }
}
