import SwiftUI
import shared

struct ChannelsFavouritesView: View {
    @ObservedObject var wrapper: ChannelsViewModelWrapper

    var body: some View {
        Group {
            if wrapper.state.favorites.isEmpty {
                VStack(spacing: 12) {
                    Spacer()
                    Image(systemName: "heart")
                        .font(.system(size: 48))
                        .foregroundColor(SVColor.onSurfaceVariant)
                    Text("No favourites yet")
                        .foregroundColor(SVColor.onSurfaceVariant)
                    Text("Long-press a channel to add it to favourites")
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                    Spacer()
                }
            } else {
                List {
                    ForEach(wrapper.state.favorites, id: \.name) { channel in
                        Button {
                            wrapper.recordChannelViewed(channel)
                        } label: {
                            HStack(spacing: 12) {
                                AsyncImage(url: URL(string: channel.tvgLogo ?? "")) { phase in
                                    if case .success(let img) = phase {
                                        img.resizable().scaledToFit()
                                    } else {
                                        Image(systemName: "tv")
                                            .foregroundColor(SVColor.onSurfaceVariant)
                                    }
                                }
                                .frame(width: 40, height: 40)
                                .cornerRadius(8)

                                Text(channel.name)
                                Spacer()
                            }
                        }
                        .buttonStyle(.plain)
                        .swipeActions(edge: .trailing) {
                            Button(role: .destructive) {
                                wrapper.toggleFavorite(channel)
                            } label: {
                                Label("Remove", systemImage: "heart.slash")
                            }
                        }
                    }
                }
                .listStyle(.plain)
            }
        }
    }
}
