package com.streamvault.data.network

import io.ktor.client.engine.HttpClientEngineFactory

expect fun platformHttpEngine(): HttpClientEngineFactory<*>

expect fun createEpgStreamingEngineFactory(
    forceIdentityEncoding: Boolean,
): HttpClientEngineFactory<*>
