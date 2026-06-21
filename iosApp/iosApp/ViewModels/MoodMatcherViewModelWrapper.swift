import Foundation
import shared

final class MoodMatcherViewModelWrapper: ObservableObject {
    let viewModel: MoodMatcherViewModel
    @Published var state: MoodMatcherUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.moodMatcherViewModel()
        self.state = viewModel.state.value as! MoodMatcherUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? MoodMatcherUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func selectMood(_ mood: Mood) { viewModel.selectMood(mood: mood) }
    func clearMood() { viewModel.clearMood() }
}
