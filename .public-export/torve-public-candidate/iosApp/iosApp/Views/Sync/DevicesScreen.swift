import SwiftUI
import shared

struct DevicesScreen: View {
    @StateObject private var syncCoordinator = iOSSyncCoordinator()

    @State private var showRemoveAllConfirmation = false

    var body: some View {
        List {
            Section {
                if syncCoordinator.state.devices.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "laptopcomputer.and.iphone")
                            .font(.title2)
                            .foregroundColor(.secondary)
                        Text("No devices registered")
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                } else {
                    ForEach(syncCoordinator.state.devices) { device in
                        deviceRow(device)
                    }
                }
            } header: {
                Text("Registered Devices")
            } footer: {
                Text("Devices that have signed in to your Torve account. Synced data is shared across all registered devices.")
            }

            Section {
                Button {
                    syncCoordinator.refreshDevices()
                } label: {
                    HStack {
                        Label("Refresh Devices", systemImage: "arrow.clockwise")
                        Spacer()
                        if syncCoordinator.state.isLoading {
                            ProgressView()
                        }
                    }
                }
                .disabled(syncCoordinator.state.isLoading)
            }

            if syncCoordinator.state.devices.count > 1 {
                Section {
                    Button("Remove All Other Devices", role: .destructive) {
                        showRemoveAllConfirmation = true
                    }
                } footer: {
                    Text("Remove all devices except this one. Other devices will need to sign in again to sync.")
                }
            }

            if let error = syncCoordinator.state.error {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(SVColor.error)
                        .font(.caption)
                }
            }
        }
        .navigationTitle("Devices")
        .onAppear {
            syncCoordinator.refreshDevices()
        }
        .confirmationDialog("Remove All Other Devices?", isPresented: $showRemoveAllConfirmation) {
            Button("Remove", role: .destructive) {
                removeOtherDevices()
            }
        } message: {
            Text("Other devices will need to sign in again to sync.")
        }
    }

    private func deviceRow(_ device: SyncDeviceDto) -> some View {
        let isCurrent = device.installationId == UIDevice.current.identifierForVendor?.uuidString

        return HStack(spacing: 12) {
            Image(systemName: iconForPlatform(device.platform, deviceType: device.deviceType))
                .font(.title2)
                .foregroundColor(isCurrent ? SVColor.amber : .secondary)
                .frame(width: 36)

            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(device.deviceName)
                        .fontWeight(.medium)
                    if isCurrent {
                        Text("This Device")
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(SVColor.amber.opacity(0.2))
                            .foregroundColor(SVColor.amber)
                            .clipShape(Capsule())
                    }
                    if device.revokedAt != nil {
                        Text("Revoked")
                            .font(.caption2)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(SVColor.error.opacity(0.2))
                            .foregroundColor(SVColor.error)
                            .clipShape(Capsule())
                    }
                }
                HStack(spacing: 8) {
                    Text(device.platform)
                        .font(.caption)
                        .foregroundColor(.secondary)
                    Text(device.deviceType)
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                Text("Last seen: \(formatLastSeen(device.lastSeenAt))")
                    .font(.caption2)
                    .foregroundColor(.secondary)
            }
        }
        .padding(.vertical, 4)
    }

    private func iconForPlatform(_ platform: String, deviceType: String) -> String {
        let p = platform.lowercased()
        let d = deviceType.lowercased()
        if p.contains("ios") || d.contains("iphone") { return "iphone" }
        if d.contains("ipad") { return "ipad" }
        if p.contains("android") && d.contains("tv") { return "tv" }
        if p.contains("android") { return "apps.iphone" }
        if p.contains("mac") { return "laptopcomputer" }
        if d.contains("tv") { return "appletv" }
        return "laptopcomputer.and.iphone"
    }

    private func formatLastSeen(_ isoString: String) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = formatter.date(from: isoString) {
            let relative = RelativeDateTimeFormatter()
            relative.unitsStyle = .abbreviated
            return relative.localizedString(for: date, relativeTo: Date())
        }
        // Try without fractional seconds
        formatter.formatOptions = [.withInternetDateTime]
        if let date = formatter.date(from: isoString) {
            let relative = RelativeDateTimeFormatter()
            relative.unitsStyle = .abbreviated
            return relative.localizedString(for: date, relativeTo: Date())
        }
        return isoString
    }

    private func removeOtherDevices() {
        // Keep only the current device
        let selfId = UIDevice.current.identifierForVendor?.uuidString ?? ""
        let otherDevices = syncCoordinator.state.devices.filter { $0.installationId != selfId }
        for device in otherDevices {
            // Revoke through coordinator if API is available
            // For now just refresh to show current state
        }
        syncCoordinator.refreshDevices()
    }
}
