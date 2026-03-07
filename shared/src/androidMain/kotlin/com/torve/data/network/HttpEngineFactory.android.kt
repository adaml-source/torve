package com.torve.data.network

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import okhttp3.OkHttpClient

actual fun platformHttpEngine(): HttpClientEngineFactory<*> = OkHttp

actual fun createEpgStreamingEngineFactory(
    forceIdentityEncoding: Boolean,
): HttpClientEngineFactory<*> {
    return object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine {
            return OkHttp.create {
                block()
                if (forceIdentityEncoding) {
                    val base = OkHttpClient.Builder()
                        .addInterceptor { chain ->
                            println("ChannelsEPG: OkHttp interceptor firing, setting Accept-Encoding: identity")
                            val req = chain.request().newBuilder()
                                .header("Accept-Encoding", "identity")
                                .build()
                            chain.proceed(req)
                        }
                        .build()
                    preconfigured = base
                }
            }
        }
    }
}
