import Foundation
import shared

final class DetailViewModelWrapper: ObservableObject {
    let viewModel: DetailViewModel
    @Published var state: DetailUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.detailViewModel()
        self.state = viewModel.state.value as! DetailUiState
        self.collector = nil

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? DetailUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Load

    func loadDetail(mediaType: String, mediaId: String) {
        guard let id = Int32(mediaId) else { return }
        viewModel.loadDetail(type: mediaType, id: id)
    }

    // MARK: - Streams

    func fetchStreams() {
        viewModel.fetchStreams()
    }

    func fetchStreamsForEpisode(season: Int32, episode: Int32) {
        viewModel.fetchStreams(season: season, episode: episode)
    }

    func resolveStream(_ stream: ParsedStream) {
        // Resolve via SettingsViewModel provider (auto-play path uses this internally)
        let settings = KoinViewModelFactory.settingsViewModel()
        let provider = settings.getDebridProvider()
        let apiKey = settings.getDebridApiKey()
        viewModel.resolveStream(stream: stream, provider: provider, apiKey: apiKey)
    }

    // MARK: - Seasons

    func selectSeason(_ number: Int32) {
        guard let item = state.mediaItem else { return }
        guard let id = Int32(item.id) else { return }
        viewModel.loadSeasonDetail(tvId: id, seasonNumber: number)
    }

    // MARK: - Watched & Library

    func toggleWatched() {
        if state.isMarkedWatched {
            viewModel.markUnwatched()
        } else {
            viewModel.markWatched()
        }
    }

    func toggleLibrary() {
        // Library toggle is not directly on DetailViewModel as a single call;
        // the KMP layer uses libraryOverlayService. For now, toggle watched as proxy.
        // TODO: Wire full library toggle when shared layer exposes it.
    }

    func setUserRating(_ rating: Int32?) {
        if let r = rating {
            viewModel.setUserRating(rating: KotlinInt(value: r))
        } else {
            viewModel.setUserRating(rating: nil)
        }
    }

    func playNextEpisode() {
        viewModel.playNextEpisode()
    }

    func markSeasonWatched(_ seasonNumber: Int32) {
        viewModel.markSeasonWatched(seasonNumber: seasonNumber)
    }

    func dismissStreamPicker() {
        viewModel.dismissStreamPicker()
    }

    func showManualPicker() {
        viewModel.showManualPicker()
    }

    func clearResolvedStream() {
        viewModel.clearResolvedStream()
    }

    // MARK: - NzbDAV / Usenet routing

    /// Fire when the source sheet becomes visible (false → true edge).
    /// Triggers the expanded warm pass for Usenet rows in the current
    /// stream list. Idempotent and safely a no-op when there are no
    /// Usenet rows; the shared coordinator dedupes.
    func onSourceSheetOpened() {
        viewModel.onSourceSheetOpened()
    }

    /// Route a USENET_NZBDAV row to the resolver. Non-Usenet rows must
    /// continue to use `resolveStream(...)` — the call site decides via
    /// `stream.accelerationProvenanceKind`.
    func selectUsenetSource(_ stream: ParsedStream) {
        viewModel.selectUsenetSource(stream: stream)
    }

    /// Acknowledge that the UI has consumed the Usenet playback intent
    /// (staged the handoff URL into the player). Clears the intent in
    /// shared state so a recomposition / re-render cannot re-launch
    /// playback for the same handoff.
    func consumeUsenetPlaybackIntent() {
        viewModel.consumeUsenetPlaybackIntent()
    }

    /// Optional explicit cleanup hook. Cancels any in-flight Usenet
    /// resolve / poll for this VM's content. Safe to call from a
    /// `.onDisappear` if the screen wants deterministic teardown.
    func clearUsenetWork() {
        viewModel.clear()
    }
}
