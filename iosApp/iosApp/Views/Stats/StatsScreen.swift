import SwiftUI
import shared

struct StatsScreen: View {
    @StateObject private var wrapper = StatsViewModelWrapper()

    var body: some View {
        ScrollView {
            if wrapper.state.isLoading {
                ProgressView("Loading stats...")
                    .frame(maxWidth: .infinity)
                    .padding(.top, 64)
            } else if let error = wrapper.state.error {
                errorView(error)
            } else {
                statsContent
            }
        }
        .navigationTitle("Stats")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button(action: { wrapper.refresh() }) {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
    }

    // MARK: - Stats Content

    private var statsContent: some View {
        VStack(spacing: 20) {
            // Summary cards row
            HStack(spacing: 12) {
                StatCard(
                    title: "Movies",
                    value: "\(wrapper.state.totalMovies)",
                    icon: "film",
                    color: SVColor.amber
                )
                StatCard(
                    title: "Episodes",
                    value: "\(wrapper.state.totalEpisodes)",
                    icon: "tv",
                    color: SVColor.emerald
                )
            }
            .padding(.horizontal)

            // Watch time card
            watchTimeSection

            // Streak
            if wrapper.state.longestStreak > 0 {
                streakCard
            }

            // Top genres
            let topGenres = wrapper.state.topGenres as? [GenreStat] ?? []
            if !topGenres.isEmpty {
                topGenresSection(topGenres)
            }

            // Activity by day
            let activity = wrapper.state.activityByDay as? [String: NSNumber] ?? [:]
            if !activity.isEmpty {
                activitySection(activity)
            }
        }
        .padding(.vertical)
    }

    // MARK: - Watch Time

    private var watchTimeSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Watch Time")
                .font(SVFont.sectionTitle)
                .padding(.horizontal)

            HStack(spacing: 12) {
                WatchTimeCard(
                    label: "Total",
                    minutes: wrapper.state.totalMinutes,
                    icon: "clock.fill"
                )
                WatchTimeCard(
                    label: "This Week",
                    minutes: wrapper.state.thisWeekMinutes,
                    icon: "calendar"
                )
                WatchTimeCard(
                    label: "This Month",
                    minutes: wrapper.state.thisMonthMinutes,
                    icon: "calendar.badge.clock"
                )
            }
            .padding(.horizontal)
        }
    }

    // MARK: - Streak

    private var streakCard: some View {
        HStack(spacing: 12) {
            Image(systemName: "flame.fill")
                .font(.system(size: 28))
                .foregroundColor(.orange)

            VStack(alignment: .leading, spacing: 2) {
                Text("Longest Streak")
                    .font(.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
                Text("\(wrapper.state.longestStreak) day\(wrapper.state.longestStreak == 1 ? "" : "s")")
                    .font(.title3)
                    .fontWeight(.bold)
            }

            Spacer()
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(SVColor.surfaceVariant)
        )
        .padding(.horizontal)
    }

    // MARK: - Top Genres

    private func topGenresSection(_ genres: [GenreStat]) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Top Genres")
                .font(SVFont.sectionTitle)
                .padding(.horizontal)

            VStack(spacing: 8) {
                let maxCount = genres.first?.count ?? 1
                ForEach(genres, id: \.name) { genre in
                    HStack(spacing: 12) {
                        Text(genre.name)
                            .font(.subheadline)
                            .frame(width: 100, alignment: .leading)

                        GeometryReader { geo in
                            let fraction = CGFloat(genre.count) / CGFloat(max(maxCount, 1))
                            RoundedRectangle(cornerRadius: 4)
                                .fill(SVColor.amber)
                                .frame(width: geo.size.width * fraction)
                        }
                        .frame(height: 16)

                        Text("\(genre.count)")
                            .font(.caption)
                            .foregroundColor(SVColor.onSurfaceVariant)
                            .frame(width: 30, alignment: .trailing)
                    }
                }
            }
            .padding(.horizontal)
        }
    }

    // MARK: - Activity by Day

    private func activitySection(_ activity: [String: NSNumber]) -> some View {
        let dayOrder = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
        let maxVal = activity.values.map { $0.intValue }.max() ?? 1

        return VStack(alignment: .leading, spacing: 12) {
            Text("Activity by Day")
                .font(SVFont.sectionTitle)
                .padding(.horizontal)

            HStack(alignment: .bottom, spacing: 8) {
                ForEach(dayOrder, id: \.self) { day in
                    let count = activity[day]?.intValue ?? 0
                    let fraction = CGFloat(count) / CGFloat(max(maxVal, 1))

                    VStack(spacing: 4) {
                        RoundedRectangle(cornerRadius: 4)
                            .fill(SVColor.amber)
                            .frame(height: max(4, 100 * fraction))

                        Text(String(day.prefix(3)))
                            .font(.caption2)
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }
                }
            }
            .frame(height: 120, alignment: .bottom)
            .padding(.horizontal)
        }
    }

    // MARK: - Error

    private func errorView(_ error: String) -> some View {
        VStack(spacing: 12) {
            Image(systemName: "exclamationmark.triangle")
                .font(.system(size: 36))
                .foregroundColor(SVColor.error)
            Text(error)
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
            Button("Retry") { wrapper.refresh() }
                .foregroundColor(SVColor.amber)
        }
        .padding(.top, 48)
    }
}

// MARK: - Stat Card

private struct StatCard: View {
    let title: String
    let value: String
    let icon: String
    let color: Color

    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: icon)
                .font(.system(size: 24))
                .foregroundColor(color)

            Text(value)
                .font(.title)
                .fontWeight(.bold)

            Text(title)
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(SVColor.surfaceVariant)
        )
    }
}

// MARK: - Watch Time Card

private struct WatchTimeCard: View {
    let label: String
    let minutes: Int64
    let icon: String

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 18))
                .foregroundColor(SVColor.amber)

            Text(formatMinutes(minutes))
                .font(.headline)
                .fontWeight(.bold)
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Text(label)
                .font(.caption2)
                .foregroundColor(SVColor.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 12)
        .padding(.horizontal, 4)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(SVColor.surfaceVariant)
        )
    }

    private func formatMinutes(_ mins: Int64) -> String {
        if mins < 60 { return "\(mins)m" }
        let hours = mins / 60
        let remaining = mins % 60
        if remaining == 0 { return "\(hours)h" }
        return "\(hours)h \(remaining)m"
    }
}
