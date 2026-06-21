import SwiftUI
import shared

struct DownloadedShowDetailScreen: View {
    let showTitle: String
    @StateObject private var wrapper = DownloadCatalogueViewModelWrapper()
    @State private var expandedSeasons: Set<Int> = []

    private var showGroup: DownloadGroup? {
        let allGroups = wrapper.state.catalogue.sections.flatMap { $0.items }
            + wrapper.state.catalogue.specialSections.flatMap { $0.items }
        return allGroups.first { $0.title == showTitle && $0.type == .show }
    }

    var body: some View {
        Group {
            if wrapper.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView().tint(SVColor.amber)
                    Spacer()
                }
            } else if let group = showGroup {
                showDetail(group)
            } else {
                VStack(spacing: 12) {
                    Image(systemName: "tv")
                        .font(.system(size: 48))
                        .foregroundColor(SVColor.onSurfaceVariant)
                    Text("Show not found")
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }
        }
        .navigationTitle(showTitle)
    }

    // MARK: - Show Detail

    private func showDetail(_ group: DownloadGroup) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                // Header with backdrop
                headerView(group)

                // Info row
                infoRow(group)

                // Seasons
                if let seasons = group.seasons {
                    ForEach(seasons, id: \.seasonNumber) { season in
                        seasonSection(season, mediaId: group.mediaId)
                    }
                }
            }
        }
    }

    // MARK: - Header

    private func headerView(_ group: DownloadGroup) -> some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: URL(string: group.backdropUrl ?? group.posterUrl ?? "")) { phase in
                if case .success(let img) = phase {
                    img.resizable()
                        .aspectRatio(16/9, contentMode: .fill)
                } else {
                    Rectangle()
                        .fill(SVColor.surfaceVariant)
                        .aspectRatio(16/9, contentMode: .fill)
                }
            }
            .frame(maxWidth: .infinity)
            .overlay(
                LinearGradient(
                    gradient: Gradient(colors: [.clear, SVColor.obsidian.opacity(0.8)]),
                    startPoint: .top,
                    endPoint: .bottom
                )
            )

            VStack(alignment: .leading, spacing: 4) {
                Text(group.title)
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(SVColor.onSurface)

                HStack(spacing: 8) {
                    if let year = group.year?.int32Value {
                        Text("\(year)")
                            .font(.subheadline)
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }
                    if let rating = group.imdbRating?.floatValue {
                        HStack(spacing: 2) {
                            Image(systemName: "star.fill")
                                .font(.caption)
                                .foregroundColor(SVColor.rating)
                            Text(String(format: "%.1f", rating))
                                .font(.subheadline)
                                .foregroundColor(SVColor.onSurfaceVariant)
                        }
                    }
                }
            }
            .padding()
        }
    }

    // MARK: - Info Row

    private func infoRow(_ group: DownloadGroup) -> some View {
        HStack(spacing: 16) {
            infoChip(icon: "film.stack", label: "\(group.itemCount) episodes")
            infoChip(icon: "internaldrive", label: formatFileSize(group.totalSizeBytes))
            if group.watchedCount > 0 {
                infoChip(icon: "eye", label: "\(group.watchedCount)/\(group.totalCount) watched")
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 12)
    }

    private func infoChip(icon: String, label: String) -> some View {
        HStack(spacing: 4) {
            Image(systemName: icon)
                .font(.caption)
                .foregroundColor(SVColor.amber)
            Text(label)
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
        }
    }

    // MARK: - Season Section

    private func seasonSection(_ season: DownloadSeason, mediaId: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            // Season header
            Button {
                withAnimation {
                    if expandedSeasons.contains(Int(season.seasonNumber)) {
                        expandedSeasons.remove(Int(season.seasonNumber))
                    } else {
                        expandedSeasons.insert(Int(season.seasonNumber))
                    }
                }
            } label: {
                HStack {
                    Text("Season \(season.seasonNumber)")
                        .font(.headline)
                        .foregroundColor(SVColor.onSurface)

                    Spacer()

                    Text("\(season.episodes.count) episodes")
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)

                    Text(formatFileSize(season.totalSizeBytes))
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)

                    Image(systemName: expandedSeasons.contains(Int(season.seasonNumber)) ? "chevron.up" : "chevron.down")
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
                .padding(.horizontal)
                .padding(.vertical, 12)
            }
            .buttonStyle(.plain)

            // Episodes
            if expandedSeasons.contains(Int(season.seasonNumber)) {
                ForEach(season.episodes, id: \.id) { episode in
                    episodeRow(episode)
                }
            }

            Divider()
                .background(SVColor.surfaceVariant)
        }
        .contextMenu {
            Button(role: .destructive) {
                wrapper.deleteSeason(mediaId, seasonNumber: Int(season.seasonNumber))
            } label: {
                Label("Delete Season", systemImage: "trash")
            }
        }
    }

    // MARK: - Episode Row

    private func episodeRow(_ episode: DownloadedItem) -> some View {
        HStack(spacing: 12) {
            // Episode number badge
            ZStack {
                Circle()
                    .fill(episode.isWatched ? SVColor.emerald.opacity(0.2) : SVColor.surfaceVariant)
                    .frame(width: 36, height: 36)

                if episode.isWatched {
                    Image(systemName: "checkmark")
                        .font(.caption)
                        .foregroundColor(SVColor.emerald)
                } else if let epNum = episode.episodeNumber?.int32Value {
                    Text("\(epNum)")
                        .font(.caption)
                        .fontWeight(.bold)
                        .foregroundColor(SVColor.onSurface)
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(episode.episodeTitle ?? episode.title)
                    .font(.subheadline)
                    .lineLimit(1)
                    .foregroundColor(SVColor.onSurface)

                HStack(spacing: 8) {
                    if let resolution = episode.resolution {
                        Text(resolution)
                            .font(.caption2)
                            .padding(.horizontal, 4)
                            .padding(.vertical, 1)
                            .background(SVColor.surfaceVariant)
                            .cornerRadius(3)
                    }
                    Text(formatFileSize(episode.fileSizeBytes))
                        .font(.caption2)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }

            Spacer()

            // Watch progress
            if episode.watchProgress > 0 && !episode.isWatched {
                CircularProgressView(progress: CGFloat(episode.watchProgress))
                    .frame(width: 24, height: 24)
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 6)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                wrapper.deleteEpisode(episode.id)
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    // MARK: - Helpers

    private func formatFileSize(_ bytes: Int64) -> String {
        let gb = Double(bytes) / (1024 * 1024 * 1024)
        if gb >= 1.0 {
            return String(format: "%.1f GB", gb)
        }
        let mb = Double(bytes) / (1024 * 1024)
        if mb >= 1.0 {
            return String(format: "%.0f MB", mb)
        }
        let kb = Double(bytes) / 1024
        return String(format: "%.0f KB", kb)
    }
}

// MARK: - Circular Progress View

struct CircularProgressView: View {
    let progress: CGFloat

    var body: some View {
        ZStack {
            Circle()
                .stroke(SVColor.surfaceVariant, lineWidth: 3)
            Circle()
                .trim(from: 0, to: progress)
                .stroke(SVColor.amber, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
    }
}
