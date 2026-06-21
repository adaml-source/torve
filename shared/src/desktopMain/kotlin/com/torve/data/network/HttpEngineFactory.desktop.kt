package com.torve.data.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.cio.CIO

actual fun platformHttpEngine(): HttpClientEngineFactory<*> = CIO

actual fun platformTmdbHttpEngine(): HttpClientEngineFactory<*> = CIO

actual fun createEpgStreamingEngineFactory(
    forceIdentityEncoding: Boolean,
): HttpClientEngineFactory<*> {
    // Desktop uses CIO directly for now. If some providers require a forced
    // Accept-Encoding override on JVM as well, that should move into the
    // shared client configuration rather than the engine factory.
    return CIO
}
