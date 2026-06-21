package com.torve.data.channels

import com.torve.db.TorveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader
import kotlin.coroutines.coroutineContext

private const val DEFAULT_DB_BATCH_SIZE = 75
private const val TITLE_MAX_LEN = 120
private const val MAX_PROGRAMMES_PER_CHANNEL_DEFAULT = 240
private const val MAX_PROGRAMMES_TOTAL_DEFAULT = 150_000
private const val MB_DIVISOR = 1024L * 1024L

private data class EpgChannelInsert(
    val channelId: String,
    val epgChannelKey: String,
    val xmltvChannelId: String?,
    val displayName: String,
    val iconUrl: String?,
)

private data class EpgProgrammeInsert(
    val channelId: String,
    val epgChannelKey: String,
    val xmltvChannelId: String?,
    val startTime: Long,
    val endTime: Long,
    val title: String,
)

private data class ChannelMeta(
    val displayName: String,
    val iconUrl: String?,
)

private fun heapStatsMb(): Pair<Long, Long> {
    val runtime = Runtime.getRuntime()
    val usedBytes = runtime.totalMemory() - runtime.freeMemory()
    val freeBytes = runtime.maxMemory() - usedBytes
    return (usedBytes / MB_DIVISOR) to (freeBytes / MB_DIVISOR)
}

/**
 * Skip to the matching END_ELEMENT for the current START_ELEMENT.
 *
 * Mirrors Android's `skipCurrentTag` helper. StAX has no `depth` accessor,
 * so we track it manually.
 */
private fun skipCurrentElement(reader: XMLStreamReader) {
    if (reader.eventType != XMLStreamConstants.START_ELEMENT) return
    var depth = 1
    while (depth > 0 && reader.hasNext()) {
        when (reader.next()) {
            XMLStreamConstants.START_ELEMENT -> depth++
            XMLStreamConstants.END_ELEMENT -> depth--
        }
    }
}

/**
 * Read concatenated text content of the current START_ELEMENT, capped at
 * [maxLen]. Skips nested elements (we never need their content for XMLTV
 * fields like display-name/title).
 */
private fun readElementTextCapped(reader: XMLStreamReader, maxLen: Int): String? {
    if (maxLen <= 0) return null
    val out = StringBuilder(minOf(32, maxLen))
    var depth = 1
    while (depth > 0 && reader.hasNext()) {
        when (reader.next()) {
            XMLStreamConstants.START_ELEMENT -> {
                // Nested element inside text — skip it so we don't read its
                // content into the parent's text buffer.
                depth++
                skipCurrentElement(reader)
                depth--
            }
            XMLStreamConstants.END_ELEMENT -> depth--
            XMLStreamConstants.CHARACTERS,
            XMLStreamConstants.CDATA,
            -> {
                if (out.length < maxLen) {
                    val text = reader.text?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        if (out.isNotEmpty() && out.last() != ' ') {
                            out.append(' ')
                        }
                        val remaining = maxLen - out.length
                        if (remaining > 0) {
                            if (text.length > remaining) out.append(text, 0, remaining)
                            else out.append(text)
                        }
                    }
                }
            }
        }
    }
    return out.toString().trim().ifEmpty { null }
}

private fun consumeChannelElement(reader: XMLStreamReader): ChannelMeta? {
    var displayName: String? = null
    var iconUrl: String? = null
    var depth = 1
    while (depth > 0 && reader.hasNext()) {
        when (reader.next()) {
            XMLStreamConstants.START_ELEMENT -> {
                when (reader.localName) {
                    "display-name" -> {
                        if (displayName.isNullOrBlank()) {
                            displayName = readElementTextCapped(reader, TITLE_MAX_LEN)
                            // readElementTextCapped consumed our END_ELEMENT.
                        } else {
                            skipCurrentElement(reader)
                        }
                    }
                    "icon" -> {
                        if (iconUrl.isNullOrBlank()) {
                            iconUrl = reader.getAttributeValue(null, "src")
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                        }
                        skipCurrentElement(reader)
                    }
                    else -> skipCurrentElement(reader)
                }
            }
            XMLStreamConstants.END_ELEMENT -> depth--
        }
    }
    if (displayName.isNullOrBlank() && iconUrl.isNullOrBlank()) return null
    return ChannelMeta(displayName?.trim().orEmpty(), iconUrl)
}

