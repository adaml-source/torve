import Foundation
import shared

/// SwiftUI wrapper for the shared `ProviderHealthRecoveryStateProvider`.
///
/// Republishes the snapshot on the main thread and exposes a one-shot
/// `refresh()` to recompute on Settings entry. Carries no secrets — the
/// snapshot is closed-shape (Boolean + Int + closed-enum list).
@MainActor
final class RestoreSetupRecoveryWrapper: ObservableObject {

    @Published var snapshot: ProviderHealthRecoverySnapshot?
    @Published var dismissed: Bool = false

    private let provider: ProviderHealthRecoveryStateProvider
    private let notifier: TransferImportCompletionNotifier
    private var notifierCollector: Closeable?

    init() {
        self.provider = KoinViewModelFactory.resolve(ProviderHealthRecoveryStateProvider.self)
        self.notifier = KoinViewModelFactory.resolve(TransferImportCompletionNotifier.self)

        // Observe the shared completion notifier — every successful import
        // bumps `lastImportEpochMs`, and we re-snapshot here so the card
        // disappears the instant the credentials show up in the store.
        notifierCollector = FlowCollectorHelper.shared.collect(flow: notifier.lastImportEpochMs) { [weak self] _ in
            DispatchQueue.main.async {
                Task { await self?.refresh() }
            }
        }
    }

    deinit { notifierCollector?.close() }

    func refresh() async {
        do {
            // Empty health-entries list — iOS doesn't render provider-health
            // rows yet; the secret-store scan is sufficient for the recovery
            // signal. Adding an entries source later is a one-line change.
            snapshot = try await provider.snapshot(healthEntries: [])
        } catch {
            // snapshot() never throws via its own logic; catch satisfies
            // the Kotlin/Native suspend-async bridge.
        }
    }

    var shouldShowCard: Bool {
        guard !dismissed else { return false }
        return snapshot?.shouldShowRecoveryCard == true
    }

    var missingCount: Int {
        guard let s = snapshot else { return 0 }
        return Int(s.missingTransferableCategoryCount)
    }

    func dismiss() { dismissed = true }
}
