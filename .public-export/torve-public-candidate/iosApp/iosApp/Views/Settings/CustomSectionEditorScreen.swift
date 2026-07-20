import SwiftUI
import shared

struct CustomSectionEditorScreen: View {
    @StateObject private var wrapper = HomeViewModelWrapper()
    @State private var customSections: [CustomSection] = []
    @State private var showAddSheet = false

    private var sectionCollector: Closeable?

    var body: some View {
        List {
            Section {
                if customSections.isEmpty {
                    VStack(spacing: 8) {
                        Image(systemName: "rectangle.stack.badge.plus")
                            .font(.title2)
                            .foregroundColor(.secondary)
                        Text("No custom sections yet")
                            .foregroundColor(.secondary)
                        Text("Create sections from TMDB discover filters to show on your Home screen")
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .multilineTextAlignment(.center)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 16)
                } else {
                    ForEach(Array(customSections.enumerated()), id: \.element.id) { index, section in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(section.title)
                                    .fontWeight(.medium)
                                HStack(spacing: 6) {
                                    Text(section.mediaType == "tv" ? "TV Shows" : section.mediaType == "both" ? "All" : "Movies")
                                        .font(.caption2)
                                        .padding(.horizontal, 6)
                                        .padding(.vertical, 2)
                                        .background(SVColor.amber.opacity(0.2))
                                        .foregroundColor(SVColor.amber)
                                        .clipShape(Capsule())

                                    Text(sortLabel(section.filters.sortBy))
                                        .font(.caption2)
                                        .foregroundColor(.secondary)
                                }
                                filterSummary(section.filters)
                            }
                            Spacer()
                            Toggle("", isOn: Binding(
                                get: { section.enabled },
                                set: { newValue in
                                    let updated = CustomSection(
                                        id: section.id,
                                        title: section.title,
                                        mediaType: section.mediaType,
                                        filters: section.filters,
                                        order: section.order,
                                        enabled: newValue
                                    )
                                    wrapper.viewModel.updateCustomSection(section: updated)
                                    loadSections()
                                }
                            ))
                            .labelsHidden()
                        }
                    }
                    .onDelete { indexSet in
                        for index in indexSet {
                            wrapper.viewModel.deleteCustomSection(sectionId: customSections[index].id)
                        }
                        loadSections()
                    }
                }
            } header: {
                HStack {
                    Text("Custom Sections")
                    Spacer()
                    Button {
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            } footer: {
                Text("Custom sections appear on the Home screen. Use TMDB filters to create curated content rails.")
            }
        }
        .navigationTitle("Custom Sections")
        .toolbar {
            EditButton()
        }
        .onAppear { loadSections() }
        .sheet(isPresented: $showAddSheet) {
            AddCustomSectionSheet(viewModel: wrapper.viewModel) {
                loadSections()
                showAddSheet = false
            }
        }
    }

    private func loadSections() {
        customSections = (wrapper.viewModel.customSections.value as? [CustomSection]) ?? []
    }

    @ViewBuilder
    private func filterSummary(_ filters: CustomSectionFilters) -> some View {
        let parts = buildFilterParts(filters)
        if !parts.isEmpty {
            Text(parts.joined(separator: " · "))
                .font(.caption)
                .foregroundColor(.secondary)
                .lineLimit(1)
        }
    }

    private func buildFilterParts(_ f: CustomSectionFilters) -> [String] {
        var parts: [String] = []
        if !f.genreIds.isEmpty { parts.append("\(f.genreIds.count) genres") }
        if let r = f.minRating { parts.append("≥ \(r)★") }
        if let yf = f.yearFrom { parts.append("From \(yf)") }
        if let yt = f.yearTo { parts.append("To \(yt)") }
        if !f.originCountries.isEmpty { parts.append(f.originCountries.joined(separator: ",")) }
        if !f.withKeywords.isEmpty { parts.append("\(f.withKeywords.count) keywords") }
        if !f.specificTmdbIds.isEmpty { parts.append("\(f.specificTmdbIds.count) titles") }
        return parts
    }

    private func sortLabel(_ sortBy: String) -> String {
        switch sortBy {
        case "popularity.desc": return "Popular"
        case "vote_average.desc": return "Top Rated"
        case "primary_release_date.desc": return "Newest"
        case "revenue.desc": return "Revenue"
        default: return sortBy
        }
    }
}

// MARK: - Add Custom Section Sheet

private struct AddCustomSectionSheet: View {
    let viewModel: HomeViewModel
    let onDone: () -> Void

    @StateObject private var settingsWrapper = SettingsViewModelWrapper()

