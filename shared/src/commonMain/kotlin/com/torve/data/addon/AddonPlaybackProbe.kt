package com.torve.data.addon

import com.torve.domain.repository.StreamReadiness
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.prepareRequest
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.http.decodeURLPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Resolve once, then give the player the returned URL rather than submitting the NZB again. */
internal class AddonPlaybackProbe(httpClient: HttpClient) {
    private val client = httpClient.config { followRedirects = false }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun check(url: String): StreamReadiness = if (isProviderMessageVideo(url)) redirect(url, url) else try {
        client.prepareGet(url) {
            header(HttpHeaders.Range, "bytes=0-0")
            // AIOStreams waits up to two minutes for TorBox before responding.
            // Keep that single resolution request alive so a slow preparation
            // does not become a series of cancelled/restarted resolutions.
            timeout { requestTimeoutMillis = 150_000; socketTimeoutMillis = 150_000 }
        }.execute { response ->
            // execute closes the streaming response on exit, even when Range is ignored.
            // Read the error body only once; trying to receive it again discards its status.
            val code = response.status.value
            when {
                code in 300..399 -> {
                    val result = redirect(url, response.headers[HttpHeaders.Location])
                    if (result is StreamReadiness.Ready) inspectRedirectedFile(result.finalUrl) ?: result else result
                }
                code in 200..299 -> AddonPlaybackFilePolicy.failure(url, response.headers[HttpHeaders.ContentDisposition])
                    ?.let { StreamReadiness.Failed(it) } ?: StreamReadiness.Ready(url)
                code == 429 -> StreamReadiness.Failed("error_rate_limited")
                else -> {
                    val body = try { response.safeAddonBodyAsText(maxBytes = 64 * 1024) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { "" }
                    val error = runCatching { json.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    if (code == 504 && (error.isNullOrBlank() || error.equals("nzb_not_ready", true))) StreamReadiness.Preparing
                    else StreamReadiness.Failed(PandaPreparationFailure.message(error) ?:
                        if (code == 401 || code == 403) "error_addon_access_denied" else "error_stream_resolve_failed")
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
      catch (_: Exception) { StreamReadiness.Preparing }

    /** Signed CDN paths can be opaque. HEAD exposes the actual filename without fetching video. */
    private suspend fun inspectRedirectedFile(url: String): StreamReadiness.Failed? {
        AddonPlaybackFilePolicy.failure(url)?.let { return StreamReadiness.Failed(it) }
        return withTimeoutOrNull(8_000) {
            var target = url
            repeat(3) {
                val next = try {
                    client.prepareRequest(target) {
                        method = HttpMethod.Head
                        timeout { requestTimeoutMillis = 5_000; socketTimeoutMillis = 5_000 }
                    }.execute { response ->
                        AddonPlaybackFilePolicy.failure(target, response.headers[HttpHeaders.ContentDisposition])
                            ?.let { return@execute StreamReadiness.Failed(it) }
                        if (response.status.value in 300..399) redirect(target, response.headers[HttpHeaders.Location])
                        else null
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                when (next) {
                    is StreamReadiness.Failed -> return@withTimeoutOrNull next
                    is StreamReadiness.Ready -> target = next.finalUrl
                    else -> return@withTimeoutOrNull null
                }
            }
            null
        }
    }

    private fun redirect(original: String, location: String?): StreamReadiness {
        val target = location?.takeIf { it.isNotBlank() }?.let {
            runCatching { URLBuilder(original).takeFrom(it).build() }.getOrNull()
        } ?: return StreamReadiness.Failed("error_stream_resolve_failed")
        if (target.protocol.name !in listOf("http", "https")) return StreamReadiness.Failed("error_stream_resolve_failed")
        AddonPlaybackFilePolicy.failure(target.toString())?.let { return StreamReadiness.Failed(it) }
        val source = Url(original)
        val staticError = if (target.host.equals(source.host, true) && target.encodedPath.startsWith("/static/")) {
            when (target.encodedPath.substringAfterLast('/')) {
                "429.mp4" -> "error_rate_limited"
                "401.mp4", "403.mp4", "payment_required.mp4" -> "error_addon_access_denied"
                "downloading.mp4" -> "error_addon_download_pending"
                "download_failed.mp4", "store_limit_exceeded.mp4", "content_proxy_limit_reached.mp4",
                "unavailable_for_legal_reasons.mp4", "no_matching_file.mp4", "500.mp4" -> "error_addon_download_failed"
                else -> null
            }
        } else null
        if (staticError == "error_addon_download_pending") return StreamReadiness.Preparing
        if (staticError != null) return StreamReadiness.Failed(staticError)
        if (isProviderMessageVideo(target.toString())) return StreamReadiness.Failed("error_addon_download_failed")
        if (target.host.equals("slate.elfhosted.com", true)) {
            val reason = when (target.parameters["title"]?.lowercase()) {
                "too many requests" -> "error_rate_limited"
                "downloading", "download in progress" -> "error_addon_download_pending"
                "unauthorized", "access denied", "forbidden", "payment required" -> "error_addon_access_denied"
                else -> "error_addon_download_failed"
            }
            return if (reason == "error_addon_download_pending") StreamReadiness.Preparing
                else StreamReadiness.Failed(reason)
        }
        return StreamReadiness.Ready(target.toString())
    }
}

/** Reject explicit sample filenames, never a video just because its runtime or size is small. */
internal object AddonPlaybackFilePolicy {
    const val SAMPLE_ONLY = "error_addon_sample_only"
    fun failure(url: String, disposition: String? = null): String? {
        val parsed = runCatching { Url(url) }.getOrNull()
        val header = disposition?.let { runCatching { ContentDisposition.parse(it) }.getOrNull() }
        val names = listOfNotNull(
            parsed?.encodedPath?.let { runCatching { it.decodeURLPart() }.getOrNull() },
            parsed?.parameters?.get("filename"),
            header?.parameter("filename"),
            header?.parameter("filename*")?.let { value ->
                val encoded = value.substringAfter("''", value)
                runCatching { encoded.decodeURLPart() }.getOrNull()
            },
        )
        return SAMPLE_ONLY.takeIf { names.any(::isSampleFile) }
    }
    private fun isSampleFile(value: String): Boolean {
        val parts = value.replace('\\', '/').lowercase().split('/')
        val name = parts.lastOrNull().orEmpty()
        if (name.substringAfterLast('.', "") !in setOf("mkv", "mp4", "m4v", "avi", "webm", "mov", "ts")) return false
        val stem = name.substringBeforeLast('.')
        return parts.dropLast(1).any { it == "sample" || it == "samples" } ||
            Regex("(?:^|[. _-])(?:sample|trailer|preview)$").containsMatchIn(stem)
    }
}

/** Cached resolver links can be inspected; raw downloads and uncached jobs cannot. */
object AddonTrackInspectionPolicy {
    fun canInspect(stream: ParsedStream): Boolean {
        val url = stream.directUrl?.let { runCatching { Url(it) }.getOrNull() } ?: return false
        if (url.protocol.name !in listOf("http", "https") || url.host.isBlank()) return false
        val path = url.encodedPath.lowercase()
        if (path.endsWith(".nzb") || path.endsWith(".torrent") || path.contains("/getnzb/") ||
            url.parameters["t"].equals("get", ignoreCase = true) ||
            stream.accelerationProvenanceKind == com.torve.domain.model.CandidateProvenanceKind.USENET_NZBDAV) return false
        return stream.isCached || (!stream.isUsenetStream() && !stream.isAddonHostedUrl() && !path.contains("/nzb/"))
    }
}
