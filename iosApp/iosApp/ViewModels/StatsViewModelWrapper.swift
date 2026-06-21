import Foundation
import shared

final class StatsViewModelWrapper: ObservableObject {
    let viewModel: StatsViewModel
    @Published var state: StatsUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.statsViewModel()
        self.state = viewModel.state.value as! StatsUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? StatsUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func refresh() { viewModel.loadStats() }
}
