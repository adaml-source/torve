import SwiftUI

struct SearchFilterSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Text("Search filters coming soon")
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
            .navigationTitle("Search Filters")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
