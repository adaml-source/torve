import SwiftUI
import shared

struct SearchScreen: View {
    @StateObject private var wrapper: CatalogViewModelWrapper
    @Environment(AppRouter.self) private var router
    @State private var searchText = ""

    init() {
        _wrapper = StateObject(wrappedValue: CatalogViewModelWrapper(mediaType: "movie"))
    }

    var body: some View {
        List {
            if wrapper.state.isSearching {
                HStack { Spacer(); ProgressView().tint(SVColor.amber); Spacer() }
            }

            let results = wrapper.state.searchResults as? [MediaItem] ?? []
            ForEach(results, id: \.id) { item in
                NavigationLink(value: Route.detail(
                    mediaId: String(item.id),
                    mediaType: item.mediaType == .series ? "tv" : "movie"
                )) {
                    HStack(spacing: 12) {
                        AsyncImage(url: URL(string: item.posterUrl ?? "")) { phase in
                            if case .success(let img) = phase {
                                img.resizable().aspectRatio(2/3, contentMode: .fill)
                            } else {
                                Rectangle().fill(SVColor.surfaceVariant)
                            }
                        }
                        .frame(width: 50, height: 75)
                        .cornerRadius(6)

                        VStack(alignment: .leading, spacing: 4) {
                            Text(item.title)
                                .font(.headline)
                                .lineLimit(1)
                            if let year = item.year {
                                Text(String(year.intValue))
                                    .font(.caption)
                                    .foregroundColor(SVColor.onSurfaceVariant)
                            }
                            if let rating = item.rating {
                                HStack(spacing: 2) {
                                    Image(systemName: "star.fill").font(.caption2).foregroundColor(SVColor.rating)
                                    Text(String(format: "%.1f", rating.doubleValue)).font(.caption).foregroundColor(SVColor.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle("Search")
        .searchable(text: Binding(
            get: { wrapper.state.searchQuery },
            set: { wrapper.updateSearchQuery($0) }
        ), prompt: "Movies, TV shows, people...")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                VoiceSearchButton { query in
                    wrapper.updateSearchQuery(query)
                }
            }
        }
    }
}
