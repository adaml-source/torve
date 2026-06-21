import SwiftUI
import shared

struct IptvSeriesView: View {
    let categories: [ChannelCategory]
    let expandedCategories: Set<String>
    let onToggleCategory: (String) -> Void
    let onChannelPlay: (Channel) -> Void
    let onChannelFavorite: (Channel) -> Void

    var body: some View {
        if categories.isEmpty {
            VStack(spacing: 12) {
                Spacer()
                Image(systemName: "tv")
                    .font(.system(size: 40))
                    .foregroundColor(.secondary)
                Text("No series available.")
                    .foregroundColor(.secondary)
                Text("Series require an Xtream Codes playlist.")
                    .font(.caption)
                    .foregroundColor(.secondary)
                Spacer()
            }
        } else {
            List {
                ForEach(categories, id: \.name) { category in
                    let isExpanded = expandedCategories.contains(category.name)

                    Section {
                        CategoryHeaderView(
                            name: category.name,
                            channelCount: category.channelCount,
                            qualityTags: Set(category.qualityTags as? [String] ?? []),
                            isExpanded: isExpanded,
                            onToggle: { onToggleCategory(category.name) }
                        )

                        if isExpanded {
                            ForEach(category.channels, id: \.channel.url) { enriched in
                                ChannelRowView(
                                    enriched: enriched,
                                    onPlay: { onChannelPlay(enriched.channel) },
                                    onFavorite: { onChannelFavorite(enriched.channel) }
                                )
                            }
                        }
                    }
                }
            }
            .listStyle(.plain)
        }
    }
}
