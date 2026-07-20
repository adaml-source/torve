import SwiftUI

@available(*, deprecated, message: "Torve no longer has a paywall; this screen is retained only for source compatibility.")
struct PaywallScreen: View {
    var body: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "checkmark.seal.fill")
                    .font(.system(size: 56))
                    .foregroundColor(SVColor.amber)

                Text("Torve is free software")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .multilineTextAlignment(.center)

                Text("Torve is free software. There are no subscriptions or paid tiers.")
                    .font(.body)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)

                VStack(alignment: .leading, spacing: 12) {
                    availabilityRow("Stream playback")
                    availabilityRow("Downloads")
                    availabilityRow("Filters and playlists")
                    availabilityRow("Sync and watched state")
                    availabilityRow("Integrations and add-ons")
                }
                .padding()
                .background(
                    RoundedRectangle(cornerRadius: 16)
                        .fill(Color(.secondarySystemBackground))
                )
            }
            .padding()
        }
        .navigationTitle("Free Software")
    }

    private func availabilityRow(_ title: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark.circle.fill")
                .foregroundColor(SVColor.emerald)
            Text(title)
                .foregroundColor(SVColor.onSurface)
            Spacer()
        }
    }
}
