import SwiftUI
import shared

/// Settings card for the backend-managed NzbDAV integration. Mirrors the
/// Android `NzbdavSetupSection` exactly: base URL + API key fields,
/// Test / Save / Remove buttons with per-action busy states, and a
/// neutral status line. All copy is hard-coded here against the shared
/// `NzbdavStatus` / `NzbdavTestResult` enums — no backend reason
/// strings, version strings, or transport details ever surface.
struct NzbdavSetupCard: View {
    @StateObject private var wrapper = NzbdavSetupViewModelWrapper()

    var body: some View {
        Section("NzbDAV") {
            Text("Torve uses your NzbDAV instance to stream Usenet sources. Connection is managed by the Torve backend.")
                .font(.caption)
                .foregroundColor(.secondary)

            statusLine

            VStack(alignment: .leading, spacing: 6) {
                Text("Base URL").font(.caption).foregroundColor(.secondary)
                TextField("https://nzbdav.example.com", text: Binding(
                    get: { wrapper.state.baseUrl },
                    set: { wrapper.updateBaseUrl($0) }
                ))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("API key").font(.caption).foregroundColor(.secondary)
                SecureField("API key", text: Binding(
                    get: { wrapper.state.apiKey },
                    set: { wrapper.updateApiKey($0) }
                ))
            }

            if let result = wrapper.state.lastTestResult {
                lastActionLine(for: result)
            }

            HStack(spacing: 8) {
                Button {
                    wrapper.test()
                } label: {
                    HStack(spacing: 6) {
                        if wrapper.state.isTesting {
                            ProgressView().scaleEffect(0.7)
                        }
                        Text("Test connection")
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)
                .disabled(actionsDisabled)

                Button {
                    wrapper.save()
                } label: {
                    HStack(spacing: 6) {
                        if wrapper.state.isSaving {
                            ProgressView().scaleEffect(0.7)
                        }
                        Text("Save")
                    }
                }
                .buttonStyle(.borderedProminent)
                .tint(.green)
                .disabled(actionsDisabled)

                Spacer()

                Button(role: .destructive) {
                    wrapper.remove()
                } label: {
                    HStack(spacing: 6) {
                        if wrapper.state.isRemoving {
                            ProgressView().scaleEffect(0.7)
                        }
                        Text("Remove")
                    }
                }
                .buttonStyle(.bordered)
                .disabled(removeDisabled)
            }
            .padding(.top, 4)
        }
    }

    private var actionsDisabled: Bool {
        wrapper.state.isTesting || wrapper.state.isSaving || wrapper.state.isRemoving
    }

    private var removeDisabled: Bool {
        actionsDisabled || (wrapper.state.status is NzbdavStatusNotConfigured)
    }

    @ViewBuilder
    private var statusLine: some View {
        let status = wrapper.state.status
        if status is NzbdavStatusLoading {
            Text("Checking status…").font(.caption).foregroundColor(.secondary)
        } else if status is NzbdavStatusNotConfigured {
            Text("Not configured").font(.caption).foregroundColor(.secondary)
        } else if let connected = status as? NzbdavStatusConnected {
            if connected.degraded {
                Text("Connected, but backend reported degraded status")
                    .font(.caption)
                    .foregroundColor(.orange)
                    .fontWeight(.medium)
            } else {
                Text("Connected")
                    .font(.caption)
                    .foregroundColor(.green)
                    .fontWeight(.medium)
            }
        } else if status is NzbdavStatusConnectionFailed {
            Text("Connection failed")
                .font(.caption)
                .foregroundColor(.red)
                .fontWeight(.medium)
        }
    }

    @ViewBuilder
    private func lastActionLine(for result: NzbdavTestResult) -> some View {
        // Switch on the sealed-interface variants; copy is constrained
        // to neutral strings that match the Android resource keys.
        if result is NzbdavTestResultOk {
            Text("Connection test succeeded").font(.caption).foregroundColor(.green)
        } else if result is NzbdavTestResultDegradedOk {
            Text("Connection test succeeded, backend reported degraded status")
                .font(.caption).foregroundColor(.orange)
        } else if result is NzbdavTestResultFailed {
            Text("Connection test failed").font(.caption).foregroundColor(.red)
        } else if result is NzbdavTestResultMissingFields {
            Text("Base URL and API key are required").font(.caption).foregroundColor(.red)
        } else if result is NzbdavTestResultSaved {
            Text("Saved").font(.caption).foregroundColor(.green)
        } else if result is NzbdavTestResultRemoved {
            Text("Removed").font(.caption).foregroundColor(.secondary)
        }
    }
}
