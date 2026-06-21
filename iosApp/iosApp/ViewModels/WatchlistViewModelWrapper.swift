import Foundation
import shared

final class WatchlistViewModelWrapper: ObservableObject {
    let viewModel: WatchlistViewModel
    @Published var state: WatchlistUiState

    @Published var inProgressItems: [WatchProgress] = []
    @Published var historyItems: [WatchHistoryEntry] = []
    @Published var isLoadingProgress: Bool = false
    @Published var isLoadingHistory: Bool = false

    private var collector: Closeable?

    private lazy var progressRepo: WatchProgressRepository = {
        KoinViewModelFactory.resolve(WatchProgressRepository.self)
    }()

    private lazy var historyRepo: WatchHistoryRepository = {
        KoinViewModelFactory.resolve(WatchHistoryRepository.self)
    }()

    init() {
        self.viewModel = KoinViewModelFactory.watchlistViewModel()
        self.state = viewModel.state.value as! WatchlistUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? WatchlistUiState {
                    self?.state = s
                }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Actions

    func loadWatchlist() { viewModel.loadWatchlist() }

    func isInWatchlist(_ mediaId: String) -> Bool {
        return viewModel.isInWatchlist(mediaId: mediaId)
    }

    func toggleWatchlist(_ mediaItem: MediaItem) {
        viewModel.toggleWatchlist(mediaItem: mediaItem)
    }

    func addToWatchlist(_ mediaItem: MediaItem, syncTrakt: Bool, syncSimkl: Bool) {
        viewModel.addToWatchlist(mediaItem: mediaItem, syncTrakt: syncTrakt, syncSimkl: syncSimkl)
    }

    func clearSnackbar() { viewModel.clearSnackbar() }

    // MARK: - In Progress

    func loadInProgress() {
        isLoadingProgress = true
        Task {
            do {
                let items = try await progressRepo.getInProgress(limit: Int64(20))
                await MainActor.run {
                    self.inProgressItems = items
                    self.isLoadingProgress = false
                }
            } catch {
                await MainActor.run {
                    self.isLoadingProgress = false
                }
            }
        }
    }

    // MARK: - History

    func loadHistory() {
        isLoadingHistory = true
        Task {
            do {
                let items = try await historyRepo.getRecent(limit: 50)
                await MainActor.run {
                    self.historyItems = items
                    self.isLoadingHistory = false
                }
            } catch {
                await MainActor.run {
                    self.isLoadingHistory = false
                }
            }
        }
    }
}
