import SwiftUI
import shared

/// Settings → "Send credentials to another device" surface (iOS).
///
/// Mirrors the desktop and Android sender flow: three explicit steps,
/// scan-first when a camera exists, paste fallback always reachable,
/// privacy explainer + manual sealed-code paste demoted to collapsed
/// disclosures. All visible copy reads from `shared.TransferCopy` so
/// a copy edit lands on every platform with one PR.
struct SecretsTransferSendScreen: View {

    @StateObject private var wrapper = SecretsTransferSenderViewModelWrapper()

    @State private var scannerOpen = false
    @State private var scannerStatus: QrScannerView.Unavailable?
    @State private var categoriesExpanded = false
    @State private var privacyExpanded = false
    @State private var advancedExpanded = false

    private let hasCamera = deviceHasAnyCamera()

    private let copy = TransferCopy.shared

    var body: some View {
        Form {
            // Step 1 — get the receiver code
            step1Section
            if hasCamera { scanSection }
            pasteSection

            // Step 2 — choose what to send (collapsed by default)
            categoryDisclosureSection

            // Step 3 — generate
            generateSection
            statusSection

            // Disclosures: how this stays private + manual sealed-code
            privacyDisclosureSection
        }
        .navigationTitle("Send credentials")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Step 1

    private var step1Section: some View {
        Section {
            Text(copy.sEND_STEP1_HEADER)
                .font(.headline)
            Text(copy.sEND_STEP1_EXPLAINER)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }
    }

    private var scanSection: some View {
        Section(header: Text("Scan QR from receiving device")) {
            if let denied = scannerStatus, denied == .permissionDenied {
                Text(copy.sEND_CAMERA_DENIED)
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
        Section(header: Text(copy.sEND_RECEIVER_FIELD_LABEL)) {
            TextEditor(text: Binding(
                get: { wrapper.receiverSessionString },
                set: { wrapper.updateReceiverSessionString($0) }
            ))
            .frame(minHeight: 80)
            .font(.system(.footnote, design: .monospaced))
            // Empty-state hint — tells the user where the receiver code
            // actually comes from. Without this, "paste here" is opaque.
            if wrapper.receiverSessionString.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Text(copy.sEND_RECEIVER_EMPTY_HINT)
                    .font(.footnote)
                    .foregroundColor(.secondary)
            } else {
                Text(copy.sEND_RECEIVER_FIELD_PLACEHOLDER)
                    .font(.footnote)
                    .foregroundColor(.secondary)
            }
        }
    }

    // MARK: - Step 2 — collapsed by default

    private var categoryDisclosureSection: some View {
        Section {
            DisclosureGroup(isExpanded: $categoriesExpanded) {
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
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(copy.sEND_STEP2_HEADER).font(.headline)
                    Text(selectedCategoriesSummary)
                        .font(.footnote)
                        .foregroundColor(.secondary)
                }
            }
        }
    }

    private var selectedCategoriesSummary: String {
        let selected = wrapper.selectedCategories
        if selected.isEmpty {
            return "Nothing selected — tap to choose."
        }
        return (selected as! Set<SecretCategory>)
            .map { TransferSecretCatalog.shared.titleFor(category: $0) }
            .sorted()
            .joined(separator: ", ")
    }

    // MARK: - Step 3 — generate

    private var generateSection: some View {
        Section {
            Text(copy.sEND_STEP3_HEADER).font(.headline)
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

        // Manual sealed-code paste — Advanced disclosure (collapsed).
        Section {
            DisclosureGroup(isExpanded: $advancedExpanded) {
                Text(status.envelopeJson)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .lineLimit(8)
                Button("Copy sealed code") {
                    UIPasteboard.general.string = status.envelopeJson
                }
            } label: {
                Text(copy.sEND_ADVANCED_HEADER).font(.subheadline)
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
                           "Posting the encrypted bundle to the Torve backend so the receiver can pull it automatically.")
        case is RelayDeliveryState.Delivered:
            TransferBanner(.success, "Delivered to the receiver",
                           "The encrypted bundle is on the relay; the receiver will import on its next poll.")
        case let failed as RelayDeliveryState.Failed:
            TransferBanner(.warning, copy.sEND_RELAY_UNAVAILABLE, failed.reason)
        default:
            EmptyView()
        }
    }

    // MARK: - Privacy disclosure (collapsed)

    private var privacyDisclosureSection: some View {
        Section {
            DisclosureGroup(isExpanded: $privacyExpanded) {
                Text(copy.sEND_PRIVACY_DISCLOSURE_BODY)
                    .font(.footnote)
                    .foregroundColor(.secondary)
            } label: {
                Text(copy.sEND_PRIVACY_DISCLOSURE_HEADER).font(.subheadline)
            }
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
