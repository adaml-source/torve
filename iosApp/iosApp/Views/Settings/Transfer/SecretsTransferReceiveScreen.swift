import SwiftUI
import CoreImage.CIFilterBuiltins
import shared

/// Settings → "Receive credentials from another device" surface.
///
/// Generates an ephemeral X25519 key pair via the shared receiver VM,
/// renders the resulting handshake as a QR plus plaintext code, and
/// counts down to expiry. When the backend relay is reachable the
/// receiver auto-imports on the next poll; otherwise the manual sealed-
/// code paste field stays primary.
struct SecretsTransferReceiveScreen: View {

    @StateObject private var wrapper = SecretsTransferReceiverViewModelWrapper()

    @State private var advancedOpen: Bool = false
    @State private var pasteInput: String = ""

    var body: some View {
        Form {
            switch wrapper.state {
            case is ReceiverState.Idle:
                Section {
                    Text("Preparing a one-time handshake…")
                        .foregroundColor(.secondary)
                }
            case let active as ReceiverState.Active:
                activeBlock(active)
            case let imported as ReceiverState.Imported:
                Section {
                    TransferBanner(.success, "Credentials imported",
                                   describeImport(imported.result))
                }
            case is ReceiverState.Expired:
                expiredBlock
            default:
                EmptyView()
            }
        }
        .navigationTitle("Receive credentials")
        .navigationBarTitleDisplayMode(.inline)
        .task { await wrapper.start() }
        .onDisappear { wrapper.cancel() }
    }

    // MARK: - Active

    @ViewBuilder
    private func activeBlock(_ active: ReceiverState.Active) -> some View {
        let copy = TransferCopy.shared
        Section {
            // Phone receiver shows the desktop-style explainer (the
            // sender will be a desktop most of the time on iOS).
            Text(copy.rECEIVE_PRIMARY_EXPLAINER_DESKTOP)
                .font(.subheadline)
                .foregroundColor(.secondary)
        }

        Section {
            qrImage(for: active.sessionString)
                .frame(maxWidth: .infinity)
                .frame(height: 280)
                .background(Color.white)
                .cornerRadius(12)
            countdownChip(remaining: Int(active.remainingSeconds))
                .frame(maxWidth: .infinity, alignment: .center)
        }

        Section { relayBanner(active.relayStatus) }

        // Receiver code (formerly "Session string") — the short
        // human-typeable handle the sender pastes if they can't scan.
        Section(header: Text(copy.rECEIVE_SHORT_CODE_LABEL)) {
            Text(active.sessionString)
                .font(.system(.caption, design: .monospaced))
                .textSelection(.enabled)
                .lineLimit(6)
            Button("Copy receiver code") {
                UIPasteboard.general.string = active.sessionString
            }
        }

        // Manual sealed-code paste fallback — Advanced disclosure.
        // Always reachable, never the primary mental model.
        let relayRegistered = active.relayStatus is RelayStatus.Registered
        Section {
            if relayRegistered {
                Button(advancedOpen ? "Hide manual paste" : copy.rECEIVE_ADVANCED_HEADER) {
                    advancedOpen.toggle()
                    if !advancedOpen { pasteInput = "" }
                }
            }
            if advancedOpen || !relayRegistered {
                TextEditor(text: Binding(
                    get: { pasteInput },
                    set: { value in
                        pasteInput = value
                        wrapper.updateEnvelopeText(value)
                    }
                ))
                .frame(minHeight: 110)
                .font(.system(.caption, design: .monospaced))
                Button {
                    Task { await wrapper.acceptEnvelopeJson() }
                } label: {
                    if active.importing {
                        HStack(spacing: 8) { ProgressView(); Text("Importing…") }
                    } else {
                        Text("Import sealed code")
                    }
                }
                .disabled(active.importing)

                if let result = active.importResult {
                    importBanner(result)
                }
            }
        }
    }

