import SwiftUI
import AVFoundation

struct SubtitleTrackPicker: View {
    @ObservedObject var engine: AVPlayerEngine
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if !engine.audioTracks.isEmpty {
                    Section("Audio") {
                        ForEach(engine.audioTracks, id: \.self) { track in
                            Button {
                                engine.selectAudioTrack(track)
                            } label: {
                                Text(track.displayName)
                            }
                        }
                    }
                }

                Section("Subtitles") {
                    Button {
                        engine.selectSubtitleTrack(nil)
                    } label: {
                        Text("Off")
                    }

                    ForEach(engine.subtitleTracks, id: \.self) { track in
                        Button {
                            engine.selectSubtitleTrack(track)
                        } label: {
                            Text(track.displayName)
                        }
                    }
                }
            }
            .navigationTitle("Audio & Subtitles")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
