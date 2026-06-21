package com.torve.android.premium

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import com.torve.android.R
import com.torve.domain.model.SubscriptionTier

enum class AccessTier {
    FREE,
    MONTHLY,
    LIFETIME,
}

enum class PremiumFeatureAccess {
    FREE,
    PREMIUM_LOCKED_VISIBLE,
}

enum class PremiumFeature {
    // Free evaluation features
    BROWSE_HOME,
    BROWSE_MOVIES,
    BROWSE_TV_SHOWS,
    BROWSE_POSTERS_AND_RAILS,
    VIEW_FOCUS_INFO_PANEL,
    VIEW_CLEARLOGO_AND_TITLE_ART,
    VIEW_METADATA_AND_RATINGS,
    OPEN_TITLE_DETAILS,
    BASIC_SEARCH_AND_FILTER,
    VIEW_ABOUT_PRIVACY_TERMS_SUPPORT,
    VIEW_PURCHASE_AND_UNLOCK,
    BASIC_APP_PREVIEW_EXPERIENCE,
    TRAILER_PLAYBACK,

    // Premium account / personalization
    ACCOUNT_SETUP,
    ACCOUNT_SIGN_IN_OUT_FOR_CLOUD,
    SYNC_WATCH_HISTORY,
    SYNC_WATCHLIST,
    SYNC_FAVORITES,
    SYNC_CUSTOM_LAYOUTS,
    CROSS_DEVICE_SYNC,
    CLOUD_BACKUP_RESTORE,

    // Premium device / pairing
    PHONE_PAIRING,
    DEVICE_LINKING,
    DEVICE_SYNC,
    TV_PHONE_CONTINUATION,
    QR_PAIRING,

    // Premium library / persistence
    WATCHLIST_EDIT,
    FAVORITES_EDIT,
    WATCHED_STATUS_EDIT,
    TRAKT_LIST_MANAGER,
    FAVORITES_MANAGER,
    PERSISTENT_COLLECTIONS,

    // Premium integrations / setup
    TRAKT_CONNECT,
    SIMKL_CONNECT,
    JELLYFIN_SETUP,
    PLEX_SETUP,
    KODI_SETUP,
    OMDB_SETUP,
    MDBLIST_SETUP,
    AI_PROVIDER_SETUP,
    CLOUD_PROVIDER_SETUP,
    ADDON_INSTALL_AND_MANAGEMENT,

    // Premium advanced / power-user tools
    DIAGNOSTICS,
    DEBUG_TOOLS,
    PROVIDER_TESTS,
    METADATA_REFRESH_AND_REBUILD,
    REMATCH_PROVIDER,
    CUSTOM_SOURCE_MANAGEMENT,
    ADVANCED_CONNECTION_CONFIGURATION,
    DEVELOPER_EVENT_LOGS,

    // Optional premium monetization
    AI_SEARCH_ADVANCED,
    ADVANCED_RECOMMENDATIONS,
    MORE_LIKE_THIS_PREMIUM,
    CHOOSE_SOURCE_PREMIUM,

    // Existing premium product gates
    STREAM_PLAYBACK,
    DOWNLOADS,
}

data class PremiumFeaturePolicy(
    val access: PremiumFeatureAccess,
    @param:StringRes val titleRes: Int,
)

object PremiumAccess {
    @StringRes val LOCKED_LABEL_RES = R.string.premium_locked
    @StringRes val LIFETIME_REQUIRED_LABEL_RES = R.string.premium_requires_lifetime
    @StringRes val UNLOCK_WITH_LIFETIME_LABEL_RES = R.string.premium_unlock_with_lifetime

    // Keep legacy constants for any code that still references them directly
    const val LOCKED_LABEL = "Locked"
    const val LIFETIME_REQUIRED_LABEL = "Available to everyone"
    const val UNLOCK_WITH_LIFETIME_LABEL = "Continue"

