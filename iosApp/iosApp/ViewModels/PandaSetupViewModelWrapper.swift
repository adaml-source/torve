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
    func addIndexer() { viewModel.addIndexer() }
    func removeIndexer(_ index: Int) { viewModel.removeIndexer(index: Int32(index)) }
    func setIndexerType(_ index: Int, _ type: String) {
        viewModel.updateIndexer(index: Int32(index)) { row in
            NzbIndexerRow(type: type, url: row.url, apiKey: row.apiKey)
        }
    }
    func setIndexerUrl(_ index: Int, _ url: String) {
        viewModel.updateIndexer(index: Int32(index)) { row in
            NzbIndexerRow(type: row.type, url: url, apiKey: row.apiKey)
        }
    }
    func setIndexerApiKey(_ index: Int, _ apiKey: String) {
        viewModel.updateIndexer(index: Int32(index)) { row in
            NzbIndexerRow(type: row.type, url: row.url, apiKey: apiKey)
        }
    }
    func setBandwidthSaver(_ enabled: Bool) { viewModel.setBandwidthSaver(on: enabled) }
    func setDownloadClient(_ client: String) { viewModel.setDownloadClient(client: client) }
    func setDownloadClientUrl(_ url: String) { viewModel.setDownloadClientUrl(url: url) }
    func setDownloadClientUsername(_ u: String) { viewModel.setDownloadClientUsername(username: u) }
    func setDownloadClientPassword(_ p: String) { viewModel.setDownloadClientPassword(password: p) }
    func setDownloadClientApiKey(_ k: String) { viewModel.setDownloadClientApiKey(apiKey: k) }

    // MARK: - Quality

    func setMaxQuality(_ q: String) { viewModel.setMaxQuality(quality: q) }
    func setQualityProfile(_ p: String) { viewModel.setQualityProfile(profile: p) }
    func setReleaseLanguage(_ l: String) { viewModel.setReleaseLanguage(language: l) }
    func toggleLanguage(_ code: String, selected: Bool) {
        viewModel.toggleLanguage(code: code, selected: selected)
    }

    // MARK: - Save / delete

    func saveConfigAndInstall() { viewModel.saveConfigAndInstall() }
    func deleteConfig() { viewModel.deleteConfig() }
    func clearError() { viewModel.clearError() }

    // MARK: - Management token lifecycle

    /// Dismiss the one-time display surface for the management token.
    func acknowledgeManagementTokenDisplay() {
        viewModel.acknowledgeManagementTokenDisplay()
    }

    /// Recovery flow: validate and persist an admin-issued management token
    /// for the current config. Flips state.hasManagementToken on success.
    func recoverManagementToken(_ adminIssuedToken: String) {
        viewModel.recoverManagementToken(adminIssuedToken: adminIssuedToken)
    }

    /// Mint a fresh management token via rotate-management. Surfaces the new
    /// value in state.pendingManagementTokenDisplay exactly once.
    func rotateManagementToken() { viewModel.rotateManagementToken() }

    /// Rotate the manifest (panda) token. Old manifest URL 404s on success.
    func rotateManifestUrl() { viewModel.rotateManifestUrl() }
}
