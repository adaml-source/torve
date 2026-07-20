import SwiftUI
import shared

struct CardStyleSettingsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    private var presets: [CardStylePreset] {
        wrapper.state.cardStylePresets as? [CardStylePreset] ?? []
    }

    private var defaultPresetId: String? {
        wrapper.state.globalDefaultPresetId
    }

    var body: some View {
        List {
            // Preset selection
            Section {
                ForEach(presets, id: \.presetId) { preset in
                    Button {
                        wrapper.viewModel.setDefaultCardStylePreset(presetId: preset.presetId)
                    } label: {
                        HStack(spacing: 12) {
                            // Preview card thumbnail
                            presetPreview(preset)

                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(preset.name)
                                        .fontWeight(.medium)
                                        .foregroundColor(SVColor.onSurface)
                                    if preset.isBuiltIn {
                                        Text("Built-in")
                                            .font(.caption2)
                                            .padding(.horizontal, 6)
                                            .padding(.vertical, 2)
                                            .background(SVColor.amber.opacity(0.2))
                                            .foregroundColor(SVColor.amber)
                                            .clipShape(Capsule())
                                    }
                                }
                                presetDetails(preset)
                            }

                            Spacer()

                            if defaultPresetId == preset.presetId {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(SVColor.amber)
                            }
                        }
                    }
                    .buttonStyle(.plain)
                }
            } header: {
                Text("Card Style Presets")
            } footer: {
                Text("Choose how media cards appear throughout the app. This affects home shelves, catalog grids, and search results.")
            }

            // Per-preset customization (for selected preset)
            if let selectedPreset = presets.first(where: { $0.presetId == defaultPresetId }) {
                Section("Customize: \(selectedPreset.name)") {
                    let style = selectedPreset.cardStyle

                    Toggle("Show Ratings", isOn: Binding(
                        get: { style.ratingPrefs.showRatingsOnDetailPage },
                        set: { newVal in
                            let newRatingPrefs = style.ratingPrefs.doCopy(
                                showRatingsOnDetailPage: newVal,
                                showTorveScoreOnDetailPage: style.ratingPrefs.showTorveScoreOnDetailPage,
                                showTorveScoreOnCards: style.ratingPrefs.showTorveScoreOnCards,
                                torveWeights: style.ratingPrefs.torveWeights,
                                enabledProviders: style.ratingPrefs.enabledProviders,
                                providerOrder: style.ratingPrefs.providerOrder,
                                maxRatingsOnCard: style.ratingPrefs.maxRatingsOnCard,
                                allowRatingsOnLandscapeCards: style.ratingPrefs.allowRatingsOnLandscapeCards,
                                pillStyle: style.ratingPrefs.pillStyle,
                                pillPosition: style.ratingPrefs.pillPosition
                            )
                            let newStyle = style.doCopy(
                                size: style.size,
                                hover: style.hover,
                                watched: style.watched,
                                appearance: style.appearance,
                                ratingPrefs: newRatingPrefs
                            )
                            wrapper.viewModel.updateCardStylePreset(presetId: selectedPreset.presetId, style: newStyle)
                        }
                    ))

                    // Rating position for this preset
                    Picker("Rating Position", selection: Binding(
                        get: { style.ratingPrefs.pillPosition },
                        set: { newPos in
                            let newRatingPrefs = style.ratingPrefs.doCopy(
                                showRatingsOnDetailPage: style.ratingPrefs.showRatingsOnDetailPage,
                                showTorveScoreOnDetailPage: style.ratingPrefs.showTorveScoreOnDetailPage,
                                showTorveScoreOnCards: style.ratingPrefs.showTorveScoreOnCards,
                                torveWeights: style.ratingPrefs.torveWeights,
                                enabledProviders: style.ratingPrefs.enabledProviders,
                                providerOrder: style.ratingPrefs.providerOrder,
                                maxRatingsOnCard: style.ratingPrefs.maxRatingsOnCard,
                                allowRatingsOnLandscapeCards: style.ratingPrefs.allowRatingsOnLandscapeCards,
                                pillStyle: style.ratingPrefs.pillStyle,
                                pillPosition: newPos
                            )
                            let newStyle = style.doCopy(
                                size: style.size,
                                hover: style.hover,
                                watched: style.watched,
                                appearance: style.appearance,
                                ratingPrefs: newRatingPrefs
                            )
                            wrapper.viewModel.updateCardStylePreset(presetId: selectedPreset.presetId, style: newStyle)
                        }
                    )) {
                        Text("Inside Card").tag(RatingPillPosition.inside)
                        Text("Outside Card").tag(RatingPillPosition.outside)
                    }
                    .pickerStyle(.segmented)

                    // Corner radius
                    VStack(alignment: .leading) {
                        Text("Corner Radius: \(Int(style.appearance.cornerRadius))")
                        Slider(
                            value: Binding(
                                get: { Double(style.appearance.cornerRadius) },
                                set: { newVal in
                                    let newAppearance = style.appearance.doCopy(
                                        cornerRadius: Float(newVal),
                                        showTitle: style.appearance.showTitle,
                                        showYear: style.appearance.showYear,
                                        showOverview: style.appearance.showOverview
                                    )
                                    let newStyle = style.doCopy(
                                        size: style.size,
                                        hover: style.hover,
                                        watched: style.watched,
                                        appearance: newAppearance,
                                        ratingPrefs: style.ratingPrefs
                                    )
                                    wrapper.viewModel.updateCardStylePreset(presetId: selectedPreset.presetId, style: newStyle)
                                }
                            ),
                            in: 0...24,
                            step: 2
                        )
                        .tint(SVColor.amber)
                    }

                    Toggle("Show Title", isOn: Binding(
                        get: { style.appearance.showTitle },
                        set: { newVal in
                            let newAppearance = style.appearance.doCopy(
                                cornerRadius: style.appearance.cornerRadius,
                                showTitle: newVal,
                                showYear: style.appearance.showYear,
                                showOverview: style.appearance.showOverview
                            )
                            let newStyle = style.doCopy(
                                size: style.size,
                                hover: style.hover,
                                watched: style.watched,
                                appearance: newAppearance,
                                ratingPrefs: style.ratingPrefs
                            )
                            wrapper.viewModel.updateCardStylePreset(presetId: selectedPreset.presetId, style: newStyle)
                        }
                    ))

                    Toggle("Show Year", isOn: Binding(
                        get: { style.appearance.showYear },
                        set: { newVal in
                            let newAppearance = style.appearance.doCopy(
                                cornerRadius: style.appearance.cornerRadius,
                                showTitle: style.appearance.showTitle,
                                showYear: newVal,
                                showOverview: style.appearance.showOverview
                            )
                            let newStyle = style.doCopy(
                                size: style.size,
                                hover: style.hover,
                                watched: style.watched,
                                appearance: newAppearance,
                                ratingPrefs: style.ratingPrefs
                            )
                            wrapper.viewModel.updateCardStylePreset(presetId: selectedPreset.presetId, style: newStyle)
                        }
                    ))

                    Toggle("Show Overview", isOn: Binding(
                        get: { style.appearance.showOverview },
                        set: { newVal in
                            let newAppearance = style.appearance.doCopy(
                                cornerRadius: style.appearance.cornerRadius,
                                showTitle: style.appearance.showTitle,
                                showYear: style.appearance.showYear,
                                showOverview: newVal
                            )
                            let newStyle = style.doCopy(
                                size: style.size,
                                hover: style.hover,
                                watched: style.watched,
                                appearance: newAppearance,
                                ratingPrefs: style.ratingPrefs
                            )
                            wrapper.viewModel.updateCardStylePreset(presetId: selectedPreset.presetId, style: newStyle)
                        }
                    ))
                }
            }

            // Preview
            Section("Preview") {
                HStack(spacing: 16) {
                    ForEach(["Poster", "Wide"], id: \.self) { mode in
                        VStack(spacing: 6) {
                            RoundedRectangle(cornerRadius: cornerRadiusForPreview)
                                .fill(SVColor.surfaceVariant)
                                .frame(
                                    width: mode == "Poster" ? 100 : 140,
                                    height: mode == "Poster" ? 150 : 80
                                )
                                .overlay(
                                    VStack(spacing: 4) {
                                        Image(systemName: "photo")
                                            .font(.title2)
                                            .foregroundColor(SVColor.onSurfaceVariant)
                                        Text(mode)
                                            .font(.caption2)
                                            .foregroundColor(SVColor.onSurfaceVariant)
                                    }
                                )
                            if showTitleForPreview {
                                Text("Title")
                                    .font(.caption)
                                    .foregroundColor(SVColor.onSurface)
                            }
                            if showYearForPreview {
                                Text("2024")
                                    .font(.caption2)
                                    .foregroundColor(.secondary)
                            }
                        }
                    }
                }
                .padding(.vertical, 8)
            }
        }
        .navigationTitle("Card Style")
    }

    // MARK: - Helpers

    private var cornerRadiusForPreview: CGFloat {
        if let preset = presets.first(where: { $0.presetId == defaultPresetId }) {
            return CGFloat(preset.cardStyle.appearance.cornerRadius)
        }
        return 8
    }

    private var showTitleForPreview: Bool {
        if let preset = presets.first(where: { $0.presetId == defaultPresetId }) {
            return preset.cardStyle.appearance.showTitle
        }
        return true
    }

    private var showYearForPreview: Bool {
        if let preset = presets.first(where: { $0.presetId == defaultPresetId }) {
            return preset.cardStyle.appearance.showYear
        }
        return true
    }

    @ViewBuilder
    private func presetPreview(_ preset: CardStylePreset) -> some View {
        RoundedRectangle(cornerRadius: CGFloat(preset.cardStyle.appearance.cornerRadius))
            .fill(SVColor.surfaceVariant)
            .frame(width: 48, height: 72)
            .overlay(
                Text(String(preset.name.prefix(1)))
                    .font(.title3)
                    .fontWeight(.bold)
                    .foregroundColor(SVColor.amber)
            )
            .overlay(
                RoundedRectangle(cornerRadius: CGFloat(preset.cardStyle.appearance.cornerRadius))
                    .stroke(
                        preset.presetId == defaultPresetId ? SVColor.amber : Color.clear,
                        lineWidth: 2
                    )
            )
    }

    @ViewBuilder
    private func presetDetails(_ preset: CardStylePreset) -> some View {
        let style = preset.cardStyle
        HStack(spacing: 8) {
            if style.appearance.showTitle {
                detailPill("Title")
            }
            if style.appearance.showYear {
                detailPill("Year")
            }
            detailPill("R:\(Int(style.appearance.cornerRadius))")
        }
    }

    @ViewBuilder
    private func detailPill(_ text: String) -> some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(SVColor.surfaceVariant)
            .foregroundColor(SVColor.onSurfaceVariant)
            .clipShape(Capsule())
    }
}
