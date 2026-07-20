package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class RegexPattern(
    val label: String = "",
    val pattern: String = "",
    val enabled: Boolean = true,
)

@Serializable
data class StreamGroup(
    val name: String,
    val matchPattern: String,
    val priority: Int,
    val enabled: Boolean = true,
)

val DEFAULT_STREAM_GROUPS = listOf(
    StreamGroup("4K / HDR", "(?i)(2160p|4k|uhd|hdr|dv)", 0),
    StreamGroup("1080p", "(?i)(1080p)", 1),
    StreamGroup("720p", "(?i)(720p)", 2),
    StreamGroup("Cloud", "(?i)(\\[RD\\]|debrid)", 0),
)
