import Foundation
import shared

/// Thin Swift wrapper over the shared `NzbdavSetupViewModel`. Mirrors the
/// Android settings card surface — the VM owns all business logic; this
/// wrapper only republishes state to SwiftUI and exposes the action
/// methods the settings screen needs to call.
///
/// Status copy is rendered from the shared `NzbdavStatus` enum; the
/// wrapper does not introduce any iOS-only mapping. No backend reason
/// strings, version strings, or transport details flow through.
final class NzbdavSetupViewModelWrapper: ObservableObject {
    let viewModel: NzbdavSetupViewModel
    @Published var state: NzbdavSetupUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.nzbdavSetupViewModel()
        self.state = viewModel.state.value as! NzbdavSetupUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? NzbdavSetupUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Form fields

    func updateBaseUrl(_ value: String) { viewModel.updateBaseUrl(value: value) }
    func updateApiKey(_ value: String) { viewModel.updateApiKey(value: value) }
    func setEnabled(_ enabled: Bool) { viewModel.setEnabled(enabled: enabled) }
    func clearLastTestResult() { viewModel.clearLastTestResult() }

    // MARK: - Actions

    func refreshStatus() { viewModel.refreshStatus() }
    func test() { viewModel.test() }
    func save() { viewModel.save() }
    func remove() { viewModel.remove() }
}
