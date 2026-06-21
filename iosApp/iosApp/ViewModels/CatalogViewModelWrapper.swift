import Foundation
import shared

final class CatalogViewModelWrapper: ObservableObject {
    let viewModel: CatalogViewModel
    @Published var state: CatalogUiState

    private var collector: Closeable?

    init(mediaType: String) {
        let koin = KoinHelper.shared.getKoin()
        let metadataRepo = koin.get(objCClass: MetadataRepositoryImpl.self) as! MetadataRepository
        // CatalogViewModel is created at runtime with mediaType
        self.viewModel = CatalogViewModel(
            metadataRepo: metadataRepo,
            mediaType: mediaType,
            watchProgressRepo: koin.get(objCClass: WatchProgressRepositoryImpl.self) as? WatchProgressRepository,
            keywordSearchService: koin.get(objCClass: KeywordSearchService.self) as? KeywordSearchService,
            prefsRepo: koin.get(objCClass: PreferencesRepositoryImpl.self) as? PreferencesRepository,
            ratingsEnricher: koin.get(objCClass: RatingsEnricher.self) as? RatingsEnricher,
            integrationSecretStore: nil,
            initialProviderId: nil
        )
        self.state = viewModel.state.value as! CatalogUiState
        self.collector = nil
        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? CatalogUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    func selectCategory(_ category: CatalogCategory) { viewModel.selectCategory(category: category) }
    func selectGenre(_ genreId: KotlinInt?) { viewModel.selectGenre(genreId: genreId) }
    func loadMore() { viewModel.loadMore() }
    func updateSearchQuery(_ query: String) { viewModel.updateSearchQuery(query: query) }
    func clearSearch() { viewModel.clearSearch() }
    func applyFilter(_ filter: CatalogFilter) { viewModel.applyFilter(filter: filter) }
    func clearFilters() { viewModel.clearFilters() }
    func toggleFilterSheet() { viewModel.toggleFilterSheet() }
    func dismissFilterSheet() { viewModel.dismissFilterSheet() }
    func refresh() { viewModel.refresh() }
}
