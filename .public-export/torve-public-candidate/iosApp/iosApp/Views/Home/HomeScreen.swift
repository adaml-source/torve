import SwiftUI
import shared

struct HomeScreen: View {
    @StateObject private var wrapper = HomeViewModelWrapper()
    @Environment(AppRouter.self) private var router

    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                // Hero banner
                if let hero = wrapper.state.heroItem {
                    heroCard(hero)
                }

                // Continue Watching
                if !wrapper.state.continueWatching.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "Continue Watching")
                        ScrollView(.horizontal, showsIndicators: false) {
                            LazyHStack(spacing: 12) {
                                ForEach(wrapper.state.continueWatching, id: \.mediaId) { progress in
                                    Button {
                                        router.navigate(to: .detail(
                                            mediaId: progress.mediaId,
                                            mediaType: progress.mediaType == .movie ? "movie" : "tv"
                                        ))
                                    } label: {
                                        PosterCard(
                                            title: progress.title,
                                            posterUrl: progress.posterUrl,
                                            watchedPercent: progress.percentage
                                        )
                                        .frame(width: 130)
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                            .padding(.horizontal)
                        }
                    }
                }

                // Shelves
                ForEach(wrapper.state.shelves, id: \.title) { shelf in
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: shelf.title) {
                            router.navigate(to: .seeAll(
                                title: shelf.title,
                                category: shelf.category ?? "",
                                mediaType: shelf.mediaType ?? "movie"
                            ))
                        }
                        HorizontalShelf(items: shelf.items as? [MediaItem] ?? []) { item in
                            router.navigate(to: .detail(
                                mediaId: String(item.id),
                                mediaType: item.mediaType == .series ? "tv" : "movie"
                            ))
                        }
                    }
                }

                // Recommendations
                if !wrapper.state.recommendedItems.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "Recommended For You")
                        HorizontalShelf(
                            items: wrapper.state.recommendedItems.map { $0.item } as? [MediaItem] ?? []
                        ) { item in
                            router.navigate(to: .detail(
                                mediaId: String(item.id),
                                mediaType: item.mediaType == .series ? "tv" : "movie"
                            ))
                        }
                    }
                }
            }
            .padding(.vertical)
        }
        .navigationTitle("Torve")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink(value: Route.search) {
                    Image(systemName: "magnifyingglass")
                }
            }
        }
        .refreshable {
            wrapper.refresh()
        }
    }

    private func heroCard(_ item: MediaItem) -> some View {
        Button {
            router.navigate(to: .detail(
                mediaId: String(item.id),
                mediaType: item.mediaType == .series ? "tv" : "movie"
            ))
        } label: {
            ZStack(alignment: .bottomLeading) {
                AsyncImage(url: URL(string: item.backdropUrl ?? item.posterUrl ?? "")) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(16/9, contentMode: .fill)
                    default:
                        Rectangle().fill(SVColor.surfaceVariant)
                            .aspectRatio(16/9, contentMode: .fill)
                    }
                }
                .frame(height: 220)
                .clipped()

                LinearGradient(
                    colors: [.clear, SVColor.obsidian.opacity(0.9)],
                    startPoint: .top, endPoint: .bottom
                )

                VStack(alignment: .leading, spacing: 4) {
                    Text(item.title)
                        .font(SVFont.heroTitle)
                        .foregroundColor(.white)
                    if let overview = item.overview {
                        Text(overview)
                            .font(.caption)
                            .foregroundColor(SVColor.onSurfaceVariant)
                            .lineLimit(2)
                    }
                }
                .padding()
            }
            .cornerRadius(16)
            .padding(.horizontal)
        }
        .buttonStyle(.plain)
    }
}
