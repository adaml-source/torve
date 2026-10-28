package com.torve.desktop.playback

import com.sun.net.httpserver.HttpServer
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/** VLC cannot attach arbitrary headers. Serve one exact, authorized URL on loopback. */
class AuthenticatedStreamProxy(
    private val uri: URI,
    private val headers: Map<String, String>,
    private val authorized: () -> Boolean = { true },
) : AutoCloseable {
    private val tokenPath = "/${UUID.randomUUID()}"
    private val executor = Executors.newFixedThreadPool(4) { r -> Thread(r, "Torve authenticated media").apply { isDaemon = true } }
    private val streams = ConcurrentHashMap.newKeySet<InputStream>()
    @Volatile private var closed = false
    private val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build()
    private val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 4)
    val url: String get() = "http://127.0.0.1:${server.address.port}$tokenPath"

    init {
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.userInfo == null && uri.fragment == null)
        require(headers.all { (key, value) -> !key.any { it == '\r' || it == '\n' } && !value.any { it == '\r' || it == '\n' } })
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                if (closed || !authorized() || exchange.requestURI.rawPath != tokenPath || exchange.requestURI.rawQuery != null) {
                    exchange.sendResponseHeaders(403, -1); return@createContext
                }
                val method = exchange.requestMethod
                if (method != "GET" && method != "HEAD") { exchange.sendResponseHeaders(405, -1); return@createContext }
                val request = HttpRequest.newBuilder(uri).timeout(Duration.ofHours(12)).method(method, HttpRequest.BodyPublishers.noBody())
                headers.forEach { (key, value) -> request.header(key, value) }
                listOf("Range", "If-Range").forEach { key -> exchange.requestHeaders.getFirst(key)?.let { request.header(key, it) } }
                val response = client.send(request.build(), HttpResponse.BodyHandlers.ofInputStream())
                response.body().use { body ->
                    streams.add(body)
                    try {
                        if (!authorized() || closed) { exchange.sendResponseHeaders(403, -1); return@use }
                        if (response.statusCode() !in setOf(200, 206, 416)) {
                            exchange.sendResponseHeaders(if (response.statusCode() in setOf(401, 403)) 403 else 502, -1); return@use
                        }
                        listOf("Content-Type", "Content-Range", "Accept-Ranges", "ETag", "Last-Modified").forEach { key ->
                            response.headers().firstValue(key).ifPresent { exchange.responseHeaders.set(key, it) }
                        }
                        val length = response.headers().firstValueAsLong("Content-Length").orElse(0L)
                        if (method == "HEAD" || response.statusCode() == 416 || length == 0L && response.headers().firstValue("Content-Length").isPresent) {
                            if (length >= 0) exchange.responseHeaders.set("Content-Length", length.toString())
                            exchange.sendResponseHeaders(response.statusCode(), -1)
                        } else {
                            exchange.sendResponseHeaders(response.statusCode(), length.coerceAtLeast(0L))
                            exchange.responseBody.use { out ->
                                val buffer = ByteArray(64 * 1024)
                                while (!closed && authorized()) {
                                    val count = body.read(buffer)
                                    if (count < 0) break
                                    out.write(buffer, 0, count)
                                }
                            }
                        }
                    } finally { streams.remove(body) }
                }
            } catch (_: Exception) {
                runCatching { exchange.sendResponseHeaders(502, -1) }
            } finally { exchange.close() }
        }
        server.start()
    }

    override fun close() {
        closed = true
        streams.forEach { runCatching { it.close() } }
        streams.clear()
        server.stop(0)
        executor.shutdownNow()
    }
}
