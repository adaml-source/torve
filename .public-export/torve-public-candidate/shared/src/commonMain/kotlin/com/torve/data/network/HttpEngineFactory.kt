package com.torve.data.network

import io.ktor.client.engine.HttpClientEngineFactory

expect fun platformHttpEngine(): HttpClientEngineFactory<*>

expect fun platformTmdbHttpEngine(): HttpClientEngineFactory<*>

expect fun createEpgStreamingEngineFactory(
    forceIdentityEncoding: Boolean,
): HttpClientEngineFactory<*>
