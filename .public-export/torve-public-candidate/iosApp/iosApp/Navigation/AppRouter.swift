import SwiftUI

@Observable
final class AppRouter {
    var homePath = NavigationPath()
    var moviesPath = NavigationPath()
    var tvShowsPath = NavigationPath()
    var channelsPath = NavigationPath()
    var watchlistPath = NavigationPath()
    var settingsPath = NavigationPath()

    var selectedTab: AppTab = .home

    func navigate(to route: Route) {
        switch selectedTab {
        case .home: homePath.append(route)
        case .movies: moviesPath.append(route)
        case .tvShows: tvShowsPath.append(route)
        case .channels: channelsPath.append(route)
        case .watchlist: watchlistPath.append(route)
        case .settings: settingsPath.append(route)
        }
    }

    func popToRoot() {
        switch selectedTab {
        case .home: homePath = NavigationPath()
        case .movies: moviesPath = NavigationPath()
        case .tvShows: tvShowsPath = NavigationPath()
        case .channels: channelsPath = NavigationPath()
        case .watchlist: watchlistPath = NavigationPath()
        case .settings: settingsPath = NavigationPath()
        }
    }
}

enum AppTab: Hashable {
    case home, movies, tvShows, channels, watchlist, settings
}
