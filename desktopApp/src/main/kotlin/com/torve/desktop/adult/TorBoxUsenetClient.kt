package com.torve.desktop.adult

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Direct TorBox Usenet integration for the desktop Adult catalog.
 *
 * Flow when a user clicks "Play" on a Newznab result:
 *  1. Fetch the NZB XML content from the indexer URL.
 *  2. POST it as multipart to `/api/v1/api/usenet/createusenetdownload`.
 *  3. Poll `/api/v1/api/usenet/mylist?id=...` until `download_state` is
 *     `"completed"` (or one of TorBox's terminal-success synonyms).
 *  4. Pick the largest video file in the result.
 *  5. GET `/api/v1/api/usenet/requestdl?token=...&usenet_id=...&file_id=...`
 *     for a streamable URL.
 *
 * Uses the JDK HttpClient so we don't drag Ktor into desktopApp just
 * for this. All errors collapse to a [Result.failure] with a short
 * human-readable reason - the caller surfaces them to the user.
 */
class TorBoxUsenetClient {

    private val http: java.net.http.HttpClient by lazy {
        java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build()
    }
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Resolve a Newznab NZB URL to a TorBox-served stream URL. [onStatus]
     * is fired with short status updates (the user sees them in the
     * Adult page row) - e.g. "Uploading NZB", "TorBox preparing 35%",
     * "Resolving link".
     */
    suspend fun resolve(
        nzbUrl: String,
        torboxApiKey: String,
        onStatus: (String) -> Unit,
    ): Result<ResolvedNzb> = runCatching {
        require(nzbUrl.isNotBlank()) { "NZB URL is empty" }
        require(torboxApiKey.isNotBlank()) { "TorBox API key not set" }

        onStatus("Downloading NZB from indexer...")
        val nzbBytes = fetchBytes(nzbUrl)
            ?: error("Could not download NZB file from indexer")

        onStatus("Uploading NZB to TorBox...")
        val createResp = uploadNzbMultipart(torboxApiKey, nzbBytes)
        val usenetId = parseCreateResponse(createResp)
            ?: run {
                val friendly = parseTorBoxError(createResp)
                if (friendly != null) error(friendly) else error("TorBox upload failed (response: ${createResp.take(160)})")
            }

        onStatus("TorBox queued · waiting...")
        val info = pollUntilReady(torboxApiKey, usenetId, onStatus)
        val files = info.files
        require(files.isNotEmpty()) { "TorBox download has no files" }

        val target = files
            .filter { isPlayableVideo(it.name) }
            .maxByOrNull { it.size }
            ?: files.maxByOrNull { it.size }
            ?: files.first()

        onStatus("Resolving stream link...")
        val streamUrl = requestDownloadUrl(torboxApiKey, usenetId, target.id)
            ?: error("TorBox returned no download URL for file id ${target.id}")

        ResolvedNzb(streamUrl = streamUrl, fileName = target.name, sizeBytes = target.size)
    }

    private fun fetchBytes(url: String): ByteArray? = runCatching {
        val req = HttpRequest.newBuilder(URI(url))
            .timeout(Duration.ofSeconds(60))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray())
        if (resp.statusCode() in 200..299) resp.body() else null
    }.getOrNull()

    private fun uploadNzbMultipart(apiKey: String, nzbBytes: ByteArray): String {
        val boundary = "torve-${UUID.randomUUID()}"
        val partHeader = ("--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"upload.nzb\"\r\n" +
            "Content-Type: application/x-nzb\r\n\r\n").toByteArray()
        val partFooter = "\r\n--$boundary--\r\n".toByteArray()
        val body = partHeader + nzbBytes + partFooter

        val req = HttpRequest.newBuilder(URI("https://api.torbox.app/v1/api/usenet/createusenetdownload"))
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "multipart/form-data; boundary=$boundary")
            .timeout(Duration.ofSeconds(60))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        return resp.body() ?: ""
    }

    private fun parseCreateResponse(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val data = root["data"]?.jsonObject ?: return@runCatching null
        // TorBox sometimes uses `usenetdownload_id`, sometimes just `id`.
        (data["usenetdownload_id"] ?: data["id"])?.jsonPrimitive?.content
    }.getOrNull()

    /**
     * Pull out a human-friendly message from a TorBox error envelope.
     * Bodies look like:
     *   {"success":false,"error":"BAD_TOKEN","detail":"...","data":null}
     * We surface `detail` first (most readable), then `error` (machine
     * code), then null if neither is present.
     */
    private fun parseTorBoxError(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body).jsonObject
        val detail = root["detail"]?.jsonPrimitive?.content
        val errorCode = root["error"]?.jsonPrimitive?.content
        when {
            !detail.isNullOrBlank() && !errorCode.isNullOrBlank() -> "$detail (code: $errorCode)"
            !detail.isNullOrBlank() -> detail
            !errorCode.isNullOrBlank() -> "TorBox error: $errorCode"
            else -> null
        }
    }.getOrNull()

    /** Heuristic - was the failure an auth issue the user can fix by rotating their key? */
    fun isAuthError(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        return "bad_token" in lower ||
            "invalid" in lower && "token" in lower ||
            "expired" in lower && "token" in lower ||
            "unauthorized" in lower ||
            "401" in lower
    }

    private suspend fun pollUntilReady(
        apiKey: String,
        usenetId: String,
        onStatus: (String) -> Unit,
    ): UsenetInfo {
        val deadline = System.currentTimeMillis() + 5 * 60 * 1000L  // 5min
        var lastProgress: Int? = null
        while (System.currentTimeMillis() < deadline) {
            val info = fetchUsenetInfo(apiKey, usenetId)
            if (info != null) {
                val state = info.state.lowercase()
                val terminalSuccess = state == "completed" ||
                    state == "downloaded" ||
                    state == "uploaded" ||
                    info.progress >= 100
                if (terminalSuccess && info.files.isNotEmpty()) return info
                if (state.contains("error") || state.contains("fail")) {
                    error("TorBox reports state=${info.state} for this NZB")
                }
                if (info.progress != lastProgress) {
                    lastProgress = info.progress
                    onStatus("TorBox preparing · ${info.state} · ${info.progress}%")
                }
            }
            delay(2000)
        }
        error("TorBox didn't finish preparing within 5 minutes")
    }

    private fun fetchUsenetInfo(apiKey: String, usenetId: String): UsenetInfo? = runCatching {
        val req = HttpRequest.newBuilder(URI("https://api.torbox.app/v1/api/usenet/mylist?id=$usenetId"))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) return@runCatching null
        val root = json.parseToJsonElement(resp.body()).jsonObject
        val dataEl = root["data"] ?: return@runCatching null
        val obj = if (dataEl.toString().startsWith("[")) {
            dataEl.jsonArray.firstOrNull()?.jsonObject ?: return@runCatching null
        } else dataEl.jsonObject
        UsenetInfo(
            state = obj["download_state"]?.jsonPrimitive?.content
                ?: obj["state"]?.jsonPrimitive?.content
                ?: "unknown",
            progress = (obj["progress"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt())
                ?: (obj["download_progress"]?.jsonPrimitive?.content?.toFloatOrNull()?.toInt())
                ?: 0,
            files = (obj["files"]?.jsonArray ?: return@runCatching null).mapNotNull { fEl ->
                val f = fEl.jsonObject
                val id = f["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val name = f["name"]?.jsonPrimitive?.content
                    ?: f["short_name"]?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                val size = f["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                UsenetFile(id = id, name = name, size = size)
            },
        )
    }.getOrNull()

    private fun requestDownloadUrl(apiKey: String, usenetId: String, fileId: String): String? = runCatching {
        val req = HttpRequest.newBuilder(URI(
            "https://api.torbox.app/v1/api/usenet/requestdl?token=$apiKey&usenet_id=$usenetId&file_id=$fileId",
        ))
            .header("Authorization", "Bearer $apiKey")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val resp = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (resp.statusCode() !in 200..299) return@runCatching null
        val body = resp.body()
        // TorBox returns either {"data": "https://..."} or a JSON
        // object with `data.data`. Walk both.
        runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            (root["data"]?.jsonPrimitive?.content)
                ?: root["data"]?.jsonObject?.get("data")?.jsonPrimitive?.content
                ?: root["url"]?.jsonPrimitive?.content
        }.getOrNull() ?: body.takeIf { it.startsWith("http") }?.trim()
    }.getOrNull()

    private fun isPlayableVideo(name: String): Boolean {
        val lower = name.lowercase()
        return listOf(".mp4", ".mkv", ".m4v", ".avi", ".mov", ".webm", ".ts").any { lower.endsWith(it) }
    }

    data class ResolvedNzb(val streamUrl: String, val fileName: String, val sizeBytes: Long)
    data class UsenetInfo(val state: String, val progress: Int, val files: List<UsenetFile>)
    data class UsenetFile(val id: String, val name: String, val size: Long)
}
