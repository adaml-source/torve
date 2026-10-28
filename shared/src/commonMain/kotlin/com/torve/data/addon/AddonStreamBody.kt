package com.torve.data.addon

import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.json.*

/** Incremental, bounded NDJSON; normal JSON retains the existing response contract. */
internal suspend fun HttpResponse.readAddonStreams(
    json: Json,
    maxBytes: Int,
    onBatch: suspend (List<StremioStream>) -> Unit,
): List<StremioStream> {
    val format = headers[HttpHeaders.ContentType]?.substringBefore(';')?.lowercase()
    if (format !in setOf("application/x-ndjson", "application/ndjson", "application/jsonl")) {
        return json.decodeFromString<StremioStreamResponse>(safeAddonBodyAsText(maxBytes)).streams
            .also { onBatch(it) }
    }
    headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
        if (it > maxBytes) throw AddonBodyTooLargeException(maxBytes, it)
    }
    val channel = bodyAsChannel()
    val chunk = ByteArray(8192)
    val line = ByteArray(256 * 1024)
    var lineBytes = 0
    var total = 0
    val streams = mutableListOf<StremioStream>()
    var published = 0
    suspend fun acceptLine() {
        val value = line.decodeToString(0, lineBytes).trim().removePrefix("\uFEFF")
        lineBytes = 0
        if (value.isBlank()) return
        val record = json.parseToJsonElement(value).jsonObject
        val entries = when {
            "streams" in record -> json.decodeFromJsonElement<StremioStreamResponse>(record).streams
            "stream" in record -> listOf(json.decodeFromJsonElement<StremioStream>(record.getValue("stream")))
            record["type"]?.jsonPrimitive?.contentOrNull == "stream" && "data" in record ->
                listOf(json.decodeFromJsonElement<StremioStream>(record.getValue("data")))
            listOf("url", "infoHash", "info_hash", "magnet", "externalUrl", "ytId").any { it in record } ->
                listOf(json.decodeFromJsonElement<StremioStream>(record))
            record["type"]?.jsonPrimitive?.contentOrNull in setOf("done", "complete", "heartbeat", "progress") -> emptyList()
            else -> error("Unsupported incremental stream record")
        }
        streams.addAll(entries)
        if (streams.size > 5000) throw AddonBodyTooLargeException(maxBytes)
        if (streams.isNotEmpty() && (published == 0 || streams.size - published >= 8)) {
            onBatch(streams.toList()); published = streams.size
        }
    }
    while (true) {
        val count = channel.readAvailable(chunk, 0, chunk.size)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > maxBytes) throw AddonBodyTooLargeException(maxBytes)
        for (index in 0 until count) {
            if (chunk[index] == 10.toByte()) acceptLine() else {
                if (lineBytes >= line.size) throw AddonBodyTooLargeException(line.size)
                line[lineBytes++] = chunk[index]
            }
        }
    }
    if (lineBytes > 0) acceptLine()
    if (published != streams.size) onBatch(streams.toList())
    return streams
}
