import Foundation
import shared

final class PandaSetupViewModelWrapper: ObservableObject {
    let viewModel: PandaSetupViewModel
    @Published var state: PandaSetupUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.pandaSetupViewModel()
        self.state = viewModel.state.value as! PandaSetupUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? PandaSetupUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Navigation

    func nextStep() { viewModel.nextStep() }
    func previousStep() { viewModel.previousStep() }

    // MARK: - Provider

    func selectProvider(_ provider: PandaProvider) { viewModel.selectProvider(provider: provider) }
    func retryLoadProviders() { viewModel.retryLoadProviders() }

    // MARK: - Auth

    func setAuthMethod(_ method: String) { viewModel.setAuthMethod(method: method) }
    func startOAuth() { viewModel.startOAuth() }
    func setApiKeyInput(_ key: String) { viewModel.setApiKeyInput(key: key) }
    func validateApiKey() { viewModel.validateApiKey() }

    // MARK: - Sources

    func toggleSource(_ id: String) { viewModel.toggleSource(sourceId: id) }

    // MARK: - Usenet

    func setEnableUsenet(_ enabled: Bool) { viewModel.setEnableUsenet(enabled: enabled) }
    func setUsenetProvider(_ provider: String) { viewModel.setUsenetProvider(provider: provider) }
    func setUsenetHost(_ host: String) { viewModel.setUsenetHost(host: host) }
    func setUsenetPort(_ port: Int32) { viewModel.setUsenetPort(port: port) }
    func setUsenetUsername(_ username: String) { viewModel.setUsenetUsername(username: username) }
    func setUsenetPassword(_ password: String) { viewModel.setUsenetPassword(password: password) }
    func setUsenetSSL(_ ssl: Bool) { viewModel.setUsenetSSL(ssl: ssl) }
    func setUsenetConnections(_ connections: Int32) { viewModel.setUsenetConnections(connections: connections) }
    func setNzbIndexer(_ indexer: String) { viewModel.setNzbIndexer(indexer: indexer) }
    func setNzbIndexerUrl(_ url: String) { viewModel.setNzbIndexerUrl(url: url) }
    func setNzbIndexerApiKey(_ key: String) { viewModel.setNzbIndexerApiKey(apiKey: key) }
    func setDownloadClient(_ client: String) { viewModel.setDownloadClient(client: client) }
    func setDownloadClientUrl(_ url: String) { viewModel.setDownloadClientUrl(url: url) }
    func setDownloadClientUsername(_ u: String) { viewModel.setDownloadClientUsername(username: u) }
    func setDownloadClientPassword(_ p: String) { viewModel.setDownloadClientPassword(password: p) }
    func setDownloadClientApiKey(_ k: String) { viewModel.setDownloadClientApiKey(apiKey: k) }

    // MARK: - Quality

    func setMaxQuality(_ q: String) { viewModel.setMaxQuality(quality: q) }
    func setQualityProfile(_ p: String) { viewModel.setQualityProfile(profile: p) }
    func setReleaseLanguage(_ l: String) { viewModel.setReleaseLanguage(language: l) }

    // MARK: - Save / delete

    func saveConfigAndInstall() { viewModel.saveConfigAndInstall() }
    func deleteConfig() { viewModel.deleteConfig() }
    func clearError() { viewModel.clearError() }
}
