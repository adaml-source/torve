import SwiftUI

struct SectionHeader: View {
    let title: String
    var onSeeAll: (() -> Void)? = nil

    var body: some View {
        HStack {
            Text(title)
                .font(SVFont.sectionTitle)
                .foregroundColor(SVColor.onSurface)
            Spacer()
            if let action = onSeeAll {
                Button(action: action) {
                    HStack(spacing: 2) {
                        Text("See All")
                        Image(systemName: "chevron.right")
                    }
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(SVColor.amber)
                }
            }
        }
        .padding(.horizontal)
    }
}
