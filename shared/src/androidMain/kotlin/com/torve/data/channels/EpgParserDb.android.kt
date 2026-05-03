package com.torve.data.channels

import com.torve.db.TorveDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.InputStreamReader
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

private fun skipCurrentTag(parser: XmlPullParser) {
    if (parser.eventType != XmlPullParser.START_TAG) return
    var depth = 1
    while (depth > 0) {
        when (parser.next()) {
            XmlPullParser.START_TAG -> depth++
            XmlPullParser.END_TAG -> depth--
            XmlPullParser.END_DOCUMENT -> return
        }
    }
}

private fun readTagTextCapped(parser: XmlPullParser, maxLen: Int): String? {
    if (maxLen <= 0) return null
    val startDepth = parser.depth
    val out = StringBuilder(minOf(32, maxLen))
    while (true) {
        when (parser.next()) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.END_TAG -> {
                if (parser.depth == startDepth) break
            }
            XmlPullParser.TEXT,
            XmlPullParser.CDSECT,
            -> {
                val text = parser.text?.trim().orEmpty()
                if (text.isNotEmpty() && out.length < maxLen) {
                    if (out.isNotEmpty() && out.last() != ' ') {
                        out.append(' ')
                    }
                    val remaining = maxLen - out.length
                    if (remaining > 0) {
                        if (text.length > remaining) {
                            out.append(text, 0, remaining)
                        } else {
                            out.append(text)
                        }
                    }
                }
            }
            XmlPullParser.START_TAG -> skipCurrentTag(parser)
        }
    }
    return out.toString().trim().ifEmpty { null }
}

private fun consumeChannelTag(
    parser: XmlPullParser,
): ChannelMeta? {
    val startDepth = parser.depth
    var displayName: String? = null
    var iconUrl: String? = null

    while (true) {
        when (parser.next()) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.END_TAG -> {
                if (parser.depth == startDepth) break
            }
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "display-name" -> {
                        if (displayName.isNullOrBlank()) {
                            displayName = readTagTextCapped(parser, TITLE_MAX_LEN)
                        } else {
                            skipCurrentTag(parser)
                        }
                    }
                    "icon" -> {
                        if (iconUrl.isNullOrBlank()) {
                            iconUrl = parser.getAttributeValue(null, "src")
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                        }
                        skipCurrentTag(parser)
                    }
                    else -> skipCurrentTag(parser)
                }
            }
        }
    }

    if (displayName.isNullOrBlank() && iconUrl.isNullOrBlank()) {
        return null
    }
    return ChannelMeta(
        displayName = displayName?.trim().orEmpty(),
        iconUrl = iconUrl,
    )
}

private fun consumeProgrammeTagAndReadTitle(
    parser: XmlPullParser,
    shouldCaptureTitle: Boolean,
): String? {
    val startDepth = parser.depth
    var title: String? = null

    while (true) {
        when (parser.next()) {
            XmlPullParser.END_DOCUMENT -> break
            XmlPullParser.END_TAG -> {
                if (parser.depth == startDepth) break
            }
            XmlPullParser.START_TAG -> {
                when (parser.name) {
                    "title" -> {
                        if (shouldCaptureTitle && title.isNullOrBlank()) {
                            title = readTagTextCapped(parser, TITLE_MAX_LEN)
                        } else {
                            skipCurrentTag(parser)
                        }
                    }
                    else -> skipCurrentTag(parser)
                }
            }
        }
    }
    return title?.take(TITLE_MAX_LEN)?.trim()?.ifEmpty { null }
}

