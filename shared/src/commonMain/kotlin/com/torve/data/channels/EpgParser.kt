package com.torve.data.channels

import com.torve.domain.model.EpgChannel
import com.torve.domain.model.EpgData
import com.torve.domain.model.EpgProgramme
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.datetime.Clock

data class EpgBatchProgress(
    val totalSeen: Int,
    val kept: Int,
    val skippedByWindow: Int,
    val skippedByChannelFilter: Int,
    val skippedByInvalidTime: Int,
    val skippedByNoMapping: Int,
    val skippedByCap: Int,
    val batchesCommitted: Int,
    val heapUsedMb: Long,
    val heapFreeMb: Long,
)

data class EpgDbParseStats(
    val channelsSeen: Int,
    val totalProgrammesSeen: Int,
    val programmesKept: Int,
    val programmesSkippedByWindow: Int,
    val programmesSkippedByChannelFilter: Int,
    val programmesSkippedByInvalidTime: Int,
    val programmesSkippedByNoMapping: Int,
    val programmesSkippedByCap: Int,
    val abortedByGlobalCap: Boolean,
    val parseDurationMs: Long,
)

/**
 * XMLTV EPG parser with streaming modes only.
 */
class EpgParser {

    private companion object {
        private const val CHANNEL_END_TAG = "</channel>"
        private const val PROGRAMME_END_TAG = "</programme>"
        private const val STREAM_CHUNK_BYTES = 64 * 1024
        private const val MAX_STREAM_BUFFER_CHARS = 512_000
        private const val STREAM_BUFFER_RETAIN_CHARS = 128_000
    }

    private data class StreamingStats(
        var totalProgrammesSeen: Int = 0,
        var programmesKept: Int = 0,
        var programmesSkippedByWindow: Int = 0,
        var channelsSeen: Int = 0,
    )

    private enum class ProgrammeParseOutcome {
        KEPT,
        SKIPPED_BY_WINDOW,
        SKIPPED_INVALID,
    }

    private val channelPattern = """(?s)<channel\s+id="([^"]*)">(.*?)</channel>""".toRegex()
    private val displayNamePattern = """<display-name[^>]*>([^<]*)</display-name>""".toRegex()
    private val iconPattern = """<icon\s+src="([^"]*)"[^/]*/?>""".toRegex()
    private val progPattern = """(?s)<programme\s+([^>]*)>(.*?)</programme>""".toRegex()
    private val attrPattern = """(\w+)="([^"]*)"""".toRegex()
    private val titlePattern = """<title[^>]*>([^<]*)</title>""".toRegex()
    private val subTitlePattern = """<sub-title[^>]*>([^<]*)</sub-title>""".toRegex()
    private val descPattern = """<desc[^>]*>([^<]*)</desc>""".toRegex()
    private val categoryPattern = """<category[^>]*>([^<]*)</category>""".toRegex()

    suspend fun parseXmlTvStreaming(
        xmlChannel: ByteReadChannel,
        windowStartMs: Long,
        windowEndMs: Long,
        maxChannels: Int = 120_000,
        maxProgrammes: Int = 400_000,
    ): EpgData {
        return parseXmlTvStreaming(
            readChunk = { target ->
                xmlChannel.readAvailable(target, 0, target.size)
            },
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            maxChannels = maxChannels,
            maxProgrammes = maxProgrammes,
        )
    }

    suspend fun parseStreaming(
        xmlChannel: ByteReadChannel,
        windowStartMs: Long?,
        windowEndMs: Long?,
        maxChannels: Int = 120_000,
        maxProgrammes: Int = 400_000,
    ): EpgData {
        val effectiveWindowStart = windowStartMs ?: Long.MIN_VALUE
        val effectiveWindowEnd = windowEndMs ?: Long.MAX_VALUE
        return parseXmlTvStreaming(
            xmlChannel = xmlChannel,
            windowStartMs = effectiveWindowStart,
            windowEndMs = effectiveWindowEnd,
            maxChannels = maxChannels,
            maxProgrammes = maxProgrammes,
        )
    }

