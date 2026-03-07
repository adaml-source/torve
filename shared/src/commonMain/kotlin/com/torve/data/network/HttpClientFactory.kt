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
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }

        install(Logging) {
            level = LogLevel.NONE
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
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 120_000
        }

        install(Logging) {
            level = LogLevel.NONE
        }
    }
}
