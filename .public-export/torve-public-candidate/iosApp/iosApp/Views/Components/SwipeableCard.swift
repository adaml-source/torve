import SwiftUI

struct SwipeableCard<Content: View>: View {
    let onDelete: (() -> Void)?
    let onFavorite: (() -> Void)?
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .swipeActions(edge: .trailing, allowsFullSwipe: false) {
                if let onDelete = onDelete {
                    Button(role: .destructive, action: onDelete) {
                        Label("Delete", systemImage: "trash")
                    }
                }
            }
            .swipeActions(edge: .leading, allowsFullSwipe: false) {
                if let onFavorite = onFavorite {
                    Button(action: onFavorite) {
                        Label("Favorite", systemImage: "heart.fill")
                    }
                    .tint(SVColor.amber)
                }
            }
    }
}
