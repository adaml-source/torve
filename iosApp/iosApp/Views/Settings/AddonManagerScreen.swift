import SwiftUI
import shared

final class AddonViewModelWrapper: ObservableObject {
    let viewModel: AddonViewModel
    @Published var state: AddonUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.addonViewModel()
        self.state = viewModel.state.value as! AddonUiState
        self.collector = nil
        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? AddonUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }
}

// MARK: - Popular Addon Data

private enum AddonCategory: String, CaseIterable {
    case all = "All"
    case streams = "Streams"
    case catalogs = "Catalogs"
    case subtitles = "Subtitles"
}

private struct PopularAddon: Identifiable {
    let id: String
    let name: String
    let description: String
    let url: String
    let categories: [AddonCategory]

    init(name: String, description: String, url: String, categories: [AddonCategory]) {
        self.id = url
        self.name = name
        self.description = description
        self.url = url
        self.categories = categories
    }
}

private let popularAddons: [PopularAddon] = [
    PopularAddon(
        name: "Cinemeta",
        description: "Movie & series info from IMDB/TMDB",
        url: "https://v3-cinemeta.strem.io/manifest.json",
        categories: [.catalogs]
    ),
    PopularAddon(
        name: "The Movie Database Addon",
        description: "Rich catalogs powered by TMDB -- trending, popular, top rated",
        url: "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json",
        categories: [.catalogs]
    ),
    PopularAddon(
        name: "Trakt Lists",
        description: "Access your Trakt lists, trending, popular, and anticipated titles",
        url: "https://2ecbbd610840-trakt.baby-beamup.club/manifest.json",
        categories: [.catalogs]
    ),
    PopularAddon(
        name: "IMDB Catalogs",
        description: "IMDB movie & series lists -- Top 250, Most Popular, Box Office",
        url: "https://1fe84bc728af-imdb-catalogs.baby-beamup.club/manifest.json",
        categories: [.catalogs]
    ),
    PopularAddon(
        name: "RPDB Catalogs",
        description: "Rating poster database -- catalogs with rating overlays",
        url: "https://1fe84bc728af-rpdb.baby-beamup.club/manifest.json",
        categories: [.catalogs]
    ),
    PopularAddon(
        name: "OpenSubtitles v3",
        description: "Subtitles from OpenSubtitles.com -- largest subtitle database",
        url: "https://opensubtitles-v3.strem.io/manifest.json",
        categories: [.subtitles]
    ),
]

// MARK: - Screen

struct AddonManagerScreen: View {
    @StateObject private var wrapper = AddonViewModelWrapper()
    @State private var searchQuery: String = ""
    @State private var selectedCategory: AddonCategory = .all
    @State private var showRemoveConfirmation: Bool = false
    @State private var addonToRemove: String? = nil

    private var installedUrls: Set<String> {
        var urls = Set<String>()
        for addon in wrapper.state.addons {
            let manifestUrl = addon.manifestUrl.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let baseUrl = manifestUrl.replacingOccurrences(of: "/manifest.json", with: "")
            urls.insert(manifestUrl)
            urls.insert(baseUrl)
        }
        return urls
    }

    private var filteredAddons: [PopularAddon] {
        let byCategory: [PopularAddon]
        if selectedCategory == .all {
            byCategory = popularAddons
        } else {
            byCategory = popularAddons.filter { $0.categories.contains(selectedCategory) }
        }
        if searchQuery.isEmpty || searchQuery.hasPrefix("http") {
            return byCategory
        }
        return byCategory.filter { $0.name.localizedCaseInsensitiveContains(searchQuery) }
    }

    private var availableAddons: [PopularAddon] {
        filteredAddons.filter { addon in
            let manifestUrl = addon.url.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
            let baseUrl = manifestUrl.replacingOccurrences(of: "/manifest.json", with: "")
            return !installedUrls.contains(manifestUrl) && !installedUrls.contains(baseUrl)
        }
    }