    @State private var title: String = ""
    @State private var mediaType: String = "movie"
    @State private var sortBy: String = "popularity.desc"
    @State private var minRating: String = ""
    @State private var yearFrom: String = ""
    @State private var yearTo: String = ""
    @State private var originCountry: String = ""
    @State private var originalLanguage: String = ""
    @State private var runtimeGte: String = ""
    @State private var runtimeLte: String = ""
    @State private var selectedGenreIds: Set<Int32> = []
    @State private var keywordIds: [Int32] = []
    @State private var specificTmdbIds: [Int32] = []

    // AI search state
    @State private var aiSearchQuery: String = ""
    @State private var isAiSearching: Bool = false
    @State private var aiSearchError: String? = nil
    @State private var inferredKeywordTerms: [String] = []

    @Environment(\.dismiss) private var dismiss

    private let sortOptions: [(String, String)] = [
        ("popularity.desc", "Most Popular"),
        ("vote_average.desc", "Highest Rated"),
        ("primary_release_date.desc", "Newest First"),
        ("revenue.desc", "Highest Revenue"),
    ]

    private var movieGenres: [(Int32, String)] {
        [
            (28, "Action"), (12, "Adventure"), (16, "Animation"), (35, "Comedy"),
            (80, "Crime"), (99, "Documentary"), (18, "Drama"), (10751, "Family"),
            (14, "Fantasy"), (36, "History"), (27, "Horror"), (10402, "Music"),
            (9648, "Mystery"), (10749, "Romance"), (878, "Sci-Fi"),
            (53, "Thriller"), (10752, "War"), (37, "Western"),
        ]
    }

    private var tvGenres: [(Int32, String)] {
        [
            (10759, "Action & Adventure"), (16, "Animation"), (35, "Comedy"),
            (80, "Crime"), (99, "Documentary"), (18, "Drama"), (10751, "Family"),
            (10762, "Kids"), (9648, "Mystery"), (10763, "News"), (10764, "Reality"),
            (10765, "Sci-Fi & Fantasy"), (10766, "Soap"), (10767, "Talk"),
            (10768, "War & Politics"), (37, "Western"),
        ]
    }

    private var genres: [(Int32, String)] {
        mediaType == "tv" ? tvGenres : movieGenres
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    HStack {
                        TextField("e.g. dark sci-fi thrillers from the 90s", text: $aiSearchQuery)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .onSubmit { performAiSearch() }
                        if isAiSearching {
                            ProgressView()
                                .controlSize(.small)
                        } else {
                            Button {
                                performAiSearch()
                            } label: {
                                Image(systemName: "sparkles")
                                    .foregroundColor(SVColor.amber)
                            }
                            .disabled(aiSearchQuery.isEmpty)
                        }
                    }

                    if settingsWrapper.state.activeAiApiKey.isEmpty {
                        Text("No AI key configured — using TMDB keyword fallback")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }

                    if let error = aiSearchError {
                        Label(error, systemImage: "exclamationmark.triangle.fill")
                            .foregroundColor(SVColor.error)
                            .font(.caption)
                    }

                    if !inferredKeywordTerms.isEmpty {
                        HStack(spacing: 6) {
                            ForEach(inferredKeywordTerms, id: \.self) { term in
                                Text(term)
                                    .font(.caption2)
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(SVColor.amber.opacity(0.15))
                                    .foregroundColor(SVColor.amber)
                                    .clipShape(Capsule())
                            }
                        }
                    }
                } header: {
                    Text("AI Search")
                } footer: {
                    Text("Describe the kind of content you want and AI will auto-fill the filters below.")
                }

                Section("Basic") {
                    TextField("Section Name", text: $title)

                    Picker("Media Type", selection: $mediaType) {
                        Text("Movies").tag("movie")
                        Text("TV Shows").tag("tv")
                        Text("Both").tag("both")
                    }

                    Picker("Sort By", selection: $sortBy) {
                        ForEach(sortOptions, id: \.0) { value, label in
                            Text(label).tag(value)
                        }
                    }
                }

