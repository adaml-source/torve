import SwiftUI

struct NextEpisodeOverlay: View {
    let episodeTitle: String
    let countdown: Int
    let onPlay: () -> Void
    let onCancel: () -> Void

    var body: some View {
        VStack(spacing: 16) {
            Text("Up Next")
                .font(.headline)
                .foregroundColor(.white)

            Text(episodeTitle)
                .font(.title3)
                .foregroundColor(.white)
                .multilineTextAlignment(.center)

            HStack(spacing: 16) {
                Button(action: onCancel) {
                    Text("Cancel")
                        .padding(.horizontal, 24)
                        .padding(.vertical, 12)
                        .background(Color.white.opacity(0.2))
                        .foregroundColor(.white)
                        .cornerRadius(8)
                }

                Button(action: onPlay) {
                    HStack {
                        Image(systemName: "play.fill")
                        Text("Play Now (\(countdown)s)")
                    }
                    .padding(.horizontal, 24)
                    .padding(.vertical, 12)
                    .background(SVColor.amber)
                    .foregroundColor(.black)
                    .cornerRadius(8)
                }
            }
        }
        .padding(32)
        .background(.ultraThinMaterial)
        .cornerRadius(20)
    }
}
