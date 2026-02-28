package com.streamvault.util

import kotlin.math.roundToInt

object FormatUtil {

    fun formatRuntime(minutes: Int?): String {
        if (minutes == null || minutes <= 0) return ""
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "${m}m"
            m == 0 -> "${h}h"
            else -> "${h}h ${m}m"
        }
    }

    fun formatRating(rating: Double?): String {
        if (rating == null || rating <= 0) return ""
        return ((rating * 10).roundToInt() / 10.0).toString()
    }

    fun formatBytes(bytes: Long?): String {
        if (bytes == null || bytes <= 0) return ""
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var unitIndex = 0
        var size = bytes.toDouble()
        while (size >= 1024 && unitIndex < units.size - 1) {
            size /= 1024
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${size.roundToInt()} ${units[unitIndex]}"
        } else {
            "${"%.1f".format(size)} ${units[unitIndex]}"
        }
    }

    fun formatYear(date: String?): String {
        if (date.isNullOrBlank()) return ""
        return date.take(4).toIntOrNull()?.toString() ?: ""
    }

    fun formatDate(date: String?): String {
        if (date.isNullOrBlank()) return ""
        val parts = date.split("-")
        if (parts.size < 3) return date
        val year = parts[0].toIntOrNull() ?: return date
        val month = parts[1].toIntOrNull() ?: return date
        val day = parts[2].toIntOrNull() ?: return date
        val monthNames = listOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        return if (month in 1..12) {
            "${monthNames[month - 1]} $day, $year"
        } else {
            date
        }
    }
}