internal suspend fun EpgParser.parseXmlTvStreamingToDb(
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

        val progress = progressSnapshot()
        onProgress?.invoke(progress)
        println(
            "ChannelsEPG: db parser batch playlistId=$playlistId generation=$generationId batches=$batchesCommitted totalSeen=${progress.totalSeen} kept=${progress.kept} skippedByWindow=${progress.skippedByWindow} skippedByNoMapping=${progress.skippedByNoMapping} skippedByCap=${progress.skippedByCap} heapUsedMb=${progress.heapUsedMb} heapFreeMb=${progress.heapFreeMb}",
        )
    }

    fun enqueueChannel(epgChannelKey: String, xmltvChannelId: String?, fallbackDisplayName: String, iconUrl: String?) {
        if (!insertedChannelKeys.add(epgChannelKey)) return
        if (channelBatch.size >= safeBatchSize || programmeBatch.size >= safeBatchSize) {
            flushBatches()
        }
        channelBatch += EpgChannelInsert(
            channelId = epgChannelKey,
            epgChannelKey = epgChannelKey,
            xmltvChannelId = xmltvChannelId,
            displayName = fallbackDisplayName.take(TITLE_MAX_LEN).ifBlank { epgChannelKey },
            iconUrl = iconUrl,
        )
    }

    fun enqueueProgramme(row: EpgProgrammeInsert) {
        if (programmeBatch.size >= safeBatchSize || channelBatch.size >= safeBatchSize) {
            flushBatches()
        }
        programmeBatch += row
    }

    println(
        "ChannelsEPG: db parser start playlistId=$playlistId generation=$generationId windowStartMs=$windowStartMs windowEndMs=$windowEndMs batchSize=$safeBatchSize maxPerChannel=$maxPerChannel maxTotal=$maxTotal heapUsedMb=${progressSnapshot().heapUsedMb} heapFreeMb=${progressSnapshot().heapFreeMb}",
    )

    db.torveQueries.clearEpgProgrammesForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)
    db.torveQueries.clearEpgChannelsForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)

    try {
        val xmlParser = XmlPullParserFactory.newInstance().newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(InputStreamReader(input, Charsets.UTF_8))
        }

        var eventType = xmlParser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            coroutineContext.ensureActive()
            if (abortedByGlobalCap) break

            if (eventType == XmlPullParser.START_TAG) {
                when (xmlParser.name) {
                    "channel" -> {
                        channelsSeen++
                        val xmltvChannelId = xmlParser.getAttributeValue(null, "id")
                            ?.trim()
                            .orEmpty()
                        val resolvedKey = resolveEpgChannelKey?.invoke(xmltvChannelId, null)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                        val meta = consumeChannelTag(xmlParser)
                        if (!resolvedKey.isNullOrBlank() && (channelFilter == null || resolvedKey in channelFilter)) {
                            val normalizedDisplay = meta?.displayName?.takeIf { it.isNotBlank() } ?: resolvedKey
                            channelMetaByKey[resolvedKey] = ChannelMeta(
                                displayName = normalizedDisplay,
                                iconUrl = meta?.iconUrl,
                            )
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
                        val xmltvChannelId = xmlParser.getAttributeValue(null, "channel")
                            ?.trim()
                            .orEmpty()
                        val startRaw = xmlParser.getAttributeValue(null, "start")
                        val stopRaw = xmlParser.getAttributeValue(null, "stop")

                        if (startRaw.isNullOrBlank() || stopRaw.isNullOrBlank() || xmltvChannelId.isBlank()) {
                            programmesSkippedByInvalidTime++
                            consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = false)
                            eventType = xmlParser.next()
                            continue
                        }

                        val startMs = parseXmltvTimestamp(startRaw)
                        val stopMs = parseXmltvTimestamp(stopRaw)
                        if (startMs <= 0L || stopMs <= startMs) {
                            programmesSkippedByInvalidTime++
                            consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = false)
                            eventType = xmlParser.next()
                            continue
                        }

                        if (stopMs < windowStartMs || startMs > windowEndMs) {
                            programmesSkippedByWindow++
                            consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = false)
                            eventType = xmlParser.next()
                            continue
                        }

                        val epgChannelKey = resolveEpgChannelKey?.invoke(xmltvChannelId, null)
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }

                        if (epgChannelKey == null) {
                            programmesSkippedByNoMapping++
                            consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = false)
                            eventType = xmlParser.next()
                            continue
                        }

                        if (channelFilter != null && epgChannelKey !in channelFilter) {
                            programmesSkippedByChannelFilter++
                            consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = false)
                            eventType = xmlParser.next()
                            continue
                        }

                        val channelCount = programmeCountsByChannel[epgChannelKey] ?: 0
                        if (channelCount >= maxPerChannel) {
                            programmesSkippedByCap++
                            consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = false)
                            eventType = xmlParser.next()
                            continue
                        }

                        if (programmesKept >= maxTotal) {
                            abortedByGlobalCap = true
                            consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = false)
                            break
                        }

                        val title = consumeProgrammeTagAndReadTitle(xmlParser, shouldCaptureTitle = true)
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
            eventType = xmlParser.next()
        }

        if (!abortedByGlobalCap) {
            flushBatches()
        }
    } catch (t: Throwable) {
        db.torveQueries.clearEpgProgrammesForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)
        db.torveQueries.clearEpgChannelsForPlaylistGeneration(userId = userId, playlistId = playlistId, generationId = generationId)
        throw t
    }

    val durationMs = Clock.System.now().toEpochMilliseconds() - startedAtMs
    println(
        "ChannelsEPG: db parser complete playlistId=$playlistId generation=$generationId channelsSeen=$channelsSeen totalSeen=$totalProgrammesSeen kept=$programmesKept skippedByWindow=$programmesSkippedByWindow skippedByChannelFilter=$programmesSkippedByChannelFilter skippedByInvalidTime=$programmesSkippedByInvalidTime skippedByNoMapping=$programmesSkippedByNoMapping skippedByCap=$programmesSkippedByCap abortedByGlobalCap=$abortedByGlobalCap durationMs=$durationMs",
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
