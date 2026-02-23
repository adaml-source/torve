import Foundation
import shared

/// ObservableObject wrapper for the KMP IptvViewModel.
/// Bridges KMP StateFlow<IptvUiState> into a SwiftUI-compatible @Published property.
class IptvViewModelWrapper: ObservableObject {
    let viewModel: IptvViewModel
    @Published var state: IptvUiState

    private var collector: Closeable?

    init() {
        let koin = KoinHelper.shared.getKoin()
        self.viewModel = koin.get(objCClass: IptvViewModel.self) as! IptvViewModel
        self.state = viewModel.state.value as! IptvUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? IptvUiState {
                    self?.state = s
                }
            }
        }
    }

    deinit {
        collector?.close()
    }

    // MARK: - Forwarded actions

    func selectSubTab(_ tab: IptvSubTab) { viewModel.selectSubTab(tab: tab) }
    func toggleViewMode() { viewModel.toggleViewMode() }
    func toggleCategoryExpanded(_ name: String) { viewModel.toggleCategoryExpanded(categoryName: name) }
    func recordChannelViewed(_ channel: IptvChannel) { viewModel.recordChannelViewed(channel: channel) }
    func toggleFavorite(_ channel: IptvChannel) { viewModel.toggleFavorite(channel: channel) }
    func updateSearchQuery(_ query: String) { viewModel.updateSearchQuery(query: query) }
    func clearSearch() { viewModel.clearSearch() }
    func selectPlaylist(_ id: String) { viewModel.selectPlaylist(playlistId: id) }
    func refreshPlaylist() { viewModel.refreshPlaylist() }
    func toggleFilterSheet() { viewModel.toggleFilterSheet() }
    func setFilter(_ filter: IptvFilterType) { viewModel.setFilter(filter: filter) }
    func setSort(_ sort: IptvSortType) { viewModel.setSort(sort: sort) }

    // Add playlist
    func showAddPlaylistDialog() { viewModel.showAddPlaylistDialog() }
    func dismissAddPlaylistDialog() { viewModel.dismissAddPlaylistDialog() }
    func setNewPlaylistName(_ name: String) { viewModel.setNewPlaylistName(name: name) }
    func setNewPlaylistUrl(_ url: String) { viewModel.setNewPlaylistUrl(url: url) }
    func setNewPlaylistEpgUrl(_ url: String) { viewModel.setNewPlaylistEpgUrl(url: url) }
    func setNewPlaylistType(_ type: String) { viewModel.setNewPlaylistType(type: type) }
    func setNewXtreamServer(_ server: String) { viewModel.setNewXtreamServer(server: server) }
    func setNewXtreamUsername(_ username: String) { viewModel.setNewXtreamUsername(username: username) }
    func setNewXtreamPassword(_ password: String) { viewModel.setNewXtreamPassword(password: password) }
    func addPlaylist() { viewModel.addPlaylist() }
}
