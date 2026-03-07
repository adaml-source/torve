import SwiftUI
import shared

struct PlaybackSettingsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    private let codecOptions = ["HEVC Preferred", "H.264 Only", "Any"]
    private let hdrOptions = ["Prefer HDR", "SDR Only", "Auto"]
    private let qualityOptions = ["480p", "720p", "1080p", "2160p (4K)", "Remux 4K"]

    var body: some View {
        List {
            autoPlaySection
            codecSection
            hdrSection
            qualitySection
            cacheSection
        }
        .navigationTitle("Playback")
    }

    // MARK: - Auto-Play

    private var autoPlaySection: some View {
        Section {
            Toggle("Auto-Play Best Stream", isOn: Binding(
                get: { wrapper.state.autoPlayEnabled },
                set: { wrapper.viewModel.setAutoPlay(enabled: $0) }
            ))
            Toggle("Auto-Play Next Episode", isOn: Binding(
                get: { wrapper.state.autoPlayNextEpisodeEnabled },
                set: { wrapper.viewModel.setAutoPlayNextEpisode(enabled: $0) }
            ))
        } footer: {
            Text("Auto-play best stream picks the highest-scored stream. Auto-play next episode continues to the next episode when playback finishes.")
        }
    }

    // MARK: - Codec

    private var codecSection: some View {
        Section("Codec Preference") {
            Picker("Codec", selection: Binding(
                get: {
                    switch wrapper.state.codecPreference {
                    case .hevcPreferred: return 0
                    case .h264Only: return 1
                    default: return 2
                    }
                },
                set: { index in
                    let pref: CodecPreference
                    switch index {
                    case 0: pref = .hevcPreferred
                    case 1: pref = .h264Only
                    default: pref = .any
                    }
                    wrapper.viewModel.setCodecPreference(pref: pref)
                }
            )) {
                ForEach(0..<codecOptions.count, id: \.self) { index in
                    Text(codecOptions[index]).tag(index)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    // MARK: - HDR

    private var hdrSection: some View {
        Section("HDR Mode") {
            Picker("HDR", selection: Binding(
                get: {
                    switch wrapper.state.hdrMode {
                    case .preferHdr: return 0
                    case .sdrOnly: return 1
                    default: return 2
                    }
                },
                set: { index in
                    let mode: HdrMode
                    switch index {
                    case 0: mode = .preferHdr
                    case 1: mode = .sdrOnly
                    default: mode = .auto_
                    }
                    wrapper.viewModel.setHdrMode(mode: mode)
                }
            )) {
                ForEach(0..<hdrOptions.count, id: \.self) { index in
                    Text(hdrOptions[index]).tag(index)
                }
            }
            .pickerStyle(.segmented)
        }
    }

    // MARK: - Quality

    private var qualitySection: some View {
        Section("Quality Limits") {
            HStack {
                Text("Max Quality")
                Spacer()
                Text(wrapper.state.maxQuality.name)
                    .foregroundColor(.secondary)
            }

            HStack {
                Text("Min Quality")
                Spacer()
                Text(wrapper.state.minQuality.name)
                    .foregroundColor(.secondary)
            }

            if let maxSize = wrapper.state.maxFileSizeMb {
                HStack {
                    Text("Max File Size")
                    Spacer()
                    Text("\(maxSize) MB")
                        .foregroundColor(.secondary)
                }
            }

            Toggle("HDR Content", isOn: Binding(
                get: { wrapper.state.hdrEnabled },
                set: { wrapper.viewModel.setHdrEnabled(enabled: $0) }
            ))

            Toggle("Cached Streams Only", isOn: Binding(
                get: { wrapper.state.cachedOnly },
                set: { wrapper.viewModel.setCachedOnly(enabled: $0) }
            ))
        } footer: {
            Text("Restrict streams to your preferred quality range. Cached-only mode ensures instant playback through your debrid service.")
        }
    }

    // MARK: - Cache

    private var cacheSection: some View {
        Section {
            Toggle("Deduplicate Results", isOn: Binding(
                get: { wrapper.state.dedupeResults },
                set: { wrapper.viewModel.setDedupeResults(enabled: $0) }
            ))
        } footer: {
            Text("Remove duplicate streams from different addons that point to the same source.")
        }
    }
}
