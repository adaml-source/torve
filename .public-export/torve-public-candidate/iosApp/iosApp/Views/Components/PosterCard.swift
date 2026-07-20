import SwiftUI
import shared

struct PosterCard: View {
    let title: String
    let posterUrl: String?
    let rating: Double?
    let watchedPercent: Double?
    let watchState: WatchState?
    let watchedPrefs: WatchedIndicatorPrefs?

    init(item: MediaItem, watchState: WatchState? = nil, watchedPrefs: WatchedIndicatorPrefs? = nil) {
        self.title = item.title
        self.posterUrl = item.posterUrl
        self.rating = item.rating?.doubleValue
        self.watchedPercent = nil
        self.watchState = watchState
        self.watchedPrefs = watchedPrefs
    }

    init(
        title: String,
        posterUrl: String?,
        rating: Double? = nil,
        watchedPercent: Double? = nil,
        watchState: WatchState? = nil,
        watchedPrefs: WatchedIndicatorPrefs? = nil
    ) {
        self.title = title
        self.posterUrl = posterUrl
        self.rating = rating
        self.watchedPercent = watchedPercent
        self.watchState = watchState
        self.watchedPrefs = watchedPrefs
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ZStack(alignment: .bottomLeading) {
                AsyncImage(url: URL(string: posterUrl ?? "")) { phase in
                    switch phase {
                    case .success(let image):
                        image.resizable().aspectRatio(2/3, contentMode: .fill)
                    default:
                        Rectangle()
                            .fill(SVColor.surfaceVariant)
                            .aspectRatio(2/3, contentMode: .fill)
                            .overlay {
                                if case .empty = phase {
                                    ProgressView().tint(SVColor.amber)
                                }
                            }
                    }
                }
                .cornerRadius(10)

                // Rich watched overlay (when WatchState + prefs are provided)
                if let ws = watchState, let p = watchedPrefs {
                    WatchedOverlay(watchState: ws, prefs: p, cornerRadius: 10)
                        .cornerRadius(10)
                }

                // Fallback simple progress bar (when only watchedPercent is set)
                if watchState == nil, let percent = watchedPercent, percent > 0 {
                    GeometryReader { geo in
                        VStack {
                            Spacer()
                            Rectangle()
                                .fill(SVColor.amber)
                                .frame(width: geo.size.width * percent, height: 3)
                        }
                    }
                    .cornerRadius(10)
                }
            }

            Text(title)
                .font(SVFont.cardTitle)
                .foregroundColor(SVColor.onSurface)
                .lineLimit(2)

            if let rating = rating {
                HStack(spacing: 2) {
                    Image(systemName: "star.fill")
                        .font(.system(size: 9))
                        .foregroundColor(SVColor.rating)
                    Text(String(format: "%.1f", rating))
                        .font(SVFont.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }
        }
    }
}
