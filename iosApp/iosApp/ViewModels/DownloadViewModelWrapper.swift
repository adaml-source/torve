import Foundation
import shared

final class DownloadViewModelWrapper: ObservableObject {
    let viewModel: DownloadViewModel
    @Published var state: DownloadUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.downloadViewModel()
        self.state = viewModel.state.value as! DownloadUiState
        self.collector = nil

        // Wire platform callbacks for iOS background downloads
        viewModel.onDownloadEnqueued = { downloadId in
            // TODO: trigger IOSDownloadService.shared.startDownload(...)
        }
        viewModel.onDownloadCancelled = { downloadId in
            // TODO: trigger IOSDownloadService.shared.cancelDownload(...)
        }
        viewModel.onFileDelete = { filePath in
            try? FileManager.default.removeItem(atPath: filePath)
        }

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? DownloadUiState {
                    self?.state = s
                }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Actions

    func loadDownloads() { viewModel.loadDownloads() }

    func enqueueDownload(_ download: Download) { viewModel.enqueueDownload(download: download) }

    func pauseDownload(_ id: String) { viewModel.pauseDownload(id: id) }

    func resumeDownload(_ id: String) { viewModel.resumeDownload(id: id) }

    func deleteDownload(_ id: String) { viewModel.deleteDownload(id: id) }

    func selectTab(_ tab: DownloadTab) { viewModel.selectTab(tab: tab) }

    func getDisplayDownloads() -> [Download] {
        return viewModel.getDisplayDownloads()
    }
}

final class DownloadCatalogueViewModelWrapper: ObservableObject {
    let viewModel: DownloadCatalogueViewModel
    @Published var state: DownloadCatalogueUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.downloadCatalogueViewModel()
        self.state = viewModel.state.value as! DownloadCatalogueUiState
        self.collector = nil

        // Wire platform callbacks
        viewModel.onFileDelete = { filePath in
            try? FileManager.default.removeItem(atPath: filePath)
        }
        viewModel.onDownloadCancelled = { _ in }

        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? DownloadCatalogueUiState {
                    self?.state = s
                }
            }
        }
    }

    deinit { collector?.close() }

    // MARK: - Actions

    func loadCatalogue() { viewModel.loadCatalogue() }

    func deleteGroup(_ group: DownloadGroup) { viewModel.deleteGroup(group: group) }

    func deleteSeason(_ mediaId: String, seasonNumber: Int) {
        viewModel.deleteSeason(mediaId: mediaId, seasonNumber: Int32(seasonNumber))
    }

    func deleteEpisode(_ downloadId: String) { viewModel.deleteEpisode(downloadId: downloadId) }

    func deleteWatched() { viewModel.deleteWatched() }
}
