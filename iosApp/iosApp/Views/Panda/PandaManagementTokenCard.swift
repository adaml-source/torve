import SwiftUI
import UIKit

/// One-time display surface for a freshly-minted Panda management token.
///
/// Masked by default; the user can Show, Copy, then "I've saved it" to dismiss.
/// Mirrors the Android PandaManagementTokenCard composable — copy tone and
/// masking behavior are intentionally identical so docs stay platform-agnostic.
/// The token is NEVER logged or attached to analytics events.
struct PandaManagementTokenCard: View {
    let token: String
    let notice: String?
    let onAcknowledge: () -> Void

    @State private var revealed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Management token (advanced)")
                .font(.headline)
            Text(
                notice ??
                "This token lets you edit your Panda config from other devices or restore access if you uninstall. Most users don't need it."
            )
            .font(.caption)
            .foregroundColor(.secondary)

            Text(revealed ? token : maskedToken)
                .font(.system(.body, design: .monospaced))
                .foregroundColor(.orange)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color.gray.opacity(0.15))
                .cornerRadius(8)

            HStack(spacing: 8) {
                Button(revealed ? "Hide" : "Show token") {
                    revealed.toggle()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)

                Button("Copy") {
                    UIPasteboard.general.string = token
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
            }

            Button {
                onAcknowledge()
            } label: {
                Text("I've saved it").frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
        }
        .padding()
        .background(Color.gray.opacity(0.1))
        .cornerRadius(12)
    }

    private var maskedToken: String {
        if token.count <= 8 {
            return String(repeating: "•", count: max(token.count, 8))
        }
        let head = token.prefix(4)
        let tail = token.suffix(4)
        let middle = String(repeating: "•", count: token.count - 8)
        return "\(head)\(middle)\(tail)"
    }
}
