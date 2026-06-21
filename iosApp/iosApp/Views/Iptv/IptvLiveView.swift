import SwiftUI
import shared

struct IptvLiveView: View {
    let categories: [ChannelCategory]
    let expandedCategories: Set<String>
    let searchQuery: String
    let searchResults: [Channel]
    let isLoading: Bool
    let onToggleCategory: (String) -> Void
    let onSearchQueryChange: (String) -> Void
    let onClearSearch: () -> Void
    let onChannelPlay: (Channel) -> Void
    let onChannelFavorite: (Channel) -> Void

    var body: some View {
        if isLoading {
            VStack {
                Spacer()
                ProgressView()
                Spacer()
            }
        } else {
            List {
                // Search results mode
                if searchQuery.count >= 2 {
                    ForEach(searchResults, id: \.url) { channel in
                        ChannelRowView(
                            enriched: EnrichedChannel(channel: channel, currentProgramme: nil, nextProgramme: nil),
                            onPlay: { onChannelPlay(channel) },
                            onFavorite: { onChannelFavorite(channel) }
                        )
                    }
                } else {
                    // Collapsible categories
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
            }
            .listStyle(.plain)
        }
    }
}
