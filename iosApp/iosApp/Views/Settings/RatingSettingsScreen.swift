import SwiftUI
import shared

struct RatingSettingsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    private var prefs: RatingDisplayPrefs {
        wrapper.state.ratingPrefs
    }

    var body: some View {
        List {
            // Master toggles
            Section("Display Options") {
                Toggle("Show Ratings on Detail Page", isOn: Binding(
                    get: { prefs.showRatingsOnDetailPage },
                    set: { updatePrefs(prefs.doCopy(
                        showRatingsOnDetailPage: $0,
                        showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
                        showTorveScoreOnCards: prefs.showTorveScoreOnCards,
                        torveWeights: prefs.torveWeights,
                        enabledProviders: prefs.enabledProviders,
                        providerOrder: prefs.providerOrder,
                        maxRatingsOnCard: prefs.maxRatingsOnCard,
                        allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
                        pillStyle: prefs.pillStyle,
                        pillPosition: prefs.pillPosition
                    )) }
                ))

                Toggle("TORVE Score on Detail Page", isOn: Binding(
                    get: { prefs.showTorveScoreOnDetailPage },
                    set: { updatePrefs(prefs.doCopy(
                        showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
                        showTorveScoreOnDetailPage: $0,
                        showTorveScoreOnCards: prefs.showTorveScoreOnCards,
                        torveWeights: prefs.torveWeights,
                        enabledProviders: prefs.enabledProviders,
                        providerOrder: prefs.providerOrder,
                        maxRatingsOnCard: prefs.maxRatingsOnCard,
                        allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
                        pillStyle: prefs.pillStyle,
                        pillPosition: prefs.pillPosition
                    )) }
                ))

                Toggle("TORVE Score on Cards", isOn: Binding(
                    get: { prefs.showTorveScoreOnCards },
                    set: { updatePrefs(prefs.doCopy(
                        showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
                        showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
                        showTorveScoreOnCards: $0,
                        torveWeights: prefs.torveWeights,
                        enabledProviders: prefs.enabledProviders,
                        providerOrder: prefs.providerOrder,
                        maxRatingsOnCard: prefs.maxRatingsOnCard,
                        allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
                        pillStyle: prefs.pillStyle,
                        pillPosition: prefs.pillPosition
                    )) }
                ))

                Toggle("Ratings on Landscape Cards", isOn: Binding(
                    get: { prefs.allowRatingsOnLandscapeCards },
                    set: { updatePrefs(prefs.doCopy(
                        showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
                        showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
                        showTorveScoreOnCards: prefs.showTorveScoreOnCards,
                        torveWeights: prefs.torveWeights,
                        enabledProviders: prefs.enabledProviders,
                        providerOrder: prefs.providerOrder,
                        maxRatingsOnCard: prefs.maxRatingsOnCard,
                        allowRatingsOnLandscapeCards: $0,
                        pillStyle: prefs.pillStyle,
                        pillPosition: prefs.pillPosition
                    )) }
                ))
            }

            // TORVE weight sliders
            Section {
                ForEach(torveWeightEntries, id: \.source) { entry in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(entry.source.displayName)
                            Spacer()
                            Text("\(entry.weight)")
                                .fontWeight(.bold)
                                .foregroundColor(SVColor.amber)
                        }
                        Slider(
                            value: Binding(
                                get: { Double(entry.weight) },
                                set: { newVal in
                                    var weights = Dictionary(uniqueKeysWithValues:
                                        torveWeightEntries.map { ($0.source, KotlinInt(int: $0.weight)) }
                                    )
                                    weights[entry.source] = KotlinInt(int: Int32(newVal))
                                    updatePrefsWithWeights(weights)
                                }
                            ),
                            in: 0...100,
                            step: 1
                        )
                        .tint(SVColor.amber)
                    }
                }

                Button("Reset Weights") {
                    let defaults = MediaRatingsKt.defaultTorveWeights()
                    updatePrefsWithWeights(defaults)
                }
                .foregroundColor(SVColor.amber)
            } header: {
                Text("TORVE Score Weights")
            } footer: {
                Text("Adjust how much each rating source contributes to the combined TORVE score.")
            }

            // Pill position
            Section("Rating Pill Position") {
                Picker("Position", selection: Binding(
                    get: { prefs.pillPosition },
                    set: { newPos in
                        updatePrefs(prefs.doCopy(
                            showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
                            showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
                            showTorveScoreOnCards: prefs.showTorveScoreOnCards,
                            torveWeights: prefs.torveWeights,
                            enabledProviders: prefs.enabledProviders,
                            providerOrder: prefs.providerOrder,
                            maxRatingsOnCard: prefs.maxRatingsOnCard,
                            allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
                            pillStyle: prefs.pillStyle,
                            pillPosition: newPos
                        ))
                    }
                )) {
                    Text("Inside Card").tag(RatingPillPosition.inside)
                    Text("Outside Card").tag(RatingPillPosition.outside)
                }
                .pickerStyle(.segmented)
            }

            // Max ratings on card
            Section {
                Stepper(
                    "Max Ratings on Card: \(prefs.maxRatingsOnCard)",
                    value: Binding(
                        get: { Int(prefs.maxRatingsOnCard) },
                        set: { newVal in
                            updatePrefs(prefs.doCopy(
                                showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
                                showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
                                showTorveScoreOnCards: prefs.showTorveScoreOnCards,
                                torveWeights: prefs.torveWeights,
                                enabledProviders: prefs.enabledProviders,
                                providerOrder: prefs.providerOrder,
                                maxRatingsOnCard: Int32(newVal),
                                allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
                                pillStyle: prefs.pillStyle,
                                pillPosition: prefs.pillPosition
                            ))
                        }
                    ),
                    in: 1...9
                )
            } footer: {
                Text("Maximum number of rating pills shown on a single card.")
            }

            // Pill style
            Section("Pill Style") {
                Picker("Style", selection: Binding(
                    get: { prefs.pillStyle },
                    set: { newStyle in
                        updatePrefs(prefs.doCopy(
                            showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
                            showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
                            showTorveScoreOnCards: prefs.showTorveScoreOnCards,
                            torveWeights: prefs.torveWeights,
                            enabledProviders: prefs.enabledProviders,
                            providerOrder: prefs.providerOrder,
                            maxRatingsOnCard: prefs.maxRatingsOnCard,
                            allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
                            pillStyle: newStyle,
                            pillPosition: prefs.pillPosition
                        ))
                    }
                )) {
                    Text("With Icon").tag(RatingPillStyle.icon)
                    Text("With Letter").tag(RatingPillStyle.letter)
                }
                .pickerStyle(.segmented)
            }

            // Per-source toggles with reorder
            Section {
                ForEach(Array(providerOrder.enumerated()), id: \.element) { index, source in
                    HStack {
                        // Reorder arrows
                        VStack(spacing: 0) {
                            Button {
                                moveProvider(at: index, direction: -1)
                            } label: {
                                Image(systemName: "chevron.up")
                                    .font(.caption)
                                    .foregroundColor(index > 0 ? SVColor.amber : .secondary)
                            }
                            .disabled(index == 0)
                            .buttonStyle(.plain)

                            Button {
                                moveProvider(at: index, direction: 1)
                            } label: {
                                Image(systemName: "chevron.down")
                                    .font(.caption)
                                    .foregroundColor(index < providerOrder.count - 1 ? SVColor.amber : .secondary)
                            }
                            .disabled(index >= providerOrder.count - 1)
                            .buttonStyle(.plain)
                        }
                        .frame(width: 24)

                        // Source icon letter
                        Text(source.iconChar)
                            .font(.caption)
                            .fontWeight(.bold)
                            .foregroundColor(SVColor.amber)
                            .frame(width: 28, height: 28)
                            .background(SVColor.amber.opacity(0.15))
                            .clipShape(RoundedRectangle(cornerRadius: 6))

                        VStack(alignment: .leading, spacing: 2) {
                            Text(source.displayName)
                                .fontWeight(.semibold)
                                .foregroundColor(isSourceEnabled(source) ? SVColor.onSurface : SVColor.onSurfaceVariant)
                        }

                        Spacer()

                        Toggle("", isOn: Binding(
                            get: { isSourceEnabled(source) },
                            set: { enabled in toggleSource(source, enabled: enabled) }
                        ))
                        .labelsHidden()
                    }
                }
            } header: {
                Text("Rating Sources")
            } footer: {
                Text("Enable, disable, and reorder which rating sources appear. Drag arrows to change priority.")
            }

            // Reset all
            Section {
                Button("Reset to Defaults") {
                    wrapper.viewModel.updateRatingPrefs(prefs: RatingDisplayPrefs())
                }
                .foregroundColor(SVColor.amber)
            }
        }
        .navigationTitle("Ratings")
    }

    // MARK: - Helpers

    private struct TorveWeightEntry {
        let source: RatingSource
        let weight: Int32
    }

    private var torveWeightEntries: [TorveWeightEntry] {
        let weights = prefs.torveWeights as? [RatingSource: KotlinInt] ?? [:]
        return weights.map { TorveWeightEntry(source: $0.key, weight: $0.value.int32Value) }
            .sorted { $0.source.displayName < $1.source.displayName }
    }

    private var providerOrder: [RatingSource] {
        prefs.providerOrder as? [RatingSource] ?? []
    }

    private func isSourceEnabled(_ source: RatingSource) -> Bool {
        let enabled = prefs.enabledProviders as? [RatingSource] ?? []
        return enabled.contains(source)
    }

    private func toggleSource(_ source: RatingSource, enabled: Bool) {
        var currentEnabled = prefs.enabledProviders as? [RatingSource] ?? []
        if enabled {
            if !currentEnabled.contains(source) {
                currentEnabled.append(source)
            }
        } else {
            currentEnabled.removeAll { $0 == source }
        }
        updatePrefs(prefs.doCopy(
            showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
            showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
            showTorveScoreOnCards: prefs.showTorveScoreOnCards,
            torveWeights: prefs.torveWeights,
            enabledProviders: currentEnabled,
            providerOrder: prefs.providerOrder,
            maxRatingsOnCard: prefs.maxRatingsOnCard,
            allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
            pillStyle: prefs.pillStyle,
            pillPosition: prefs.pillPosition
        ))
    }

    private func moveProvider(at index: Int, direction: Int) {
        var order = providerOrder
        let newIndex = index + direction
        guard newIndex >= 0, newIndex < order.count else { return }
        let item = order.remove(at: index)
        order.insert(item, at: newIndex)
        updatePrefs(prefs.doCopy(
            showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
            showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
            showTorveScoreOnCards: prefs.showTorveScoreOnCards,
            torveWeights: prefs.torveWeights,
            enabledProviders: prefs.enabledProviders,
            providerOrder: order,
            maxRatingsOnCard: prefs.maxRatingsOnCard,
            allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
            pillStyle: prefs.pillStyle,
            pillPosition: prefs.pillPosition
        ))
    }

    private func updatePrefs(_ newPrefs: RatingDisplayPrefs) {
        wrapper.viewModel.updateRatingPrefs(prefs: newPrefs)
    }

    private func updatePrefsWithWeights(_ weights: [RatingSource: KotlinInt]) {
        updatePrefs(prefs.doCopy(
            showRatingsOnDetailPage: prefs.showRatingsOnDetailPage,
            showTorveScoreOnDetailPage: prefs.showTorveScoreOnDetailPage,
            showTorveScoreOnCards: prefs.showTorveScoreOnCards,
            torveWeights: weights,
            enabledProviders: prefs.enabledProviders,
            providerOrder: prefs.providerOrder,
            maxRatingsOnCard: prefs.maxRatingsOnCard,
            allowRatingsOnLandscapeCards: prefs.allowRatingsOnLandscapeCards,
            pillStyle: prefs.pillStyle,
            pillPosition: prefs.pillPosition
        ))
    }
}
