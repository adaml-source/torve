import SwiftUI
import shared

/// Settings → "Send credentials to another device" surface.
///
/// Mirrors the Android sender screen: scan a QR (camera-permission
/// gated, hidden on devices with no rear camera), or paste the receive
/// code by hand. Pick categories. Generate the sealed envelope. Show
/// the relay-delivery state. Manual sealed-code copy stays reachable
/// always — no relay outcome leaves the user without a path forward.
struct SecretsTransferSendScreen: View {

    @StateObject private var wrapper = SecretsTransferSenderViewModelWrapper()

    @State private var scannerOpen = false
    @State private var scannerStatus: QrScannerView.Unavailable?

    private let hasCamera = deviceHasAnyCamera()

    var body: some View {
        Form {
            instructionsSection
            if hasCamera { scanSection }
            pasteSection
            categorySection
            generateSection
            statusSection
        }
        .navigationTitle("Send credentials")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Sections

    private var instructionsSection: some View {
        Section {
            Text("Open Receive credentials on the other Torve device, then scan its QR or paste the session string here.")
                .font(.subheadline)
                .foregroundColor(.secondary)
            TransferBanner(.info, "End-to-end encrypted",
                           "Credentials are sealed with a one-time key derived from the receiver's QR. The Torve backend only forwards the envelope — it can't read the contents. Manual paste is the same sealed envelope shown as text.")
        }
    }

    private var scanSection: some View {
        Section(header: Text("Scan QR from receiving device")) {
            if let denied = scannerStatus, denied == .permissionDenied {
                Text("Camera permission denied. Use paste below.")
                    .font(.footnote)
                    .foregroundColor(.red)
            }
            if scannerOpen {
                QrScannerView(
                    onQrDetected: { scanned in
                        scannerOpen = false
                        wrapper.updateReceiverSessionString(scanned)
                    },
                    onUnavailable: { reason in
                        scannerOpen = false
                        scannerStatus = reason
                    }
                )
                .frame(height: 280)
                .cornerRadius(12)
                Button("Close camera") { scannerOpen = false }
            } else {
                Button("Open camera") {
                    scannerStatus = nil
                    scannerOpen = true
                }
            }
        }
    }

    private var pasteSection: some View {
        Section(header: Text("Or paste the receive code")) {
            TextEditor(text: Binding(
                get: { wrapper.receiverSessionString },
                set: { wrapper.updateReceiverSessionString($0) }
            ))
            .frame(minHeight: 80)
            .font(.system(.footnote, design: .monospaced))
        }
    }

    private var categorySection: some View {
        Section(header: Text("Choose what to send")) {
            ForEach(TransferSecretCatalog.shared.specs as! [TransferCategorySpec], id: \.category) { spec in
                Toggle(isOn: Binding(
                    get: { wrapper.selectedCategories.contains(spec.category) },
                    set: { wrapper.setCategoryEnabled(spec.category, $0) }
                )) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(spec.title).font(.body)
                        Text(spec.description).font(.caption).foregroundColor(.secondary)
                    }
                }
            }
        }
    }

    private var generateSection: some View {
        Section {
            Button {
                Task { await wrapper.generateEnvelope() }
            } label: {
                if wrapper.status is SenderStatus.Generating {
                    HStack(spacing: 8) {
                        ProgressView()
                        Text("Generating…")
                    }
                } else {
                    Text("Generate sealed code")
                }
            }
            .disabled(wrapper.status is SenderStatus.Generating)
        }
    }

    @ViewBuilder
    private var statusSection: some View {
        switch wrapper.status {
        case is SenderStatus.Idle:
            EmptyView()
        case is SenderStatus.Generating:
            Section { TransferBanner(.info, "Sealing credentials", "Credentials stay local while the encrypted envelope is generated.") }
        case let err as SenderStatus.Error:
            Section { TransferBanner(.error, "Could not generate code", err.message) }
        case let ready as SenderStatus.Ready:
            readyBlock(ready)
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func readyBlock(_ status: SenderStatus.Ready) -> some View {
        let included = (status.includedCategories as! [SecretCategory])
            .map { TransferSecretCatalog.shared.titleFor(category: $0) }
            .joined(separator: ", ")
        let missing = (status.categoriesWithoutSecrets as! [SecretCategory])
            .map { TransferSecretCatalog.shared.titleFor(category: $0) }
        let missingCompanion = (status.categoriesMissingCompanionConfig as! [SecretCategory])
            .map { TransferSecretCatalog.shared.titleFor(category: $0) }

        Section {
            TransferBanner(
                .success,
                "Sealed code ready",
                buildSealedSummary(
                    secretCount: Int(status.secretCount),
                    configCount: Int(status.configCount),
                    included: included,
                    missing: missing
                )
            )

            if !missingCompanion.isEmpty {
                TransferBanner(
                    .warning,
                    "Companion config missing",
                    "Tokens for \(missingCompanion.joined(separator: ", ")) are included, but their server URL is not set on this device. The receiver will need to fill it in manually."
                )
            }

            relayBanner(status.relayDelivery)
        }

        Section(header: Text("Manual fallback — sealed code")) {
            Text(status.envelopeJson)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .lineLimit(8)
            Button("Copy sealed code") {
                UIPasteboard.general.string = status.envelopeJson
            }
        }
    }

    @ViewBuilder
    private func relayBanner(_ delivery: RelayDeliveryState) -> some View {
        switch delivery {
        case is RelayDeliveryState.NotAttempted:
            EmptyView()
        case is RelayDeliveryState.Posting:
            TransferBanner(.info, "Delivering through relay…",
                           "Posting the sealed envelope to the Torve backend so the receiver can pull it automatically.")
        case is RelayDeliveryState.Delivered:
            TransferBanner(.success, "Delivered to the receiver",
                           "The sealed envelope is on the relay; the receiver will import on its next poll.")
        case let failed as RelayDeliveryState.Failed:
            TransferBanner(.warning, "Relay delivery failed", failed.reason)
        default:
            EmptyView()
        }
    }

    private func buildSealedSummary(
        secretCount: Int,
        configCount: Int,
        included: String,
        missing: [String]
    ) -> String {
        var s = "Encrypted \(secretCount) credential record" + (secretCount != 1 ? "s" : "")
        if configCount > 0 {
            s += " + \(configCount) config record" + (configCount != 1 ? "s" : "")
        }
        s += " for: " + (included.isEmpty ? "selected categories" : included)
        if !missing.isEmpty {
            s += ". No local credentials found for: " + missing.joined(separator: ", ")
        }
        return s + "."
    }
}
