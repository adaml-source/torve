import SwiftUI
import shared

struct AccountScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var email: String = ""
    @State private var showDeleteConfirmation = false

    var body: some View {
        List {
            profileSection
            syncStatusSection
            connectedServicesSection
            dangerZoneSection
        }
        .navigationTitle("Account & Sync")
        .confirmationDialog(
            "Delete Account",
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete Everything", role: .destructive) {
                // Will be wired to account deletion flow
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will permanently delete all your data, disconnect all services, and remove your account. This action cannot be undone.")
        }
    }

    // MARK: - Profile

    private var profileSection: some View {
        Section("Profile") {
            HStack(spacing: 16) {
                Image(systemName: "person.crop.circle.fill")
                    .font(.system(size: 48))
                    .foregroundColor(SVColor.amber)

                VStack(alignment: .leading, spacing: 4) {
                    if let traktUser = wrapper.state.traktUser {
                        Text(traktUser.username)
                            .fontWeight(.semibold)
                    } else {
                        Text("Not signed in")
                            .fontWeight(.semibold)
                    }
                    Text("Torve Account")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
            .padding(.vertical, 4)
        }
    }

    // MARK: - Sync Status

    private var syncStatusSection: some View {
        Section("Sync") {
            if wrapper.state.isSyncing {
                HStack {
                    ProgressView()
                    Text("Syncing...")
                        .foregroundColor(.secondary)
                        .padding(.leading, 8)
                }
            }

            Button {
                wrapper.viewModel.syncAll()
            } label: {
                Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
            }
            .disabled(wrapper.state.isSyncing)

            if let lastSync = wrapper.state.lastSyncTime {
                HStack {
                    Text("Last Sync")
                    Spacer()
                    Text(formatTimestamp(lastSync))
                        .foregroundColor(.secondary)
                }
            }

            if let error = wrapper.state.syncError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundColor(SVColor.error)
            }

            if let success = wrapper.state.syncSuccess {
                Label(success, systemImage: "checkmark.circle.fill")
                    .foregroundColor(SVColor.emerald)
            }
        }
    }

    // MARK: - Connected Services

    private var connectedServicesSection: some View {
        Section("Connected Services") {
            serviceRow("Debrid", connected: wrapper.state.debridConnected, detail: wrapper.state.debridUser?.username)
            serviceRow("Trakt", connected: wrapper.state.traktConnected, detail: wrapper.state.traktUser?.username)
            serviceRow("SIMKL", connected: wrapper.state.simklConnected, detail: wrapper.state.simklUser?.name)
            serviceRow("Plex", connected: wrapper.state.plexConnected, detail: nil)
        }
    }

    private func serviceRow(_ name: String, connected: Bool, detail: String?) -> some View {
        HStack {
            Text(name)
            Spacer()
            if connected {
                if let detail = detail {
                    Text(detail)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Image(systemName: "checkmark.circle.fill")
                    .foregroundColor(SVColor.emerald)
            } else {
                Text("Not connected")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }

    // MARK: - Danger Zone

    private var dangerZoneSection: some View {
        Section {
            Button("Delete All Data", role: .destructive) {
                showDeleteConfirmation = true
            }
        } footer: {
            Text("This permanently removes all local data including watch history, preferences, and cached content.")
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
