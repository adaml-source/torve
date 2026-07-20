import Foundation
import shared

/// Helper to observe KMP StateFlow from SwiftUI.
/// Wraps any KMP ViewModel's StateFlow<T> into an @Published property.
class ObservableViewModel<State: AnyObject>: ObservableObject {
    @Published var state: State

    private var collector: Closeable?

    init(initialState: State, stateFlow: Kotlinx_coroutines_coreStateFlow) {
        self.state = initialState
        self.collector = nil

        // Collect the StateFlow
        collector = FlowCollectorHelper.shared.collect(flow: stateFlow) { [weak self] newState in
            DispatchQueue.main.async {
                if let state = newState as? State {
                    self?.state = state
                }
            }
        }
    }

    deinit {
        collector?.close()
    }
}
