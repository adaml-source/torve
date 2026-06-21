import Foundation
import shared

final class CalendarViewModelWrapper: ObservableObject {
    let viewModel: CalendarViewModel
    @Published var state: CalendarUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.calendarViewModel()
        self.state = viewModel.state.value as! CalendarUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? CalendarUiState {
                    self?.state = s
                }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Actions

    func refresh() { viewModel.refresh() }
}