private fun consumeProgrammeElement(
    reader: XMLStreamReader,
    captureTitle: Boolean,
): String? {
    var title: String? = null
    var depth = 1
    while (depth > 0 && reader.hasNext()) {
        when (reader.next()) {
            XMLStreamConstants.START_ELEMENT -> {
                when (reader.localName) {
                    "title" -> {
                        if (captureTitle && title.isNullOrBlank()) {
                            title = readElementTextCapped(reader, TITLE_MAX_LEN)
                        } else {
                            skipCurrentElement(reader)
                        }
                    }
                    else -> skipCurrentElement(reader)
                }
            }
            XMLStreamConstants.END_ELEMENT -> depth--
        }
    }
    return title?.take(TITLE_MAX_LEN)?.trim()?.ifEmpty { null }
}

/**
 * StAX-based XMLTV → SQLDelight ingest. Mirrors the Android XmlPullParser
 * implementation in [EpgParser.parseXmlTvStreamingToDb] (Android source set)
 * so EPG behaviour stays identical across platforms — same caps, same
 * mapping pipeline, same batch flush rhythm.
 */
internal suspend fun EpgParser.parseXmlTvStreamingToDbDesktop(
    input: InputStream,
    db: TorveDatabase,
    userId: String,
    playlistId: String,
    generationId: Long,
    windowStartMs: Long,
    windowEndMs: Long,
    channelFilter: Set<String>?,
    resolveEpgChannelKey: ((xmltvChannelId: String, xmltvDisplayName: String?) -> String?)? = null,
    batchSize: Int = DEFAULT_DB_BATCH_SIZE,
    maxProgrammesPerChannel: Int = MAX_PROGRAMMES_PER_CHANNEL_DEFAULT,
    maxProgrammesTotal: Int = MAX_PROGRAMMES_TOTAL_DEFAULT,
    onProgress: ((EpgBatchProgress) -> Unit)? = null,
): EpgDbParseStats = withContext(Dispatchers.IO) {
    val startedAtMs = Clock.System.now().toEpochMilliseconds()
    val safeBatchSize = batchSize.coerceIn(50, 200)
    val maxPerChannel = maxProgrammesPerChannel.coerceAtLeast(1)
    val maxTotal = maxProgrammesTotal.coerceAtLeast(1)

    val channelBatch = ArrayList<EpgChannelInsert>(safeBatchSize)
    val programmeBatch = ArrayList<EpgProgrammeInsert>(safeBatchSize)
    val insertedChannelKeys = HashSet<String>()
    val programmeCountsByChannel = HashMap<String, Int>()
    val channelMetaByKey = HashMap<String, ChannelMeta>()

    var channelsSeen = 0
    var totalProgrammesSeen = 0
    var programmesKept = 0
    var programmesSkippedByWindow = 0
    var programmesSkippedByChannelFilter = 0
    var programmesSkippedByInvalidTime = 0
    var programmesSkippedByNoMapping = 0
    var programmesSkippedByCap = 0
    var batchesCommitted = 0
    var abortedByGlobalCap = false

    fun progressSnapshot(): EpgBatchProgress {
        val (heapUsedMb, heapFreeMb) = heapStatsMb()
        return EpgBatchProgress(
            totalSeen = totalProgrammesSeen,
            kept = programmesKept,
            skippedByWindow = programmesSkippedByWindow,
            skippedByChannelFilter = programmesSkippedByChannelFilter,
            skippedByInvalidTime = programmesSkippedByInvalidTime,
            skippedByNoMapping = programmesSkippedByNoMapping,
            skippedByCap = programmesSkippedByCap,
            batchesCommitted = batchesCommitted,
            heapUsedMb = heapUsedMb,
            heapFreeMb = heapFreeMb,
        )
    }

    fun flushBatches() {
        if (channelBatch.isEmpty() && programmeBatch.isEmpty()) return
        db.torveQueries.transaction {
            channelBatch.forEach { row ->
                db.torveQueries.insertEpgChannel(
                    user_id = userId,
                    playlist_id = playlistId,
                    generation_id = generationId,
                    channel_id = row.channelId,
                    epg_channel_key = row.epgChannelKey,
                    xmltv_channel_id = row.xmltvChannelId,
                    display_name = row.displayName,
                    icon_url = row.iconUrl,
                    updated_at = Clock.System.now().toEpochMilliseconds(),
                )
            }
            programmeBatch.forEach { row ->
                db.torveQueries.insertEpgProgramme(
                    user_id = userId,
                    playlist_id = playlistId,
                    generation_id = generationId,
                    channel_id = row.channelId,
                    epg_channel_key = row.epgChannelKey,
                    xmltv_channel_id = row.xmltvChannelId,
                    start_time = row.startTime,
                    end_time = row.endTime,
                    title = row.title,
                )
            }
        }
        channelBatch.clear()
        programmeBatch.clear()
        batchesCommitted++
        onProgress?.invoke(progressSnapshot())
    }

    fun enqueueChannel(epgChannelKey: String, xmltvChannelId: String?, fallbackDisplayName: String, iconUrl: String?) {
        if (!insertedChannelKeys.add(epgChannelKey)) return
        if (channelBatch.size >= safeBatchSize || programmeBatch.size >= safeBatchSize) flushBatches()
        channelBatch += EpgChannelInsert(
            channelId = epgChannelKey,
            epgChannelKey = epgChannelKey,
            xmltvChannelId = xmltvChannelId,
            displayName = fallbackDisplayName.take(TITLE_MAX_LEN).ifBlank { epgChannelKey },
            iconUrl = iconUrl,
        )
    }

    fun enqueueProgramme(row: EpgProgrammeInsert) {
        if (programmeBatch.size >= safeBatchSize || channelBatch.size >= safeBatchSize) flushBatches()
        programmeBatch += row
    }

    db.torveQueries.clearEpgProgrammesForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)
    db.torveQueries.clearEpgChannelsForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)

    val factory = XMLInputFactory.newInstance().apply {
        // Defensive against XML bombs / external entity attacks. Public IPTV
        // EPG feeds are untrusted input.
        setProperty(XMLInputFactory.SUPPORT_DTD, false)
        setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
        setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
        setProperty(XMLInputFactory.IS_NAMESPACE_AWARE, false)
        setProperty(XMLInputFactory.IS_COALESCING, false)
    }
    val reader: XMLStreamReader = factory.createXMLStreamReader(input, "UTF-8")

    try {
        while (reader.hasNext()) {
            coroutineContext.ensureActive()
            if (abortedByGlobalCap) break
            val event = reader.next()
            if (event != XMLStreamConstants.START_ELEMENT) continue

            when (reader.localName) {
                "channel" -> {
                    channelsSeen++
                    val xmltvChannelId = reader.getAttributeValue(null, "id")?.trim().orEmpty()
                    val resolvedKey = resolveEpgChannelKey?.invoke(xmltvChannelId, null)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    val meta = consumeChannelElement(reader)
                    if (!resolvedKey.isNullOrBlank() && (channelFilter == null || resolvedKey in channelFilter)) {
                        val normalizedDisplay = meta?.displayName?.takeIf { it.isNotBlank() } ?: resolvedKey
                        channelMetaByKey[resolvedKey] = ChannelMeta(normalizedDisplay, meta?.iconUrl)
                        enqueueChannel(
                            epgChannelKey = resolvedKey,
                            xmltvChannelId = xmltvChannelId.takeIf { it.isNotBlank() },
                            fallbackDisplayName = normalizedDisplay,
                            iconUrl = meta?.iconUrl,
                        )
                    }
                }
                "programme" -> {
                    totalProgrammesSeen++
                    val xmltvChannelId = reader.getAttributeValue(null, "channel")?.trim().orEmpty()
                    val startRaw = reader.getAttributeValue(null, "start")
                    val stopRaw = reader.getAttributeValue(null, "stop")

                    if (startRaw.isNullOrBlank() || stopRaw.isNullOrBlank() || xmltvChannelId.isBlank()) {
                        programmesSkippedByInvalidTime++
                        consumeProgrammeElement(reader, captureTitle = false)
                        continue
                    }
                    val startMs = parseXmltvTimestamp(startRaw)
                    val stopMs = parseXmltvTimestamp(stopRaw)
                    if (startMs <= 0L || stopMs <= startMs) {
                        programmesSkippedByInvalidTime++
                        consumeProgrammeElement(reader, captureTitle = false)
                        continue
                    }
                    if (stopMs < windowStartMs || startMs > windowEndMs) {
                        programmesSkippedByWindow++
                        consumeProgrammeElement(reader, captureTitle = false)
                        continue
                    }

                    val epgChannelKey = resolveEpgChannelKey?.invoke(xmltvChannelId, null)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    if (epgChannelKey == null) {
                        programmesSkippedByNoMapping++
                        consumeProgrammeElement(reader, captureTitle = false)
                        continue
                    }
                    if (channelFilter != null && epgChannelKey !in channelFilter) {
                        programmesSkippedByChannelFilter++
                        consumeProgrammeElement(reader, captureTitle = false)
                        continue
                    }
                    val channelCount = programmeCountsByChannel[epgChannelKey] ?: 0
                    if (channelCount >= maxPerChannel) {
                        programmesSkippedByCap++
                        consumeProgrammeElement(reader, captureTitle = false)
                        continue
                    }
                    if (programmesKept >= maxTotal) {
                        abortedByGlobalCap = true
                        consumeProgrammeElement(reader, captureTitle = false)
                        break
                    }

                    val title = consumeProgrammeElement(reader, captureTitle = true)
                        ?.take(TITLE_MAX_LEN)
                        ?.ifBlank { null }
                        ?: "Unknown"

                    val channelMeta = channelMetaByKey[epgChannelKey]
                    enqueueChannel(
                        epgChannelKey = epgChannelKey,
                        xmltvChannelId = xmltvChannelId,
                        fallbackDisplayName = channelMeta?.displayName ?: epgChannelKey,
                        iconUrl = channelMeta?.iconUrl,
                    )
                    enqueueProgramme(
                        EpgProgrammeInsert(
                            channelId = epgChannelKey,
                            epgChannelKey = epgChannelKey,
                            xmltvChannelId = xmltvChannelId,
                            startTime = startMs,
                            endTime = stopMs,
                            title = title,
                        ),
                    )
                    programmesKept++
                    programmeCountsByChannel[epgChannelKey] = channelCount + 1
                }
                else -> Unit
            }
        }

        if (!abortedByGlobalCap) flushBatches()
    } catch (t: Throwable) {
        db.torveQueries.clearEpgProgrammesForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)
        db.torveQueries.clearEpgChannelsForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)
        throw t
    } finally {
        runCatching { reader.close() }
    }

    val durationMs = Clock.System.now().toEpochMilliseconds() - startedAtMs
    println(
        "ChannelsEPG: db parser complete (desktop) playlistId=$playlistId generation=$generationId channelsSeen=$channelsSeen totalSeen=$totalProgrammesSeen kept=$programmesKept skippedByWindow=$programmesSkippedByWindow skippedByChannelFilter=$programmesSkippedByChannelFilter skippedByInvalidTime=$programmesSkippedByInvalidTime skippedByNoMapping=$programmesSkippedByNoMapping skippedByCap=$programmesSkippedByCap abortedByGlobalCap=$abortedByGlobalCap durationMs=$durationMs",
    )

    EpgDbParseStats(
        channelsSeen = channelsSeen,
        totalProgrammesSeen = totalProgrammesSeen,
        programmesKept = programmesKept,
        programmesSkippedByWindow = programmesSkippedByWindow,
        programmesSkippedByChannelFilter = programmesSkippedByChannelFilter,
        programmesSkippedByInvalidTime = programmesSkippedByInvalidTime,
        programmesSkippedByNoMapping = programmesSkippedByNoMapping,
        programmesSkippedByCap = programmesSkippedByCap,
        abortedByGlobalCap = abortedByGlobalCap,
        parseDurationMs = durationMs,
    )
}
