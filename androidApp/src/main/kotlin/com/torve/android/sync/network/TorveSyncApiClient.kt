package com.torve.android.sync.network

import com.torve.android.BuildConfig
import com.torve.android.sync.model.SyncAuthResponse
import com.torve.android.sync.model.SyncLoginRequest
import com.torve.android.sync.model.SyncEventDispatchResponse
import com.torve.android.sync.model.SyncLogoutRequest
import com.torve.android.sync.model.SyncPairingClaimRequest
import com.torve.android.sync.model.SyncPairingClaimResponse
import com.torve.android.sync.model.SyncPairingCodeRequest
import com.torve.android.sync.model.SyncPairingCodeResponse
import com.torve.android.sync.model.SyncPairingStatusRequest
import com.torve.android.sync.model.SyncPairingStatusResponse
import com.torve.android.sync.model.SyncPlaybackIntentRequest
import com.torve.android.sync.model.SyncRefreshRequest
import com.torve.android.sync.model.SyncRegisterRequest
import com.torve.android.sync.model.SyncSearchPushRequest
import com.torve.android.sync.model.SyncStatusMessage
import com.torve.android.sync.model.SyncDeviceDto
import com.torve.android.sync.model.SyncWatchStateLatestResponse
import com.torve.android.sync.model.SyncWatchStateReportRequest
import com.torve.android.sync.model.SyncWatchStateReportResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.HttpStatusCode

class TorveSyncApiClient(
    private val httpClient: HttpClient,
    private val baseUrl: String = BuildConfig.SYNC_BASE_URL,
) {
    suspend fun register(payload: SyncRegisterRequest): SyncAuthResponse {
        return httpClient.post("$baseUrl/auth/register") {
            setBody(payload)
        }.body()
    }

    suspend fun login(payload: SyncLoginRequest): SyncAuthResponse {
        return httpClient.post("$baseUrl/auth/login") {
            setBody(payload)
        }.body()
    }

    suspend fun refresh(refreshToken: String): SyncAuthResponse {
        return httpClient.post("$baseUrl/auth/refresh") {
            setBody(SyncRefreshRequest(refreshToken))
        }.body()
    }

    suspend fun logout(accessToken: String, refreshToken: String?): SyncStatusMessage {
        return httpClient.post("$baseUrl/auth/logout") {
            bearerAuth(accessToken)
            setBody(SyncLogoutRequest(refreshToken))
        }.body()
    }

    suspend fun createPairingCode(payload: SyncPairingCodeRequest): SyncPairingCodeResponse {
        return httpClient.post("$baseUrl/pairing/code") {
            setBody(payload)
        }.body()
    }

    suspend fun checkPairingStatus(payload: SyncPairingStatusRequest): SyncPairingStatusResponse {
        return httpClient.post("$baseUrl/pairing/status") {
            setBody(payload)
        }.body()
    }

    suspend fun claimPairingCode(accessToken: String, code: String): SyncPairingClaimResponse {
        return httpClient.post("$baseUrl/pairing/claim") {
            bearerAuth(accessToken)
            setBody(SyncPairingClaimRequest(code))
        }.body()
    }

    suspend fun getDevices(accessToken: String): List<SyncDeviceDto> {
        return httpClient.get("$baseUrl/devices") {
            bearerAuth(accessToken)
        }.body()
    }

    suspend fun revokeDevice(accessToken: String, deviceId: String): SyncStatusMessage {
        return httpClient.post("$baseUrl/devices/$deviceId/revoke") {
            bearerAuth(accessToken)
        }.body()
    }

    suspend fun sendSearchPush(
        accessToken: String,
        payload: SyncSearchPushRequest,
    ): SyncEventDispatchResponse {
        return httpClient.post("$baseUrl/events/search_push") {
            bearerAuth(accessToken)
            setBody(payload)
        }.body()
    }

    suspend fun sendPlaybackIntent(
        accessToken: String,
        payload: SyncPlaybackIntentRequest,
    ): SyncEventDispatchResponse {
        return httpClient.post("$baseUrl/events/playback_intent") {
            bearerAuth(accessToken)
            setBody(payload)
        }.body()
    }

    suspend fun reportWatchState(
        accessToken: String,
        payload: SyncWatchStateReportRequest,
    ): SyncWatchStateReportResponse {
        return httpClient.post("$baseUrl/me/watch_state/report") {
            bearerAuth(accessToken)
            setBody(payload)
        }.body()
    }

    /**
     * Fetch the newest watch-state report for this content across all of the
     * user's devices. Returns `null` when the backend has no row for the given
     * content yet (404) — callers fall back to local state.
     */
    suspend fun getLatestWatchState(
        accessToken: String,
        contentId: String,
    ): SyncWatchStateLatestResponse? {
        return try {
            httpClient.get("$baseUrl/me/watch_state/latest") {
                bearerAuth(accessToken)
                parameter("content_id", contentId)
            }.body()
        } catch (e: ClientRequestException) {
            if (e.response.status == HttpStatusCode.NotFound) null else throw e
        }
    }
}
