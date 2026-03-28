package com.torve.data.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin

actual fun platformHttpEngine(): HttpClientEngineFactory<*> = Darwin

actual fun platformTmdbHttpEngine(): HttpClientEngineFactory<*> = platformHttpEngine()

actual fun createEpgStreamingEngineFactory(
    forceIdentityEncoding: Boolean,
): HttpClientEngineFactory<*> = platformHttpEngine()
