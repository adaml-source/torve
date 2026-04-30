import Foundation
import shared

/// SwiftUI-friendly bridge for the Kotlin `SetupWizardCoordinator`.
///
/// Exposes:
///   - `state`: per-intent state map keyed by `SetupIntent`.
///   - `summary`: the live `ReadyToWatchSummary`.
///   - `load()`: hydrate persisted progress; idempotent.
///   - `beginIntent(_:)`, `validate(_:)`, `reset(_:)`: per-intent actions.
///
/// Two flow collectors — one for state, one for summary — are kept alive
/// for the wrapper's lifetime and closed in deinit. Avoids retain
/// cycles through `[weak self]` captures.
final class SetupWizardCoordinatorWrapper: ObservableObject {
    let coordinator: SetupWizardCoordinator
    @Published private(set) var state: [SetupIntent: SetupIntentState] = [:]
    @Published private(set) var summary: ReadyToWatchSummary

    private var stateCollector: Closeable?
    private var summaryCollector: Closeable?

    init() {
        self.coordinator = KoinViewModelFactory.setupWizardCoordinator()
        // Initial values pulled synchronously so SwiftUI doesn't render
        // an empty state on first appearance.
        self.summary = coordinator.summary.value as! ReadyToWatchSummary
        self.state = Self.toSwiftMap(coordinator.state.value)

        stateCollector = FlowCollectorHelper.shared.collect(flow: coordinator.state) { [weak self] newValue in
            DispatchQueue.main.async {
                guard let dict = newValue else { return }
                self?.state = Self.toSwiftMap(dict)
            }
        }
        summaryCollector = FlowCollectorHelper.shared.collect(flow: coordinator.summary) { [weak self] newValue in
            DispatchQueue.main.async {
                if let s = newValue as? ReadyToWatchSummary {
                    self?.summary = s
                }
            }
        }
    }

    deinit {
        stateCollector?.close()
        summaryCollector?.close()
    }

    /// Hydrate persisted state from prefs. Safe to call multiple times.
    /// VALIDATING entries are downgraded to IN_PROGRESS so a crash
    /// mid-validate never sticks the UI on a spinner.
    func load() {
        Task { try? await coordinator.load() }
    }

    func beginIntent(_ intent: SetupIntent) {
        coordinator.beginIntent(intent: intent)
    }

    func validate(_ intent: SetupIntent) {
        // Discard the returned Job — UI observes `state` for the result.
        _ = coordinator.validate(intent: intent)
    }

    func reset(_ intent: SetupIntent) {
        coordinator.reset(intent: intent)
    }

    func snapshot(_ intent: SetupIntent) -> SetupIntentState {
        return coordinator.snapshot(intent: intent)
    }

    /// Bridges a `Map<SetupIntent, SetupIntentState>` from Kotlin (an
    /// `NSDictionary` after Obj-C interop) into a typed Swift dictionary.
    private static func toSwiftMap(_ raw: Any?) -> [SetupIntent: SetupIntentState] {
        guard let raw = raw as? [AnyHashable: Any] else { return [:] }
        var out: [SetupIntent: SetupIntentState] = [:]
        for (key, value) in raw {
            if let intent = key as? SetupIntent, let state = value as? SetupIntentState {
                out[intent] = state
            }
        }
        return out
    }
}
