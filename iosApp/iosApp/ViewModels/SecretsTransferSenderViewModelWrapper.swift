import Foundation
import shared

/// SwiftUI-friendly wrapper for the shared `SecretsTransferSenderViewModel`.
///
/// Mirrors the existing wrapper pattern in this folder: subscribes to
/// the Kotlin `StateFlow`, republishes mapped state on the main thread,
/// and exposes a small surface of `func`s the SwiftUI view calls.
@MainActor
final class SecretsTransferSenderViewModelWrapper: ObservableObject {

    let viewModel: SecretsTransferSenderViewModel

    @Published var receiverSessionString: String = ""
    @Published var selectedCategories: Set<SecretCategory> = []
    @Published var status: SenderStatus = SenderStatus.Idle()

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.secretsTransferSenderViewModel()
        let initial = viewModel.state.value as! SenderState
        self.receiverSessionString = initial.receiverSessionString
        self.selectedCategories = Set(initial.selectedCategories.map { $0 as! SecretCategory })
        self.status = initial.status

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            guard let s = newState as? SenderState else { return }
            DispatchQueue.main.async {
                guard let self = self else { return }
                self.receiverSessionString = s.receiverSessionString
                self.selectedCategories = Set(s.selectedCategories.map { $0 as! SecretCategory })
                self.status = s.status
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Inputs

    func updateReceiverSessionString(_ value: String) {
        viewModel.updateReceiverSessionString(value: value)
    }

    func setCategoryEnabled(_ category: SecretCategory, _ enabled: Bool) {
        viewModel.setCategoryEnabled(category: category, enabled: enabled)
    }

    func generateEnvelope() async {
        do {
            try await viewModel.generateEnvelope()
        } catch {
            // The shared VM never throws via its own logic — `setError`
            // routes failures through `status`. Catch is here to satisfy
            // Swift's async-throws bridge from Kotlin/Native suspend.
        }
    }
}
