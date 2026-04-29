package com.torve.data.network

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

object HttpClientFactory {
    const val DEFAULT_REQUEST_TIMEOUT_MS = 30_000L
    const val DEFAULT_CONNECT_TIMEOUT_MS = 10_000L
    const val DEFAULT_SOCKET_TIMEOUT_MS = 30_000L

    const val EPG_REQUEST_TIMEOUT_MS = 120_000L
    const val EPG_CONNECT_TIMEOUT_MS = 20_000L
    const val EPG_SOCKET_TIMEOUT_MS = 120_000L

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    fun create(): HttpClient = HttpClient(platformHttpEngine()) {
        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
        }

        install(Logging) {
            level = LogLevel.HEADERS
            // Authorization carries Bearer tokens for every integration
            // (Panda manifest + management, Trakt, Simkl, debrid, Torve
            // backend). Redact so HEADERS logging never leaks raw secrets
            // into logcat / stdout / crash reports.
            sanitizeHeader { it.equals("Authorization", ignoreCase = true) }
        }

        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    fun createTmdb(): HttpClient = HttpClient(platformTmdbHttpEngine()) {
        install(ContentNegotiation) {
            json(json)
        }

        install(HttpTimeout) {
            requestTimeoutMillis = DEFAULT_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = DEFAULT_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = DEFAULT_SOCKET_TIMEOUT_MS
        }

        install(Logging) {
            level = LogLevel.HEADERS
            // Authorization carries Bearer tokens for every integration
            // (Panda manifest + management, Trakt, Simkl, debrid, Torve
            // backend). Redact so HEADERS logging never leaks raw secrets
            // into logcat / stdout / crash reports.
            sanitizeHeader { it.equals("Authorization", ignoreCase = true) }
        }

        defaultRequest {
            contentType(ContentType.Application.Json)
        }
    }

    fun createEpgStreamingClient(
        forceIdentityEncoding: Boolean,
    ): HttpClient = HttpClient(createEpgStreamingEngineFactory(forceIdentityEncoding)) {
        expectSuccess = false

        install(HttpTimeout) {
            requestTimeoutMillis = EPG_REQUEST_TIMEOUT_MS
            connectTimeoutMillis = EPG_CONNECT_TIMEOUT_MS
            socketTimeoutMillis = EPG_SOCKET_TIMEOUT_MS
        }

        install(Logging) {
            level = LogLevel.NONE
        }
    }
}
