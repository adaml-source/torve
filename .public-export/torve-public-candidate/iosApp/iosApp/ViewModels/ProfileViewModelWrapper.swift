import Foundation
import shared

final class ProfileViewModelWrapper: ObservableObject {
    let viewModel: ProfileViewModel
    @Published var state: ProfileUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.profileViewModel()
        self.state = viewModel.state.value as! ProfileUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? ProfileUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Profile management

    func loadProfiles() { viewModel.loadProfiles() }
    func createProfile(name: String, avatarIndex: Int32 = 0) { viewModel.createProfile(name: name, avatarIndex: avatarIndex) }
    func switchProfile(id: String) { viewModel.switchProfile(id: id) }
    func verifyPinAndSwitch(profileId: String, pin: String) { viewModel.verifyPinAndSwitch(profileId: profileId, pin: pin) }
    func dismissPinPrompt() { viewModel.dismissPinPrompt() }
    func updateProfileName(id: String, name: String) { viewModel.updateProfileName(id: id, name: name) }
    func setProfilePin(id: String, pin: String?) { viewModel.setProfilePin(id: id, pin: pin) }
    func setContentRating(id: String, rating: ContentRating?) { viewModel.setContentRating(id: id, rating: rating) }
    func deleteProfile(id: String) { viewModel.deleteProfile(id: id) }
    func showEditDialog(profile: UserProfile) { viewModel.showEditDialog(profile: profile) }
    func dismissEditDialog() { viewModel.dismissEditDialog() }
    func clearError() { viewModel.clearError() }
}
