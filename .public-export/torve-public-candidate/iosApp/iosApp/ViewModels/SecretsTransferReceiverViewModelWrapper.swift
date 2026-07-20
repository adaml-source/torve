import Foundation
import shared

/// SwiftUI-friendly wrapper for the shared `SecretsTransferReceiverViewModel`.
///
/// The wrapper republishes the receiver state machine
/// (`Idle/Active/Imported/Expired`) on the main thread for SwiftUI and
/// forwards user actions (manual paste import, restart, cancel) to the
/// underlying Kotlin VM.
@MainActor
final class SecretsTransferReceiverViewModelWrapper: ObservableObject {

    let viewModel: SecretsTransferReceiverViewModel

    @Published var state: ReceiverState = ReceiverState.Idle()

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.secretsTransferReceiverViewModel()
        self.state = viewModel.state.value as! ReceiverState

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            guard let s = newState as? ReceiverState else { return }
            DispatchQueue.main.async {
                self?.state = s
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Lifecycle

    func start() async {
        do {
            try await viewModel.start()
        } catch {
            // start() never throws via its own logic.
        }
    }

    func cancel() {
        viewModel.cancel()
    }

    func restart() async {
        do {
            try await viewModel.restart()
        } catch {
            // restart() never throws via its own logic.
        }
    }

    // MARK: - Manual paste fallback

    func updateEnvelopeText(_ text: String) {
        viewModel.updateEnvelopeText(text: text)
    }

    func acceptEnvelopeJson() async -> TransferImportResult? {
        do {
            return try await viewModel.acceptEnvelopeJson()
        } catch {
            return nil
        }
    }
}
