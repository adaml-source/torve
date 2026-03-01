package com.streamvault.android.sync.network

import com.streamvault.android.BuildConfig
import com.streamvault.android.sync.model.SyncAuthResponse
import com.streamvault.android.sync.model.SyncLoginRequest
import com.streamvault.android.sync.model.SyncEventDispatchResponse
import com.streamvault.android.sync.model.SyncLogoutRequest
import com.streamvault.android.sync.model.SyncPairingClaimRequest
import com.streamvault.android.sync.model.SyncPairingClaimResponse
import com.streamvault.android.sync.model.SyncPairingCodeRequest
import com.streamvault.android.sync.model.SyncPairingCodeResponse
import com.streamvault.android.sync.model.SyncPairingStatusRequest
import com.streamvault.android.sync.model.SyncPairingStatusResponse
import com.streamvault.android.sync.model.SyncPlaybackIntentRequest
import com.streamvault.android.sync.model.SyncRefreshRequest
import com.streamvault.android.sync.model.SyncRegisterRequest
import com.streamvault.android.sync.model.SyncSearchPushRequest
import com.streamvault.android.sync.model.SyncStatusMessage
import com.streamvault.android.sync.model.SyncDeviceDto
import com.streamvault.android.sync.model.SyncWatchStateReportRequest
import com.streamvault.android.sync.model.SyncWatchStateReportResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody

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
        return httpClient.post("$baseUrl/watch_state/report") {
            bearerAuth(accessToken)
            setBody(payload)
        }.body()
    }
}
