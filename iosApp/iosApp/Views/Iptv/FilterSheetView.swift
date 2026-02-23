import SwiftUI
import shared

struct FilterSheetView: View {
    let activeFilter: IptvFilterType
    let activeSort: IptvSortType
    let onFilterSelected: (IptvFilterType) -> Void
    let onSortSelected: (IptvSortType) -> Void
    let onDismiss: () -> Void

    private let filters: [(IptvFilterType, String)] = [
        (.all, "All"),
        (.hd, "HD"),
        (.fhd, "FHD"),
        (.uhd, "4K / UHD"),
        (.favorites, "Favorites"),
    ]

    private let sorts: [(IptvSortType, String)] = [
        (.default_, "Default"),
        (.nameAz, "Name A-Z"),
        (.nameZa, "Name Z-A"),
        (.recentlyAdded, "Recently Added"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section("Filter") {
                    ForEach(filters, id: \.0) { filter, label in
                        Button(action: { onFilterSelected(filter) }) {
                            HStack {
                                Text(label)
                                    .foregroundColor(.primary)
                                Spacer()
                                if activeFilter == filter {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.blue)
                                }
                            }
                        }
                    }
                }

                Section("Sort") {
                    ForEach(sorts, id: \.0) { sort, label in
                        Button(action: { onSortSelected(sort) }) {
                            HStack {
                                Text(label)
                                    .foregroundColor(.primary)
                                Spacer()
                                if activeSort == sort {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(.blue)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Filter & Sort")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done", action: onDismiss)
                }
            }
        }
    }
}
