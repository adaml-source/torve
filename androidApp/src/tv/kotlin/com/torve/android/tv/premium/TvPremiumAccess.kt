package com.torve.android.tv.premium

enum class AccessTier {
    FREE,
    LIFETIME,
}

enum class TvFeatureAccess {
    FREE,
    PREMIUM_LOCKED_VISIBLE,
}

enum class TvEntitledFeature {
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

data class TvFeaturePolicy(
    val access: TvFeatureAccess,
    val title: String,
    val unlockSummary: String,
)

object TvPremiumAccess {
    const val LOCKED_LABEL = "Locked"
    const val LIFETIME_REQUIRED_LABEL = "Requires Lifetime Access"
    const val UNLOCK_WITH_LIFETIME_LABEL = "Unlock with Lifetime Access"

    val lifetimeBenefits: List<String> = listOf(
        "Watchlist and Favorites",
        "Trakt, Simkl, Jellyfin, Plex, and Kodi integrations",
        "Phone pairing and cross-device sync",
        "Advanced personalization and library management",
        "Provider setup, diagnostics, and advanced tools",
    )

    private val featureMatrix: Map<TvEntitledFeature, TvFeaturePolicy> = mapOf(
        // Free
        TvEntitledFeature.BROWSE_HOME to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Browse Home",
            unlockSummary = "Browse Home rails and recommendations.",
        ),
        TvEntitledFeature.BROWSE_MOVIES to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Browse Movies",
            unlockSummary = "Browse movie catalogs and rails.",
        ),
        TvEntitledFeature.BROWSE_TV_SHOWS to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Browse TV Shows",
            unlockSummary = "Browse TV show catalogs and rails.",
        ),
        TvEntitledFeature.BROWSE_POSTERS_AND_RAILS to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Browse Posters",
            unlockSummary = "Navigate posters and rows with the remote.",
        ),
        TvEntitledFeature.VIEW_FOCUS_INFO_PANEL to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Focused Info Panel",
            unlockSummary = "View high-level metadata while browsing.",
        ),
        TvEntitledFeature.VIEW_CLEARLOGO_AND_TITLE_ART to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Title Artwork",
            unlockSummary = "View clearlogo and title artwork in browse.",
        ),
        TvEntitledFeature.VIEW_METADATA_AND_RATINGS to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Metadata and Ratings",
            unlockSummary = "Inspect synopsis, cast, and rating providers.",
        ),
        TvEntitledFeature.OPEN_TITLE_DETAILS to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "View Details",
            unlockSummary = "Open detail pages for movies and TV shows.",
        ),
        TvEntitledFeature.BASIC_SEARCH_AND_FILTER to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Basic Search",
            unlockSummary = "Use basic search and browse filtering.",
        ),
        TvEntitledFeature.VIEW_ABOUT_PRIVACY_TERMS_SUPPORT to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "About and Legal",
            unlockSummary = "View About, Privacy, Terms, and Support pages.",
        ),
        TvEntitledFeature.VIEW_PURCHASE_AND_UNLOCK to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Purchase Screen",
            unlockSummary = "Open Lifetime Access purchase and restore flows.",
        ),
        TvEntitledFeature.BASIC_APP_PREVIEW_EXPERIENCE to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "App Preview",
            unlockSummary = "Explore Torve before purchase.",
        ),
        TvEntitledFeature.TRAILER_PLAYBACK to TvFeaturePolicy(
            access = TvFeatureAccess.FREE,
            title = "Trailer Playback",
            unlockSummary = "Play trailers when available.",
        ),

        // Premium account / personalization
        TvEntitledFeature.ACCOUNT_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Account Setup",
            unlockSummary = "Set up Torve account features on this TV.",
        ),
        TvEntitledFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cloud Sign In",
            unlockSummary = "Use cloud sign-in and account sync tools.",
        ),
        TvEntitledFeature.SYNC_WATCH_HISTORY to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Synced Watch History",
            unlockSummary = "Sync watch progress and history across devices.",
        ),
        TvEntitledFeature.SYNC_WATCHLIST to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Synced Watchlist",
            unlockSummary = "Sync Watchlist across your devices.",
        ),
        TvEntitledFeature.SYNC_FAVORITES to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Synced Favorites",
            unlockSummary = "Sync Favorites across your devices.",
        ),
        TvEntitledFeature.SYNC_CUSTOM_LAYOUTS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Custom Layout Sync",
            unlockSummary = "Save and sync personalized home layouts.",
        ),
        TvEntitledFeature.CROSS_DEVICE_SYNC to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cross-Device Sync",
            unlockSummary = "Sync app state between TV and mobile devices.",
        ),
        TvEntitledFeature.CLOUD_BACKUP_RESTORE to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cloud Backup",
            unlockSummary = "Back up and restore settings from cloud.",
        ),

        // Premium device / pairing
        TvEntitledFeature.PHONE_PAIRING to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Phone Pairing",
            unlockSummary = "Pair your phone with this TV.",
        ),
        TvEntitledFeature.DEVICE_LINKING to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Device Linking",
            unlockSummary = "Link and manage devices under one account.",
        ),
        TvEntitledFeature.DEVICE_SYNC to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Device Sync",
            unlockSummary = "Sync data and preferences across devices.",
        ),
        TvEntitledFeature.TV_PHONE_CONTINUATION to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "TV-Phone Continuation",
            unlockSummary = "Continue playback between TV and phone.",
        ),
        TvEntitledFeature.QR_PAIRING to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "QR Pairing",
            unlockSummary = "Use QR-based pairing and linking flows.",
        ),

        // Premium library / persistence
        TvEntitledFeature.WATCHLIST_EDIT to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Watchlist Editing",
            unlockSummary = "Add and remove Watchlist entries.",
        ),
        TvEntitledFeature.FAVORITES_EDIT to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Favorites Editing",
            unlockSummary = "Add and remove Favorites entries.",
        ),
        TvEntitledFeature.WATCHED_STATUS_EDIT to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Watched State",
            unlockSummary = "Mark titles watched or unwatched.",
        ),
        TvEntitledFeature.TRAKT_LIST_MANAGER to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Trakt Lists",
            unlockSummary = "Manage Trakt lists and sync behavior.",
        ),
        TvEntitledFeature.FAVORITES_MANAGER to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Favorites Manager",
            unlockSummary = "Manage persistent favorites and saved lists.",
        ),
        TvEntitledFeature.PERSISTENT_COLLECTIONS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Persistent Collections",
            unlockSummary = "Create and maintain persistent collections.",
        ),

        // Premium integrations / setup
        TvEntitledFeature.TRAKT_CONNECT to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Trakt Connect",
            unlockSummary = "Connect and authorize Trakt integration.",
        ),
        TvEntitledFeature.SIMKL_CONNECT to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Simkl Connect",
            unlockSummary = "Connect and authorize Simkl integration.",
        ),
        TvEntitledFeature.JELLYFIN_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Jellyfin Setup",
            unlockSummary = "Configure Jellyfin server integration.",
        ),
        TvEntitledFeature.PLEX_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Plex Setup",
            unlockSummary = "Configure Plex server integration.",
        ),
        TvEntitledFeature.KODI_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Kodi Setup",
            unlockSummary = "Configure Kodi host integration.",
        ),
        TvEntitledFeature.OMDB_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "OMDb Setup",
            unlockSummary = "Configure OMDb metadata provider access.",
        ),
        TvEntitledFeature.MDBLIST_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "MDBList Setup",
            unlockSummary = "Configure MDBList keys and collection sync.",
        ),
        TvEntitledFeature.AI_PROVIDER_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "AI Provider Setup",
            unlockSummary = "Configure AI provider and keys.",
        ),
        TvEntitledFeature.CLOUD_PROVIDER_SETUP to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cloud Provider Setup",
            unlockSummary = "Configure cloud provider accounts and sources.",
        ),
        TvEntitledFeature.ADDON_INSTALL_AND_MANAGEMENT to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Addon Management",
            unlockSummary = "Install and manage external addons.",
        ),

        // Premium advanced / power-user tools
        TvEntitledFeature.DIAGNOSTICS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Diagnostics",
            unlockSummary = "Open diagnostics and troubleshooting tools.",
        ),
        TvEntitledFeature.DEBUG_TOOLS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Debug Tools",
            unlockSummary = "Access debug and developer diagnostics.",
        ),
        TvEntitledFeature.PROVIDER_TESTS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Provider Tests",
            unlockSummary = "Run provider validation and test flows.",
        ),
        TvEntitledFeature.METADATA_REFRESH_AND_REBUILD to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Metadata Rebuild",
            unlockSummary = "Refresh and rebuild metadata caches.",
        ),
        TvEntitledFeature.REMATCH_PROVIDER to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Provider Re-Match",
            unlockSummary = "Manually re-match titles against providers.",
        ),
        TvEntitledFeature.CUSTOM_SOURCE_MANAGEMENT to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Custom Sources",
            unlockSummary = "Manage custom source pipelines and routing.",
        ),
        TvEntitledFeature.ADVANCED_CONNECTION_CONFIGURATION to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Advanced Connections",
            unlockSummary = "Configure advanced integration options.",
        ),
        TvEntitledFeature.DEVELOPER_EVENT_LOGS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Developer Event Logs",
            unlockSummary = "Inspect internal event and sync logs.",
        ),

        // Optional premium monetization features
        TvEntitledFeature.AI_SEARCH_ADVANCED to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "AI Search",
            unlockSummary = "Use advanced AI-assisted search and discovery.",
        ),
        TvEntitledFeature.ADVANCED_RECOMMENDATIONS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Advanced Recommendations",
            unlockSummary = "Use premium recommendation flows.",
        ),
        TvEntitledFeature.MORE_LIKE_THIS_PREMIUM to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "More Like This",
            unlockSummary = "Use premium recommendation expansion.",
        ),
        TvEntitledFeature.CHOOSE_SOURCE_PREMIUM to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Choose Source",
            unlockSummary = "Select custom playback sources and integrations.",
        ),

        // Existing gates
        TvEntitledFeature.STREAM_PLAYBACK to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Stream Playback",
            unlockSummary = "Play premium cloud streams.",
        ),
        TvEntitledFeature.DOWNLOADS to TvFeaturePolicy(
            access = TvFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Downloads",
            unlockSummary = "Download and manage offline media.",
        ),
    )

    fun tierFrom(isLifetimeEntitled: Boolean): AccessTier {
        return if (isLifetimeEntitled) AccessTier.LIFETIME else AccessTier.FREE
    }

    fun requiresLifetimeAccess(feature: TvEntitledFeature): Boolean {
        return featureMatrix.getValue(feature).access == TvFeatureAccess.PREMIUM_LOCKED_VISIBLE
    }

    fun canAccess(feature: TvEntitledFeature, tier: AccessTier): Boolean {
        return !requiresLifetimeAccess(feature) || tier == AccessTier.LIFETIME
    }

    fun isPremiumLocked(feature: TvEntitledFeature, tier: AccessTier): Boolean {
        return requiresLifetimeAccess(feature) && tier == AccessTier.FREE
    }

    fun titleFor(feature: TvEntitledFeature): String = featureMatrix.getValue(feature).title

    fun unlockSummaryFor(feature: TvEntitledFeature): String = featureMatrix.getValue(feature).unlockSummary
}
