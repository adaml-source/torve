import SwiftUI
import shared

struct HomeLayoutScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var sections: [HomeSectionItem] = [
        HomeSectionItem(id: "hero", title: "Hero Banner", icon: "star.fill", enabled: true, isHero: true),
        HomeSectionItem(id: "continue_watching", title: "Continue Watching", icon: "play.circle", enabled: true),
        HomeSectionItem(id: "trending_movies", title: "Trending Movies", icon: "flame", enabled: true),
        HomeSectionItem(id: "trending_shows", title: "Trending Shows", icon: "flame", enabled: true),
        HomeSectionItem(id: "popular_movies", title: "Popular Movies", icon: "chart.bar", enabled: true),
        HomeSectionItem(id: "popular_shows", title: "Popular Shows", icon: "chart.bar", enabled: true),
        HomeSectionItem(id: "top_rated_movies", title: "Top Rated Movies", icon: "star.fill", enabled: true),
        HomeSectionItem(id: "top_rated_shows", title: "Top Rated Shows", icon: "star.fill", enabled: true),
        HomeSectionItem(id: "watchlist", title: "Watchlist", icon: "bookmark", enabled: true),
        HomeSectionItem(id: "recently_added", title: "Recently Added", icon: "clock", enabled: true),
    ]

    @State private var customSections: [CustomSectionItem] = []
    @State private var expandedSectionId: String? = nil

    private var presets: [CardStylePreset] {
        wrapper.state.cardStylePresets as? [CardStylePreset] ?? []
    }

    var body: some View {
        List {
            Section {
                Text("Toggle sections on or off and drag to reorder. Tap the chevron to customize card style per section.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            // Hero section (non-draggable, always enabled)
            if let hero = sections.first(where: { $0.isHero }) {
                Section("Hero") {
                    HStack {
                        Image(systemName: hero.icon)
                            .foregroundColor(SVColor.amber)
                            .frame(width: 20)
                        Text(hero.title)
                            .fontWeight(.medium)
                        Spacer()
                        Text("Always On")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
            }

            // Draggable sections
            Section("Sections") {
                ForEach($sections.filter({ !$0.wrappedValue.isHero })) { $section in
                    DisclosureGroup(
                        isExpanded: Binding(
                            get: { expandedSectionId == section.id },
                            set: { expandedSectionId = $0 ? section.id : nil }
                        )
                    ) {
                        // Card style picker per section
                        if !presets.isEmpty {
                            Picker("Card Style", selection: Binding(
                                get: { section.presetId ?? "default" },
                                set: { section.presetId = $0 == "default" ? nil : $0 }
                            )) {
                                Text("Default").tag("default")
                                ForEach(presets, id: \.presetId) { preset in
                                    Text(preset.name).tag(preset.presetId)
                                }
                            }
                            .pickerStyle(.menu)
                            .tint(SVColor.amber)
                        }

                        Button("Reset Section") {
                            section.enabled = true
                            section.presetId = nil
                        }
                        .foregroundColor(SVColor.amber)
                        .font(.subheadline)
                    } label: {
                        HStack {
                            Image(systemName: section.icon)
                                .foregroundColor(section.enabled ? SVColor.amber : .secondary)
                                .frame(width: 20)
                            Text(section.title)
                                .fontWeight(.medium)
                                .foregroundColor(section.enabled ? SVColor.onSurface : SVColor.onSurfaceVariant)
                            Spacer()
                            Toggle("", isOn: $section.enabled)
                                .labelsHidden()
                        }
                    }
                }
                .onMove { source, destination in
                    var movable = sections.filter { !$0.isHero }
                    movable.move(fromOffsets: source, toOffset: destination)
                    sections = [sections.first(where: { $0.isHero })].compactMap { $0 } + movable
                }
            }

            // Custom sections
            Section {
                if customSections.isEmpty {
                    Text("No custom sections added yet")
                        .foregroundColor(.secondary)
                } else {
                    ForEach($customSections) { $section in
                        HStack {
                            Image(systemName: "rectangle.stack")
                                .foregroundColor(SVColor.amber)
                                .frame(width: 20)
                            Text(section.title)
                                .fontWeight(.medium)
                            Spacer()
                            Toggle("", isOn: $section.enabled)
                                .labelsHidden()
                        }
                    }
                    .onMove { source, destination in
                        customSections.move(fromOffsets: source, toOffset: destination)
                    }
                    .onDelete { indexSet in
                        customSections.remove(atOffsets: indexSet)
                    }
                }
            } header: {
                HStack {
                    Text("Custom Sections")
                    Spacer()
                    NavigationLink(value: Route.customSectionEditor) {
                        Image(systemName: "plus")
                    }
                }
            }

            // Reset all
            Section {
                Button("Reset All") {
                    sections = [
                        HomeSectionItem(id: "hero", title: "Hero Banner", icon: "star.fill", enabled: true, isHero: true),
                        HomeSectionItem(id: "continue_watching", title: "Continue Watching", icon: "play.circle", enabled: true),
                        HomeSectionItem(id: "trending_movies", title: "Trending Movies", icon: "flame", enabled: true),
                        HomeSectionItem(id: "trending_shows", title: "Trending Shows", icon: "flame", enabled: true),
                        HomeSectionItem(id: "popular_movies", title: "Popular Movies", icon: "chart.bar", enabled: true),
                        HomeSectionItem(id: "popular_shows", title: "Popular Shows", icon: "chart.bar", enabled: true),
                        HomeSectionItem(id: "top_rated_movies", title: "Top Rated Movies", icon: "star.fill", enabled: true),
                        HomeSectionItem(id: "top_rated_shows", title: "Top Rated Shows", icon: "star.fill", enabled: true),
                        HomeSectionItem(id: "watchlist", title: "Watchlist", icon: "bookmark", enabled: true),
                        HomeSectionItem(id: "recently_added", title: "Recently Added", icon: "clock", enabled: true),
                    ]
                    expandedSectionId = nil
                }
                .foregroundColor(SVColor.amber)
            }
        }
        .navigationTitle("Home Layout")
        .toolbar {
            EditButton()
        }
    }
}

private struct HomeSectionItem: Identifiable {
    let id: String
    var title: String
    var icon: String
    var enabled: Bool
    var isHero: Bool = false
    var presetId: String? = nil
}

private struct CustomSectionItem: Identifiable {
    let id: String
    var title: String
    var enabled: Bool
}