    val lifetimeBenefitResIds: List<Int> = listOf(
        R.string.premium_benefit_integrations,
        R.string.premium_benefit_pairing,
        R.string.premium_benefit_personalization,
        R.string.premium_benefit_tools,
    )

    fun lifetimeBenefits(context: Context): List<String> =
        lifetimeBenefitResIds.map { context.getString(it) }

    val lifetimeBenefits: List<String> = listOf(
        "Trakt, Simkl, Jellyfin, Plex, and Kodi integrations",
        "Phone pairing and cross-device sync",
        "Advanced personalization and library management",
        "Provider setup, diagnostics, and advanced tools",
    )

    private val featureMatrix: Map<PremiumFeature, PremiumFeaturePolicy> = mapOf(
        // Free
        PremiumFeature.BROWSE_HOME to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_browse_home),
        PremiumFeature.BROWSE_MOVIES to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_browse_movies),
        PremiumFeature.BROWSE_TV_SHOWS to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_browse_tv_shows),
        PremiumFeature.BROWSE_POSTERS_AND_RAILS to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_browse_posters),
        PremiumFeature.VIEW_FOCUS_INFO_PANEL to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_focused_info),
        PremiumFeature.VIEW_CLEARLOGO_AND_TITLE_ART to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_title_artwork),
        PremiumFeature.VIEW_METADATA_AND_RATINGS to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_metadata_ratings),
        PremiumFeature.OPEN_TITLE_DETAILS to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_view_details),
        PremiumFeature.BASIC_SEARCH_AND_FILTER to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_basic_search),
        PremiumFeature.VIEW_ABOUT_PRIVACY_TERMS_SUPPORT to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_about_legal),
        PremiumFeature.VIEW_PURCHASE_AND_UNLOCK to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_purchase_screen),
        PremiumFeature.BASIC_APP_PREVIEW_EXPERIENCE to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_app_preview),
        PremiumFeature.TRAILER_PLAYBACK to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_trailer_playback),

        // Premium account / personalization
        PremiumFeature.ACCOUNT_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_account_setup),
        PremiumFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_cloud_sign_in),
        PremiumFeature.SYNC_WATCH_HISTORY to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_synced_history),
        PremiumFeature.SYNC_WATCHLIST to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_synced_watchlist),
        PremiumFeature.SYNC_FAVORITES to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_synced_favorites),
        PremiumFeature.SYNC_CUSTOM_LAYOUTS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_custom_layout_sync),
        PremiumFeature.CROSS_DEVICE_SYNC to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_cross_device_sync),
        PremiumFeature.CLOUD_BACKUP_RESTORE to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_cloud_backup),

        // Device / pairing — management is free for authenticated users;
        // creating new pairings and cross-device sync stay premium.
        PremiumFeature.PHONE_PAIRING to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_phone_pairing),
        PremiumFeature.DEVICE_LINKING to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_device_linking),
        PremiumFeature.DEVICE_SYNC to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_device_sync),
        PremiumFeature.TV_PHONE_CONTINUATION to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_tv_phone_continuation),
        PremiumFeature.QR_PAIRING to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_qr_pairing),

        // Premium library / persistence
        PremiumFeature.WATCHLIST_EDIT to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_watchlist_editing),
        PremiumFeature.FAVORITES_EDIT to PremiumFeaturePolicy(PremiumFeatureAccess.FREE, R.string.premium_favorites_editing),
        PremiumFeature.WATCHED_STATUS_EDIT to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_watched_state),
        PremiumFeature.TRAKT_LIST_MANAGER to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_trakt_lists),
        PremiumFeature.FAVORITES_MANAGER to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_favorites_manager),
        PremiumFeature.PERSISTENT_COLLECTIONS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_persistent_collections),

        // Premium integrations / setup
        PremiumFeature.TRAKT_CONNECT to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_trakt_connect),
        PremiumFeature.SIMKL_CONNECT to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_simkl_connect),
        PremiumFeature.JELLYFIN_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_jellyfin_setup),
        PremiumFeature.PLEX_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_plex_setup),
        PremiumFeature.KODI_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_kodi_setup),
        PremiumFeature.OMDB_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_omdb_setup),
        PremiumFeature.MDBLIST_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_mdblist_setup),
        PremiumFeature.AI_PROVIDER_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_ai_provider_setup),
        PremiumFeature.CLOUD_PROVIDER_SETUP to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_cloud_provider_setup),
        PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_addon_management),

        // Premium advanced / power-user tools
        PremiumFeature.DIAGNOSTICS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_diagnostics),
        PremiumFeature.DEBUG_TOOLS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_debug_tools),
        PremiumFeature.PROVIDER_TESTS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_provider_tests),
        PremiumFeature.METADATA_REFRESH_AND_REBUILD to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_metadata_rebuild),
        PremiumFeature.REMATCH_PROVIDER to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_provider_rematch),
        PremiumFeature.CUSTOM_SOURCE_MANAGEMENT to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_custom_sources),
        PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_advanced_connections),
        PremiumFeature.DEVELOPER_EVENT_LOGS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_developer_logs),

        // Optional premium monetization features
        PremiumFeature.AI_SEARCH_ADVANCED to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_ai_search),
        PremiumFeature.ADVANCED_RECOMMENDATIONS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_advanced_recommendations),
        PremiumFeature.MORE_LIKE_THIS_PREMIUM to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_more_like_this),
        PremiumFeature.CHOOSE_SOURCE_PREMIUM to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_choose_source),

        // Existing gates
        PremiumFeature.STREAM_PLAYBACK to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_stream_playback),
        PremiumFeature.DOWNLOADS to PremiumFeaturePolicy(PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE, R.string.premium_downloads),
    )

    fun tierFrom(isLifetimeEntitled: Boolean): AccessTier {
        return if (isLifetimeEntitled) AccessTier.LIFETIME else AccessTier.FREE
    }

    fun tierFrom(
        subscriptionTier: SubscriptionTier?,
        isPremiumActive: Boolean,
    ): AccessTier {
        if (!isPremiumActive) return AccessTier.FREE
        return when (subscriptionTier) {
            SubscriptionTier.MONTHLY -> AccessTier.MONTHLY
            SubscriptionTier.LIFETIME -> AccessTier.LIFETIME
            else -> AccessTier.LIFETIME
        }
    }

    /** Returns true when the feature requires any premium tier (monthly or lifetime). */
    fun requiresPremiumAccess(feature: PremiumFeature): Boolean {
        return false
    }

    /** @deprecated Use [requiresPremiumAccess] — name was misleading; both monthly and lifetime satisfy the check. */
    fun requiresLifetimeAccess(feature: PremiumFeature): Boolean = requiresPremiumAccess(feature)

    fun canAccess(feature: PremiumFeature, tier: AccessTier): Boolean {
        return true
    }

    fun isPremiumLocked(feature: PremiumFeature, tier: AccessTier): Boolean {
        if (com.torve.android.BuildConfig.DEBUG) {
            runCatching { Log.d("PremiumAccess", "FREE_ACCESS: feature=${feature.name} tier=$tier locked=false") }
        }
        return false
    }

    @StringRes
    fun titleResFor(feature: PremiumFeature): Int = featureMatrix.getValue(feature).titleRes

    fun titleFor(context: Context, feature: PremiumFeature): String = context.getString(titleResFor(feature))

    /** Legacy non-Context overload — returns English. Prefer titleFor(context, feature). */
    fun titleFor(feature: PremiumFeature): String = when (feature) {
        PremiumFeature.BROWSE_HOME -> "Browse Home"
        PremiumFeature.BROWSE_MOVIES -> "Browse Movies"
        PremiumFeature.BROWSE_TV_SHOWS -> "Browse TV Shows"
        PremiumFeature.STREAM_PLAYBACK -> "Stream Playback"
        PremiumFeature.DOWNLOADS -> "Downloads"
        else -> feature.name.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
    }

    fun unlockSummaryFor(feature: PremiumFeature): String = ""
}
