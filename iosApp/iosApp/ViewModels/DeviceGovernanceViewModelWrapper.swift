import Foundation
import shared

final class DeviceGovernanceViewModelWrapper: ObservableObject {
    let viewModel: DeviceGovernanceViewModel
    @Published var state: DeviceGovernanceUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.deviceGovernanceViewModel()
        self.state = viewModel.state.value as! DeviceGovernanceUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? DeviceGovernanceUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func fetchAccessState() { viewModel.fetchAccessState() }
    func fetchDevices() { viewModel.fetchDevices() }
    func removeDevice(deviceId: String) { viewModel.removeDevice(deviceId: deviceId) }
    func activateCurrentDevice() { viewModel.activateCurrentDevice() }
    func renameDevice(deviceId: String, newName: String) { viewModel.renameDevice(deviceId: deviceId, newName: newName) }
    func dismissDeviceLimitReached() { viewModel.dismissDeviceLimitReached() }
}
