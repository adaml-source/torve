import SwiftUI
import UniformTypeIdentifiers

struct ShareSheet: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

struct BackupSyncScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()
    @State private var iCloudSyncEnabled = false
    @State private var showExportSheet = false
    @State private var showImportPicker = false
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
                        if wrapper.state.isSyncing {
                            ProgressView()
                        }
                    }
                }
                .disabled(wrapper.state.isSyncing)

                Button {
                    showImportPicker = true
                } label: {
                    Label("Import Backup", systemImage: "square.and.arrow.down")
                }
                .disabled(wrapper.state.isSyncing)
            } footer: {
                Text("Export your extensions, preferences, watch progress, and channel favorites to a file. API keys and tokens are never included.")
            }

            if let lastSync = wrapper.state.lastSyncTime?.int64Value {
                Section {
                    HStack {
                        Image(systemName: "clock")
                            .foregroundColor(.secondary)
                        Text("Last sync: \(formattedDate(from: lastSync))")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            if let error = wrapper.state.syncError {
                Section {
                    HStack {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .foregroundColor(SVColor.error)
                        Text(error)
                            .font(.caption)
                            .foregroundColor(SVColor.error)
                    }
                }
            }

            if let success = wrapper.state.syncSuccess {
                Section {
                    HStack {
                        Image(systemName: "checkmark.circle.fill")
                            .foregroundColor(SVColor.emerald)
                        Text(success)
                            .font(.caption)
                            .foregroundColor(SVColor.emerald)
                    }
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
                // fileImporter cancelled or errored; syncError will surface from VM if needed
                break
            }
        }
    }

    private func exportBackup() {
        wrapper.viewModel.exportBackup { jsonStr in
            let tempURL = FileManager.default.temporaryDirectory
                .appendingPathComponent("torve_backup.json")
            do {
                try jsonStr.write(to: tempURL, atomically: true, encoding: .utf8)
                DispatchQueue.main.async {
                    exportURL = tempURL
                    showExportSheet = true
                }
            } catch {
                // Write failure is unlikely for temp dir; VM already cleared isSyncing
            }
        }
    }

    private func importBackup(from url: URL) {
        guard url.startAccessingSecurityScopedResource() else { return }
        defer { url.stopAccessingSecurityScopedResource() }

        do {
            let jsonStr = try String(contentsOf: url, encoding: .utf8)
            wrapper.viewModel.importBackup(jsonStr: jsonStr)
        } catch {
            // File read failed; the VM won't be notified so nothing else to do
        }
    }

    private func formattedDate(from epochMillis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(epochMillis) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
