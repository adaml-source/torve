import SwiftUI
import shared

struct HomeCustomizeSheet: View {
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Text("Home screen customization coming soon")
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
            .navigationTitle("Customize Home")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
