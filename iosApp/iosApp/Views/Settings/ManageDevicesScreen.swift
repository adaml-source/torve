import SwiftUI
import shared

struct ManageDevicesScreen: View {
    @StateObject private var wrapper = DeviceGovernanceViewModelWrapper()
    @State private var deviceToRemove: ManagedDeviceDto? = nil

    var body: some View {
        List {
            headerSection

            Section("Active Devices") {
                ForEach(wrapper.state.devices.filter { ($0 as! ManagedDeviceDto).is_active }, id: \.id) { raw in
                    let device = raw as! ManagedDeviceDto
                    DeviceRow(device: device, onRemove: { deviceToRemove = device })
                }
            }

            let inactiveDevices = wrapper.state.devices.filter { !($0 as! ManagedDeviceDto).is_active }
            if !inactiveDevices.isEmpty {
                Section("Inactive") {
                    ForEach(inactiveDevices, id: \.id) { raw in
                        let device = raw as! ManagedDeviceDto
                        DeviceRow(device: device, onRemove: nil)
                    }
                }
            }

            footerSection
        }
        .navigationTitle("Manage Devices")
        .onAppear { wrapper.fetchDevices() }
        .refreshable { wrapper.fetchDevices() }
        .confirmationDialog(
            "Remove Device",
            isPresented: Binding(
                get: { deviceToRemove != nil },
                set: { if !$0 { deviceToRemove = nil } }
            ),
            presenting: deviceToRemove
        ) { device in
            Button("Remove", role: .destructive) {
                wrapper.removeDevice(deviceId: device.id)
                deviceToRemove = nil
            }
            Button("Cancel", role: .cancel) { deviceToRemove = nil }
        } message: { device in
            Text("Remove \"\(device.device_name)\" from your Torve Pro account? This will free a device slot.")
        }
    }

    private var headerSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text("Your Torve Pro account can be active on up to \(wrapper.state.maxActiveDevices) devices at a time.")
                    .font(.subheadline)
                    .foregroundColor(SVColor.onSurfaceVariant)

                Text("\(wrapper.state.activeDeviceCount) of \(wrapper.state.maxActiveDevices) devices active")
                    .font(.headline)
                    .foregroundColor(SVColor.amber)
            }
        }
    }

    private var footerSection: some View {
        Section {
            Text("Removing a device frees a slot for another device.")
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
            Text("Inactive devices may stop counting automatically after extended inactivity.")
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
        }
    }
}

private struct DeviceRow: View {
    let device: ManagedDeviceDto
    let onRemove: (() -> Void)?

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.title3)
                .foregroundColor(device.is_active ? SVColor.amber : SVColor.onSurfaceVariant)
                .frame(width: 32)

            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(device.device_name)
                        .fontWeight(.medium)
                    if device.is_current {
                        Text("THIS DEVICE")
                            .font(.caption2)
                            .fontWeight(.bold)
                            .foregroundColor(SVColor.amber)
                    }
                }
                Text("\(device.platform) · \(device.device_type)")
                    .font(.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
                if device.is_active {
                    Text("Active")
                        .font(.caption2)
                        .foregroundColor(SVColor.emerald)
                } else if device.removed_at != nil {
                    Text("Removed")
                        .font(.caption2)
                        .foregroundColor(SVColor.error)
                }
            }

            Spacer()

            if device.is_active && !device.is_current, let onRemove {
                Button(action: onRemove) {
                    Image(systemName: "trash")
                        .foregroundColor(SVColor.error)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.vertical, 4)
    }

    private var iconName: String {
        switch device.device_type {
        case "tv": return "tv"
        case "tablet": return "ipad"
        default: return "iphone"
        }
    }
}
