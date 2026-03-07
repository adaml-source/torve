import SwiftUI
import shared

struct ChannelRowView: View {
    let enriched: EnrichedChannel
    let onPlay: () -> Void
    let onFavorite: () -> Void

    private var channel: Channel { enriched.channel }

    var body: some View {
        Button(action: onPlay) {
            HStack(spacing: 12) {
                // Channel logo
                if let logo = channel.tvgLogo, let url = URL(string: logo) {
                    AsyncImage(url: url) { image in
                        image.resizable().aspectRatio(contentMode: .fit)
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 8)
                            .fill(Color.blue.opacity(0.15))
                    }
                    .frame(width: 48, height: 48)
                    .clipShape(RoundedRectangle(cornerRadius: 8))
                } else {
                    RoundedRectangle(cornerRadius: 8)
                        .fill(Color.blue.opacity(0.15))
                        .frame(width: 48, height: 48)
                        .overlay(
                            Text(String(channel.name.prefix(2)).uppercased())
                                .font(.caption)
                                .fontWeight(.bold)
                                .foregroundColor(.blue)
                        )
                }

                VStack(alignment: .leading, spacing: 2) {
                    Text(channel.name)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(1)
                        .foregroundColor(.primary)

                    if let current = enriched.currentProgramme {
                        Text(current.title)
                            .font(.caption)
                            .foregroundColor(.blue)
                            .lineLimit(1)
                    }

                    if let next = enriched.nextProgramme {
                        Text("Next: \(next.title)")
                            .font(.caption2)
                            .foregroundColor(.secondary)
                            .lineLimit(1)
                    }
                }

                Spacer()

                Button(action: onFavorite) {
                    Image(systemName: channel.isFavorite ? "heart.fill" : "heart")
                        .foregroundColor(channel.isFavorite ? .red : .secondary)
                        .font(.body)
                }
                .buttonStyle(.plain)
            }
            .padding(.horizontal)
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }
}
