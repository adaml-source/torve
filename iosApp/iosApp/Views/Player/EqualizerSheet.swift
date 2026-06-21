import SwiftUI

/// Equalizer sheet matching Android's EqualizerSheet.
/// Dark theme with amber accents, 10-band sliders, preset picker, bass boost & loudness.
struct EqualizerSheet: View {
    @ObservedObject var equalizer: iOSAudioEqualizer
    @Environment(\.dismiss) private var dismiss

    private let frequencyLabels = iOSAudioEqualizer.bandFrequencies.map { hz -> String in
        hz >= 1000 ? "\(Int(hz / 1000))k" : "\(Int(hz))"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    // Enable toggle
                    enableToggle

                    // Preset picker
                    presetPicker

                    // Band sliders
                    bandSlidersSection

                    // Bass boost
                    effectSlider(
                        label: "Bass Boost",
                        value: Binding(
                            get: { equalizer.bassBoost },
                            set: { equalizer.setBassBoost($0) }
                        ),
                        range: 0...1
                    )

                    // Loudness
                    effectSlider(
                        label: "Loudness",
                        value: Binding(
                            get: { equalizer.loudnessGain },
                            set: { equalizer.setLoudnessGain($0) }
                        ),
                        range: 0...1
                    )

                    // Reset button
                    Button {
                        equalizer.reset()
                    } label: {
                        Text("Reset")
                            .font(.subheadline.weight(.medium))
                            .foregroundColor(SVColor.onSurfaceVariant)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(SVColor.surfaceVariant)
                            .cornerRadius(8)
                    }
                    .padding(.top, 4)
                }
                .padding()
            }
            .background(SVColor.surface.ignoresSafeArea())
            .navigationTitle("Equalizer")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                        .foregroundColor(SVColor.amber)
                }
            }
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbarBackground(SVColor.surface, for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
        }
        .preferredColorScheme(.dark)
    }

    // MARK: - Enable Toggle

    private var enableToggle: some View {
        HStack {
            Text("Enabled")
                .font(.subheadline)
                .foregroundColor(SVColor.onSurface)
            Spacer()
            Toggle("", isOn: Binding(
                get: { equalizer.isEnabled },
                set: { equalizer.setEnabled($0) }
            ))
            .tint(SVColor.amber)
            .labelsHidden()
        }
        .padding(.horizontal, 4)
    }

    // MARK: - Preset Picker

    private var presetPicker: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Preset")
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)

            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(EqPreset.allCases.filter { $0 != .custom }, id: \.id) { preset in
                        presetChip(preset)
                    }
                }
            }
        }
    }

    private func presetChip(_ preset: EqPreset) -> some View {
        let isSelected = equalizer.selectedPreset == preset
        return Button {
            equalizer.applyPreset(preset)
        } label: {
            Text(preset.rawValue)
                .font(.caption.weight(.medium))
                .foregroundColor(isSelected ? SVColor.obsidian : SVColor.onSurface)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(isSelected ? SVColor.amber : SVColor.surfaceVariant)
                .cornerRadius(16)
        }
    }

    // MARK: - Band Sliders

    private var bandSlidersSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Bands")
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
                .padding(.leading, 4)

            ForEach(0..<iOSAudioEqualizer.bandCount, id: \.self) { index in
                bandSliderRow(index: index)
            }
        }
    }

    private func bandSliderRow(index: Int) -> some View {
        HStack(spacing: 8) {
            Text(frequencyLabels[index])
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundColor(SVColor.onSurfaceVariant)
                .frame(width: 28, alignment: .trailing)

            Slider(
                value: Binding(
                    get: { equalizer.bandGains[index] },
                    set: { equalizer.setBandGain(band: index, gain: $0) }
                ),
                in: Double(iOSAudioEqualizer.minGain)...Double(iOSAudioEqualizer.maxGain)
            )
            .tint(equalizer.isEnabled ? SVColor.amber : SVColor.onSurfaceVariant)
            .disabled(!equalizer.isEnabled)

            Text(String(format: "%+.0f", equalizer.bandGains[index]) + "dB")
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundColor(equalizer.isEnabled ? SVColor.amber : SVColor.onSurfaceVariant)
                .frame(width: 38, alignment: .leading)
        }
    }

    // MARK: - Effect Slider

    private func effectSlider(label: String, value: Binding<Float>, range: ClosedRange<Float>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(label): \(Int(value.wrappedValue * 100))%")
                .font(.caption)
                .foregroundColor(equalizer.isEnabled ? SVColor.onSurface : SVColor.onSurfaceVariant)

            Slider(
                value: Binding(
                    get: { Double(value.wrappedValue) },
                    set: { value.wrappedValue = Float($0) }
                ),
                in: Double(range.lowerBound)...Double(range.upperBound)
            )
            .tint(equalizer.isEnabled ? SVColor.amber : SVColor.onSurfaceVariant)
            .disabled(!equalizer.isEnabled)
        }
    }
}