    suspend fun parseXmlTvStreaming(
        readChunk: suspend (ByteArray) -> Int,
        windowStartMs: Long,
        windowEndMs: Long,
        maxChannels: Int = 120_000,
        maxProgrammes: Int = 400_000,
    ): EpgData {
        val channels = mutableMapOf<String, EpgChannel>()
        val programmes = mutableListOf<EpgProgramme>()
        val chunkBuffer = ByteArray(STREAM_CHUNK_BYTES)
        val parseBuffer = StringBuilder(STREAM_CHUNK_BYTES * 2)
        val stats = StreamingStats()
        val parseStartMs = Clock.System.now().toEpochMilliseconds()

        while (true) {
            val bytesRead = readChunk(chunkBuffer)
            if (bytesRead < 0) break
            if (bytesRead == 0) continue

            parseBuffer.append(chunkBuffer.decodeToString(startIndex = 0, endIndex = bytesRead))
            processStreamingBuffer(
                buffer = parseBuffer,
                channels = channels,
                programmes = programmes,
                windowStartMs = windowStartMs,
                windowEndMs = windowEndMs,
                maxChannels = maxChannels,
                maxProgrammes = maxProgrammes,
                compactMode = true,
                stats = stats,
            )
        }

        processStreamingBuffer(
            buffer = parseBuffer,
            channels = channels,
            programmes = programmes,
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            maxChannels = maxChannels,
            maxProgrammes = maxProgrammes,
            compactMode = true,
            stats = stats,
        )

        val parseDurationMs = Clock.System.now().toEpochMilliseconds() - parseStartMs
        println(
            "ChannelsEPG: parser summary channelsSeen=${stats.channelsSeen} totalProgrammesSeen=${stats.totalProgrammesSeen} programmesKept=${stats.programmesKept} programmesSkippedByWindow=${stats.programmesSkippedByWindow} durationMs=$parseDurationMs",
        )

        return EpgData(channels = channels, programmes = programmes)
    }

    private fun processStreamingBuffer(
        buffer: StringBuilder,
        channels: MutableMap<String, EpgChannel>,
        programmes: MutableList<EpgProgramme>,
        windowStartMs: Long?,
        windowEndMs: Long?,
        maxChannels: Int,
        maxProgrammes: Int,
        compactMode: Boolean,
        stats: StreamingStats?,
    ) {
        while (true) {
            val channelStart = buffer.indexOf("<channel")
            val programmeStart = buffer.indexOf("<programme")
            val nextBlockStart = when {
                channelStart < 0 -> programmeStart
                programmeStart < 0 -> channelStart
                else -> minOf(channelStart, programmeStart)
            }

            if (nextBlockStart < 0) {
                trimStreamingBuffer(buffer)
                return
            }

            if (nextBlockStart > 0) {
                buffer.deleteRange(0, nextBlockStart)
            }

            if (buffer.startsWith("<channel")) {
                val endIdx = buffer.indexOf(CHANNEL_END_TAG)
                if (endIdx < 0) {
                    trimStreamingBuffer(buffer)
                    return
                }
                val blockEnd = endIdx + CHANNEL_END_TAG.length
                if (channels.size < maxChannels) {
                    parseChannelBlock(
                        channelBlock = buffer.substring(0, blockEnd),
                        channels = channels,
                        stats = stats,
                    )
                }
                buffer.deleteRange(0, blockEnd)
                continue
            }

            if (buffer.startsWith("<programme")) {
                val endIdx = buffer.indexOf(PROGRAMME_END_TAG)
                if (endIdx < 0) {
                    trimStreamingBuffer(buffer)
                    return
                }
                val blockEnd = endIdx + PROGRAMME_END_TAG.length
                if (programmes.size < maxProgrammes) {
                    parseProgrammeBlock(
                        programmeBlock = buffer.substring(0, blockEnd),
                        programmes = programmes,
                        windowStartMs = windowStartMs,
                        windowEndMs = windowEndMs,
                        compactMode = compactMode,
                        stats = stats,
                    )
                }
                buffer.deleteRange(0, blockEnd)
                continue
            }

            // Unrecognized leading content: skip one char and continue.
            buffer.deleteRange(0, 1)
        }
    }

    private fun trimStreamingBuffer(buffer: StringBuilder) {
        if (buffer.length > MAX_STREAM_BUFFER_CHARS) {
            buffer.deleteRange(0, buffer.length - STREAM_BUFFER_RETAIN_CHARS)
        }
    }

    private fun parseChannelBlock(
        channelBlock: String,
        channels: MutableMap<String, EpgChannel>,
        stats: StreamingStats?,
    ) {
        val match = channelPattern.find(channelBlock) ?: return
        val id = match.groupValue(1)?.trim().orEmpty()
        if (id.isEmpty()) return
        val body = match.groupValue(2).orEmpty()
        val displayName = displayNamePattern.find(body)?.groupValue(1)?.trim()?.ifEmpty { id } ?: id
        val icon = iconPattern.find(body)?.groupValue(1)?.trim()?.ifEmpty { null }
        channels[id] = EpgChannel(id = id, displayName = displayName, iconUrl = icon)
        stats?.channelsSeen = (stats?.channelsSeen ?: 0) + 1
    }

