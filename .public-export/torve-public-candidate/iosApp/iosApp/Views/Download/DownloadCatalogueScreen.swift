import SwiftUI
import shared

struct DownloadCatalogueScreen: View {
    @StateObject private var wrapper = DownloadCatalogueViewModelWrapper()
    @Environment(AppRouter.self) private var router

    var body: some View {
        Group {
            if wrapper.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView("Loading library...")
                        .tint(SVColor.amber)
                    Spacer()
                }
            } else if wrapper.state.catalogue.isEmpty {
                emptyState
            } else {
                catalogueContent
            }
        }
        .navigationTitle("Downloaded Library")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Menu {
                    Button { wrapper.loadCatalogue() } label: {
                        Label("Refresh", systemImage: "arrow.clockwise")
                    }
                    Button(role: .destructive) { wrapper.deleteWatched() } label: {
                        Label("Delete Watched", systemImage: "trash")
                    }
                } label: {
                    Image(systemName: "ellipsis.circle")
                }
            }
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "tray")
                .font(.system(size: 60))
                .foregroundColor(SVColor.onSurfaceVariant)
            Text("No Downloaded Content")
                .font(.title2)
                .fontWeight(.semibold)
            Text("Your downloaded movies and shows will appear here.")
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
    }

    // MARK: - Catalogue Content

    private var catalogueContent: some View {
        ScrollView {
            VStack(spacing: 0) {
                // Storage info
                storageHeader

                // Active downloads section
                if !wrapper.state.activeDownloads.isEmpty {
                    activeDownloadsSection
                }

                // Special sections (Continue Watching, Recently Added)
                ForEach(wrapper.state.catalogue.specialSections, id: \.title) { section in
                    catalogueSection(section)
                }

                // Regular sections
                ForEach(wrapper.state.catalogue.sections, id: \.title) { section in
                    catalogueSection(section)
                }
            }
        }
    }

    // MARK: - Storage Header

    private var storageHeader: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(formatFileSize(wrapper.state.catalogue.totalSizeBytes))
                    .font(.title3)
                    .fontWeight(.bold)
                    .foregroundColor(SVColor.amber)
                Text("\(wrapper.state.catalogue.movieCount) movies, \(wrapper.state.catalogue.showCount) shows (\(wrapper.state.catalogue.episodeCount) episodes)")
                    .font(.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
            Spacer()
        }
        .padding(.horizontal)
        .padding(.vertical, 12)
    }

    // MARK: - Active Downloads

    private var activeDownloadsSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Active Downloads")
                .font(SVFont.sectionTitle)
                .foregroundColor(SVColor.onSurface)
                .padding(.horizontal)

            ForEach(wrapper.state.activeDownloads, id: \.id) { download in
                HStack(spacing: 12) {
                    AsyncImage(url: URL(string: download.posterUrl ?? "")) { phase in
                        if case .success(let img) = phase {
                            img.resizable().scaledToFill()
                        } else {
                            Rectangle()
                                .fill(SVColor.surfaceVariant)
                        }
                    }
                    .frame(width: 40, height: 56)
                    .cornerRadius(6)

                    VStack(alignment: .leading, spacing: 4) {
                        Text(download.title)
                            .font(.subheadline)
                            .lineLimit(1)
                        ProgressView(value: Double(download.progressPercent))
                            .tint(SVColor.amber)
                    }

                    Text("\(Int(download.progressPercent * 100))%")
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
                .padding(.horizontal)
            }
        }
        .padding(.vertical, 8)
    }

    // MARK: - Catalogue Section

    private func catalogueSection(_ section: CatalogueSection) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(section.title)
                .font(SVFont.sectionTitle)
                .foregroundColor(SVColor.onSurface)
                .padding(.horizontal)
                .padding(.top, 12)

            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(section.items, id: \.mediaId) { group in
                        Button {
                            if group.type == .show {
                                router.navigate(to: .downloadedShowDetail(showTitle: group.title))
                            }
                            // Movies could navigate to player or detail
                        } label: {
                            downloadGroupCard(group)
                        }
                        .buttonStyle(.plain)
                        .contextMenu {
                            Button(role: .destructive) {
                                wrapper.deleteGroup(group)
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    private func downloadGroupCard(_ group: DownloadGroup) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomTrailing) {
                AsyncImage(url: URL(string: group.posterUrl ?? "")) { phase in
                    if case .success(let img) = phase {
                        img.resizable()
                            .aspectRatio(2/3, contentMode: .fill)
                    } else {
                        Rectangle()
                            .fill(SVColor.surfaceVariant)
                            .aspectRatio(2/3, contentMode: .fill)
                            .overlay(
                                Image(systemName: "film")
                                    .foregroundColor(SVColor.onSurfaceVariant)
                            )
                    }
                }
                .frame(width: 130)
                .cornerRadius(10)

                // Quality badge
                if let quality = group.genres?.first {
                    // The first item might be resolution info
                }

                // Watch progress overlay
                if group.overallProgress > 0 {
                    GeometryReader { geo in
                        VStack {
                            Spacer()
                            Rectangle()
                                .fill(SVColor.amber)
                                .frame(height: 3)
                                .frame(width: geo.size.width * CGFloat(group.overallProgress))
                        }
                    }
                }
            }

            Text(group.title)
                .font(SVFont.cardTitle)
                .foregroundColor(SVColor.onSurface)
                .lineLimit(2)

            HStack(spacing: 4) {
                if group.type == .show {
                    Text("\(group.itemCount) episodes")
                        .font(SVFont.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
                Text(formatFileSize(group.totalSizeBytes))
                    .font(SVFont.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
        }
        .frame(width: 130)
    }

    // MARK: - Helpers

    private func formatFileSize(_ bytes: Int64) -> String {
        let gb = Double(bytes) / (1024 * 1024 * 1024)
        if gb >= 1.0 {
            return String(format: "%.1f GB", gb)
        }
        let mb = Double(bytes) / (1024 * 1024)
        if mb >= 1.0 {
            return String(format: "%.0f MB", mb)
        }
        let kb = Double(bytes) / 1024
        return String(format: "%.0f KB", kb)
    }
}
