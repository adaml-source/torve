import SwiftUI
import shared

struct MoviesScreen: View {
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
            .navigationTitle("Movies")
            .searchable(text: $searchText, prompt: "Search movies...")
        }
    }
}

struct MediaCardView: View {
    let item: MediaItem

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            AsyncImage(url: URL(string: item.posterUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(2/3, contentMode: .fill)
                case .failure:
                    Rectangle()
                        .fill(Color.gray.opacity(0.3))
                        .aspectRatio(2/3, contentMode: .fill)
                case .empty:
                    Rectangle()
                        .fill(Color.gray.opacity(0.3))
                        .aspectRatio(2/3, contentMode: .fill)
                        .overlay(ProgressView())
                @unknown default:
                    EmptyView()
                }
            }
            .cornerRadius(12)

            Text(item.title)
                .font(.caption)
                .foregroundColor(.primary)
                .lineLimit(2)

            if let rating = item.rating {
                HStack(spacing: 2) {
                    Image(systemName: "star.fill")
                        .font(.caption2)
                        .foregroundColor(.yellow)
                    Text(String(format: "%.1f", rating.doubleValue))
                        .font(.caption2)
                        .foregroundColor(.secondary)
                }
            }
        }
    }
}
