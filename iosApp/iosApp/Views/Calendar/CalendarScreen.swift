import SwiftUI
import shared

struct CalendarScreen: View {
    @StateObject private var wrapper = CalendarViewModelWrapper()
    @Environment(AppRouter.self) private var router

    var body: some View {
        Group {
            if !wrapper.state.traktConnected {
                traktNotConnected
            } else if wrapper.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView("Loading calendar...")
                        .tint(SVColor.amber)
                    Spacer()
                }
            } else if let error = wrapper.state.error {
                errorView(error)
            } else if wrapper.state.episodes.isEmpty {
                emptyState
            } else {
                calendarList
            }
        }
        .navigationTitle("Calendar")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { wrapper.refresh() } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
    }

    // MARK: - Trakt Not Connected

    private var traktNotConnected: some View {
        VStack(spacing: 16) {
            Image(systemName: "calendar.badge.exclamationmark")
                .font(.system(size: 60))
                .foregroundColor(SVColor.onSurfaceVariant)
            Text("Connect Trakt")
                .font(.title2)
                .fontWeight(.semibold)
            Text("Connect your Trakt.tv account to see upcoming episodes from shows you watch.")
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Button("Go to Settings") {
                router.navigate(to: .traktSettings)
            }
            .buttonStyle(.borderedProminent)
            .tint(SVColor.amber)
        }
    }

    // MARK: - Error View

    private func errorView(_ error: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.largeTitle)
                .foregroundColor(SVColor.error)
            Text(error)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Button("Retry") { wrapper.refresh() }
                .buttonStyle(.borderedProminent)
                .tint(SVColor.amber)
        }
        .padding()
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "calendar")
                .font(.system(size: 60))
                .foregroundColor(SVColor.onSurfaceVariant)
            Text("No Upcoming Episodes")
                .font(.title2)
                .fontWeight(.semibold)
            Text("No new episodes are airing in the next 33 days for shows you watch on Trakt.")
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    // MARK: - Calendar List

    /// Converts the Kotlin Map<String, List<TraktCalendarEpisode>> to a Swift-friendly array of tuples.
    private var groupedSections: [(label: String, episodes: [TraktCalendarEpisode])] {
        let dict = wrapper.state.groupedEpisodes
        var result: [(String, [TraktCalendarEpisode])] = []
        for key in dict.keys {
            guard let label = key as? String,
                  let list = dict[label] as? [TraktCalendarEpisode] else { continue }
            result.append((label, list))
        }
        return result.sorted { a, b in
            sortKey(a.0) < sortKey(b.0)
        }
    }

    private func sortKey(_ label: String) -> String {
        switch label {
        case "Today": return "0"
        case "Tomorrow": return "1"
        default: return "2_\(label)"
        }
    }

    private var calendarList: some View {
        List {
            ForEach(groupedSections, id: \.label) { section in
                Section {
                    ForEach(Array(section.episodes.enumerated()), id: \.offset) { _, episode in
                        episodeRow(episode)
                    }
                } header: {
                    HStack {
                        Text(section.label)
                            .font(.headline)
                            .foregroundColor(isToday(section.label) ? SVColor.amber : SVColor.onSurface)
                        Spacer()
                        Text("\(section.episodes.count)")
                            .font(.caption)
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }
                }
            }
        }
        .listStyle(.plain)
    }

    // MARK: - Episode Row

    private func episodeRow(_ episode: TraktCalendarEpisode) -> some View {
        Button {
            if let tmdbId = episode.showTmdbId?.int32Value {
                router.navigate(to: .detail(mediaId: "tmdb:\(tmdbId)", mediaType: "tv"))
            }
        } label: {
            HStack(spacing: 12) {
                // Show initial badge
                ZStack {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(SVColor.surfaceVariant)
                        .frame(width: 48, height: 48)

                    Text(String(episode.showTitle.prefix(2)).uppercased())
                        .font(.system(size: 16, weight: .bold))
                        .foregroundColor(SVColor.amber)
                }

                VStack(alignment: .leading, spacing: 4) {
                    Text(episode.showTitle)
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(SVColor.onSurface)
                        .lineLimit(1)

                    Text("S\(String(format: "%02d", episode.season))E\(String(format: "%02d", episode.episode))")
                        .font(.caption)
                        .foregroundColor(SVColor.amber)

                    if !episode.episodeTitle.isEmpty {
                        Text(episode.episodeTitle)
                            .font(.caption)
                            .foregroundColor(SVColor.onSurfaceVariant)
                            .lineLimit(1)
                    }
                }

                Spacer()

                // Air time
                if let time = formatAirTime(episode.firstAired) {
                    Text(time)
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
            .padding(.vertical, 4)
        }
        .buttonStyle(.plain)
    }

    // MARK: - Helpers

    private func isToday(_ label: String) -> Bool {
        return label == "Today"
    }

    private func formatAirTime(_ isoString: String) -> String? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        guard let date = formatter.date(from: isoString) ?? ISO8601DateFormatter().date(from: isoString) else {
            return nil
        }
        let timeFormatter = DateFormatter()
        timeFormatter.dateFormat = "HH:mm"
        return timeFormatter.string(from: date)
    }
}
