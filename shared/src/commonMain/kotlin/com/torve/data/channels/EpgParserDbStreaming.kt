package com.torve.data.channels

import com.torve.db.TorveDatabase
import com.torve.domain.model.EpgChannel
import com.torve.domain.model.EpgProgramme
import kotlinx.coroutines.ensureActive
import kotlinx.datetime.Clock
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/** Bounded XMLTV reader for Apple; no DOM, external entities or full-feed buffering. */
internal suspend fun EpgParser.parseXmlTvToDbStreaming(
    readChunk: suspend (ByteArray) -> Int,
    db: TorveDatabase, userId: String, playlistId: String, generationId: Long,
    windowStartMs: Long, windowEndMs: Long, channelFilter: Set<String>?,
    resolveEpgChannelKey: ((String, String?) -> String?)?, batchSize: Int,
    maxProgrammesPerChannel: Int = 240, maxProgrammesTotal: Int = 150_000,
    onProgress: ((EpgBatchProgress) -> Unit)? = null,
): EpgDbParseStats {
    val started = TimeSource.Monotonic.markNow()
    val size = batchSize.coerceIn(50, 200)
    val channelBatch = mutableListOf<Pair<EpgChannel, String>>()
    val programmeBatch = mutableListOf<Pair<EpgProgramme, String>>()
    val keys = mutableSetOf<String>()
    val metadata = mutableMapOf<String, EpgChannel>()
    val mapping = mutableMapOf<String, String?>()
    val counts = mutableMapOf<String, Int>()
    var channelsSeen = 0; var seen = 0; var kept = 0; var window = 0; var filtered = 0
    var invalid = 0; var unmapped = 0; var capped = 0; var batches = 0; var aborted = false
    var persistenceMs = 0L
    fun progress() = onProgress?.invoke(EpgBatchProgress(seen, kept, window, filtered, invalid, unmapped, capped, batches, 0, 0))
    fun flush() {
        if (channelBatch.isEmpty() && programmeBatch.isEmpty()) return
        val timer = TimeSource.Monotonic.markNow()
        db.torveQueries.transaction {
            channelBatch.forEach { (channel, key) ->
                db.torveQueries.insertEpgChannel(user_id = userId, playlist_id = playlistId, generation_id = generationId,
                    channel_id = key, epg_channel_key = key, xmltv_channel_id = channel.id,
                    display_name = channel.displayName, icon_url = channel.iconUrl, updated_at = Clock.System.now().toEpochMilliseconds())
            }
            programmeBatch.forEach { (programme, key) ->
                db.torveQueries.insertEpgProgrammeWithDetails(user_id = userId, playlist_id = playlistId, generation_id = generationId,
                    channel_id = key, epg_channel_key = key, xmltv_channel_id = programme.channelId,
                    start_time = programme.startTime, end_time = programme.endTime, title = programme.title.take(120),
                    sub_title = programme.subTitle?.take(120), description = programme.description?.take(2048))
            }
        }
        channelBatch.clear(); programmeBatch.clear(); batches++
        persistenceMs += timer.elapsedNow().inWholeMilliseconds
        progress()
    }
    fun resolve(id: String, name: String?): String? {
        if (id in mapping) return mapping[id]
        val key = if (resolveEpgChannelKey != null) resolveEpgChannelKey(id, name) else id
        mapping[id] = key
        return key
    }
    fun channel(channel: EpgChannel, key: String) {
        if (keys.add(key)) channelBatch += channel to key
        if (channelBatch.size >= size) flush()
    }
    db.torveQueries.transaction {
        db.torveQueries.clearEpgProgrammesForPlaylistGeneration(userId, playlistId, generationId)
        db.torveQueries.clearEpgChannelsForPlaylistGeneration(userId, playlistId, generationId)
    }
    try {
        try {
            readXmlTvRecords(readChunk, ::parseXmltvTimestamp, { value ->
                channelsSeen++
                if (channelsSeen > 120_000) throw EpgStreamLimitException("XMLTV channel limit exceeded")
                metadata[value.id] = value
                mapping.remove(value.id)
                val key = resolve(value.id, value.displayName)
                if (key != null && (channelFilter == null || key in channelFilter)) channel(value, key)
            }, { value ->
                seen++
                if (seen > 2_000_000 || kept >= maxProgrammesTotal.coerceAtLeast(1)) { aborted = true; throw EpgScanComplete() }
                when {
                    value.startTime <= 0 || value.endTime <= value.startTime || value.channelId.isBlank() -> invalid++
                    value.endTime < windowStartMs || value.startTime > windowEndMs -> window++
                    else -> {
                        val info = metadata[value.channelId]
                        val key = resolve(value.channelId, info?.displayName)
                        when {
                            key == null -> unmapped++
                            channelFilter != null && key !in channelFilter -> filtered++
                            (counts[key] ?: 0) >= maxProgrammesPerChannel.coerceAtLeast(1) -> capped++
                            else -> {
                                channel(info ?: EpgChannel(value.channelId, key), key)
                                programmeBatch += value to key; kept++; counts[key] = (counts[key] ?: 0) + 1
                                if (programmeBatch.size >= size) flush()
                            }
                        }
                    }
                }
                if (seen % 25_000 == 0) progress()
            })
        } catch (_: EpgScanComplete) { /* A bounded, deliberately truncated generation. */ }
        flush()
    } catch (error: Throwable) {
        db.torveQueries.transaction {
            db.torveQueries.clearEpgProgrammesForPlaylistGeneration(userId, playlistId, generationId)
            db.torveQueries.clearEpgChannelsForPlaylistGeneration(userId, playlistId, generationId)
        }
        throw error
    }
    return EpgDbParseStats(channelsSeen, keys.size, seen, kept, window, filtered, invalid, unmapped, capped,
        aborted, started.elapsedNow().inWholeMilliseconds, persistenceMs)
}

