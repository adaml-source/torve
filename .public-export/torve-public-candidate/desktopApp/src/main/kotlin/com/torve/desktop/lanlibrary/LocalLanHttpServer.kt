package com.torve.desktop.lanlibrary

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.torve.domain.lanlibrary.LanLibraryManifest
import com.torve.domain.lanlibrary.RangeParseResult
import com.torve.domain.lanlibrary.RangeRequest
import com.torve.domain.lanlibrary.RangeResponse
import kotlinx.serialization.json.Json
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * JDK `HttpServer` exposing three routes:
 *
 *   GET  /local/manifest                  - JSON manifest, no paths
 *   POST /local/stream-token/{id}         - issues a per-id stream token
 *                                          (Prompt 9C). Hub auth header
 *                                          required; uniform 404 on
 *                                          unknown ids so a bystander
 *                                          can't probe the id space.
 *   GET  /local/stream/{id}?token=...     - ranged file stream
 *
 * Bind model (Prompt 9):
 *   - Loopback by default - only the desktop talks to itself. This is
 *     what runs whenever the master `lanServingEnabled` toggle is on
 *     but the explicit LAN-bind toggle is off.
 *   - LAN-bind mode - when both toggles are on, the server binds to
 *     the wildcard address so peer devices on the same LAN can connect.
 *     Activated only via [start] with `bindToLan = true`.
 *
 * Auth model (unchanged):
 *   - Both routes require header `X-Torve-Lan-Auth: <serverSecret>`.
 *     Wrong/missing → 401 with no body.
 *   - The stream route ALSO requires a `?token=...` query that
 *     validates against [LanMediaTokenTable.resolveTokenForId] for the
 *     `{id}` path segment AND the resolved file passing
 *     [DownloadFolderAllowlist.isAllowed]. Any of those failing → 404
 *     (we don't distinguish "wrong token" from "wrong id" so a
 *     bystander can't probe the id space).
 *
 * The token + the secret are kept in process memory; both regenerate
 * on every server start.
 */
class LocalLanHttpServer(
    private val manifestProvider: () -> LanLibraryManifest,
    private val tokenTable: LanMediaTokenTable,
    private val allowlist: DownloadFolderAllowlist,
) {
    private val json = Json { encodeDefaults = true }
    private val random = SecureRandom()
    private val threadCounter = AtomicInteger()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var currentSecret: String? = null

    /** Port the server is currently bound to, or -1 if not running. */
    val port: Int get() = server?.address?.port ?: -1

    /**
     * The address actually bound on. `null` while stopped. Used by the
     * registry publisher to announce a reachable host:port to peers.
     */
    val boundAddress: InetSocketAddress? get() = server?.address

    /** True iff the server is currently bound to a non-loopback address. */
    val isBoundToLan: Boolean
        get() = server?.address?.address?.let { !it.isLoopbackAddress } ?: false

    /** Shared secret callers must include in the auth header. Null when stopped. */
    val secret: String? get() = currentSecret

    /**
     * Start the server. Idempotent - calling twice is a no-op.
     * @param desiredPort 0 to let the OS assign a free port.
     * @param bindToLan when true, bind the wildcard address so peers
     * on the same LAN can reach the server. When false, bind loopback
     * only (the default - peers cannot reach the server).
     */
    fun start(desiredPort: Int = 0, bindToLan: Boolean = false) {
        if (server != null) return
        val bindAddress = if (bindToLan) {
            // 0.0.0.0 - let the OS bind every interface. The user
            // already opted in via the toggle; the auth secret + token
            // table are still required to pull anything off the wire.
            InetAddress.getByName("0.0.0.0")
        } else {
            InetAddress.getLoopbackAddress()
        }
        val addr = InetSocketAddress(bindAddress, desiredPort)
        val srv = HttpServer.create(addr, BACKLOG)
        currentSecret = freshSecret()
        srv.executor = Executors.newFixedThreadPool(WORKER_THREADS, namedFactory("torve-lan-"))
        srv.createContext("/local/manifest") { ex -> handleManifest(ex) }
        srv.createContext("/local/stream-token/") { ex -> handleStreamToken(ex) }
        srv.createContext("/local/stream/") { ex -> handleStream(ex) }
        srv.start()
        server = srv
    }

    fun stop() {
        val s = server
        server = null
        currentSecret = null
        s?.stop(0)
        // Token table also gets cleared on lifecycle stop - caller's
        // [LanServingController] does that explicitly so each component
        // owns its own memory.
    }

    // ── /local/manifest ──────────────────────────────────────────────

    private fun handleManifest(exchange: HttpExchange) {
        try {
            if (!authorize(exchange)) return
            if (exchange.requestMethod != "GET") {
                sendStatus(exchange, 405)
                return
            }
            val manifest = manifestProvider()
            val body = json.encodeToString(LanLibraryManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store, private")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        } catch (t: Throwable) {
            runCatching { sendStatus(exchange, 500) }
        }
    }

    // ── /local/stream-token/{id} ─────────────────────────────────────

    private fun handleStreamToken(exchange: HttpExchange) {
        try {
            if (!authorize(exchange)) return
            if (exchange.requestMethod != "POST") {
                sendStatus(exchange, 405)
                return
            }
            val path = exchange.requestURI.path  // /local/stream-token/<id>
            val id = path.removePrefix("/local/stream-token/").trim('/')
            // Uniform 404 on missing/invalid id AND on unknown id, so
            // a bystander with the hub auth header still cannot probe
            // the id space (matches the streaming route's behavior).
            if (id.isEmpty() || id.contains('/')) {
                sendStatus(exchange, 404)
                return
            }
            val token = tokenTable.issueAccessToken(id)
            if (token == null) {
                sendStatus(exchange, 404)
                return
            }
            // Cap the advertised expiry at the token table's TTL so a
            // consumer that caches the response never tries to use a
            // dead token. We don't know the table's TTL constant from
            // here directly; use the documented default.
            val expiresAt = System.currentTimeMillis() + LanMediaTokenTable.DEFAULT_TOKEN_TTL_MS
            val streamPath = "/local/stream/$id?token=$token"
            val responseBody = """{"path":"$streamPath","token":"$token","expires_at_epoch_ms":$expiresAt}"""
                .toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store, private")
            exchange.sendResponseHeaders(200, responseBody.size.toLong())
            exchange.responseBody.use { it.write(responseBody) }
        } catch (t: Throwable) {
            runCatching { sendStatus(exchange, 500) }
        }
    }

    // ── /local/stream/{id} ───────────────────────────────────────────

    private fun handleStream(exchange: HttpExchange) {
        try {
            if (!authorize(exchange)) return
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                sendStatus(exchange, 405)
                return
            }
            val path = exchange.requestURI.path  // /local/stream/<id>
            val id = path.removePrefix("/local/stream/").trim('/')
            if (id.isEmpty() || id.contains('/')) {
                sendStatus(exchange, 404)
                return
            }
            val token = exchange.requestURI.query.orEmpty()
                .split('&')
                .mapNotNull {
                    val eq = it.indexOf('=')
                    if (eq < 0) null else it.substring(0, eq) to it.substring(eq + 1)
                }
                .firstOrNull { it.first == "token" }
                ?.second
                ?: run { sendStatus(exchange, 404); return }
            val file = tokenTable.resolveTokenForId(id, token)
                ?: run { sendStatus(exchange, 404); return }
            // Defense in depth: never serve a file outside the allowlist
            // even if the token table somehow held a stale reference.
            if (!allowlist.isAllowed(file)) {
                sendStatus(exchange, 404)
                return
            }
            val totalSize = file.length()
            val rangeHeader = exchange.requestHeaders.getFirst("Range")
            val parsed = RangeRequest.parse(rangeHeader, totalSize)
            val mime = exchange.requestHeaders.getFirst("Accept")
                ?: "application/octet-stream"
            val response = RangeRequest.buildResponse(
                parsed = parsed,
                totalSize = totalSize,
                contentType = guessMimeFromExtension(file.extension) ?: "application/octet-stream",
            )
            applyResponse(exchange, response, parsed, file)
        } catch (t: Throwable) {
            runCatching { sendStatus(exchange, 500) }
        }
    }

    private fun applyResponse(
        exchange: HttpExchange,
        response: RangeResponse,
        parsed: RangeParseResult,
        file: java.io.File,
    ) {
        exchange.responseHeaders.add("Accept-Ranges", response.acceptRangesHeader)
        response.contentRangeHeader?.let { exchange.responseHeaders.add("Content-Range", it) }
        exchange.responseHeaders.add("Content-Type", response.contentType)
        exchange.responseHeaders.add("Cache-Control", "no-store, private")
        if (response.contentLength == 0L) {
            exchange.sendResponseHeaders(response.status, -1)
            return
        }
        exchange.sendResponseHeaders(response.status, response.contentLength)
        if (exchange.requestMethod == "HEAD") return
        when (parsed) {
            is RangeParseResult.Satisfiable -> streamRange(exchange, file, parsed.start, parsed.endInclusive)
            RangeParseResult.NoRange -> streamRange(exchange, file, 0, file.length() - 1)
            else -> { /* error responses already sized 0 */ }
        }
    }

    private fun streamRange(exchange: HttpExchange, file: java.io.File, start: Long, endInclusive: Long) {
        val length = endInclusive - start + 1
        if (length <= 0) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(STREAM_BUF_BYTES)
            var remaining = length
            exchange.responseBody.use { out ->
                while (remaining > 0) {
                    val toRead = minOf(remaining.toLong(), buf.size.toLong()).toInt()
                    val read = raf.read(buf, 0, toRead)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
            }
        }
    }

    // ── auth & helpers ──────────────────────────────────────────────

    private fun authorize(exchange: HttpExchange): Boolean {
        val expected = currentSecret
        val provided = exchange.requestHeaders.getFirst("X-Torve-Lan-Auth")
        if (expected == null || provided == null || !constantTimeEquals(expected, provided)) {
            sendStatus(exchange, 401)
            return false
        }
        return true
    }

    private fun sendStatus(exchange: HttpExchange, code: Int) {
        runCatching {
            exchange.responseHeaders.add("Cache-Control", "no-store, private")
            exchange.sendResponseHeaders(code, -1)
            exchange.responseBody.close()
        }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun freshSecret(): String {
        val bytes = ByteArray(SECRET_BYTES)
        random.nextBytes(bytes)
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v shr 4]); sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private fun namedFactory(prefix: String): ThreadFactory = ThreadFactory { r ->
        val t = Thread(r, prefix + threadCounter.incrementAndGet())
        t.isDaemon = true
        t
    }

    private fun guessMimeFromExtension(ext: String): String? = when (ext.lowercase()) {
        "mp4", "m4v" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "webm" -> "video/webm"
        "ts" -> "video/mp2t"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "m4a" -> "audio/mp4"
        "mp3" -> "audio/mpeg"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        else -> null
    }

    companion object {
        private const val BACKLOG = 8
        private const val WORKER_THREADS = 4
        private const val SECRET_BYTES = 32
        private const val STREAM_BUF_BYTES = 64 * 1024
        private const val HEX = "0123456789abcdef"
    }
}
