import SwiftUI
import shared
import UniformTypeIdentifiers

struct SettingsScreen: View {
    @State private var showPaywall = false
    @State private var showDownloads = false

    var body: some View {
        NavigationStack {
            List {
                Section("Account") {
                    NavigationLink(destination: PaywallScreen()) {
                        Label("Subscription", systemImage: "star.circle")
                    }
                    NavigationLink(destination: DownloadsScreen()) {
                        Label("Downloads", systemImage: "arrow.down.circle")
                    }
                }

                Section("Playback") {
                    NavigationLink(destination: PlaybackSettingsView()) {
                        Label("Stream Preferences", systemImage: "play.circle")
                    }
                }

                Section("Services") {
                    NavigationLink(destination: DebridSettingsView()) {
                        Label("Cloud Service", systemImage: "server.rack")
                    }
                    NavigationLink(destination: TraktSettingsView()) {
                        Label("Trakt.tv", systemImage: "list.bullet")
                    }
                    NavigationLink(destination: AddonSettingsView()) {
                        Label("Content Sources", systemImage: "puzzlepiece.extension")
                    }
                }

                Section("Backup & Sync") {
                    NavigationLink(destination: BackupSyncView()) {
                        Label("Backup & Restore", systemImage: "arrow.triangle.2.circlepath")
                    }
                }

                Section("About") {
                    HStack {
                        Text("Version")
                        Spacer()
                        Text("0.1.0")
                            .foregroundColor(.secondary)
                    }
                }
            }
            .navigationTitle("Settings")
        }
    }
}

struct BackupSyncView: View {
    @State private var iCloudSyncEnabled = false
    @State private var isExporting = false
    @State private var isImporting = false
    @State private var showExportSheet = false
    @State private var showImportPicker = false
    @State private var statusMessage: String? = nil
    @State private var exportURL: URL? = nil

    var body: some View {
        List {
            Section {
                Toggle("iCloud Sync", isOn: $iCloudSyncEnabled)
            } footer: {
                Text("Automatically sync your addons, preferences, and watch progress across your Apple devices via iCloud.")
            }

            Section("Manual Backup") {
                Button {
                    exportBackup()
                } label: {
                    HStack {
                        Label("Export Backup", systemImage: "square.and.arrow.up")
                        Spacer()
                        if isExporting {
                            ProgressView()
                        }
                    }
                }
                .disabled(isExporting)

                Button {
                    showImportPicker = true
                } label: {
                    Label("Import Backup", systemImage: "square.and.arrow.down")
                }
                .disabled(isImporting)
            } footer: {
                Text("Export your addons, preferences, watch progress, and IPTV favorites to a file. API keys and tokens are never included.")
            }

            if let statusMessage = statusMessage {
                Section {
                    Text(statusMessage)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
            }
        }
        .navigationTitle("Backup & Sync")
        .sheet(isPresented: $showExportSheet) {
            if let url = exportURL {
                ShareSheet(activityItems: [url])
            }
        }
        .fileImporter(
            isPresented: $showImportPicker,
            allowedContentTypes: [UTType.json, UTType.data],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                importBackup(from: url)
            case .failure:
                statusMessage = "Failed to select file"
            }
        }
    }

