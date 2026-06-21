import Foundation
import shared

final class SetupWizardViewModelWrapper: ObservableObject {
    let viewModel: SetupWizardViewModel
    @Published var state: SetupUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.setupWizardViewModel()
        self.state = viewModel.state.value as! SetupUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? SetupUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Navigation

    func nextStep() { viewModel.nextStep() }
    func previousStep() { viewModel.previousStep() }
    func skipStep() { viewModel.skipStep() }

    // MARK: - Debrid

    func setDebridProvider(_ provider: DebridServiceType) { viewModel.setDebridProvider(provider: provider) }
    func setDebridApiKey(_ key: String) { viewModel.setDebridApiKey(key: key) }
    func connectDebrid() { viewModel.connectDebrid() }

    // MARK: - Trakt

    func startTraktAuth() { viewModel.startTraktAuth() }
    func setTraktClientId(_ id: String) { viewModel.setTraktClientId(id: id) }
    func setTraktClientSecret(_ secret: String) { viewModel.setTraktClientSecret(secret: secret) }

    // MARK: - Quality

    func setMaxQuality(_ quality: StreamQuality) { viewModel.setMaxQuality(quality: quality) }
    func setCachedOnly(_ enabled: Bool) { viewModel.setCachedOnly(enabled: enabled) }

    // MARK: - Terms

    func setTermsAccepted(_ accepted: Bool) { viewModel.setTermsAccepted(accepted: accepted) }

    // MARK: - Channels

    func setChannelPlaylistUrl(_ url: String) { viewModel.setChannelPlaylistUrl(url: url) }
    func setChannelPlaylistName(_ name: String) { viewModel.setChannelPlaylistName(name: name) }
    func setChannelPlaylistType(_ type: String) { viewModel.setChannelPlaylistType(type: type) }
    func setChannelXtreamServer(_ server: String) { viewModel.setChannelXtreamServer(server: server) }
    func setChannelXtreamUsername(_ username: String) { viewModel.setChannelXtreamUsername(username: username) }
    func setChannelXtreamPassword(_ password: String) { viewModel.setChannelXtreamPassword(password: password) }

    // MARK: - Complete

    func completeSetup() { viewModel.completeSetup() }
}
