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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import io.ktor.http.isSuccess

/**
 * Kodi JSON-RPC client for remote playback control.
 */
class KodiClient(
    private val httpClient: HttpClient,
) {
    /** Uses Kodi's active player rather than assuming video player id 1 is running. */
    suspend fun control(host: KodiHost, action: KodiRemoteAction) {
        when (action) {
            KodiRemoteAction.UP, KodiRemoteAction.DOWN, KodiRemoteAction.LEFT, KodiRemoteAction.RIGHT,
            KodiRemoteAction.SELECT, KodiRemoteAction.BACK, KodiRemoteAction.HOME, KodiRemoteAction.CONTEXT_MENU -> {
                rpc(host, action.rpcMethod)
            }
            else -> {
                val players = rpc(host, "Player.GetActivePlayers") as? JsonArray
                val player = players?.mapNotNull { it.jsonObject["playerid"]?.jsonPrimitive?.intOrNull }?.firstOrNull()
                    ?: throw IllegalStateException("Kodi is not playing anything.")
                val params = buildJsonObject {
                    put("playerid", player)
                    if (action == KodiRemoteAction.SEEK_BACK || action == KodiRemoteAction.SEEK_FORWARD) {
                        put("value", if (action == KodiRemoteAction.SEEK_BACK) "smallbackward" else "smallforward")
                    }
                }
                rpc(host, action.rpcMethod, params)
            }
        }
    }

    private suspend fun rpc(host: KodiHost, method: String, params: JsonObject? = null): JsonElement {
        val response = httpClient.post(host.jsonRpcUrl) {
            contentType(ContentType.Application.Json)
            setBody(KodiRpcRequest(method = method, params = params))
        }
        check(response.status.isSuccess()) { "Kodi connection failed (${response.status.value})." }
        val body: JsonObject = response.body()
        check(body["error"] == null) { "Kodi could not perform this action." }
        return body["result"] ?: throw IllegalStateException("Kodi returned no result.")
    }
    suspend fun ping(host: KodiHost): Boolean {
        return try {
            val resp: KodiRpcResponse = httpClient.post(host.jsonRpcUrl) {
                contentType(ContentType.Application.Json)
                setBody(KodiRpcRequest(method = "JSONRPC.Ping"))
            }.body()
            resp.result != null
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
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
            rpc(host, "Player.Open", params)
            true
        } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            false
        }
    }
}

enum class KodiRemoteAction(val rpcMethod: String) {
    UP("Input.Up"), DOWN("Input.Down"), LEFT("Input.Left"), RIGHT("Input.Right"),
    SELECT("Input.Select"), BACK("Input.Back"), HOME("Input.Home"), CONTEXT_MENU("Input.ContextMenu"),
    PLAY_PAUSE("Player.PlayPause"), STOP("Player.Stop"), SEEK_BACK("Player.Seek"), SEEK_FORWARD("Player.Seek");
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