    @ViewBuilder
    private func qrImage(for payload: String) -> some View {
        if let image = renderQrImage(payload) {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(12)
        } else {
            VStack {
                Text("QR rendering unavailable.")
                    .foregroundColor(.black)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private func renderQrImage(_ payload: String) -> UIImage? {
        guard !payload.isEmpty else { return nil }
        let filter = CIFilter.qrCodeGenerator()
        filter.setValue(Data(payload.utf8), forKey: "inputMessage")
        filter.setValue("M", forKey: "inputCorrectionLevel")
        guard let output = filter.outputImage else { return nil }

        let size: CGFloat = 280
        let scale = size / max(output.extent.width, output.extent.height)
        let transformed = output.transformed(by: CGAffineTransform(scaleX: scale, y: scale))
        guard let cgImage = CIContext().createCGImage(transformed, from: transformed.extent) else {
            return nil
        }
        return UIImage(cgImage: cgImage)
    }

    // MARK: - Sub-components

    private func countdownChip(remaining: Int) -> some View {
        let mm = max(0, remaining / 60)
        let ss = max(0, remaining % 60)
        let label = String(format: "Expires in %d:%02d", mm, ss)
        let color: Color = remaining <= 30 ? .red : remaining <= 120 ? .orange : .accentColor
        return Text(label)
            .font(.callout.weight(.semibold))
            .foregroundColor(color)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(color.opacity(0.18))
            .clipShape(Capsule())
    }

    @ViewBuilder
    private func relayBanner(_ status: RelayStatus) -> some View {
        switch status {
        case is RelayStatus.NotConfigured:
            EmptyView()
        case is RelayStatus.Registering:
            TransferBanner(.info, "Setting up auto-import…",
                           "Asking the Torve backend to forward a encrypted bundle to this device.")
        case is RelayStatus.Registered:
            TransferBanner(.success, "Auto-import is on",
                           "When the sender posts the encrypted bundle, this device imports it automatically. Manual paste stays available below.")
        case let unavailable as RelayStatus.Unavailable:
            TransferBanner(.warning, "Auto-import unavailable",
                           "\(unavailable.reason) Use the paste field below.")
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private func importBanner(_ result: TransferImportResult) -> some View {
        switch result {
        case let success as TransferImportResult.Success:
            TransferBanner(.success, "Credentials imported", describeImport(success))
        case let malformed as TransferImportResult.MalformedEnvelope:
            TransferBanner(.error, "Invalid sealed code", malformed.reason)
        case let decryptFailure as TransferImportResult.DecryptFailure:
            TransferBanner(.error, decryptTitle(decryptFailure.result),
                           decryptDescription(decryptFailure.result))
        case let applyFailure as TransferImportResult.ApplyFailure:
            TransferBanner(.error, "Could not apply credentials",
                           applyDescription(applyFailure.result))
        case is TransferImportResult.NoActiveSession:
            TransferBanner(.error, "No active receive session",
                           "Generate a new receive code first.")
        case is TransferImportResult.MissingPrivateKey:
            TransferBanner(.error, "Receive session is no longer usable",
                           "Generate a new receive code and try again.")
        default:
            EmptyView()
        }
    }

    @ViewBuilder
    private var expiredBlock: some View {
        Section {
            TransferBanner(.warning, "Receive code expired",
                           "Generate a new handshake to receive credentials.")
            Button("New handshake") {
                Task { await wrapper.restart() }
            }
        }
    }

    // MARK: - Description helpers

    private func describeImport(_ result: TransferImportResult.Success) -> String {
        let applied = Int(result.applyResult.applied)
        let configCount = Int(result.applyResult.configApplied)
        var s = configCount > 0
            ? "Credentials and setup details imported. Some providers may take a moment to reconnect. "
            : "Credentials imported. Some providers may take a moment to reconnect. "
        s += "Imported \(applied) credential record"
        if applied != 1 { s += "s" }
        if configCount > 0 {
            s += " + \(configCount) companion config record"
            if configCount != 1 { s += "s" }
        }
        s += "."
        let skippedKeys = (result.applyResult.skippedKeyNames as! [String])
        if !skippedKeys.isEmpty {
            s += " Skipped unknown keys: " + skippedKeys.joined(separator: ", ") + "."
        }
        let skippedConfig = (result.applyResult.skippedConfigKeys as! [String])
        if !skippedConfig.isEmpty {
            s += " Skipped config keys not on the receiver allowlist: " + skippedConfig.joined(separator: ", ") + "."
        }
        let missing = (result.applyResult.categoriesMissingCompanionConfig as! [SecretCategory])
        if !missing.isEmpty {
            s += " Imported credentials but missing companion config for: " +
                missing.map { $0.name }.joined(separator: ", ") +
                ". Fill in the matching server URL in Settings to finish setup."
        }
        return s
    }

    private func decryptTitle(_ result: TransferDecryptResult) -> String {
        switch result {
        case is TransferDecryptResult.Expired: return "Sealed code expired"
        case is TransferDecryptResult.AuthenticationFailure: return "Could not decrypt code"
        case is TransferDecryptResult.UnsupportedVersion: return "Unsupported transfer version"
        case is TransferDecryptResult.Replayed: return "Code already used"
        case is TransferDecryptResult.EnvelopePayloadMismatch: return "Code failed integrity check"
        case is TransferDecryptResult.Malformed: return "Malformed sealed code"
        default: return "Could not decrypt code"
        }
    }

    private func decryptDescription(_ result: TransferDecryptResult) -> String {
        switch result {
        case is TransferDecryptResult.Expired:
            return "Ask the sender to generate a fresh sealed code."
        case is TransferDecryptResult.AuthenticationFailure:
            return "This code was not sealed for this receive session, or it was changed."
        case let v as TransferDecryptResult.UnsupportedVersion:
            return "This app cannot read transfer version \(v.seenVersion)."
        case is TransferDecryptResult.Replayed:
            return "This transfer nonce has already been consumed on this device."
        case is TransferDecryptResult.EnvelopePayloadMismatch:
            return "The envelope and payload expiry values do not match."
        case let m as TransferDecryptResult.Malformed:
            return m.reason
        default:
            return ""
        }
    }

    private func applyDescription(_ result: TransferApplyResult) -> String {
        switch result {
        case is TransferApplyResult.DuplicateNonce:
            return "This transfer nonce has already been consumed on this device."
        case is TransferApplyResult.NothingApplied:
            return "No known credential keys were found in the payload."
        case let store as TransferApplyResult.StoreFailure:
            var s = store.message
            if store.rollbackAttempted {
                s += store.rollbackSucceeded
                    ? " Rollback succeeded; existing credentials were restored."
                    : " Rollback failed; verify credentials manually."
            }
            return s
        case let success as TransferApplyResult.Success:
            return "Imported \(success.applied) credential record(s)."
        default:
            return ""
        }
    }
}
