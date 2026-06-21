import Foundation
import shared

/// SwiftUI-friendly wrapper for the shared `TransferDiagnosticsCollector`
/// and `TransferAttemptTracker`.
///
/// The wrapper republishes the redaction-safe snapshot on the main
/// thread and exposes two async actions: `refresh()` (no relay probe)
/// and `probeRelay()` (single non-destructive `getSession` to a bogus
/// session id). It never carries credentials, envelope JSON, session
/// strings, public keys, or access tokens — the shared snapshot type
/// is closed-shape by construction.
@MainActor
final class TransferDiagnosticsWrapper: ObservableObject {

    @Published var snapshot: TransferDiagnosticsSnapshot?
    @Published var isProbing: Bool = false

    private let collector: TransferDiagnosticsCollector

    init() {
        self.collector = KoinViewModelFactory.resolve(TransferDiagnosticsCollector.self)
    }

    func refresh() async {
        do {
            snapshot = try await collector.collect(probeRelay: false)
        } catch {
            // collector.collect never throws via its own logic; catch is
            // here to satisfy the Kotlin/Native suspend bridge.
        }
    }

    func probeRelay() async {
        guard !isProbing else { return }
        isProbing = true
        defer { isProbing = false }
        do {
            snapshot = try await collector.collect(probeRelay: true)
        } catch {
            // see refresh()
        }
    }

    // MARK: - Closed-enum label maps (no backend strings ever rendered)

    static func relayLabel(_ r: RelayReachability) -> String {
        switch r {
        case .unknown: return "unknown"
        case .reachable: return "reachable"
        case .unavailable: return "unavailable"
        case .unauthorized: return "unauthorized"
        case .networkError: return "network error"
        case .notSignedIn: return "not signed in"
        case .noCryptoEngine: return "no crypto engine"
        @unknown default: return "unknown"
        }
    }

    static func roleLabel(_ r: AttemptRole) -> String {
        switch r {
        case .sender: return "sender"
        case .receiver: return "receiver"
        @unknown default: return "unknown"
        }
    }

    static func outcomeLabel(_ o: AttemptOutcome) -> String {
        switch o {
        case .registered: return "registered"
        case .delivered: return "delivered"
        case .imported: return "imported"
        case .failed: return "failed"
        case .relayUnavailable: return "relay unavailable"
        @unknown default: return "unknown"
        }
    }

    static func errorCategoryLabel(_ c: TransferTelemetryErrorCategory) -> String {
        // The shared enum's `value` property is the closed lowercased
        // token used in telemetry; reuse it verbatim so the diagnostics
        // surface and the telemetry vocabulary stay aligned.
        return c.value
    }

    static func relayPillIsOK(_ r: RelayReachability) -> Bool {
        return r == .reachable
    }
}
