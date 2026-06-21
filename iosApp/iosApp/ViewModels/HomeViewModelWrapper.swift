import Foundation
import shared

final class HomeViewModelWrapper: ObservableObject {
    let viewModel: HomeViewModel
    @Published var state: HomeUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.homeViewModel()
        self.state = viewModel.state.value as! HomeUiState
        self.collector = nil
        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? HomeUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func refresh() { viewModel.refresh() }
}
