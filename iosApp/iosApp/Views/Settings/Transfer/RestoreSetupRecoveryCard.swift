import SwiftUI

/// Non-blocking "Restore setup from another device" card.
///
/// Visible only when the shared recovery-state provider reports that
/// 2+ transferable provider categories have no local credentials.
/// "Receive credentials" is a `NavigationLink` to `Route.transferReceive`
/// so it works inside the existing NavigationStack without requiring
/// a programmatic path binding. "Set up manually" dismisses the card
/// for the current Settings session.
struct RestoreSetupRecoveryCard: View {

    @StateObject private var wrapper = RestoreSetupRecoveryWrapper()

    var body: some View {
        Group {
            if wrapper.shouldShowCard {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Restore setup from another device")
                        .font(.headline)
                    Text("Transfer encrypted credentials from a device that already works. Faster than re-entering each one by hand.")
                        .font(.footnote)
                        .foregroundColor(.secondary)
                    Text("\(wrapper.missingCount) provider categories missing local credentials.")
                        .font(.caption)
                        .foregroundColor(.secondary)
                    HStack {
                        Spacer()
                        Button("Set up manually") { wrapper.dismiss() }
                            .buttonStyle(.bordered)
                        NavigationLink(value: Route.transferReceive) {
                            Text("Receive credentials")
                        }
                        .buttonStyle(.borderedProminent)
                    }
                }
                .padding(12)
                .background(Color.green.opacity(0.10))
                .cornerRadius(10)
            }
        }
        .task { await wrapper.refresh() }
    }
}