                Section("Genres") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100))], spacing: 8) {
                        ForEach(genres, id: \.0) { id, name in
                            Button {
                                if selectedGenreIds.contains(id) {
                                    selectedGenreIds.remove(id)
                                } else {
                                    selectedGenreIds.insert(id)
                                }
                            } label: {
                                Text(name)
                                    .font(.caption)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 6)
                                    .frame(maxWidth: .infinity)
                                    .background(selectedGenreIds.contains(id) ? SVColor.amber : Color(.tertiarySystemBackground))
                                    .foregroundColor(selectedGenreIds.contains(id) ? .black : .primary)
                                    .clipShape(Capsule())
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.vertical, 4)
                }

                Section("Filters") {
                    TextField("Min Rating (0-10)", text: $minRating)
                        .keyboardType(.decimalPad)
                    TextField("Year From", text: $yearFrom)
                        .keyboardType(.numberPad)
                    TextField("Year To", text: $yearTo)
                        .keyboardType(.numberPad)
                    TextField("Country Code (e.g. US)", text: $originCountry)
                        .textInputAutocapitalization(.characters)
                    TextField("Language (e.g. en)", text: $originalLanguage)
                        .textInputAutocapitalization(.never)
                }

                Section("Runtime (minutes)") {
                    TextField("Min Runtime", text: $runtimeGte)
                        .keyboardType(.numberPad)
                    TextField("Max Runtime", text: $runtimeLte)
                        .keyboardType(.numberPad)
                }
            }
            .navigationTitle("New Section")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        createSection()
                    }
                    .disabled(title.isEmpty)
                }
            }
        }
    }

    private func performAiSearch() {
        guard !aiSearchQuery.isEmpty else { return }
        isAiSearching = true
        aiSearchError = nil

        let koin = KoinHelper.shared.getKoin()
        guard let searchService = koin.get(objCClass: KeywordSearchService.self) as? KeywordSearchService else {
            aiSearchError = "Search service not available"
            isAiSearching = false
            return
        }

        let aiKey = settingsWrapper.state.activeAiApiKey
        let provider = settingsWrapper.state.aiProvider
        let query = aiSearchQuery

        Task {
            do {
                let result: KeywordSearchResult
                if !aiKey.isEmpty {
                    result = try await searchService.searchWithAi(provider: provider, apiKey: aiKey, phrase: query)
                } else {
                    result = try await searchService.searchWithTmdbFallback(phrase: query)
                }

                await MainActor.run {
                    if !result.title.isEmpty { title = result.title }

                    if result.mode == "specific", !result.specificItems.isEmpty {
                        specificTmdbIds = result.specificItems.map { Int32($0.tmdbId) }
                        selectedGenreIds = []
                        keywordIds = []
                        yearFrom = ""
                        yearTo = ""
                        minRating = ""
                        sortBy = "popularity.desc"
                    } else {
                        specificTmdbIds = []
                        if !result.genreIds.isEmpty {
                            selectedGenreIds = Set(result.genreIds.map { ($0 as! KotlinInt).int32Value })
                        }
                        if !result.keywordIds.isEmpty {
                            keywordIds = result.keywordIds.map { ($0 as! KotlinInt).int32Value }
                        }
                        if let yf = result.yearFrom { yearFrom = "\(yf)" }
                        if let yt = result.yearTo { yearTo = "\(yt)" }
                        if !result.sortBy.isEmpty { sortBy = result.sortBy }
                        if let mr = result.minRating { minRating = "\(mr)" }
                        if let mt = result.mediaType { mediaType = mt }
                    }

                    inferredKeywordTerms = result.inferredKeywordTerms as? [String] ?? []
                    isAiSearching = false
                }
            } catch {
                await MainActor.run {
                    aiSearchError = error.localizedDescription
                    isAiSearching = false
                }
            }
        }
    }

    private func createSection() {
        let countries: [String] = originCountry.isEmpty ? [] :
            originCountry.split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces) }

        let filters = CustomSectionFilters(
            genreIds: selectedGenreIds.map { KotlinInt(int: $0) },
            sortBy: sortBy,
            minRating: Float(minRating).map { KotlinFloat(float: $0) },
            yearFrom: Int32(yearFrom).map { KotlinInt(int: $0) },
            yearTo: Int32(yearTo).map { KotlinInt(int: $0) },
            originCountries: countries,
            originalLanguage: originalLanguage.isEmpty ? nil : originalLanguage,
            runtimeGte: Int32(runtimeGte).map { KotlinInt(int: $0) },
            runtimeLte: Int32(runtimeLte).map { KotlinInt(int: $0) },
            certification: nil,
            certificationGte: nil,
            certificationLte: nil,
            certificationCountry: nil,
            withCast: [],
            withCrew: [],
            withWatchProviders: [],
            watchRegion: nil,
            withKeywords: keywordIds.map { KotlinInt(int: $0) },
            specificTmdbIds: specificTmdbIds.map { KotlinInt(int: $0) }
        )

        let section = CustomSection(
            id: UUID().uuidString,
            title: title,
            mediaType: mediaType,
            filters: filters,
            order: Int32(0),
            enabled: true
        )

        viewModel.addCustomSection(section: section)
        onDone()
    }
}