    var body: some View {
        List {
            // Search / URL input
            Section {
                HStack {
                    Image(systemName: "magnifyingglass")
                        .foregroundColor(.secondary)
                    TextField("Search addons or paste URL...", text: $searchQuery)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)

                    if searchQuery.hasPrefix("http") {
                        if wrapper.state.isInstalling {
                            ProgressView()
                        } else {
                            Button {
                                wrapper.viewModel.setInstallUrl(url: searchQuery)
                                wrapper.viewModel.installAddon()
                                searchQuery = ""
                            } label: {
                                Image(systemName: "plus.circle.fill")
                                    .foregroundColor(SVColor.amber)
                            }
                        }
                    }
                }
            } header: {
                Text("\(wrapper.state.addons.count) addon(s) installed")
            }

            // Category filter chips
            Section {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(AddonCategory.allCases, id: \.self) { category in
                            Button {
                                selectedCategory = category
                            } label: {
                                Text(category.rawValue)
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                                    .padding(.horizontal, 12)
                                    .padding(.vertical, 6)
                                    .background(selectedCategory == category ? SVColor.amber : SVColor.surfaceVariant)
                                    .foregroundColor(selectedCategory == category ? SVColor.obsidian : SVColor.onSurface)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 2)
                }
            }

            // Error display
            if let error = wrapper.state.installError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(SVColor.error)

                    if !wrapper.state.lastInstallUrl.isEmpty {
                        HStack(spacing: 12) {
                            Button {
                                if let url = URL(string: wrapper.state.lastInstallUrl) {
                                    UIApplication.shared.open(url)
                                }
                            } label: {
                                Label("Open in Browser", systemImage: "safari")
                            }

                            Button {
                                UIPasteboard.general.string = wrapper.state.lastInstallUrl
                            } label: {
                                Label("Copy URL", systemImage: "doc.on.doc")
                            }
                        }
                        .font(.subheadline)
                        .foregroundColor(SVColor.amber)

                        Text("Make sure the URL ends with /manifest.json")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            // Installed addons
            if !wrapper.state.addons.isEmpty {
                Section("Installed") {
                    ForEach(wrapper.state.addons, id: \.manifestUrl) { addon in
                        HStack(spacing: 12) {
                            addonInitial(addon.name)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(addon.name)
                                    .fontWeight(.medium)
                                if let desc = addon.manifest.description_, !desc.isEmpty {
                                    Text(desc)
                                        .font(.caption)
                                        .foregroundColor(.secondary)
                                        .lineLimit(1)
                                }
                            }

                            Spacer()

                            Toggle("", isOn: Binding(
                                get: { addon.enabled },
                                set: { wrapper.viewModel.toggleAddon(manifestUrl: addon.manifestUrl, enabled: $0) }
                            ))
                            .labelsHidden()
                        }
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                addonToRemove = addon.manifestUrl
                                showRemoveConfirmation = true
                            } label: {
                                Label("Remove", systemImage: "trash")
                            }
                        }
                    }
                }
            }

            // Available addons
            if !availableAddons.isEmpty {
                Section("Available") {
                    ForEach(availableAddons) { addon in
                        HStack(spacing: 12) {
                            addonInitial(addon.name)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(addon.name)
                                    .fontWeight(.medium)
                                Text(addon.description)
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .lineLimit(2)
                                Text(addon.categories.map { $0.rawValue }.joined(separator: " \u{2022} "))
                                    .font(.caption2)
                                    .foregroundColor(SVColor.onSurfaceVariant)
                            }

                            Spacer()

                            Button {
                                wrapper.viewModel.setInstallUrl(url: addon.url)
                                wrapper.viewModel.installAddon()
                            } label: {
                                if wrapper.state.isInstalling && wrapper.state.installingUrl == addon.url {
                                    ProgressView()
                                } else {
                                    Text("Install")
                                        .font(.subheadline)
                                        .fontWeight(.medium)
                                        .foregroundColor(wrapper.state.isInstalling ? .secondary : SVColor.amber)
                                }
                            }
                            .disabled(wrapper.state.isInstalling)
                        }
                    }
                }
            }
        }
        .navigationTitle("Content Sources")
        .confirmationDialog(
            "Remove Addon",
            isPresented: $showRemoveConfirmation,
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                if let url = addonToRemove {
                    wrapper.viewModel.removeAddon(manifestUrl: url)
                }
                addonToRemove = nil
            }
            Button("Cancel", role: .cancel) {
                addonToRemove = nil
            }
        } message: {
            Text("This addon and its catalog data will be removed.")
        }
    }

    @ViewBuilder
    private func addonInitial(_ name: String) -> some View {
        Text(String(name.prefix(1)).uppercased())
            .font(.headline)
            .fontWeight(.bold)
            .foregroundColor(SVColor.amber)
            .frame(width: 36, height: 36)
            .background(SVColor.amber.opacity(0.15))
            .clipShape(Circle())
    }
}
