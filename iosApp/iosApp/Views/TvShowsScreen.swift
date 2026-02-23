import SwiftUI
import shared

struct TvShowsScreen: View {
    @State private var items: [MediaItem] = []
    @State private var isLoading = true
    @State private var selectedCategory = "Trending"
    @State private var searchText = ""

    let categories = ["Trending", "Popular", "Top Rated"]

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // Category picker
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 12) {
                        ForEach(categories, id: \.self) { category in
                            Button(action: { selectedCategory = category }) {
                                Text(category)
                                    .padding(.horizontal, 16)
                                    .padding(.vertical, 8)
                                    .background(selectedCategory == category ? Color.blue : Color.gray.opacity(0.3))
                                    .foregroundColor(.white)
                                    .cornerRadius(20)
                            }
                        }
                    }
                    .padding(.horizontal)
                    .padding(.vertical, 8)
                }

                if isLoading {
                    Spacer()
                    ProgressView()
                    Spacer()
                } else {
                    ScrollView {
                        LazyVGrid(columns: [
                            GridItem(.adaptive(minimum: 130), spacing: 12)
                        ], spacing: 12) {
                            ForEach(items, id: \.id) { item in
                                NavigationLink(destination: DetailScreen(mediaItem: item)) {
                                    MediaCardView(item: item)
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                }
            }
            .navigationTitle("TV Shows")
            .searchable(text: $searchText, prompt: "Search TV shows...")
        }
    }
}