    private fun parseProgrammeBlock(
        programmeBlock: String,
        programmes: MutableList<EpgProgramme>,
        windowStartMs: Long?,
        windowEndMs: Long?,
        compactMode: Boolean,
        stats: StreamingStats?,
    ): ProgrammeParseOutcome {
        val match = progPattern.find(programmeBlock) ?: return ProgrammeParseOutcome.SKIPPED_INVALID
        val attrs = match.groupValue(1).orEmpty()
        val body = match.groupValue(2).orEmpty()

        val attrMap = mutableMapOf<String, String>()
        attrPattern.findAll(attrs).forEach { matchResult ->
            val key = matchResult.groupValue(1)
            val value = matchResult.groupValue(2)
            if (key != null && value != null) {
                attrMap[key] = value
            }
        }

        val startStr = attrMap["start"] ?: return ProgrammeParseOutcome.SKIPPED_INVALID
        val stopStr = attrMap["stop"] ?: return ProgrammeParseOutcome.SKIPPED_INVALID
        val channelId = attrMap["channel"] ?: return ProgrammeParseOutcome.SKIPPED_INVALID

        val startTime = parseXmltvTimestamp(startStr)
        val endTime = parseXmltvTimestamp(stopStr)
        stats?.totalProgrammesSeen = (stats?.totalProgrammesSeen ?: 0) + 1
        if (endTime <= startTime) return ProgrammeParseOutcome.SKIPPED_INVALID
        if (windowStartMs != null && endTime < windowStartMs) {
            stats?.programmesSkippedByWindow = (stats?.programmesSkippedByWindow ?: 0) + 1
            return ProgrammeParseOutcome.SKIPPED_BY_WINDOW
        }
        if (windowEndMs != null && startTime > windowEndMs) {
            stats?.programmesSkippedByWindow = (stats?.programmesSkippedByWindow ?: 0) + 1
            return ProgrammeParseOutcome.SKIPPED_BY_WINDOW
        }

        val title = titlePattern.find(body)?.groupValue(1)?.trim().orEmpty().ifEmpty { "Unknown" }
        val subTitle = if (compactMode) {
            null
        } else {
            subTitlePattern.find(body)?.groupValue(1)?.trim()?.ifEmpty { null }
        }
        val desc = if (compactMode) {
            descPattern.find(body)?.groupValue(1)?.trim()?.take(320)?.ifEmpty { null }
        } else {
            descPattern.find(body)?.groupValue(1)?.trim()?.take(800)?.ifEmpty { null }
        }
        val category = if (compactMode) {
            null
        } else {
            categoryPattern.find(body)?.groupValue(1)?.trim()?.ifEmpty { null }
        }
        val icon = if (compactMode) {
            null
        } else {
            iconPattern.find(body)?.groupValue(1)?.trim()?.ifEmpty { null }
        }

        programmes.add(
            EpgProgramme(
                channelId = channelId,
                startTime = startTime,
                endTime = endTime,
                title = title,
                subTitle = subTitle,
                description = desc,
                category = category,
                iconUrl = icon,
            ),
        )
        stats?.programmesKept = (stats?.programmesKept ?: 0) + 1
        return ProgrammeParseOutcome.KEPT
    }

    private fun MatchResult.groupValue(index: Int): String? = groups[index]?.value

    /**
     * Parse XMLTV timestamp: "20250221180000 +0000" → epoch millis.
     */
    internal fun parseXmltvTimestamp(ts: String): Long {
        val d = ts.take(14)
        if (d.length < 14) return 0L

        val year = d.substring(0, 4).toIntOrNull() ?: return 0L
        val month = d.substring(4, 6).toIntOrNull() ?: return 0L
        val day = d.substring(6, 8).toIntOrNull() ?: return 0L
        val hour = d.substring(8, 10).toIntOrNull() ?: return 0L
        val minute = d.substring(10, 12).toIntOrNull() ?: return 0L
        val second = d.substring(12, 14).toIntOrNull() ?: return 0L

        // Simple epoch calculation (ignoring leap seconds)
        val tzPart = ts.substring(14).trim()
        val offsetMinutes = parseOffsetMinutes(tzPart)

        // Days from epoch to year
        var days = 0L
        for (y in 1970 until year) {
            days += if (isLeapYear(y)) 366 else 365
        }
        val daysInMonth = intArrayOf(0, 31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        if (isLeapYear(year)) daysInMonth[2] = 29
        for (m in 1 until month) {
            days += daysInMonth[m]
        }
        days += day - 1

        val totalSeconds = days * 86400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
        return totalSeconds * 1000L
    }

    private fun parseOffsetMinutes(offsetPart: String): Int {
        if (offsetPart.isBlank() || offsetPart.equals("z", ignoreCase = true)) return 0
        val normalized = offsetPart.trim().replace(":", "")
        if (normalized.length < 5) return 0
        val signChar = normalized[0]
        if (signChar != '+' && signChar != '-') return 0
        val hours = normalized.substring(1, 3).toIntOrNull() ?: return 0
        val minutes = normalized.substring(3, 5).toIntOrNull() ?: return 0
        val sign = if (signChar == '-') -1 else 1
        return sign * (hours * 60 + minutes)
    }

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}
