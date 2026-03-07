import SwiftUI
import shared

struct DownloadScreen: View {
    @StateObject private var wrapper = DownloadViewModelWrapper()

    var body: some View {
        VStack(spacing: 0) {
            tabPicker
            downloadContent
        }
        .navigationTitle("Downloads")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { wrapper.loadDownloads() } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
    }

    // MARK: - Tab Picker

    private var tabPicker: some View {
        Picker("Tab", selection: Binding(
            get: { wrapper.state.selectedTab },
            set: { wrapper.selectTab($0) }
        )) {
            Text("All").tag(DownloadTab.all)
            Text("Active").tag(DownloadTab.active)
            Text("Completed").tag(DownloadTab.completed)
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    // MARK: - Content

    @ViewBuilder
    private var downloadContent: some View {
        if wrapper.state.isLoading {
            Spacer()
            ProgressView().tint(SVColor.amber)
            Spacer()
        } else if let error = wrapper.state.error {
            VStack(spacing: 12) {
                Image(systemName: "exclamationmark.triangle")
                    .font(.largeTitle)
                    .foregroundColor(SVColor.error)
                Text(error)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                Button("Retry") { wrapper.loadDownloads() }
                    .buttonStyle(.borderedProminent)
                    .tint(SVColor.amber)
            }
            .padding()
        } else {
            let downloads = displayDownloads
            if downloads.isEmpty {
                emptyState
            } else {
                downloadList(downloads)
            }
        }
    }

    private var displayDownloads: [Download] {
        switch wrapper.state.selectedTab {
        case .all: return wrapper.state.downloads
        case .active: return wrapper.state.activeDownloads
        case .completed: return wrapper.state.completedDownloads
        default: return wrapper.state.downloads
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 16) {
            Spacer()
            Image(systemName: "arrow.down.circle")
                .font(.system(size: 60))
                .foregroundColor(SVColor.onSurfaceVariant)
            Text("No Downloads")
                .font(.title2)
                .fontWeight(.semibold)
            Text("Download movies and episodes to watch offline.")
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
    }

    // MARK: - Download List

    private func downloadList(_ downloads: [Download]) -> some View {
        List {
            ForEach(downloads, id: \.id) { download in
                downloadRow(download)
            }
        }
        .listStyle(.plain)
    }

    private func downloadRow(_ download: Download) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 12) {
                // Poster
                AsyncImage(url: URL(string: download.posterUrl ?? "")) { phase in
                    if case .success(let img) = phase {
                        img.resizable().scaledToFill()
                    } else {
                        Rectangle()
                            .fill(SVColor.surfaceVariant)
                            .overlay(
                                Image(systemName: "film")
                                    .foregroundColor(SVColor.onSurfaceVariant)
                            )
                    }
                }
                .frame(width: 50, height: 70)
                .cornerRadius(8)

                VStack(alignment: .leading, spacing: 4) {
                    Text(download.title)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(2)

                    statusLabel(download.status)

                    if let size = download.fileSizeBytes?.int64Value {
                        Text(formatFileSize(size))
                            .font(.caption)
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }
                }

                Spacer()

                actionButtons(download)
            }

            // Progress bar for active downloads
            if download.status == .downloading || download.status == .paused {
                ProgressView(value: Double(download.progressPercent))
                    .tint(download.status == .paused ? SVColor.onSurfaceVariant : SVColor.amber)
                    .animation(.easeInOut, value: download.progressPercent)

                Text("\(Int(download.progressPercent * 100))%")
                    .font(.caption2)
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
        }
        .padding(.vertical, 4)
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                wrapper.deleteDownload(download.id)
            } label: {
                Label("Delete", systemImage: "trash")
            }
        }
    }

    // MARK: - Status Label

    private func statusLabel(_ status: DownloadStatus) -> some View {
        HStack(spacing: 4) {
            Circle()
                .fill(statusColor(status))
                .frame(width: 8, height: 8)
            Text(statusText(status))
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
        }
    }

    private func statusColor(_ status: DownloadStatus) -> Color {
        switch status {
        case .pending: return .gray
        case .downloading: return SVColor.amber
        case .paused: return .orange
        case .completed: return SVColor.emerald
        case .failed: return SVColor.error
        default: return .gray
        }
    }

    private func statusText(_ status: DownloadStatus) -> String {
        switch status {
        case .pending: return "Pending"
        case .downloading: return "Downloading"
        case .paused: return "Paused"
        case .completed: return "Completed"
        case .failed: return "Failed"
        default: return "Unknown"
        }
    }

    // MARK: - Action Buttons

    private func actionButtons(_ download: Download) -> some View {
        HStack(spacing: 8) {
            switch download.status {
            case .downloading:
                Button { wrapper.pauseDownload(download.id) } label: {
                    Image(systemName: "pause.circle.fill")
                        .font(.title2)
                        .foregroundColor(SVColor.amber)
                }
                .buttonStyle(.plain)

            case .paused:
                Button { wrapper.resumeDownload(download.id) } label: {
                    Image(systemName: "play.circle.fill")
                        .font(.title2)
                        .foregroundColor(SVColor.emerald)
                }
                .buttonStyle(.plain)

            case .pending:
                ProgressView()
                    .tint(SVColor.amber)

            case .failed:
                Button { wrapper.resumeDownload(download.id) } label: {
                    Image(systemName: "arrow.clockwise.circle.fill")
                        .font(.title2)
                        .foregroundColor(SVColor.amber)
                }
                .buttonStyle(.plain)

            case .completed:
                Image(systemName: "checkmark.circle.fill")
                    .font(.title2)
                    .foregroundColor(SVColor.emerald)

            default:
                EmptyView()
            }
        }
    }

    // MARK: - Helpers

    private func formatFileSize(_ bytes: Int64) -> String {
        let gb = Double(bytes) / (1024 * 1024 * 1024)
        if gb >= 1.0 {
            return String(format: "%.1f GB", gb)
        }
        let mb = Double(bytes) / (1024 * 1024)
        return String(format: "%.0f MB", mb)
    }
}
