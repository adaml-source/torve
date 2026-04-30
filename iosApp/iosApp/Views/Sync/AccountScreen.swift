import SwiftUI
import shared

struct AccountScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()
    @ObservedObject private var api = TorveAPIClient.shared

    @State private var email: String = ""
    @State private var showDeleteConfirmation = false
    @State private var deletionError: String?
    @State private var deletionInFlight = false
    @State private var exportInFlight = false
    @State private var exportFileURL: URL?
    @State private var showExportShare = false
    @State private var exportError: String?

    var body: some View {
        List {
            profileSection
            syncStatusSection
            connectedServicesSection
            dataRightsSection
            dangerZoneSection
        }
        .navigationTitle("Account & Sync")
        .confirmationDialog(
            "Delete Account",
            isPresented: $showDeleteConfirmation,
            titleVisibility: .visible
        ) {
            Button("Delete Everything", role: .destructive) {
                Task { await performDeleteAccount() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This will permanently delete all your data, disconnect all services, and remove your account. This action cannot be undone.")
        }
        .alert("Could not delete account", isPresented: .constant(deletionError != nil)) {
            Button("OK") { deletionError = nil }
        } message: {
            Text(deletionError ?? "")
        }
        .alert("Could not export data", isPresented: .constant(exportError != nil)) {
            Button("OK") { exportError = nil }
        } message: {
            Text(exportError ?? "")
        }
        .sheet(isPresented: $showExportShare) {
            if let url = exportFileURL {
                ShareSheet(items: [url])
            }
        }
    }

    private func performDeleteAccount() async {
        deletionInFlight = true
        defer { deletionInFlight = false }
        do {
            try await api.deleteAccount()
            // On success, AuthClient state is already cleared. Navigation
            // back to login is the responsibility of the parent observing
            // TorveAPIClient.shared.isLoggedIn.
        } catch APIError.unauthorized {
            // Token already expired — treat as success since the user
            // can no longer authenticate anyway.
        } catch {
            deletionError = error.localizedDescription
        }
    }

    private func performExport() async {
        exportInFlight = true
        defer { exportInFlight = false }
        do {
            let data = try await api.exportData()
            // Write to a temp file so iOS can hand it to the share sheet.
            let fname = "torve-export-\(Int(Date().timeIntervalSince1970)).json"
            let url = FileManager.default.temporaryDirectory.appendingPathComponent(fname)
            try data.write(to: url)
            exportFileURL = url
            showExportShare = true
        } catch {
            exportError = error.localizedDescription
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

    // MARK: - Data Rights (Prompt 12 hardening)

    private var dataRightsSection: some View {
        Section("Your data") {
            Button {
                Task { await performExport() }
            } label: {
                if exportInFlight {
                    HStack {
                        ProgressView()
                        Text("Exporting…")
                            .padding(.leading, 8)
                    }
                } else {
                    Label("Export my data", systemImage: "square.and.arrow.down")
                }
            }
            .disabled(exportInFlight || !api.isLoggedIn)

            Link(
                destination: URL(string: "https://torve.app/privacy.html")!
            ) {
                Label("Privacy Policy", systemImage: "lock.shield")
            }
            Link(
                destination: URL(string: "https://torve.app/terms.html")!
            ) {
                Label("Terms of Service", systemImage: "doc.text")
            }
            Link(
                destination: URL(string: "mailto:support@torve.app")!
            ) {
                Label("Contact support", systemImage: "envelope")
            }
        }
    }

    // MARK: - Danger Zone

    private var dangerZoneSection: some View {
        Section {
            Button(role: .destructive) {
                showDeleteConfirmation = true
            } label: {
                if deletionInFlight {
                    HStack {
                        ProgressView()
                        Text("Deleting account…")
                            .padding(.leading, 8)
                    }
                } else {
                    Text("Delete account")
                }
            }
            .disabled(deletionInFlight || !api.isLoggedIn)
        } header: {
            Text("Account deletion")
        } footer: {
            Text("Permanently removes your Torve account and all associated data on our servers. This action cannot be undone.")
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