private class EpgScanComplete : RuntimeException()

/** XML tokenization is incremental and strictly checks balanced tags and feed completion. */
internal suspend fun readXmlTvRecords(
    readChunk: suspend (ByteArray) -> Int, timestamp: (String) -> Long,
    onChannel: (EpgChannel) -> Unit, onProgramme: (EpgProgramme) -> Unit,
) {
    val bytes = ByteArray(64 * 1024)
    val decoder = EpgUtf8Decoder()
    var buffer = ""
    val stack = mutableListOf<String>()
    var rootSeen = false; var rootClosed = false
    var id = ""; var start = 0L; var stop = 0L; var display = ""; var icon: String? = null; var title = ""
    var subtitle: String? = null; var description: String? = null
    var text = StringBuilder()
    fun appendText(value: String, cdata: Boolean = false) {
        if (stack.lastOrNull() !in setOf("display-name", "title", "sub-title", "desc")) return
        if (text.length < 2048) text.append((if (cdata) value else xmlTvText(value)).take(2048 - text.length))
    }
    fun end(name: String) {
        check(stack.lastOrNull() == name) { "Malformed XMLTV closing tag" }
        if (stack.size == 3) {
            if (name == "display-name" && display.isEmpty()) display = text.toString().trim().take(120)
            if (name == "title" && title.isEmpty()) title = text.toString().trim().take(120)
            if (name == "sub-title" && subtitle == null) subtitle = text.toString().trim().take(120).ifBlank { null }
            if (name == "desc" && description == null) description = text.toString().trim().ifBlank { null }
        }
        if (stack.size == 2) {
            if (name == "channel" && id.isNotBlank()) onChannel(EpgChannel(id, display.ifBlank { id }, icon))
            if (name == "programme") onProgramme(EpgProgramme(id, start, stop, title.ifBlank { "Unknown" },
                subTitle = subtitle, description = description))
        }
        stack.removeAt(stack.lastIndex)
        if (name == "tv" && stack.isEmpty()) rootClosed = true
    }
    while (true) {
        coroutineContext.ensureActive()
        val count = readChunk(bytes)
        if (count < 0) break
        if (count == 0) continue
        buffer += decoder.decode(bytes, count)
        var consumed = 0
        while (consumed < buffer.length) {
            val opening = buffer.indexOf('<', consumed)
            if (opening < 0) break // Keep a complete text run so split entities stay intact.
            val preceding = buffer.substring(consumed, opening)
            if (stack.isEmpty()) check(preceding.trim().trim('\uFEFF').isEmpty()) { "Not an XMLTV feed" }
            else appendText(preceding)
            consumed = opening
            val tokenEnd: Int
            when {
                buffer.startsWith("<!--", opening) -> {
                    val closing = buffer.indexOf("-->", opening + 4)
                    if (closing < 0) break
                    tokenEnd = closing + 3
                }
                buffer.startsWith("<![CDATA[", opening) -> {
                    val closing = buffer.indexOf("]]>", opening + 9)
                    if (closing < 0) break
                    appendText(buffer.substring(opening + 9, closing), cdata = true)
                    tokenEnd = closing + 3
                }
                buffer.startsWith("<?", opening) -> {
                    val closing = buffer.indexOf("?>", opening + 2)
                    if (closing < 0) break
                    tokenEnd = closing + 2
                }
                else -> {
                    val closing = xmlTvTagEnd(buffer, opening + 1)
                    if (closing < 0) break
                    val raw = buffer.substring(opening + 1, closing).trim()
                    tokenEnd = closing + 1
                    when {
                        raw.startsWith("!DOCTYPE") -> check(!rootSeen && !raw.contains('[')) { "XMLTV entity declarations are unsupported" }
                        raw.startsWith('!') -> error("Unsupported XMLTV declaration")
                        raw.startsWith('/') -> end(raw.drop(1).trim())
                        else -> {
                            val name = raw.takeWhile { !it.isWhitespace() && it != '/' }
                            if (stack.isEmpty()) {
                                check(name == "tv" && !rootSeen && !rootClosed) { "Not an XMLTV feed" }; rootSeen = true
                            }
                            check(stack.size < 64) { "XMLTV nesting limit exceeded" }
                            val attrs = xmlTvAttributes(raw.drop(name.length))
                            stack += name
                            if (stack.size == 2) {
                                id = attrs[if (name == "programme") "channel" else "id"].orEmpty()
                                start = timestamp(attrs["start"].orEmpty()); stop = timestamp(attrs["stop"].orEmpty())
                                display = ""; icon = null; title = ""; subtitle = null; description = null
                            }
                            if (stack.size == 3) {
                                text = StringBuilder()
                                if (name == "icon" && stack[1] == "channel") icon = attrs["src"]
                            }
                            if (raw.endsWith('/')) end(name)
                        }
                    }
                }
            }
            consumed = tokenEnd
        }
        buffer = buffer.substring(consumed)
        if (buffer.length > 1024 * 1024) throw EpgStreamLimitException("XMLTV token exceeds the size limit")
    }
    decoder.finish()
    check(rootSeen && rootClosed && stack.isEmpty() && buffer.isBlank()) { "Incomplete XMLTV feed" }
}

