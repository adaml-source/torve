package com.torve.data.device

import com.torve.data.auth.AuthClient
import io.ktor.client.HttpClient
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable

@Serializable
data class DeviceActivityReport(
    val installation_id: String,
    val platform: String,
    val session_id: String,
    val sequence: Int,
    val foreground_ms: Long,
    val playback_ms: Long,
    val manufacturer: String,
    val device_model: String,
    val os_version: String,
    val app_version: String?,
    val app_build: String?,
)

class DeviceActivityApi(private val httpClient: HttpClient, private val auth: AuthClient) {
    suspend fun report(owner: String, body: DeviceActivityReport): Boolean {
        return try {
            val token = auth.getValidAccessToken() ?: return false
            if (auth.authUserFlow.value?.id != owner) return false
            httpClient.post("${AuthClient.DEFAULT_BASE_URL}/me/device-activity") {
                bearerAuth(token); contentType(ContentType.Application.Json); setBody(body)
            }.status.isSuccess()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}
