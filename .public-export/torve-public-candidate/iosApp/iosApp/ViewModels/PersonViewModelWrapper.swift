import Foundation
import shared

final class PersonViewModelWrapper: ObservableObject {
    let viewModel: PersonViewModel
    @Published var state: PersonUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.personViewModel()
        self.state = viewModel.state.value as! PersonUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? PersonUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func loadPerson(personId: Int32) {
        viewModel.loadPerson(personId: personId)
    }
}
