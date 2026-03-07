import SwiftUI
import shared

struct IptvHomeView: View {
    let recentlyViewed: [Channel]
    let favorites: [Channel]
    let liveCategories: [ChannelCategory]
    let onChannelPlay: (Channel) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Recently Viewed
                if !recentlyViewed.isEmpty {
                    SectionHeaderView(title: "Recently Viewed")
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(recentlyViewed, id: \.url) { channel in
                                CompactChannelCardView(channel: channel) {
                                    onChannelPlay(channel)
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                }

                // Favorites
                if !favorites.isEmpty {
                    SectionHeaderView(title: "Favorites")
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(favorites, id: \.url) { channel in
                                CompactChannelCardView(channel: channel) {
                                    onChannelPlay(channel)
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                }

                // Category summaries
                ForEach(Array(liveCategories.prefix(5)), id: \.name) { category in
                    SectionHeaderView(title: "\(category.name) (\(category.channelCount))")
                    ScrollView(.horizontal, showsIndicators: false) {
                        LazyHStack(spacing: 12) {
                            ForEach(Array(category.channels.prefix(10)), id: \.channel.url) { enriched in
                                CompactChannelCardView(channel: enriched.channel) {
                                    onChannelPlay(enriched.channel)
                                }
                            }
                        }
                        .padding(.horizontal)
                    }
                }

                if recentlyViewed.isEmpty && favorites.isEmpty && liveCategories.isEmpty {
                    Text("No content yet. Add a playlist to get started.")
                        .foregroundColor(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(48)
                }
            }
            .padding(.vertical)
        }
    }
}

private struct SectionHeaderView: View {
    let title: String

    var body: some View {
        Text(title)
            .font(.subheadline)
            .fontWeight(.bold)
            .padding(.horizontal)
    }
}

private struct CompactChannelCardView: View {
    let channel: Channel
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 6) {
                if let logo = channel.tvgLogo, let url = URL(string: logo) {
                    AsyncImage(url: url) { image in
                        image.resizable().aspectRatio(contentMode: .fit)
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Color.blue.opacity(0.15))
                    }
                    .frame(width: 64, height: 64)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color.blue.opacity(0.15))
                        .frame(width: 64, height: 64)
                        .overlay(
                            Text(String(channel.name.prefix(2)).uppercased())
                                .font(.caption)
                                .fontWeight(.bold)
                                .foregroundColor(.blue)
                        )
                }

                Text(channel.name)
                    .font(.caption)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .foregroundColor(.primary)
            }
            .frame(width: 100)
            .padding(8)
            .background(Color(.secondarySystemBackground))
            .cornerRadius(8)
        }
        .buttonStyle(.plain)
    }
}
