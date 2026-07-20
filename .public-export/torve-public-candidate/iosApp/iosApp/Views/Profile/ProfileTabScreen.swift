import SwiftUI
import shared

/// Profile hub screen matching Android's ProfileTabScreen layout.
/// Shows user avatar + name, optional stats summary, and quick-access navigation cards.
struct ProfileTabScreen: View {
    @StateObject private var settingsWrapper = SettingsViewModelWrapper()
    @Environment(AppRouter.self) private var router

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Profile header
                profileHeader
                    .padding(.top, 16)

                // Stats summary (if Trakt connected)
                if let stats = settingsWrapper.state.traktStats {
                    statsCard(stats)
                }

                // Quick Access section
                quickAccessSection

                // App version
                Text("Torve v1.0.0")
                    .font(SVFont.caption)
                    .foregroundColor(SVColor.onSurfaceVariant.opacity(0.5))
                    .padding(.top, 8)
            }
            .padding(.horizontal)
            .padding(.bottom, 24)
        }
        .background(SVColor.obsidian.ignoresSafeArea())
        .navigationTitle("Profile")
    }

    // MARK: - Profile Header

    private var profileHeader: some View {
        HStack(spacing: 16) {
            // Avatar circle
            let initial: String = {
                if let ch = settingsWrapper.state.traktUser?.username.first {
                    return String(ch).uppercased()
                }
                return "S"
            }()
            ZStack {
                Circle()
                    .fill(SVColor.amber)
                    .frame(width: 64, height: 64)
                Text(initial)
                    .font(.system(size: 26, weight: .bold))
                    .foregroundColor(SVColor.obsidian)
            }

            VStack(alignment: .leading, spacing: 4) {
                Text(settingsWrapper.state.traktUser?.username ?? "Torve User")
                    .font(.title3)
                    .fontWeight(.bold)
                    .foregroundColor(SVColor.onSurface)

                if settingsWrapper.state.traktConnected {
                    Text("Trakt connected")
                        .font(SVFont.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }

            Spacer()
        }
        .padding()
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(SVColor.surfaceVariant)
        )
    }

    // MARK: - Stats Card

    private func statsCard(_ stats: TraktStats) -> some View {
        HStack {
            statItem(value: "\(stats.moviesWatched)", label: "Movies")
            Spacer()
            statItem(value: "\(stats.episodesWatched)", label: "Episodes")
            Spacer()
            statItem(value: "\(stats.minutesWatched / 60)h", label: "Watch Time")
        }
        .padding(20)
        .background(
            RoundedRectangle(cornerRadius: 16)
                .fill(SVColor.surfaceVariant)
        )
    }

    private func statItem(value: String, label: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.title3)
                .fontWeight(.bold)
                .foregroundColor(SVColor.amber)
            Text(label)
                .font(SVFont.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
        }
    }

    // MARK: - Quick Access

    private var quickAccessSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Quick Access")
                .font(SVFont.sectionTitle)
                .foregroundColor(SVColor.amber)

            VStack(spacing: 0) {
                profileNavItem(
                    icon: "chart.bar.fill",
                    label: "Stats",
                    subtitle: "Your watch activity",
                    route: .stats
                )
                Divider().background(SVColor.onSurfaceVariant.opacity(0.2))

                profileNavItem(
                    icon: "calendar",
                    label: "Calendar",
                    subtitle: "Upcoming episodes & releases",
                    route: .calendar
                )
                Divider().background(SVColor.onSurfaceVariant.opacity(0.2))

                profileNavItem(
                    icon: "arrow.down.circle.fill",
                    label: "Downloads",
                    subtitle: "Manage offline content",
                    route: .downloads
                )
                Divider().background(SVColor.onSurfaceVariant.opacity(0.2))

                profileNavItem(
                    icon: "sparkles",
                    label: "Discover",
                    subtitle: "Find something new to watch",
                    route: .discover
                )
                Divider().background(SVColor.onSurfaceVariant.opacity(0.2))

                profileNavItem(
                    icon: "checkmark.seal.fill",
                    label: "Free Software",
                    subtitle: "No subscriptions or paid tiers",
                    route: .legal
                )
                Divider().background(SVColor.onSurfaceVariant.opacity(0.2))

                profileNavItem(
                    icon: "person.2.fill",
                    label: "Profiles",
                    subtitle: "Switch or manage profiles",
                    route: .profile
                )
                Divider().background(SVColor.onSurfaceVariant.opacity(0.2))

                profileNavItem(
                    icon: "gearshape.fill",
                    label: "Settings",
                    subtitle: "Accounts, quality, appearance",
                    route: .settings
                )
            }
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(SVColor.surfaceVariant)
            )
            .clipShape(RoundedRectangle(cornerRadius: 16))
        }
    }

    private func profileNavItem(icon: String, label: String, subtitle: String, route: Route) -> some View {
        Button {
            router.navigate(to: route)
        } label: {
            HStack(spacing: 16) {
                Image(systemName: icon)
                    .font(.system(size: 20))
                    .foregroundColor(SVColor.amber)
                    .frame(width: 24)

                VStack(alignment: .leading, spacing: 2) {
                    Text(label)
                        .font(.body)
                        .fontWeight(.medium)
                        .foregroundColor(SVColor.onSurface)
                    Text(subtitle)
                        .font(SVFont.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }

                Spacer()

                Image(systemName: "chevron.right")
                    .font(.caption)
                    .foregroundColor(SVColor.onSurfaceVariant.opacity(0.5))
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
        .buttonStyle(.plain)
    }
}
