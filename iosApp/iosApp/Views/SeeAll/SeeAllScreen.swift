import SwiftUI
import shared

struct SeeAllScreen: View {
    let title: String
    let category: String
    let mediaType: String
    @StateObject private var wrapper: CatalogViewModelWrapper
    @Environment(AppRouter.self) private var router

    init(title: String, category: String, mediaType: String) {
        self.title = title
        self.category = category
        self.mediaType = mediaType
        _wrapper = StateObject(wrappedValue: CatalogViewModelWrapper(mediaType: mediaType))
    }

    var body: some View {
        ScrollView {
            LazyVGrid(columns: [
                GridItem(.adaptive(minimum: 130), spacing: 12)
            ], spacing: 12) {
                let items = wrapper.state.items as? [MediaItem] ?? []
                ForEach(items, id: \.id) { item in
                    NavigationLink(value: Route.detail(
                        mediaId: String(item.id),
                        mediaType: mediaType
                    )) {
                        PosterCard(item: item)
                    }
                    .buttonStyle(.plain)
                    .onAppear {
                        if item.id == items.last?.id {
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
        .navigationTitle(title)
    }
}
