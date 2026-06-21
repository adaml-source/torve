package com.torve.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class UserPreferences(
    val preferredQuality: String = "1080p",
    val hdrEnabled: Boolean = false,
    val dvEnabled: Boolean = false,
    val cachedOnly: Boolean = true,
    val autoPlayNext: Boolean = true,
    val backgroundPlayback: Boolean = false,
)
