import SwiftUI
import shared

struct WatchlistScreen: View {
    @StateObject private var wrapper = WatchlistViewModelWrapper()
    @Environment(AppRouter.self) private var router
    @State private var selectedSegment = 0

    private let segments = ["Watchlist", "In Progress", "History"]

    var body: some View {
        VStack(spacing: 0) {
            // Segmented Picker
            Picker("Tab", selection: $selectedSegment) {
                ForEach(0..<segments.count, id: \.self) { index in
                    Text(segments[index]).tag(index)
                }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)
            .padding(.vertical, 8)

            // Tab Content
            switch selectedSegment {
            case 0:
                watchlistContent
            case 1:
                inProgressContent
            case 2:
                historyContent
            default:
                EmptyView()
            }
        }
        .navigationTitle("Watchlist")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button {
                    wrapper.loadWatchlist()
                    wrapper.loadInProgress()
                    wrapper.loadHistory()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let message = wrapper.state.snackbarMessage {
                snackbar(message)
            }
        }
        .onChange(of: selectedSegment) { _, newValue in
            switch newValue {
            case 1: wrapper.loadInProgress()
            case 2: wrapper.loadHistory()
            default: break
            }
        }
    }

    // MARK: - Watchlist Content

    private var watchlistContent: some View {
        Group {
            if wrapper.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView("Loading watchlist...")
                        .tint(SVColor.amber)
                    Spacer()
                }
            } else if let error = wrapper.state.error {
                errorView(error)
            } else if wrapper.state.items.isEmpty {
                emptyState(
                    icon: "bookmark",
                    title: "Watchlist Empty",
                    message: "Add movies and shows from their detail pages to build your watchlist."
                )
            } else {
                watchlistSections
            }
        }
    }

    private var watchlistSections: some View {
        ScrollView {
            let items = wrapper.state.items
            let movies = items.filter { $0.mediaType == .movie }
            let shows = items.filter { $0.mediaType != .movie }

            VStack(alignment: .leading, spacing: 20) {
                if !movies.isEmpty {
                    watchlistRow(title: "Movies", items: movies)
                }
                if !shows.isEmpty {
                    watchlistRow(title: "TV Shows", items: shows)
                }
            }
            .padding(.top, 8)
        }
    }

    private func watchlistRow(title: String, items: [WatchlistItem]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(SVFont.sectionTitle)
                .foregroundColor(SVColor.onSurface)
                .padding(.horizontal)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(items, id: \.mediaId) { item in
                        Button {
                            let mediaType = item.mediaType == .movie ? "movie" : "tv"
                            router.navigate(to: .detail(mediaId: item.mediaId, mediaType: mediaType))
                        } label: {
                            posterCard(
                                posterUrl: item.posterUrl,
                                title: item.title,
                                subtitle: subtitleForWatchlistItem(item)
                            )
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button(role: .destructive) {
                                removeItem(item)
                            } label: {
                                Label("Remove from Watchlist", systemImage: "trash")
                            }
                        }
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private func subtitleForWatchlistItem(_ item: WatchlistItem) -> String {
        var parts: [String] = []
        if let rating = item.rating {
            parts.append(String(format: "%.1f", rating.doubleValue))
        }
        if let year = item.year?.int32Value {
            parts.append("\(year)")
        }
        return parts.joined(separator: " | ")
    }

    // MARK: - In Progress Content

    private var inProgressContent: some View {
        Group {
            if wrapper.isLoadingProgress {
                VStack {
                    Spacer()
                    ProgressView("Loading progress...")
                        .tint(SVColor.amber)
                    Spacer()
                }
            } else if wrapper.inProgressItems.isEmpty {
                emptyState(
                    icon: "play.circle",
                    title: "Nothing In Progress",
                    message: "Start watching something and it will appear here."
                )
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(wrapper.inProgressItems, id: \.mediaId) { item in
                            Button {
                                let mediaType = item.mediaType == .movie ? "movie" : "tv"
                                router.navigate(to: .detail(mediaId: item.mediaId, mediaType: mediaType))
                            } label: {
                                continueWatchingCard(item)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.top, 8)
                }
            }
        }
        .onAppear { wrapper.loadInProgress() }
    }

    private func continueWatchingCard(_ item: WatchProgress) -> some View {
        HStack(spacing: 12) {
            // Poster
            AsyncImage(url: URL(string: item.posterUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(2/3, contentMode: .fill)
                case .failure:
                    smallPosterPlaceholder
                case .empty:
                    smallPosterPlaceholder
                        .overlay(ProgressView().tint(SVColor.amber))
                @unknown default:
                    EmptyView()
                }
            }
            .frame(width: 60, height: 90)
            .cornerRadius(8)

            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(item.showTitle ?? item.title)
                    .font(SVFont.cardTitle)
                    .foregroundColor(SVColor.onSurface)
                    .lineLimit(1)

                if let season = item.seasonNumber?.int32Value,
                   let episode = item.episodeNumber?.int32Value {
                    Text("S\(String(format: "%02d", season))E\(String(format: "%02d", episode))")
                        .font(SVFont.caption)
                        .foregroundColor(SVColor.amber)
                }

                // Progress bar
                let posMs = Int64(item.positionMs)
                let durMs = Int64(item.durationMs)
                let progress = durMs > 0
                    ? Float(posMs) / Float(durMs)
                    : Float(0)

                ProgressView(value: progress)
                    .tint(SVColor.amber)

                // Remaining time
                let remainingMs = max(0, durMs - posMs)
                let remainingMin = remainingMs / 60000
                Text("\(remainingMin)min remaining")
                    .font(SVFont.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
            }

            Spacer()
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(SVColor.surfaceVariant)
        )
    }

    // MARK: - History Content

    private var historyContent: some View {
        Group {
            if wrapper.isLoadingHistory {
                VStack {
                    Spacer()
                    ProgressView("Loading history...")
                        .tint(SVColor.amber)
                    Spacer()
                }
            } else if wrapper.historyItems.isEmpty {
                emptyState(
                    icon: "clock",
                    title: "No Watch History",
                    message: "Your watch history will appear here."
                )
            } else {
                ScrollView {
                    LazyVStack(spacing: 12) {
                        ForEach(wrapper.historyItems, id: \.id) { entry in
                            Button {
                                router.navigate(to: .detail(mediaId: entry.mediaId, mediaType: entry.mediaType))
                            } label: {
                                historyEntryCard(entry)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal)
                    .padding(.top, 8)
                }
            }
        }
        .onAppear { wrapper.loadHistory() }
    }

    private func historyEntryCard(_ entry: WatchHistoryEntry) -> some View {
        HStack(spacing: 12) {
            // Poster
            AsyncImage(url: URL(string: entry.posterUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(2/3, contentMode: .fill)
                case .failure:
                    smallPosterPlaceholder
                case .empty:
                    smallPosterPlaceholder
                        .overlay(ProgressView().tint(SVColor.amber))
                @unknown default:
                    EmptyView()
                }
            }
            .frame(width: 60, height: 90)
            .cornerRadius(8)

            // Info
            VStack(alignment: .leading, spacing: 4) {
                Text(entry.showTitle ?? entry.title)
                    .font(SVFont.cardTitle)
                    .foregroundColor(SVColor.onSurface)
                    .lineLimit(1)

                if let season = entry.seasonNumber?.int32Value,
                   let episode = entry.episodeNumber?.int32Value {
                    Text("S\(String(format: "%02d", season))E\(String(format: "%02d", episode))")
                        .font(SVFont.caption)
                        .foregroundColor(SVColor.amber)
                }

                let watchedMin = Int64(entry.durationWatchedMs) / 60000
                Text("Watched \(watchedMin)min")
                    .font(SVFont.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)

                // Watched date
                let date = Date(timeIntervalSince1970: TimeInterval(entry.watchedAt / 1000))
                Text(date, style: .relative)
                    .font(SVFont.caption)
                    .foregroundColor(SVColor.onSurfaceVariant.opacity(0.7))
            }

            Spacer()
        }
        .padding(10)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(SVColor.surfaceVariant)
        )
    }

    // MARK: - Shared Components

    private func posterCard(posterUrl: String?, title: String, subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            AsyncImage(url: URL(string: posterUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(2/3, contentMode: .fill)
                case .failure:
                    posterPlaceholder
                case .empty:
                    posterPlaceholder
                        .overlay(ProgressView().tint(SVColor.amber))
                @unknown default:
                    EmptyView()
                }
            }
            .frame(width: 120, height: 180)
            .cornerRadius(12)

            Text(title)
                .font(SVFont.cardTitle)
                .foregroundColor(SVColor.onSurface)
                .lineLimit(2)
                .frame(width: 120, alignment: .leading)

            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.caption2)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .frame(width: 120, alignment: .leading)
            }
        }
    }

    private var posterPlaceholder: some View {
        Rectangle()
            .fill(SVColor.surfaceVariant)
            .frame(width: 120, height: 180)
    }

    private var smallPosterPlaceholder: some View {
        Rectangle()
            .fill(SVColor.surfaceVariant)
            .frame(width: 60, height: 90)
    }

    // MARK: - Empty State

    private func emptyState(icon: String, title: String, message: String) -> some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: icon)
                .font(.system(size: 60))
                .foregroundColor(SVColor.onSurfaceVariant)
            Text(title)
                .font(.title2)
                .fontWeight(.semibold)
                .foregroundColor(SVColor.onSurface)
            Text(message)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
    }

    // MARK: - Error View

    private func errorView(_ error: String) -> some View {
        VStack(spacing: 12) {
            Spacer()
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundColor(SVColor.error)
            Text(error)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Button("Retry") { wrapper.loadWatchlist() }
                .buttonStyle(.borderedProminent)
                .tint(SVColor.amber)
            Spacer()
        }
        .padding()
    }

    // MARK: - Snackbar

    private func snackbar(_ message: String) -> some View {
        Text(message)
            .font(.subheadline)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(SVColor.surfaceVariant)
            .foregroundColor(SVColor.onSurface)
            .cornerRadius(10)
            .shadow(radius: 4)
            .padding(.bottom, 16)
            .transition(.move(edge: .bottom).combined(with: .opacity))
            .onAppear {
                DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                    wrapper.clearSnackbar()
                }
            }
    }

    // MARK: - Helpers

    private func removeItem(_ item: WatchlistItem) {
        let mediaItem = MediaItem(
            id: item.mediaId,
            tmdbId: KotlinInt(int: item.tmdbId),
            imdbId: item.imdbId,
            type: item.mediaType,
            title: item.title,
            year: nil,
            overview: nil,
            posterUrl: item.posterUrl,
            backdropUrl: item.backdropUrl,
            rating: item.rating.map { KotlinDouble(double: $0.doubleValue) },
            voteCount: nil,
            runtime: nil,
            genres: [],
            genreIds: [],
            cast: [],
            director: nil,
            directorId: nil,
            releaseDate: nil,
            status: nil,
            trailerKey: nil,
            seasons: [],
            tagline: nil,
            popularity: nil,
            ratings: nil
        )
        wrapper.toggleWatchlist(mediaItem)
    }
}
