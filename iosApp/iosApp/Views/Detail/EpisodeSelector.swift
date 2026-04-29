import SwiftUI
import shared

struct EpisodeSelector: View {
    @ObservedObject var wrapper: DetailViewModelWrapper
    @State private var showStreamPicker = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Section title with mark-season-watched button
            HStack {
                Text("Episodes")
                    .font(SVFont.sectionTitle)
                    .foregroundColor(SVColor.onSurface)

                Spacer()

                Button {
                    wrapper.markSeasonWatched(wrapper.state.selectedSeason)
                } label: {
                    HStack(spacing: 4) {
                        Image(systemName: "checkmark.circle")
                        Text("Mark Season Watched")
                    }
                    .font(.caption)
                    .foregroundColor(SVColor.amber)
                }
            }

            // Season picker
            seasonPicker()

            // Episode list
            if wrapper.state.isLoadingSeasonDetail {
                HStack {
                    Spacer()
                    ProgressView().tint(SVColor.amber)
                    Spacer()
                }
                .padding(.vertical, 20)
            } else if let season = wrapper.state.seasonDetail {
                let episodes = season.episodes as? [Episode] ?? []
                ForEach(episodes, id: \.episodeNumber) { episode in
                    episodeRow(episode)
                }
            }
        }
        .sheet(isPresented: $showStreamPicker) {
            StreamPickerView(
                streams: wrapper.state.streams as? [ParsedStream] ?? [],
                isResolving: wrapper.state.isResolving,
                onStreamSelected: { stream in
                    if stream.accelerationProvenanceKind == .usenetNzbdav {
                        wrapper.selectUsenetSource(stream)
                    } else {
                        wrapper.resolveStream(stream)
                    }
                    showStreamPicker = false
                },
                onDismiss: { showStreamPicker = false }
            )
        }
        .onChange(of: showStreamPicker) { _, isVisible in
            if isVisible { wrapper.onSourceSheetOpened() }
        }
    }

    // MARK: - Season Picker

    @ViewBuilder
    private func seasonPicker() -> some View {
        if let item = wrapper.state.mediaItem {
            let seasons = item.seasons as? [Season] ?? []
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(seasons, id: \.seasonNumber) { season in
                        let isSelected = wrapper.state.selectedSeason == season.seasonNumber
                        Button {
                            wrapper.selectSeason(season.seasonNumber)
                        } label: {
                            Text("S\(season.seasonNumber)")
                                .font(.system(size: 14, weight: .medium))
                                .padding(.horizontal, 14)
                                .padding(.vertical, 8)
                                .background(isSelected ? SVColor.amber : SVColor.surfaceVariant)
                                .foregroundColor(isSelected ? .black : SVColor.onSurface)
                                .cornerRadius(16)
                        }
                    }
                }
            }
        }
    }

    // MARK: - Episode Row

    @ViewBuilder
    private func episodeRow(_ episode: Episode) -> some View {
        let watchedKey = "s\(wrapper.state.selectedSeason)e\(episode.episodeNumber)"
        let isWatched = wrapper.state.watchedEpisodes.contains(watchedKey)

        Button {
            wrapper.fetchStreamsForEpisode(
                season: wrapper.state.selectedSeason,
                episode: episode.episodeNumber
            )
            showStreamPicker = true
        } label: {
            HStack(spacing: 12) {
                // Episode thumbnail
                AsyncImage(url: URL(string: episode.stillUrl ?? "")) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(16/9, contentMode: .fill)
                    default:
                        ZStack {
                            Rectangle().fill(SVColor.surfaceVariant)
                            Image(systemName: "play.circle")
                                .font(.title3)
                                .foregroundColor(SVColor.onSurfaceVariant)
                        }
                    }
                }
                .frame(width: 120, height: 68)
                .cornerRadius(8)
                .clipped()

                // Episode info
                VStack(alignment: .leading, spacing: 4) {
                    Text("E\(episode.episodeNumber) - \(episode.name)")
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .foregroundColor(SVColor.onSurface)
                        .lineLimit(1)

                    if !episode.overview.isEmpty {
                        Text(episode.overview)
                            .font(.caption)
                            .foregroundColor(SVColor.onSurfaceVariant)
                            .lineLimit(2)
                    }

                    if let airDate = episode.airDate, !airDate.isEmpty {
                        Text(airDate)
                            .font(.system(size: 10))
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }
                }

                Spacer()

                // Watched badge
                if isWatched {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundColor(SVColor.emerald)
                }
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }
}
