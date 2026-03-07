import SwiftUI
import shared

struct CategoryManagerSheet: View {
    @ObservedObject var wrapper: ChannelsViewModelWrapper
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button("Show All") { wrapper.showAllCategories() }
                    Button("Hide All") { wrapper.hideAllCategories() }
                }

                Section("Categories") {
                    ForEach(wrapper.state.allCategories, id: \.name) { category in
                        Toggle(
                            category.name,
                            isOn: Binding(
                                get: { !wrapper.state.hiddenCategories.contains(category.name) },
                                set: { _ in wrapper.toggleHiddenCategory(category.name) }
                            )
                        )
                    }
                }
            }
            .navigationTitle("Manage Categories")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
