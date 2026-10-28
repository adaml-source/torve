package com.torve.android.files

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI

/** Progressive NAS playback: range requests and credentials remain on the approved origin. */
internal class OriginBoundDataSource(private val origin: URI, private val headers: Map<String, String>) : BaseDataSource(true) {
    private var connection: HttpURLConnection? = null
    private var input: InputStream? = null
    private var remaining = C.LENGTH_UNSET.toLong()
    private var opened = false
    private var uri: Uri? = null
    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        var target = URI(dataSpec.uri.toString())
        require(sameWebDavOrigin(origin, target))
        var response: HttpURLConnection? = null
        for (redirect in 0..5) {
            if (!sameWebDavOrigin(origin, target) || target.userInfo != null) throw IOException("The file redirects outside its connection")
            val candidate = (target.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 15_000; readTimeout = 30_000
                setRequestProperty("Accept-Encoding", "identity")
                (dataSpec.httpRequestHeaders + headers).forEach { (name, value) -> setRequestProperty(name, value) }
                if (dataSpec.position > 0 || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    val end = if (dataSpec.length != C.LENGTH_UNSET.toLong()) (dataSpec.position + dataSpec.length - 1).toString() else ""
                    setRequestProperty("Range", "bytes=${dataSpec.position}-$end")
                }
            }
            connection = candidate
            val code = candidate.responseCode
            if (code in setOf(301, 302, 303, 307, 308)) {
                val location = candidate.getHeaderField("Location") ?: throw IOException("Missing redirect address")
                candidate.disconnect(); connection = null
                target = target.resolve(location)
                continue
            }
            if (code !in 200..299) throw IOException("File playback returned HTTP $code")
            response = candidate
            break
        }
        val source = response ?: throw IOException("Too many file redirects")
        uri = Uri.parse(target.toASCIIString())
        input = source.inputStream
        val skip = if (source.responseCode == 200) dataSpec.position else 0L
        if (source.responseCode == 206) {
            val start = Regex("bytes (\\d+)-.*").matchEntire(source.getHeaderField("Content-Range").orEmpty())?.groupValues?.get(1)?.toLongOrNull()
            if (start != dataSpec.position) throw IOException("The file returned a different byte range")
        }
        var skipped = 0L
        val buffer = ByteArray(8192)
        while (skipped < skip) {
            val count = input!!.read(buffer, 0, minOf(buffer.size.toLong(), skip - skipped).toInt())
            if (count < 0) throw IOException("File ended before the requested position")
            skipped += count
        }
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else source.contentLengthLong.takeIf { it >= 0 }?.minus(skip) ?: C.LENGTH_UNSET.toLong()
        if (remaining < 0 && remaining != C.LENGTH_UNSET.toLong()) throw IOException("Invalid file length")
        opened = true; transferStarted(dataSpec)
        return remaining
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val count = input?.read(buffer, offset, if (remaining < 0) length else minOf(length.toLong(), remaining).toInt()) ?: C.RESULT_END_OF_INPUT
        if (count < 0) {
            if (remaining > 0) throw IOException("File playback was interrupted")
            return C.RESULT_END_OF_INPUT
        }
        if (remaining > 0) remaining -= count
        bytesTransferred(count)
        return count
    }
    override fun getUri(): Uri? = uri
    override fun getResponseHeaders(): Map<String, List<String>> = connection?.headerFields?.filterKeys { it != null }.orEmpty()
    override fun close() {
        try { input?.close() } finally {
            input = null; connection?.disconnect(); connection = null; uri = null
            if (opened) { opened = false; transferEnded() }
        }
    }
    class Factory(private val url: String, private val headers: Map<String, String>) : DataSource.Factory {
        override fun createDataSource(): DataSource = OriginBoundDataSource(URI(url), headers)
    }
}
