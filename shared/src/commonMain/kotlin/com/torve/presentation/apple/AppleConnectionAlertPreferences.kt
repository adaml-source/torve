package com.torve.presentation.apple

import com.torve.domain.providerhealth.ProviderHealthCategory
import com.torve.domain.providerhealth.ProviderHealthEntry
import com.torve.domain.providerhealth.ProviderHealthStatus
import kotlinx.serialization.json.Json

const val KEY_CONNECTION_ALERTS_ENABLED = "connection_alerts_enabled"
const val KEY_CONNECTION_ALERT_MUTED_CATEGORIES = "connection_alert_muted_categories"

/** Non-secret, profile-owned preferences. They never change a connection's actual health. */
data class AppleConnectionAlertPreferences(val enabled: Boolean = true, val mutedCategories: List<String> = emptyList()) {
    fun allows(category: ProviderHealthCategory): Boolean = enabled && category.name !in mutedCategories
    fun shouldAlert(entry: ProviderHealthEntry): Boolean = allows(entry.category) &&
        entry.status in setOf(ProviderHealthStatus.RED, ProviderHealthStatus.YELLOW)
}

internal fun decodeConnectionAlertPreferences(enabled: String?, muted: String?): AppleConnectionAlertPreferences {
    val known = ProviderHealthCategory.entries.map { it.name }.toSet()
    val categories = runCatching { Json.decodeFromString<List<String>>(muted.orEmpty()) }.getOrDefault(emptyList())
    return AppleConnectionAlertPreferences(enabled != "false", categories.filter { it in known }.distinct())
}
