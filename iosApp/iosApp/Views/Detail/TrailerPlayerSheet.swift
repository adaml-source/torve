import SwiftUI
import AVKit

struct TrailerPlayerSheet: View {
    let trailerUrl: String
    @State private var player: AVPlayer?

    var body: some View {
        VideoPlayer(player: player)
            .ignoresSafeArea()
            .onAppear {
                if let url = URL(string: trailerUrl) {
                    player = AVPlayer(url: url)
                    player?.play()
                }
            }
            .onDisappear {
                player?.pause()
                player = nil
            }
    }
}
