import Foundation
import shared

enum Route: Hashable {
    case home
    case catalog(mediaType: String)
    case detail(mediaId: String, mediaType: String)
    case person(personId: Int32)
    case player(streamUrl: String, title: String, fallbackUrl: String = "")
    case search
    case seeAll(title: String, category: String, mediaType: String)
    case channels
    case channelPlayer(channelUrl: String, channelName: String)
    case provider(providerId: Int32, providerName: String)
    case genreCatalog(mediaType: String, genreId: Int32, genreName: String)
    case settings
    case pandaSetup
    case traktSettings
    case simklSettings
    case addonManager
    case integrations
    case streamGroups
    case homeLayout
    case ratingSettings
    case regexPatterns
    case mdbListSettings
    case cardStyleSettings
    case streamingServices
    case customSectionEditor
    case playbackSettings
    case diagnostics
    case privacyPolicy
    case backupSync
    case watchlist
    case downloads
    case downloadCatalogue
    case downloadedShowDetail(showTitle: String)
    case calendar
    case discover
    case moodMatcher
    case stats
    case profile
    case profileTab
    case setupWizard
    case login
    case account
    case devices
    case manageDevices
    case deviceLimitReached
    case legal
    case transferSend
    case transferReceive
    case transferDiagnostics
}
