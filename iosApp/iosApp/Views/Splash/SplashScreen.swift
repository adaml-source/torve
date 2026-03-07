import SwiftUI

/// Branded splash screen with animated fade-in.
/// Auto-dismisses after ~2 seconds via the onFinished callback.
struct SplashScreen: View {
    let onFinished: () -> Void

    @State private var logoOpacity: Double = 0
    @State private var titleOpacity: Double = 0
    @State private var subtitleOpacity: Double = 0

    var body: some View {
        ZStack {
            SVColor.obsidian.ignoresSafeArea()

            VStack(spacing: 20) {
                // App icon / logo
                ZStack {
                    Circle()
                        .fill(SVColor.amber.opacity(0.15))
                        .frame(width: 120, height: 120)

                    Image(systemName: "play.rectangle.fill")
                        .font(.system(size: 52))
                        .foregroundColor(SVColor.amber)
                }
                .opacity(logoOpacity)

                // App name
                Text("Torve")
                    .font(.system(size: 32, weight: .bold))
                    .foregroundColor(SVColor.onSurface)
                    .opacity(titleOpacity)

                // Tagline
                Text("Browse. Pick. Watch.")
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .opacity(subtitleOpacity)
            }
        }
        .onAppear {
            withAnimation(.easeIn(duration: 0.5)) {
                logoOpacity = 1.0
            }
            withAnimation(.easeIn(duration: 0.5).delay(0.3)) {
                titleOpacity = 1.0
            }
            withAnimation(.easeIn(duration: 0.5).delay(0.6)) {
                subtitleOpacity = 1.0
            }

            DispatchQueue.main.asyncAfter(deadline: .now() + 2.0) {
                onFinished()
            }
        }
    }
}
