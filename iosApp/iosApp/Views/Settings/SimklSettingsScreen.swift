import SwiftUI
import shared

struct SimklSettingsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    var body: some View {
        List {
            if wrapper.state.simklConnected {
                connectedSection
                disconnectSection
            } else {
                connectSection
            }
        }
        .navigationTitle("SIMKL")
    }

    // MARK: - Connected

    @ViewBuilder
    private var connectedSection: some View {
        Section("Account") {
            Label("Connected", systemImage: "checkmark.circle.fill")
                .foregroundColor(SVColor.emerald)

            if let user = wrapper.state.simklUser {
                HStack {
                    Text("Username")
                    Spacer()
                    Text(user.name)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private var disconnectSection: some View {
        Section {
            Button("Disconnect SIMKL", role: .destructive) {
                wrapper.viewModel.disconnectSimkl()
            }
        }
    }

    // MARK: - Not Connected

    @ViewBuilder
    private var connectSection: some View {
        Section {
            if let deviceCode = wrapper.state.simklDeviceCode {
                VStack(alignment: .leading, spacing: 12) {
                    Text("Go to **simkl.com/pin** and enter this code:")
                        .font(.subheadline)
                    Text(deviceCode.userCode)
                        .font(.system(.title, design: .monospaced))
                        .fontWeight(.bold)
                        .foregroundColor(SVColor.amber)
                    if wrapper.state.isPollingSimkl {
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
                    wrapper.viewModel.startSimklDeviceAuth()
                } label: {
                    Label("Connect with SIMKL", systemImage: "link")
                }
            }
        } footer: {
            Text("Connect your SIMKL account to track your anime, TV shows, and movies.")
        }

        if let error = wrapper.state.simklError {
            Section {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundColor(SVColor.error)
            }
        }
    }
}
