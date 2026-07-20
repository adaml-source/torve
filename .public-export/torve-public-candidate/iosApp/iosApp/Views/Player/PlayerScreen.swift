import SwiftUI
import AVKit

struct PlayerScreen: View {
    let streamUrl: String
    let title: String
    var fallbackUrl: String = ""
    @StateObject private var engine = AVPlayerEngine()
    @StateObject private var equalizer = iOSAudioEqualizer()
    @State private var showControls = true
    @State private var showEqualizer = false
    @State private var showExternalPlayerPicker = false
    @State private var hideTask: Task<Void, Never>?
    @Environment(\.dismiss) private var dismiss

    // Gesture seek state
    @State private var seekGestureActive = false
    @State private var seekOffsetSeconds: Double = 0
    @State private var gestureStartTime: Double = 0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VideoPlayer(player: engine.player)
                .ignoresSafeArea()
                .onTapGesture {
                    withAnimation { showControls.toggle() }
                    scheduleHide()
                }
                .gesture(seekDragGesture)

            // Seek indicator overlay
            if seekGestureActive {
                seekIndicatorOverlay
            }

            if showControls {
                PlayerControlsOverlay(
                    engine: engine,
                    title: title,
                    onDismiss: { dismiss() },
                    onEqualizerTap: { showEqualizer = true },
                    onExternalPlayerTap: { showExternalPlayerPicker = true }
                )
                .transition(.opacity)
            }
        }
        .navigationBarHidden(true)
        .statusBarHidden(true)
        .onAppear {
            if let url = URL(string: streamUrl) {
                engine.load(url: url, title: title)
                engine.play()
            }
            scheduleHide()
        }
        .onDisappear {
            engine.pause()
            hideTask?.cancel()
        }
        .sheet(isPresented: $showEqualizer) {
            EqualizerSheet(equalizer: equalizer)
                .presentationDetents([.large])
        }
        .confirmationDialog("Open in External Player", isPresented: $showExternalPlayerPicker, titleVisibility: .visible) {
            let installed = ExternalPlayerLauncher.getInstalledPlayers()
            if installed.isEmpty {
                Button("Copy Stream URL") {
                    ExternalPlayerLauncher.copyURL(streamUrl)
                }
            } else {
                ForEach(installed) { player in
                    Button(player.rawValue) {
                        engine.pause()
                        ExternalPlayerLauncher.play(url: streamUrl, in: player)
                    }
                }
                Button("Copy Stream URL") {
                    ExternalPlayerLauncher.copyURL(streamUrl)
                }
            }
            Button("Cancel", role: .cancel) {}
        }
    }

    // MARK: - Seek Drag Gesture

    private var seekDragGesture: some Gesture {
        DragGesture(minimumDistance: 30, coordinateSpace: .local)
            .onChanged { value in
                if !seekGestureActive {
                    seekGestureActive = true
                    gestureStartTime = engine.currentTime
                }
                // Map horizontal translation to seek amount.
                // 300pt drag ~ 60 seconds of seek, with acceleration for larger drags.
                let translation = value.translation.width
                let baseFactor: Double = 0.2 // seconds per point
                let accelerated = translation * baseFactor
                seekOffsetSeconds = accelerated
            }
            .onEnded { _ in
                if seekGestureActive {
                    let target = gestureStartTime + seekOffsetSeconds
                    let clamped = max(0, min(target, engine.duration))
                    engine.seek(to: clamped)
                }
                seekGestureActive = false
                seekOffsetSeconds = 0
            }
    }

    private var seekIndicatorOverlay: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                Text(seekOffsetLabel)
                    .font(.system(size: 28, weight: .bold, design: .rounded))
                    .foregroundColor(.white)
                    .padding(.horizontal, 20)
                    .padding(.vertical, 10)
                    .background(Color.black.opacity(0.7))
                    .cornerRadius(12)
                Spacer()
            }
            Spacer()
        }
    }

    private var seekOffsetLabel: String {
        let seconds = Int(seekOffsetSeconds)
        if seconds >= 0 {
            return "+\(seconds)s"
        } else {
            return "\(seconds)s"
        }
    }

    // MARK: - Helpers

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(5))
            guard !Task.isCancelled else { return }
            await MainActor.run {
                withAnimation { showControls = false }
            }
        }
    }
}