    private func exportBackup() {
        isExporting = true
        // Placeholder — in production this would call into KMP shared SyncRepository
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
            let payload: [String: Any] = [
                "version": 1,
                "exportedAt": Int(Date().timeIntervalSince1970 * 1000),
                "deviceName": UIDevice.current.name,
                "addons": [],
                "preferences": [],
                "watchProgress": [],
                "iptvPlaylists": [],
                "iptvFavorites": []
            ]
            if let data = try? JSONSerialization.data(withJSONObject: payload, options: .prettyPrinted) {
                let tempURL = FileManager.default.temporaryDirectory
                    .appendingPathComponent("streamvault_backup.json")
                try? data.write(to: tempURL)
                exportURL = tempURL
                showExportSheet = true
                statusMessage = "Backup exported successfully"
            }
            isExporting = false
        }
    }

    private func importBackup(from url: URL) {
        isImporting = true
        guard url.startAccessingSecurityScopedResource() else {
            statusMessage = "Cannot access file"
            isImporting = false
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }

        do {
            let data = try Data(contentsOf: url)
            if let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
               let version = json["version"] as? Int, version >= 1 {
                // Placeholder — in production this would call into KMP shared SyncRepository
                statusMessage = "Backup imported successfully"
            } else {
                statusMessage = "Invalid backup file"
            }
        } catch {
            statusMessage = "Import failed: \(error.localizedDescription)"
        }
        isImporting = false
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

// Placeholder views for settings sub-screens
struct DebridSettingsView: View {
    var body: some View {
        Text("Cloud Service Settings")
            .navigationTitle("Cloud Service")
    }
}

struct TraktSettingsView: View {
    var body: some View {
        Text("Trakt Settings")
            .navigationTitle("Trakt.tv")
    }
}

struct AddonSettingsView: View {
    var body: some View {
        Text("Content Source Settings")
            .navigationTitle("Content Sources")
    }
}

struct PaywallScreen: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text("StreamVault Pro")
                    .font(.largeTitle)
                    .fontWeight(.bold)

                Text("Stream, download, and watch IPTV with a Pro subscription")
                    .font(.body)
                    .foregroundColor(.secondary)
                    .multilineTextAlignment(.center)

                // Feature comparison
                VStack(spacing: 12) {
                    FeatureRow(feature: "Search & Browse", free: true, pro: true)
                    FeatureRow(feature: "Stream Playback", free: false, pro: true)
                    FeatureRow(feature: "Downloads", free: false, pro: true)
                    FeatureRow(feature: "IPTV / Live TV", free: false, pro: true)
                    FeatureRow(feature: "Multi-Cloud", free: false, pro: true)
                }
                .padding()

                // Pricing
                VStack(spacing: 12) {
                    PricingCard(title: "Monthly", price: "$4.99/mo", description: "Cancel anytime", highlighted: false)
                    PricingCard(title: "Lifetime", price: "$29.99", description: "One-time payment", highlighted: true)
                }

                Button(action: {}) {
                    Text("Subscribe Now")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color.blue)
                        .foregroundColor(.white)
                        .cornerRadius(12)
                }

                Button("Restore Purchase") {}
                    .foregroundColor(.secondary)
            }
            .padding()
        }
        .navigationTitle("Pro")
    }
}

struct FeatureRow: View {
    let feature: String
    let free: Bool
    let pro: Bool

    var body: some View {
        HStack {
            Text(feature)
                .frame(maxWidth: .infinity, alignment: .leading)
            Image(systemName: free ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundColor(free ? .green : .red)
                .frame(width: 50)
            Image(systemName: pro ? "checkmark.circle.fill" : "xmark.circle.fill")
                .foregroundColor(pro ? .green : .red)
                .frame(width: 50)
        }
    }
}

struct PricingCard: View {
    let title: String
    let price: String
    let description: String
    let highlighted: Bool

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(title).fontWeight(.bold)
                    if highlighted {
                        Text("BEST VALUE")
                            .font(.caption2)
                            .padding(.horizontal, 8)
                            .padding(.vertical, 2)
                            .background(Color.blue)
                            .foregroundColor(.white)
                            .cornerRadius(4)
                    }
                }
                Text(description)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
            Spacer()
            Text(price)
                .font(.title2)
                .fontWeight(.bold)
                .foregroundColor(.blue)
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .cornerRadius(12)
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(highlighted ? Color.blue : Color.clear, lineWidth: 2)
        )
    }
}

struct PlaybackSettingsView: View {
    @State private var autoPlayEnabled = true
    @State private var autoPlayNextEpisodeEnabled = true
    @State private var codecPreference = 0  // 0=HEVC Preferred, 1=H.264 Only, 2=Any
    @State private var hdrMode = 2          // 0=Prefer HDR, 1=SDR Only, 2=Auto

    private let codecOptions = ["HEVC Preferred", "H.264 Only", "Any"]
    private let hdrOptions = ["Prefer HDR", "SDR Only", "Auto"]

    var body: some View {
        List {
            Section {
                Toggle("Auto-Play Best Stream", isOn: $autoPlayEnabled)
                Toggle("Auto-Play Next Episode", isOn: $autoPlayNextEpisodeEnabled)
            } footer: {
                Text("Auto-play best stream picks the highest-scored stream. Auto-play next episode continues to the next episode when playback finishes.")
            }

            Section("Codec Preference") {
                Picker("Codec", selection: $codecPreference) {
                    ForEach(0..<codecOptions.count, id: \.self) { index in
                        Text(codecOptions[index]).tag(index)
                    }
                }
                .pickerStyle(.segmented)
            }

            Section("HDR Mode") {
                Picker("HDR", selection: $hdrMode) {
                    ForEach(0..<hdrOptions.count, id: \.self) { index in
                        Text(hdrOptions[index]).tag(index)
                    }
                }
                .pickerStyle(.segmented)
            }
        }
        .navigationTitle("Playback")
    }
}

struct DownloadsScreen: View {
    var body: some View {
        Text("Downloads")
            .navigationTitle("Downloads")
    }
}
