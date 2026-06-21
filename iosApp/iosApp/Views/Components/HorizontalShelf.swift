import SwiftUI
import shared

struct HorizontalShelf: View {
    let items: [MediaItem]
    let onItemTap: (MediaItem) -> Void
    var cardWidth: CGFloat = 130

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            LazyHStack(spacing: 12) {
                ForEach(items, id: \.id) { item in
                    Button { onItemTap(item) } label: {
                        PosterCard(item: item)
                            .frame(width: cardWidth)
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal)
        }
    }
}
