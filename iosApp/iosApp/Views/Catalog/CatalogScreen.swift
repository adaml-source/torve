import SwiftUI
import shared

struct CatalogScreen: View {
    let mediaType: String
    @StateObject private var wrapper: CatalogViewModelWrapper
    @Environment(AppRouter.self) private var router
    @State private var searchText = ""

    init(mediaType: String) {
        self.mediaType = mediaType
        _wrapper = StateObject(wrappedValue: CatalogViewModelWrapper(mediaType: mediaType))
    }

    private var displayItems: [MediaItem] {
        let s = wrapper.state
        if s.searchQuery.count >= 2 {
            return s.searchResults as? [MediaItem] ?? []
        }
        return s.items as? [MediaItem] ?? []
    }

    var body: some View {
        VStack(spacing: 0) {
            // Category chips
            if wrapper.state.searchQuery.count < 2 {
                categoryChips
            }

            // Content
            if wrapper.state.isLoading && displayItems.isEmpty {
                Spacer()
                ProgressView().tint(SVColor.amber)
                Spacer()
            } else {
                ScrollView {
                    // Shelves (only show when not searching)
                    if wrapper.state.searchQuery.count < 2 && wrapper.state.shelvesLoaded {
                        shelvesSection
                    }

                    // Grid
                    LazyVGrid(columns: [
                        GridItem(.adaptive(minimum: 130), spacing: 12)
                    ], spacing: 12) {
                        ForEach(displayItems, id: \.id) { item in
                            NavigationLink(value: Route.detail(
                                mediaId: String(item.id),
                                mediaType: mediaType
                            )) {
                                PosterCard(item: item)
                            }
                            .buttonStyle(.plain)
                            .onAppear {
                                if item.id == displayItems.last?.id {
                                    wrapper.loadMore()
                                }
                            }
                        }
                    }
                    .padding(.horizontal)

                    if wrapper.state.isLoadingMore {
                        ProgressView().tint(SVColor.amber).padding()
                    }
                }
            }
        }
        .navigationTitle(mediaType == "movie" ? "Movies" : "TV Shows")
        .searchable(text: Binding(
            get: { wrapper.state.searchQuery },
            set: { wrapper.updateSearchQuery($0) }
        ), prompt: "Search \(mediaType == "movie" ? "movies" : "TV shows")...")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { wrapper.toggleFilterSheet() } label: {
                    Image(systemName: "line.3.horizontal.decrease.circle")
                        .overlay(alignment: .topTrailing) {
                            if wrapper.state.activeFilterCount > 0 {
                                Circle()
                                    .fill(SVColor.amber)
                                    .frame(width: 8, height: 8)
                            }
                        }
                }
            }
        }
        .sheet(isPresented: Binding(
            get: { wrapper.state.showFilterSheet },
            set: { if !$0 { wrapper.dismissFilterSheet() } }
        )) {
            CatalogFilterSheet(
                currentFilter: wrapper.state.filter,
                onApply: { wrapper.applyFilter($0) },
                onClear: { wrapper.clearFilters() }
            )
        }
        .refreshable { wrapper.refresh() }
    }

    private var categoryChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 10) {
                ForEach([CatalogCategory.trending, CatalogCategory.popular, CatalogCategory.topRated], id: \.self) { cat in
                    Button { wrapper.selectCategory(cat) } label: {
                        Text(cat.label)
                            .font(.system(size: 14, weight: .medium))
                            .padding(.horizontal, 16)
                            .padding(.vertical, 8)
                            .background(wrapper.state.selectedCategory == cat ? SVColor.amber : SVColor.surfaceVariant)
                            .foregroundColor(wrapper.state.selectedCategory == cat ? .black : SVColor.onSurface)
                            .cornerRadius(20)
                    }
                }
            }
            .padding(.horizontal)
            .padding(.vertical, 8)
        }
    }

    private var shelvesSection: some View {
        VStack(spacing: 16) {
            if !wrapper.state.continueWatching.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Continue Watching")
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(wrapper.state.continueWatching, id: \.mediaId) { progress in
                                NavigationLink(value: Route.detail(
                                    mediaId: progress.mediaId,
                                    mediaType: mediaType
                                )) {
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

            if !wrapper.state.trendingItems.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    SectionHeader(title: "Trending")
                    shelfRow(items: wrapper.state.trendingItems as? [MediaItem] ?? [])
                }
            }
        }
        .padding(.bottom, 8)
    }

    private func shelfRow(items: [MediaItem]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 12) {
                ForEach(items, id: \.id) { item in
                    NavigationLink(value: Route.detail(
                        mediaId: String(item.id),
                        mediaType: mediaType
                    )) {
                        PosterCard(item: item).frame(width: 110)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
        }
    }
}
