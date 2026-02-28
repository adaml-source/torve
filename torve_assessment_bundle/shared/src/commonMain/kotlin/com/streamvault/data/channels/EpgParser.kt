package com.streamvault.data.channels

import com.streamvault.domain.model.EpgChannel
import com.streamvault.domain.model.EpgData
import com.streamvault.domain.model.EpgProgramme

/**
 * Simple XMLTV EPG parser using regex-based extraction.
 * Handles the most common XMLTV format without requiring a full XML parser.
 */
class EpgParser {

    fun parse(xmlContent: String): EpgData {
        val channels = mutableMapOf<String, EpgChannel>()
        val programmes = mutableListOf<EpgProgramme>()

        // Parse channels
        val channelPattern = """<channel\s+id="([^"]*)">(.*?)</channel>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val displayNamePattern = """<display-name[^>]*>([^<]*)</display-name>""".toRegex()
        val iconPattern = """<icon\s+src="([^"]*)"[^/]*/?>""".toRegex()

        channelPattern.findAll(xmlContent).forEach { match ->
            val id = match.groupValues[1]
            val body = match.groupValues[2]
            val displayName = displayNamePattern.find(body)?.groupValues?.get(1) ?: id
            val icon = iconPattern.find(body)?.groupValues?.get(1)
            channels[id] = EpgChannel(id, displayName, icon)
        }

        // Parse programmes
        val progPattern = """<programme\s+([^>]*)>(.*?)</programme>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val attrPattern = """(\w+)="([^"]*)"""".toRegex()
        val titlePattern = """<title[^>]*>([^<]*)</title>""".toRegex()
        val subTitlePattern = """<sub-title[^>]*>([^<]*)</sub-title>""".toRegex()
        val descPattern = """<desc[^>]*>([^<]*)</desc>""".toRegex()
        val categoryPattern = """<category[^>]*>([^<]*)</category>""".toRegex()

        progPattern.findAll(xmlContent).forEach { match ->
            val attrs = match.groupValues[1]
            val body = match.groupValues[2]

            val attrMap = mutableMapOf<String, String>()
            attrPattern.findAll(attrs).forEach { attrMap[it.groupValues[1]] = it.groupValues[2] }

            val startStr = attrMap["start"] ?: return@forEach
            val stopStr = attrMap["stop"] ?: return@forEach
            val channelId = attrMap["channel"] ?: return@forEach

            val title = titlePattern.find(body)?.groupValues?.get(1) ?: "Unknown"
            val subTitle = subTitlePattern.find(body)?.groupValues?.get(1)
            val desc = descPattern.find(body)?.groupValues?.get(1)
            val category = categoryPattern.find(body)?.groupValues?.get(1)
            val icon = iconPattern.find(body)?.groupValues?.get(1)

            programmes.add(
                EpgProgramme(
                    channelId = channelId,
                    startTime = parseXmltvTimestamp(startStr),
                    endTime = parseXmltvTimestamp(stopStr),
                    title = title,
                    subTitle = subTitle,
                    description = desc,
                    category = category,
                    iconUrl = icon,
                ),
            )
        }

        return EpgData(channels, programmes)
    }

    /**
     * Parse XMLTV timestamp: "20250221180000 +0000" → epoch millis.
     */
    private fun parseXmltvTimestamp(ts: String): Long {
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
        val offsetMinutes = if (tzPart.isNotEmpty() && tzPart.length >= 5) {
            val sign = if (tzPart[0] == '-') -1 else 1
            val hh = tzPart.substring(1, 3).toIntOrNull() ?: 0
            val mm = tzPart.substring(3, 5).toIntOrNull() ?: 0
            sign * (hh * 60 + mm)
        } else 0

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

    private fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
}
