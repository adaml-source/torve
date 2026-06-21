import SwiftUI
import shared

struct TraktSettingsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    var body: some View {
        List {
            if wrapper.state.traktConnected {
                connectedSection
                syncSection
                scrobbleSection
                disconnectSection
            } else {
                connectSection
            }
        }
        .navigationTitle("Trakt.tv")
    }

    // MARK: - Connected

    @ViewBuilder
    private var connectedSection: some View {
        Section("Account") {
            Label("Connected", systemImage: "checkmark.circle.fill")
                .foregroundColor(SVColor.emerald)

            if let user = wrapper.state.traktUser {
                HStack {
                    Text("Username")
                    Spacer()
                    Text(user.username)
                        .foregroundColor(.secondary)
                }
            }

            if let stats = wrapper.state.traktStats {
                HStack {
                    Text("Movies Watched")
                    Spacer()
                    Text("\(stats.movies.watched)")
                        .foregroundColor(.secondary)
                }
                HStack {
                    Text("Shows Watched")
                    Spacer()
                    Text("\(stats.shows.watched)")
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private var syncSection: some View {
        Section("Sync") {
            Button {
                wrapper.viewModel.syncTrakt()
            } label: {
                HStack {
                    Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
                    Spacer()
                    if wrapper.state.traktSyncing {
                        ProgressView()
                    }
                }
            }
            .disabled(wrapper.state.traktSyncing)

            if let lastSync = wrapper.state.traktLastSyncTime {
                HStack {
                    Text("Last Synced")
                    Spacer()
                    Text(formatTimestamp(lastSync))
                        .foregroundColor(.secondary)
                }
            }

            if wrapper.state.traktSyncSuccess {
                Label("Sync completed", systemImage: "checkmark")
                    .foregroundColor(SVColor.emerald)
            }
        }
    }

    private var scrobbleSection: some View {
        Section {
            Toggle("Scrobble to Trakt", isOn: Binding(
                get: { wrapper.state.traktScrobbleEnabled },
                set: { wrapper.viewModel.setTraktScrobble(enabled: $0) }
            ))
        } footer: {
            Text("Automatically mark movies and episodes as watched on Trakt when you finish watching.")
        }
    }

    private var disconnectSection: some View {
        Section {
            Button("Disconnect Trakt", role: .destructive) {
                wrapper.viewModel.disconnectTrakt()
            }
        }
    }

    // MARK: - Not Connected

    @ViewBuilder
    private var connectSection: some View {
        Section {
            if let deviceCode = wrapper.state.traktDeviceCode {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Go to **trakt.tv/activate** and enter this code:")
                        .font(.subheadline)
                    Text(deviceCode.userCode)
                        .font(.system(.title, design: .monospaced))
                        .fontWeight(.bold)
                        .foregroundColor(SVColor.amber)
                    if wrapper.state.isPollingTrakt {
                        HStack {
                            ProgressView()
                            Text("Waiting for authorization...")
                                .foregroundColor(.secondary)
                                .padding(.leading, 8)
                        }
                    }
                }
                .padding(.vertical, 4)
            } else {
                Button {
                    wrapper.viewModel.startTraktDeviceAuth()
                } label: {
                    Label("Connect with Trakt", systemImage: "link")
                }
            }
        } footer: {
            Text("Connect your Trakt account to sync watchlists, watch history, and ratings across devices.")
        }

        if let error = wrapper.state.traktError {
            Section {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundColor(SVColor.error)
            }
        }
    }

    // MARK: - Helpers

    private func formatTimestamp(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0)
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}
