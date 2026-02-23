import SwiftUI
import shared

struct ContentView: View {

    var body: some View {
        TabView {
            MoviesScreen()
                .tabItem {
                    Label("Movies", systemImage: "film")
                }

            TvShowsScreen()
                .tabItem {
                    Label("TV Shows", systemImage: "tv")
                }

            IptvScreen()
                .tabItem {
                    Label("IPTV", systemImage: "antenna.radiowaves.left.and.right")
                }

            SettingsScreen()
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
        }
        .preferredColorScheme(.dark)
    }
}
