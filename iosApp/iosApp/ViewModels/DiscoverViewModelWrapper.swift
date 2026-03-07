import Foundation
import shared

final class DiscoverViewModelWrapper: ObservableObject {
    let viewModel: DiscoverViewModel
    @Published var state: DiscoverUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.discoverViewModel()
        self.state = viewModel.state.value as! DiscoverUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? DiscoverUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func selectTab(_ tab: DiscoverTab) { viewModel.selectTab(tab: tab) }
}