private fun xmlTvTagEnd(value: String, from: Int): Int {
    var quote: Char? = null
    for (index in from until value.length) {
        val char = value[index]
        if (quote != null) { if (char == quote) quote = null }
        else if (char == '\'' || char == '"') quote = char
        else if (char == '>') return index
    }
    return -1
}
private val xmlTvAttribute = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
private fun xmlTvAttributes(value: String): Map<String, String> = xmlTvAttribute.findAll(value).associate {
    it.groupValues[1] to xmlTvText(it.groups[2]?.value ?: it.groups[3]?.value.orEmpty())
}
private val xmlTvEntity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")
private fun xmlTvText(value: String): String = xmlTvEntity.replace(value) {
    when (val entity = it.groupValues[1]) {
        "amp" -> "&"; "lt" -> "<"; "gt" -> ">"; "quot" -> "\""; "apos" -> "'"
        else -> {
            val code = if (entity.startsWith("#x")) entity.drop(2).toIntOrNull(16) else entity.drop(1).toIntOrNull()
            when {
                code == null || code !in 1..0x10ffff || code in 0xd800..0xdfff -> "\uFFFD"
                code <= 0xffff -> code.toChar().toString()
                else -> { val point = code - 0x10000; "${(0xd800 + (point shr 10)).toChar()}${(0xdc00 + (point and 1023)).toChar()}" }
            }
        }
    }
}
private class EpgUtf8Decoder {
    private var pending = byteArrayOf()
    fun decode(bytes: ByteArray, count: Int): String {
        val data = pending + bytes.copyOf(count)
        var first = data.lastIndex
        while (first >= 0 && data[first].toInt() and 0xc0 == 0x80) first--
        val lead = data.getOrNull(first)?.toInt()?.and(255) ?: 0
        val needed = when { lead in 0xc2..0xdf -> 2; lead in 0xe0..0xef -> 3; lead in 0xf0..0xf4 -> 4; else -> 1 }
        val end = if (first >= 0 && data.size - first < needed) first else data.size
        pending = data.copyOfRange(end, data.size)
        return data.decodeToString(0, end, throwOnInvalidSequence = true)
    }
    fun finish() { check(pending.isEmpty()) { "Incomplete XMLTV UTF-8 character" } }
}
