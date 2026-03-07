import SwiftUI

struct MultiRatingPills: View {
    var imdb: Double?
    var tmdb: Double?
    var rt: Int?

    var body: some View {
        HStack(spacing: 6) {
            if let imdb = imdb {
                RatingPill(label: "IMDb", value: String(format: "%.1f", imdb), color: .yellow)
            }
            if let tmdb = tmdb {
                RatingPill(label: "TMDB", value: String(format: "%.1f", tmdb), color: SVColor.emerald)
            }
            if let rt = rt {
                RatingPill(label: "RT", value: "\(rt)%", color: .red)
            }
        }
    }
}

private struct RatingPill: View {
    let label: String
    let value: String
    let color: Color

    var body: some View {
        HStack(spacing: 3) {
            Text(label)
                .font(SVFont.pill)
                .foregroundColor(color)
            Text(value)
                .font(SVFont.pill)
                .foregroundColor(SVColor.onSurface)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(color.opacity(0.15))
        .cornerRadius(6)
    }
}
