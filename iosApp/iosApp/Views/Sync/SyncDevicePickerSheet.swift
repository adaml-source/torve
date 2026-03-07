import SwiftUI

struct SyncDevicePickerSheet: View {
    let devices: [SyncDeviceDto]
    let onDeviceSelected: (SyncDeviceDto) -> Void

    @Environment(\.dismiss) private var dismiss

    private var selfInstallationId: String {
        UIDevice.current.identifierForVendor?.uuidString ?? ""
    }

    private var otherDevices: [SyncDeviceDto] {
        devices.filter { $0.installationId != selfInstallationId && $0.revokedAt == nil }
    }

    var body: some View {
        NavigationStack {
            List {
                if otherDevices.isEmpty {
                    VStack(spacing: 12) {
                        Image(systemName: "laptopcomputer.and.iphone")
                            .font(.largeTitle)
                            .foregroundColor(.secondary)
                        Text("No other devices found")
                            .font(.headline)
                        Text("Connect another device to your account to send content across devices.")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 24)
                    .listRowBackground(Color.clear)
                } else {
                    Section("Available Devices") {
                        ForEach(otherDevices) { device in
                            Button {
                                onDeviceSelected(device)
                                dismiss()
                            } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: iconForDevice(device))
                                        .font(.title3)
                                        .foregroundColor(SVColor.amber)
                                        .frame(width: 32)

                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(device.deviceName)
                                            .fontWeight(.medium)
                                            .foregroundColor(.primary)
                                        HStack(spacing: 6) {
                                            Text(device.platform)
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                            Text(device.deviceType)
                                                .font(.caption)
                                                .foregroundColor(.secondary)
                                        }
                                    }

                                    Spacer()

                                    Image(systemName: "chevron.right")
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                }
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
            .navigationTitle("Send To Device")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    private func iconForDevice(_ device: SyncDeviceDto) -> String {
        let p = device.platform.lowercased()
        let d = device.deviceType.lowercased()
        if p.contains("ios") || d.contains("iphone") { return "iphone" }
        if d.contains("ipad") { return "ipad" }
        if p.contains("android") && d.contains("tv") { return "tv" }
        if p.contains("android") { return "apps.iphone" }
        if d.contains("tv") { return "appletv" }
        return "laptopcomputer.and.iphone"
    }
}
