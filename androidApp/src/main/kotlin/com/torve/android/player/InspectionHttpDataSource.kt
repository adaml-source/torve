package com.torve.android.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** One byte budget covers manifests, range reads and every track in a metadata inspection. */
internal class InspectionReadBudget(limit: Long) {
    private val bytes = AtomicLong(limit)
    private val cancelled = AtomicBoolean(false)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
    fun remaining(): Long = if (cancelled.get()) 0 else bytes.get()
    fun reserve(length: Int): Int {
        if (cancelled.get()) throw IOException("Metadata inspection cancelled")
        val reserved = bytes.getAndUpdate { (it - length).coerceAtLeast(0) }.coerceAtMost(length.toLong()).toInt()
        if (reserved <= 0) throw IOException("Metadata inspection byte limit")
        return reserved
    }
    fun refund(count: Int) { bytes.addAndGet(count.toLong()) }
    fun register(connection: HttpURLConnection) {
        connections.add(connection)
        if (cancelled.get()) { connection.disconnect(); throw IOException("Metadata inspection cancelled") }
    }
    fun release(connection: HttpURLConnection) { connections.remove(connection); connection.disconnect() }
    fun cancel() { cancelled.set(true); connections.forEach { it.disconnect() }; connections.clear() }
}

/** Does not skip an unbounded response when a server ignores Range; auth stays on its origin. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class InspectionHttpDataSource(
    private val origin: URI,
    private val headers: Map<String, String>,
    private val strictOrigin: Boolean,
    private val budget: InspectionReadBudget,
) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var opened = false
    private var uri: Uri? = null

    private fun sameOrigin(target: URI) = origin.scheme.equals(target.scheme, true) &&
        origin.host.equals(target.host, true) && port(origin) == port(target)
    private fun port(value: URI) = value.port.takeIf { it >= 0 } ?: if (value.scheme == "https") 443 else 80

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        var target = URI(dataSpec.uri.toString())
        try {
            for (redirect in 0..5) {
                if (budget.remaining() <= 0) throw IOException("Metadata inspection ended")
                if (target.scheme !in setOf("http", "https") || target.host.isNullOrBlank() || target.userInfo != null ||
                    (strictOrigin && !sameOrigin(target))) throw IOException("Unsupported inspection address")
                val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) budget.remaining() else minOf(dataSpec.length, budget.remaining())
                val candidate = (target.toURL().openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 3000
                    readTimeout = 3000
                    setRequestProperty("Accept-Encoding", "identity")
                    if (sameOrigin(target)) (dataSpec.httpRequestHeaders + headers).forEach { (name, value) -> setRequestProperty(name, value) }
                    setRequestProperty("Range", "bytes=${dataSpec.position}-${dataSpec.position + length - 1}")
                }
                connection = candidate
                budget.register(candidate)
                val code = candidate.responseCode
                if (code in setOf(301, 302, 303, 307, 308)) {
                    val location = candidate.getHeaderField("Location") ?: throw IOException("Missing redirect address")
                    budget.release(candidate); connection = null
                    target = target.resolve(location)
                    continue
                }
                if (code !in 200..299) throw IOException("Metadata returned HTTP $code")
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)").matchEntire(candidate.getHeaderField("Content-Range").orEmpty())
                if ((code == 200 && dataSpec.position > 0) || (code == 206 && range?.groupValues?.get(1)?.toLongOrNull() != dataSpec.position)) {
                    throw IOException("Metadata server does not support the requested byte range")
                }
                uri = Uri.parse(target.toASCIIString())
                input = candidate.inputStream
                opened = true
                transferStarted(dataSpec)
                // Expose the full resource size so MP4 can seek to its trailing moov atom.
                val fullLength = range?.groupValues?.get(3)?.toLongOrNull()?.minus(dataSpec.position)
                    ?: candidate.contentLengthLong.takeIf { it >= 0 }
                return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else fullLength ?: C.LENGTH_UNSET.toLong()
            }
            throw IOException("Too many metadata redirects")
        } catch (error: Exception) { close(); throw error }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val reserved = budget.reserve(length)
        val count = input?.read(buffer, offset, reserved) ?: C.RESULT_END_OF_INPUT
        budget.refund(reserved - count.coerceAtLeast(0))
        if (count > 0) bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = connection?.headerFields?.filterKeys { it != null }.orEmpty()
    override fun close() {
        try { input?.close() } finally {
            input = null; connection?.let(budget::release); connection = null; uri = null
            if (opened) { opened = false; transferEnded() }
        }
    }
}
