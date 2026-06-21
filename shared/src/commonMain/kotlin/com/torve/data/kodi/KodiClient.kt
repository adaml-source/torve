package com.torve.data.kodi

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Kodi JSON-RPC client for remote playback control.
 */
class KodiClient(
    private val httpClient: HttpClient,
) {
    suspend fun ping(host: KodiHost): Boolean {
        return try {
            val resp: KodiRpcResponse = httpClient.post(host.jsonRpcUrl) {
                contentType(ContentType.Application.Json)
                setBody(KodiRpcRequest(method = "JSONRPC.Ping"))
            }.body()
            resp.result != null
        } catch (_: Exception) {
            false
        }
    }

    suspend fun playUrl(host: KodiHost, url: String): Boolean {
        return try {
            val params = buildJsonObject {
                put("item", buildJsonObject {
                    put("file", url)
                })
            }
            httpClient.post(host.jsonRpcUrl) {
                contentType(ContentType.Application.Json)
                setBody(KodiRpcRequest(method = "Player.Open", params = params))
            }
            true
        } catch (_: Exception) {
            false
        }
    }
}

data class KodiHost(
    val name: String,
    val ip: String,
    val port: Int = 8080,
) {
    val jsonRpcUrl: String get() {
        val sanitizedIp = ip.filter { it.isLetterOrDigit() || it == '.' || it == ':' || it == '-' }
        val sanitizedPort = port.coerceIn(1, 65535)
        return "http://$sanitizedIp:$sanitizedPort/jsonrpc"
    }
}

@Serializable
data class KodiRpcRequest(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null,
    val id: Int = 1,
)

@Serializable
data class KodiRpcResponse(
    val result: JsonElement? = null,
    val error: JsonElement? = null,
)
