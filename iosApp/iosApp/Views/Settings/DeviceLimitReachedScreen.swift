import SwiftUI
import shared

struct DeviceLimitReachedScreen: View {
    @Environment(AppRouter.self) private var router
    @StateObject private var wrapper = DeviceGovernanceViewModelWrapper()
    @State private var deviceToRemove: ManagedDeviceDto? = nil

    var body: some View {
        List {
            headerSection

            Section("Active Devices") {
                ForEach(wrapper.state.devices.filter { ($0 as! ManagedDeviceDto).is_active }, id: \.id) { raw in
                    let device = raw as! ManagedDeviceDto
                    ActiveDeviceRow(device: device, onRemove: { deviceToRemove = device })
                }
            }

            Section {
                Text("To activate this device, remove one inactive or unused device from your account.")
                    .font(.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
        }
        .navigationTitle("Device Limit Reached")
        .onAppear { wrapper.fetchDevices() }
        .onChange(of: wrapper.state.premiumAccess) { _, newValue in
            if newValue {
                router.popToRoot()
            }
        }
        .onChange(of: wrapper.state.activateSuccess) { _, newValue in
            if newValue {
                router.popToRoot()
            }
        }
        .confirmationDialog(
            "Remove Device",
            isPresented: Binding(
                get: { deviceToRemove != nil },
                set: { if !$0 { deviceToRemove = nil } }
            ),
            presenting: deviceToRemove
        ) { device in
            Button("Remove & Activate", role: .destructive) {
                wrapper.removeDevice(deviceId: device.id)
                deviceToRemove = nil
            }
            Button("Cancel", role: .cancel) { deviceToRemove = nil }
        } message: { device in
            Text("Remove \"\(device.device_name)\"? Your current device will be activated for this account.")
        }
    }

    private var headerSection: some View {
        Section {
            VStack(spacing: 16) {
                Image(systemName: "desktopcomputer.and.arrow.down")
                    .font(.system(size: 40))
                    .foregroundColor(SVColor.amber)

                Text(wrapper.state.deviceLimitKnown
                    ? "Your account is already active on \(wrapper.state.maxActiveDevices) devices. Remove one device to activate this device."
                    : "Checking your account device limit. Remove one active device to activate this device.")
                    .font(.subheadline)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)

                if wrapper.state.activeDeviceCountKnown && wrapper.state.deviceLimitKnown {
                    Text("\(wrapper.state.activeDeviceCount) of \(wrapper.state.maxActiveDevices) devices active")
                        .font(.headline)
                        .foregroundColor(SVColor.amber)
                }
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }
}

private struct ActiveDeviceRow: View {
    let device: ManagedDeviceDto
    let onRemove: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: iconName)
                .font(.title3)
                .foregroundColor(SVColor.amber)
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
            }

            Spacer()

            if !device.is_current {
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
