import Foundation
import shared

final class SettingsViewModelWrapper: ObservableObject {
    let viewModel: SettingsViewModel
    @Published var state: SettingsUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.settingsViewModel()
        self.state = viewModel.state.value as! SettingsUiState
        self.collector = nil
        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? SettingsUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }
}
