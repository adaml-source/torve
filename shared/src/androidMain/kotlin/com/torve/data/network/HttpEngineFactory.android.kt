package com.torve.data.network

import com.torve.platform.torveVerboseLog
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import java.net.Inet4Address
import java.net.InetAddress
import okhttp3.Dns
import okhttp3.OkHttpClient

actual fun platformHttpEngine(): HttpClientEngineFactory<*> = OkHttp

actual fun platformTmdbHttpEngine(): HttpClientEngineFactory<*> {
    return object : HttpClientEngineFactory<OkHttpConfig> {
        override fun create(block: OkHttpConfig.() -> Unit): io.ktor.client.engine.HttpClientEngine {
            return OkHttp.create {
                block()
                preconfigured = OkHttpClient.Builder()
                    .dns(TmdbIpv4FirstDns())
                    .retryOnConnectionFailure(true)
                    .build()
            }
        }
    }
}

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

private class TmdbIpv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = Dns.SYSTEM.lookup(hostname)
        val ordered = addresses.sortedWith(
            compareBy<InetAddress> { if (it is Inet4Address) 0 else 1 }
                .thenBy { it.hostAddress ?: it.hostName },
        )
        torveVerboseLog {
            "TMDB_DNS host=$hostname resolved=${ordered.joinToString(",") { it.hostAddress ?: it.hostName }}"
        }
        return ordered
    }
}
