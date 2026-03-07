import SwiftUI
import shared

// MARK: - Colors

private let WatchedGreen = Color(red: 0.298, green: 0.686, blue: 0.314)
private let RewatchPurple = Color(red: 0.482, green: 0.122, blue: 0.635)

// MARK: - WatchedOverlay

struct WatchedOverlay: View {
    let watchState: WatchState
    let prefs: WatchedIndicatorPrefs
    var cornerRadius: CGFloat = 8

    var body: some View {
        if !prefs.enabled || !watchState.isStarted { return AnyView(EmptyView()) }
        return AnyView(overlayContent)
    }

    private var overlayContent: some View {
        ZStack {
            // Dim overlay for fully watched items
            if watchState.isCompleted && prefs.dimWatched {
                Color.black.opacity(Double(prefs.dimAmount))
            }

            // Watched indicator by style
            if watchState.isCompleted {
                styleIndicator
            }

            // Progress bar for partially watched
            if !watchState.isCompleted && watchState.progressPercent > 0 && prefs.progressBarForPartial {
                VStack {
                    Spacer()
                    progressBar
                }
            }

            // Rewatch count badge
            if watchState.isCompleted && prefs.rewatchBadge && watchState.rewatchCount > 1 {
                rewatchBadge
            }
        }
    }

    // MARK: - Style Indicator

    @ViewBuilder
    private var styleIndicator: some View {
        let style = prefs.style
        if style == WatchedIndicatorStyle.checkmarkBadge {
            checkmarkBadgeView
        } else if style == WatchedIndicatorStyle.checkmarkOverlay {
            checkmarkOverlayView
        } else if style == WatchedIndicatorStyle.eyeIcon {
            eyeIconView
        } else if style == WatchedIndicatorStyle.banner {
            bannerView
        } else if style == WatchedIndicatorStyle.border {
            borderView
        } else if style == WatchedIndicatorStyle.dot {
            dotView
        } else {
            // .none or unknown — just dim (no additional indicator)
            EmptyView()
        }
    }

    // MARK: - Checkmark Badge (green circle with checkmark, top-right)

    private var checkmarkBadgeView: some View {
        VStack {
            HStack {
                Spacer()
                ZStack {
                    Circle()
                        .fill(WatchedGreen)
                        .frame(width: 20, height: 20)
                    Circle()
                        .stroke(Color.white.opacity(0.8), lineWidth: 1.5)
                        .frame(width: 20, height: 20)
                    Image(systemName: "checkmark")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundColor(.white)
                }
                .padding(6)
            }
            Spacer()
        }
    }

    // MARK: - Checkmark Overlay (full dim + large checkmark centered)

    private var checkmarkOverlayView: some View {
        ZStack {
            Color.black.opacity(0.4)
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 40))
                .foregroundColor(WatchedGreen.opacity(0.85))
        }
    }

    // MARK: - Eye Icon (eye in dark pill, top-right)

    private var eyeIconView: some View {
        VStack {
            HStack {
                Spacer()
                Image(systemName: "eye.fill")
                    .font(.system(size: 12))
                    .foregroundColor(WatchedGreen)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.7))
                    .cornerRadius(4)
                    .padding(6)
            }
            Spacer()
        }
    }

    // MARK: - Banner (diagonal triangle at top-right corner with checkmark)

    private var bannerView: some View {
        ZStack(alignment: .topTrailing) {
            Canvas { context, size in
                var path = Path()
                path.move(to: CGPoint(x: size.width - 50, y: 0))
                path.addLine(to: CGPoint(x: size.width, y: 0))
                path.addLine(to: CGPoint(x: size.width, y: 50))
                path.closeSubpath()
                context.fill(path, with: .color(WatchedGreen.opacity(0.85)))
            }
            Text("\u{2713}")
                .font(.system(size: 12, weight: .bold))
                .foregroundColor(.white)
                .padding(.top, 4)
                .padding(.trailing, 6)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
    }

    // MARK: - Border (green border around the card)

    private var borderView: some View {
        RoundedRectangle(cornerRadius: cornerRadius)
            .stroke(WatchedGreen, lineWidth: 2)
    }

    // MARK: - Dot (small green dot, top-right)

    private var dotView: some View {
        VStack {
            HStack {
                Spacer()
                ZStack {
                    Circle()
                        .fill(WatchedGreen)
                        .frame(width: 10, height: 10)
                    Circle()
                        .stroke(Color.white.opacity(0.6), lineWidth: 1)
                        .frame(width: 10, height: 10)
                }
                .padding(6)
            }
            Spacer()
        }
    }

    // MARK: - Progress Bar (amber bar at bottom)

    private var progressBar: some View {
        GeometryReader { geo in
            VStack {
                Spacer()
                ZStack(alignment: .leading) {
                    Rectangle()
                        .fill(Color.black.opacity(0.5))
                        .frame(height: 3)
                    Rectangle()
                        .fill(SVColor.amber)
                        .frame(width: geo.size.width * CGFloat(watchState.progressPercent), height: 3)
                }
            }
        }
    }

    // MARK: - Rewatch Badge (purple pill with xN, bottom-right)

    private var rewatchBadge: some View {
        VStack {
            Spacer()
            HStack {
                Spacer()
                Text("\u{00D7}\(watchState.rewatchCount)")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundColor(.white)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 1)
                    .background(RewatchPurple)
                    .cornerRadius(4)
                    .padding(6)
            }
        }
    }
}
