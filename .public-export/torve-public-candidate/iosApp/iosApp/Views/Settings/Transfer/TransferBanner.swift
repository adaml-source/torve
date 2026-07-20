import SwiftUI

/// Single source of the credential-transfer banner UI shared by the
/// iOS send and receive screens. Mirrors the banner-tone palette used
/// in the Android wrapper.
struct TransferBanner: View {

    enum Tone {
        case info
        case success
        case warning
        case error
    }

    let tone: Tone
    let title: String
    let body: String

    init(_ tone: Tone, _ title: String, _ body: String) {
        self.tone = tone
        self.title = title
        self.body = body
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).fontWeight(.semibold)
            Text(body).font(.caption).foregroundColor(textColor)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(backgroundColor)
        .cornerRadius(10)
    }

    private var backgroundColor: Color {
        switch tone {
        case .info:    return Color.gray.opacity(0.15)
        case .success: return Color.green.opacity(0.18)
        case .warning: return Color.orange.opacity(0.18)
        case .error:   return Color.red.opacity(0.18)
        }
    }

    private var textColor: Color {
        switch tone {
        case .info:    return .secondary
        case .success: return .green
        case .warning: return .orange
        case .error:   return .red
        }
    }
}
