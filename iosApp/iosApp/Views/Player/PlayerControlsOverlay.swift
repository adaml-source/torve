import SwiftUI
import AVKit

struct PlayerControlsOverlay: View {
    @ObservedObject var engine: AVPlayerEngine
    let title: String
    let onDismiss: () -> Void
    var onEqualizerTap: (() -> Void)? = nil
    var onExternalPlayerTap: (() -> Void)? = nil

    @State private var audioDelayMs: Int = 0

    var body: some View {
        VStack {
            // Top bar
            HStack {
                Button(action: onDismiss) {
                    Image(systemName: "chevron.left")
                        .font(.title2)
                        .foregroundColor(.white)
                }
                Text(title)
                    .font(.headline)
                    .foregroundColor(.white)
                    .lineLimit(1)
                Spacer()

                // EQ button
                if let onEqualizerTap {
                    Button(action: onEqualizerTap) {
                        Image(systemName: "waveform")
                            .font(.body)
                            .foregroundColor(SVColor.amber)
                    }
                    .padding(.trailing, 4)
                }

                // External player button
                if let onExternalPlayerTap {
                    Button(action: onExternalPlayerTap) {
                        Image(systemName: "arrow.up.right.square")
                            .font(.body)
                            .foregroundColor(SVColor.amber)
                    }
                    .padding(.trailing, 4)
                }

                // AirPlay
                AirPlayButton()
                    .frame(width: 36, height: 36)
            }
            .padding()

            Spacer()

            // Center controls
            HStack(spacing: 48) {
                Button { engine.seekRelative(-15) } label: {
                    Image(systemName: "gobackward.15")
                        .font(.system(size: 32))
                        .foregroundColor(.white)
                }

                Button { engine.togglePlayPause() } label: {
                    Image(systemName: engine.isPlaying ? "pause.fill" : "play.fill")
                        .font(.system(size: 48))
                        .foregroundColor(.white)
                }

                Button { engine.seekRelative(15) } label: {
                    Image(systemName: "goforward.15")
                        .font(.system(size: 32))
                        .foregroundColor(.white)
                }
            }

            Spacer()

            // Bottom bar with seek + audio delay
            VStack(spacing: 8) {
                Slider(
                    value: Binding(
                        get: { engine.currentTime },
                        set: { engine.seek(to: $0) }
                    ),
                    in: 0...max(engine.duration, 1)
                )
                .tint(SVColor.amber)

                HStack {
                    Text(formatTime(engine.currentTime))

                    Spacer()

                    // Audio delay stepper
                    audioDelayStepper

                    Spacer()

                    Text("-\(formatTime(engine.duration - engine.currentTime))")
                }
                .font(.caption)
                .foregroundColor(.white.opacity(0.7))
            }
            .padding(.horizontal)
            .padding(.bottom)
        }
        .background(
            LinearGradient(
                colors: [.black.opacity(0.6), .clear, .clear, .black.opacity(0.6)],
                startPoint: .top, endPoint: .bottom
            )
        )
    }

    // MARK: - Audio Delay Stepper

    private var audioDelayStepper: some View {
        HStack(spacing: 6) {
            Button {
                audioDelayMs = max(audioDelayMs - 100, -2000)
            } label: {
                Image(systemName: "minus.circle")
                    .font(.caption)
                    .foregroundColor(SVColor.amber)
            }

            Text("Audio \(audioDelayMs >= 0 ? "+" : "")\(audioDelayMs)ms")
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundColor(audioDelayMs != 0 ? SVColor.amber : .white.opacity(0.5))

            Button {
                audioDelayMs = min(audioDelayMs + 100, 2000)
            } label: {
                Image(systemName: "plus.circle")
                    .font(.caption)
                    .foregroundColor(SVColor.amber)
            }
        }
    }

    private func formatTime(_ seconds: Double) -> String {
        guard seconds.isFinite else { return "0:00" }
        let total = Int(seconds)
        let h = total / 3600
        let m = (total % 3600) / 60
        let s = total % 60
        if h > 0 {
            return String(format: "%d:%02d:%02d", h, m, s)
        }
        return String(format: "%d:%02d", m, s)
    }
}

struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
        let routePickerView = AVRoutePickerView()
        routePickerView.tintColor = .white
        routePickerView.activeTintColor = UIColor(SVColor.amber)
        return routePickerView
    }
    func updateUIView(_ uiView: UIView, context: Context) {}
}
