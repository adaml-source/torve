package com.torve.desktop.playback

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.*
import java.time.Duration
import org.junit.Test
import kotlin.test.*

class AuthenticatedStreamProxyTest {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private fun get(url: String, range: String? = null): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(5))
        range?.let { request.header("Range", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    @Test fun rangeRequestsKeepAuthenticationAndExactBytes() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var auth: String? = null
        var range: String? = null
        upstream.createContext("/video") { exchange ->
            auth = exchange.requestHeaders.getFirst("X-Emby-Token")
            range = exchange.requestHeaders.getFirst("Range")
            exchange.responseHeaders.set("Content-Range", "bytes 2-5/10")
            exchange.sendResponseHeaders(206, 4)
            exchange.responseBody.use { it.write("2345".toByteArray()) }; exchange.close()
        }
        upstream.start()
        try {
            AuthenticatedStreamProxy(URI("http://127.0.0.1:${upstream.address.port}/video"), mapOf("X-Emby-Token" to "fixture-token")).use { proxy ->
                val response = get(proxy.url, "bytes=2-5")
                assertEquals(206, response.statusCode()); assertEquals("2345", response.body().decodeToString())
                assertEquals("bytes 2-5/10", response.headers().firstValue("Content-Range").orElse(null))
                assertEquals("fixture-token", auth); assertEquals("bytes=2-5", range)
                assertFalse(proxy.url.contains("fixture-token"))
            }
        } finally { upstream.stop(0) }
    }
    @Test fun redirectsNeverReceiveCredentialsAndWrongPathsNeverReachUpstream() {
        val upstream = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var requests = 0; var redirected = false
        upstream.createContext("/video") { exchange -> requests++; exchange.responseHeaders.set("Location", "/stolen"); exchange.sendResponseHeaders(302, -1); exchange.close() }
        upstream.createContext("/stolen") { exchange -> redirected = true; exchange.sendResponseHeaders(200, -1); exchange.close() }
        upstream.start()
        try {
            AuthenticatedStreamProxy(URI("http://127.0.0.1:${upstream.address.port}/video"), mapOf("Authorization" to "fixture-secret")).use { proxy ->
                assertEquals(403, get(proxy.url + "/extra").statusCode())
                assertEquals(403, get(proxy.url + "?extra=1").statusCode())
                assertEquals(0, requests)
                assertEquals(502, get(proxy.url).statusCode())
                assertEquals(1, requests); assertFalse(redirected)
            }
        } finally { upstream.stop(0) }
    }
    @Test fun revokedProfileCannotReadTheStream() {
        var allowed = false
        AuthenticatedStreamProxy(URI("http://127.0.0.1:1/unreachable"), emptyMap()) { allowed }.use { proxy ->
            assertEquals(403, get(proxy.url).statusCode())
        }
    }
}
