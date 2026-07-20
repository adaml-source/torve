import SwiftUI
import shared

/// Read-only credential-transfer diagnostics surface for iOS.
///
/// All values come from `TransferDiagnosticsCollector` via the shared
/// snapshot type, which is closed-shape by construction (booleans,
/// closed enums, bucketed counts, epoch_ms). Backend bodies, raw error
/// strings, session strings, envelopes, and access tokens are
/// structurally unable to reach this view.
struct TransferDiagnosticsScreen: View {

    @StateObject private var wrapper = TransferDiagnosticsWrapper()

    var body: some View {
        Form {
            redactionSection
            statusSection
            lastAttemptSection
            actionsSection
        }
        .navigationTitle("Transfer diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .task { await wrapper.refresh() }
    }

    // MARK: - Sections

    private var redactionSection: some View {
        Section {
            TransferBanner(
                .info,
                "Read-only diagnostics",
                "Diagnostics never include credentials, envelope JSON, QR payloads, access tokens, or private keys. Every value below is a closed enum or a bucketed count."
            )
        }
    }

    @ViewBuilder
    private var statusSection: some View {
        if let snap = wrapper.snapshot {
            Section(header: Text("Status")) {
                StatusRow(
                    label: "Crypto engine",
                    value: snap.cryptoEngineAvailable ? "available" : "unavailable",
                    ok: snap.cryptoEngineAvailable
                )
                StatusRow(
                    label: "Signed in",
                    value: snap.signedIn ? "yes" : "no",
                    ok: snap.signedIn
                )
                StatusRow(
                    label: "Backend relay",
                    value: TransferDiagnosticsWrapper.relayLabel(snap.relayReachable),
                    ok: TransferDiagnosticsWrapper.relayPillIsOK(snap.relayReachable)
                )
            }
        } else {
            Section { Text("Loading…").foregroundColor(.secondary) }
        }
    }

    @ViewBuilder
    private var lastAttemptSection: some View {
        if let snap = wrapper.snapshot {
            Section(header: Text("Last transfer attempt")) {
                if let last = snap.lastAttempt {
                    LabeledLine("Role", TransferDiagnosticsWrapper.roleLabel(last.role))
                    LabeledLine("Outcome", TransferDiagnosticsWrapper.outcomeLabel(last.outcome))
                    if let err = last.errorCategory {
                        LabeledLine("Reason", TransferDiagnosticsWrapper.errorCategoryLabel(err))
                    }
                    LabeledLine("Timestamp", "epoch_ms=\(Int64(last.recordedAtEpochMs))")
                } else {
                    Text("No attempt recorded yet on this device.")
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private var actionsSection: some View {
        Section {
            Button {
                Task { await wrapper.probeRelay() }
            } label: {
                if wrapper.isProbing {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Probing relay…")
                    }
                } else {
                    Text("Probe relay now")
                }
            }
            .disabled(wrapper.isProbing)

            Button("Refresh") {
                Task { await wrapper.refresh() }
            }
        }
    }
}

private struct StatusRow: View {
    let label: String
    let value: String
    let ok: Bool

    var body: some View {
        HStack {
            Text(label)
            Spacer()
            Text(value)
                .font(.callout.weight(.semibold))
                .foregroundColor(ok ? .green : .orange)
                .padding(.horizontal, 10)
                .padding(.vertical, 4)
                .background((ok ? Color.green : Color.orange).opacity(0.18))
                .clipShape(Capsule())
        }
    }
}

private struct LabeledLine: View {
    let label: String
    let value: String

    init(_ label: String, _ value: String) {
        self.label = label
        self.value = value
    }

    var body: some View {
        HStack(alignment: .top) {
            Text(label).foregroundColor(.secondary)
            Spacer()
            Text(value).font(.callout.monospacedDigit())
        }
    }
}
