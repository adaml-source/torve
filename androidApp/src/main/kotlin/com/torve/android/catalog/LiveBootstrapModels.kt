package com.torve.android.catalog

import com.torve.domain.model.Channel
import com.torve.domain.model.EpgProgramme
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val LIVE_BOOTSTRAP_DISPLAY_PREFIX = "live_bootstrap_display_v1_"

internal val LiveBootstrapJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
}

@Serializable
internal data class LiveBootstrapShelf(
    val entries: List<LiveBootstrapShelfEntry> = emptyList(),
)

@Serializable
internal data class LiveBootstrapShelfEntry(
    val channel: Channel,
    val currentProgramme: EpgProgramme? = null,
    val nextProgramme: EpgProgramme? = null,
    val programmes: List<EpgProgramme> = emptyList(),
)

internal fun liveDisplayShelfBootstrapKey(userId: String, playlistId: String, categoryName: String): String {
    return "$LIVE_BOOTSTRAP_DISPLAY_PREFIX${userId.hashCode()}_${playlistId.hashCode()}_${categoryName.hashCode()}"
}
