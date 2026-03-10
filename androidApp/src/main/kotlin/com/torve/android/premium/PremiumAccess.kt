package com.torve.android.premium

enum class AccessTier {
    FREE,
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
    val title: String,
    val unlockSummary: String,
)

object PremiumAccess {
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

    private val featureMatrix: Map<PremiumFeature, PremiumFeaturePolicy> = mapOf(
        // Free
        PremiumFeature.BROWSE_HOME to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Browse Home",
            unlockSummary = "Browse Home rails and recommendations.",
        ),
        PremiumFeature.BROWSE_MOVIES to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Browse Movies",
            unlockSummary = "Browse movie catalogs and rails.",
        ),
        PremiumFeature.BROWSE_TV_SHOWS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Browse TV Shows",
            unlockSummary = "Browse TV show catalogs and rails.",
        ),
        PremiumFeature.BROWSE_POSTERS_AND_RAILS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Browse Posters",
            unlockSummary = "Navigate posters and rows with the remote.",
        ),
        PremiumFeature.VIEW_FOCUS_INFO_PANEL to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Focused Info Panel",
            unlockSummary = "View high-level metadata while browsing.",
        ),
        PremiumFeature.VIEW_CLEARLOGO_AND_TITLE_ART to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Title Artwork",
            unlockSummary = "View clearlogo and title artwork in browse.",
        ),
        PremiumFeature.VIEW_METADATA_AND_RATINGS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Metadata and Ratings",
            unlockSummary = "Inspect synopsis, cast, and rating providers.",
        ),
        PremiumFeature.OPEN_TITLE_DETAILS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "View Details",
            unlockSummary = "Open detail pages for movies and TV shows.",
        ),
        PremiumFeature.BASIC_SEARCH_AND_FILTER to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Basic Search",
            unlockSummary = "Use basic search and browse filtering.",
        ),
        PremiumFeature.VIEW_ABOUT_PRIVACY_TERMS_SUPPORT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "About and Legal",
            unlockSummary = "View About, Privacy, Terms, and Support pages.",
        ),
        PremiumFeature.VIEW_PURCHASE_AND_UNLOCK to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Purchase Screen",
            unlockSummary = "Open Lifetime Access purchase and restore flows.",
        ),
        PremiumFeature.BASIC_APP_PREVIEW_EXPERIENCE to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "App Preview",
            unlockSummary = "Explore Torve before purchase.",
        ),
        PremiumFeature.TRAILER_PLAYBACK to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.FREE,
            title = "Trailer Playback",
            unlockSummary = "Play trailers when available.",
        ),

        // Premium account / personalization
        PremiumFeature.ACCOUNT_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Account Setup",
            unlockSummary = "Set up Torve account features on this TV.",
        ),
        PremiumFeature.ACCOUNT_SIGN_IN_OUT_FOR_CLOUD to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cloud Sign In",
            unlockSummary = "Use cloud sign-in and account sync tools.",
        ),
        PremiumFeature.SYNC_WATCH_HISTORY to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Synced Watch History",
            unlockSummary = "Sync watch progress and history across devices.",
        ),
        PremiumFeature.SYNC_WATCHLIST to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Synced Watchlist",
            unlockSummary = "Sync Watchlist across your devices.",
        ),
        PremiumFeature.SYNC_FAVORITES to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Synced Favorites",
            unlockSummary = "Sync Favorites across your devices.",
        ),
        PremiumFeature.SYNC_CUSTOM_LAYOUTS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Custom Layout Sync",
            unlockSummary = "Save and sync personalized home layouts.",
        ),
        PremiumFeature.CROSS_DEVICE_SYNC to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cross-Device Sync",
            unlockSummary = "Sync app state between TV and mobile devices.",
        ),
        PremiumFeature.CLOUD_BACKUP_RESTORE to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cloud Backup",
            unlockSummary = "Back up and restore settings from cloud.",
        ),

        // Premium device / pairing
        PremiumFeature.PHONE_PAIRING to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Phone Pairing",
            unlockSummary = "Pair your phone with this TV.",
        ),
        PremiumFeature.DEVICE_LINKING to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Device Linking",
            unlockSummary = "Link and manage devices under one account.",
        ),
        PremiumFeature.DEVICE_SYNC to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Device Sync",
            unlockSummary = "Sync data and preferences across devices.",
        ),
        PremiumFeature.TV_PHONE_CONTINUATION to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "TV-Phone Continuation",
            unlockSummary = "Continue playback between TV and phone.",
        ),
        PremiumFeature.QR_PAIRING to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "QR Pairing",
            unlockSummary = "Use QR-based pairing and linking flows.",
        ),

        // Premium library / persistence
        PremiumFeature.WATCHLIST_EDIT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Watchlist Editing",
            unlockSummary = "Add and remove Watchlist entries.",
        ),
        PremiumFeature.FAVORITES_EDIT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Favorites Editing",
            unlockSummary = "Add and remove Favorites entries.",
        ),
        PremiumFeature.WATCHED_STATUS_EDIT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Watched State",
            unlockSummary = "Mark titles watched or unwatched.",
        ),
        PremiumFeature.TRAKT_LIST_MANAGER to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Trakt Lists",
            unlockSummary = "Manage Trakt lists and sync behavior.",
        ),
        PremiumFeature.FAVORITES_MANAGER to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Favorites Manager",
            unlockSummary = "Manage persistent favorites and saved lists.",
        ),
        PremiumFeature.PERSISTENT_COLLECTIONS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Persistent Collections",
            unlockSummary = "Create and maintain persistent collections.",
        ),

        // Premium integrations / setup
        PremiumFeature.TRAKT_CONNECT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Trakt Connect",
            unlockSummary = "Connect and authorize Trakt integration.",
        ),
        PremiumFeature.SIMKL_CONNECT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Simkl Connect",
            unlockSummary = "Connect and authorize Simkl integration.",
        ),
        PremiumFeature.JELLYFIN_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Jellyfin Setup",
            unlockSummary = "Configure Jellyfin server integration.",
        ),
        PremiumFeature.PLEX_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Plex Setup",
            unlockSummary = "Configure Plex server integration.",
        ),
        PremiumFeature.KODI_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Kodi Setup",
            unlockSummary = "Configure Kodi host integration.",
        ),
        PremiumFeature.OMDB_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "OMDb Setup",
            unlockSummary = "Configure OMDb metadata provider access.",
        ),
        PremiumFeature.MDBLIST_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "MDBList Setup",
            unlockSummary = "Configure MDBList keys and collection sync.",
        ),
        PremiumFeature.AI_PROVIDER_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "AI Provider Setup",
            unlockSummary = "Configure AI provider and keys.",
        ),
        PremiumFeature.CLOUD_PROVIDER_SETUP to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Cloud Provider Setup",
            unlockSummary = "Configure cloud provider accounts and sources.",
        ),
        PremiumFeature.ADDON_INSTALL_AND_MANAGEMENT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Addon Management",
            unlockSummary = "Install and manage external addons.",
        ),

        // Premium advanced / power-user tools
        PremiumFeature.DIAGNOSTICS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Diagnostics",
            unlockSummary = "Open diagnostics and troubleshooting tools.",
        ),
        PremiumFeature.DEBUG_TOOLS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Debug Tools",
            unlockSummary = "Access debug and developer diagnostics.",
        ),
        PremiumFeature.PROVIDER_TESTS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Provider Tests",
            unlockSummary = "Run provider validation and test flows.",
        ),
        PremiumFeature.METADATA_REFRESH_AND_REBUILD to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Metadata Rebuild",
            unlockSummary = "Refresh and rebuild metadata caches.",
        ),
        PremiumFeature.REMATCH_PROVIDER to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Provider Re-Match",
            unlockSummary = "Manually re-match titles against providers.",
        ),
        PremiumFeature.CUSTOM_SOURCE_MANAGEMENT to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Custom Sources",
            unlockSummary = "Manage custom source pipelines and routing.",
        ),
        PremiumFeature.ADVANCED_CONNECTION_CONFIGURATION to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Advanced Connections",
            unlockSummary = "Configure advanced integration options.",
        ),
        PremiumFeature.DEVELOPER_EVENT_LOGS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Developer Event Logs",
            unlockSummary = "Inspect internal event and sync logs.",
        ),

        // Optional premium monetization features
        PremiumFeature.AI_SEARCH_ADVANCED to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "AI Search",
            unlockSummary = "Use advanced AI-assisted search and discovery.",
        ),
        PremiumFeature.ADVANCED_RECOMMENDATIONS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Advanced Recommendations",
            unlockSummary = "Use premium recommendation flows.",
        ),
        PremiumFeature.MORE_LIKE_THIS_PREMIUM to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "More Like This",
            unlockSummary = "Use premium recommendation expansion.",
        ),
        PremiumFeature.CHOOSE_SOURCE_PREMIUM to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Choose Source",
            unlockSummary = "Select custom playback sources and integrations.",
        ),

        // Existing gates
        PremiumFeature.STREAM_PLAYBACK to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Stream Playback",
            unlockSummary = "Play premium cloud streams.",
        ),
        PremiumFeature.DOWNLOADS to PremiumFeaturePolicy(
            access = PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE,
            title = "Downloads",
            unlockSummary = "Download and manage offline media.",
        ),
    )

    fun tierFrom(isLifetimeEntitled: Boolean): AccessTier {
        return if (isLifetimeEntitled) AccessTier.LIFETIME else AccessTier.FREE
    }

    fun requiresLifetimeAccess(feature: PremiumFeature): Boolean {
        return featureMatrix.getValue(feature).access == PremiumFeatureAccess.PREMIUM_LOCKED_VISIBLE
    }

    fun canAccess(feature: PremiumFeature, tier: AccessTier): Boolean {
        return !requiresLifetimeAccess(feature) || tier == AccessTier.LIFETIME
    }

    fun isPremiumLocked(feature: PremiumFeature, tier: AccessTier): Boolean {
        return requiresLifetimeAccess(feature) && tier == AccessTier.FREE
    }

    fun titleFor(feature: PremiumFeature): String = featureMatrix.getValue(feature).title

    fun unlockSummaryFor(feature: PremiumFeature): String = featureMatrix.getValue(feature).unlockSummary
}

