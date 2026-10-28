package com.torve.presentation.settings

import com.torve.domain.repository.PreferencesRepository
import com.torve.domain.providerhealth.ProviderHealthCategory

enum class OptionalAppFeature(val preferenceKey: String) {
    JELLYFIN("app_feature_jellyfin_enabled"),
    SPORTS("app_feature_sports_enabled"),
    CHANNELS("app_feature_channels_enabled"),
    ARR_STACK("app_feature_arr_stack_enabled");

    companion object {
        /** SPORTS remains a legacy preference identity, with no navigation, toggle or automatic work. */
        val availableEntries: List<OptionalAppFeature> = entries.filterNot { it == SPORTS }
    }
}

data class OptionalAppFeatures(
    val jellyfin: Boolean = true,
    val sports: Boolean = false,
    val channels: Boolean = true,
    val arrStack: Boolean = true,
) {
    fun isEnabled(feature: OptionalAppFeature): Boolean = when (feature) {
        OptionalAppFeature.JELLYFIN -> jellyfin
        OptionalAppFeature.SPORTS -> false
        OptionalAppFeature.CHANNELS -> channels
        OptionalAppFeature.ARR_STACK -> arrStack
    }
    fun withEnabled(feature: OptionalAppFeature, enabled: Boolean): OptionalAppFeatures = when (feature) {
        OptionalAppFeature.JELLYFIN -> copy(jellyfin = enabled)
        OptionalAppFeature.SPORTS -> copy(sports = false)
        OptionalAppFeature.CHANNELS -> copy(channels = enabled)
        OptionalAppFeature.ARR_STACK -> copy(arrStack = enabled)
    }
}

/** Read persisted policy before automatic work, including cold start and workers. */
suspend fun PreferencesRepository.isAppFeatureEnabled(feature: OptionalAppFeature): Boolean =
    feature != OptionalAppFeature.SPORTS && (getString(feature.preferenceKey)?.toBooleanStrictOrNull() ?: true)

suspend fun PreferencesRepository.readOptionalAppFeatures() = OptionalAppFeatures(
    jellyfin = isAppFeatureEnabled(OptionalAppFeature.JELLYFIN),
    sports = isAppFeatureEnabled(OptionalAppFeature.SPORTS),
    channels = isAppFeatureEnabled(OptionalAppFeature.CHANNELS),
    arrStack = isAppFeatureEnabled(OptionalAppFeature.ARR_STACK),
)

suspend fun PreferencesRepository.isHealthProviderEnabled(providerKey: String, category: ProviderHealthCategory? = null): Boolean = when {
    category == ProviderHealthCategory.IPTV || category == ProviderHealthCategory.EPG -> isAppFeatureEnabled(OptionalAppFeature.CHANNELS)
    category == ProviderHealthCategory.REQUEST_MANAGER -> isAppFeatureEnabled(OptionalAppFeature.ARR_STACK)
    category == ProviderHealthCategory.PLEX_JELLYFIN -> isAppFeatureEnabled(OptionalAppFeature.JELLYFIN) ||
        !getString("plex_server_url").isNullOrBlank()
    providerKey.startsWith("iptv:") -> isAppFeatureEnabled(OptionalAppFeature.CHANNELS)
    providerKey.startsWith("sports:") -> isAppFeatureEnabled(OptionalAppFeature.SPORTS)
    providerKey.startsWith("automation:") || providerKey.startsWith("seerr:") -> isAppFeatureEnabled(OptionalAppFeature.ARR_STACK)
    providerKey == "library:plex_jellyfin" -> isAppFeatureEnabled(OptionalAppFeature.JELLYFIN) ||
        !getString("plex_server_url").isNullOrBlank()
    providerKey.startsWith("jellyfin:") -> isAppFeatureEnabled(OptionalAppFeature.JELLYFIN)
    else -> true
}
