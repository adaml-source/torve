import SwiftUI
import shared

struct DiscoverScreen: View {
    @StateObject private var wrapper = DiscoverViewModelWrapper()
    @Environment(AppRouter.self) private var router

    private let columns = [
        GridItem(.adaptive(minimum: 150), spacing: 16)
    ]

    var body: some View {
        VStack(spacing: 0) {
            // Tab picker
            Picker("Category", selection: Binding(
                get: { wrapper.state.selectedTab },
                set: { wrapper.selectTab($0) }
            )) {
                Text("Movies").tag(DiscoverTab.movies)
                Text("TV Shows").tag(DiscoverTab.tvShows)
                Text("Channels").tag(DiscoverTab.liveTv)
            }
            .pickerStyle(.segmented)
            .padding()

            if wrapper.state.selectedTab == .liveTv {
                channelsPlaceholder
            } else {
                genreGrid
            }
        }
        .navigationTitle("Discover")
    }

    // MARK: - Genre Grid

    private var genreGrid: some View {
        ScrollView {
            LazyVGrid(columns: columns, spacing: 16) {
                ForEach(genreList, id: \.id) { genre in
                    NavigationLink(value: Route.catalog(mediaType: mediaTypeString)) {
                        GenreCard(genre: genre)
                    }
                }
            }
            .padding()
        }
    }

    private var channelsPlaceholder: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 48))
                .foregroundColor(SVColor.onSurfaceVariant)
            Text("Browse Channels")
                .font(.title3)
                .fontWeight(.semibold)
            Text("Head over to the Channels tab to explore live TV.")
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Spacer()
        }
        .padding()
    }

    // MARK: - Helpers

    private var genreList: [GenreDisplay] {
        let genres = wrapper.state.genres as? [GenreDisplay] ?? []
        return genres
    }

    private var mediaTypeString: String {
        wrapper.state.selectedTab == .movies ? "movie" : "tv"
    }
}

// MARK: - Genre Card

private struct GenreCard: View {
    let genre: GenreDisplay

    private static let genreIcons: [String: String] = [
        "Action": "bolt.fill",
        "Adventure": "map.fill",
        "Animation": "sparkles",
        "Comedy": "face.smiling.fill",
        "Crime": "lock.shield.fill",
        "Documentary": "video.fill",
        "Drama": "theatermasks.fill",
        "Family": "figure.2.and.child.holdinghands",
        "Fantasy": "wand.and.stars",
        "History": "clock.fill",
        "Horror": "eye.fill",
        "Music": "music.note",
        "Mystery": "questionmark.circle.fill",
        "Romance": "heart.fill",
        "Sci-Fi": "atom",
        "Thriller": "exclamationmark.triangle.fill",
        "War": "shield.fill",
        "Western": "sun.dust.fill",
        "Action & Adventure": "bolt.fill",
        "Kids": "figure.child",
        "Reality": "camera.fill",
        "Sci-Fi & Fantasy": "sparkles",
        "War & Politics": "building.columns.fill",
    ]

    private static let genreColors: [String: Color] = [
        "Action": .red,
        "Adventure": .orange,
        "Animation": .cyan,
        "Comedy": .yellow,
        "Crime": .gray,
        "Documentary": .blue,
        "Drama": .purple,
        "Family": .green,
        "Fantasy": .indigo,
        "History": .brown,
        "Horror": Color(red: 0.3, green: 0.0, blue: 0.0),
        "Music": .pink,
        "Mystery": .teal,
        "Romance": .pink,
        "Sci-Fi": .cyan,
        "Thriller": .orange,
        "War": Color(red: 0.35, green: 0.25, blue: 0.15),
        "Western": .brown,
    ]

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: Self.genreIcons[genre.name] ?? "film")
                .font(.system(size: 28))
                .foregroundColor(.white)

            Text(genre.name)
                .font(.subheadline)
                .fontWeight(.semibold)
                .foregroundColor(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
        }
        .frame(maxWidth: .infinity)
        .frame(height: 100)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(
                    LinearGradient(
                        colors: [
                            (Self.genreColors[genre.name] ?? SVColor.amber).opacity(0.8),
                            (Self.genreColors[genre.name] ?? SVColor.amber).opacity(0.4),
                        ],
                        startPoint: .topLeading,
                        endPoint: .bottomTrailing
                    )
                )
        )
    }
}
