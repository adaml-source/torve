import SwiftUI
import shared

struct CatalogFilterSheet: View {
    let currentFilter: CatalogFilter
    let onApply: (CatalogFilter) -> Void
    let onClear: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var minRating: Float?
    @State private var yearFrom: Int32?
    @State private var yearTo: Int32?
    @State private var runtimeFilter: RuntimeFilter?
    @State private var sortBy: SortOption

    init(currentFilter: CatalogFilter, onApply: @escaping (CatalogFilter) -> Void, onClear: @escaping () -> Void) {
        self.currentFilter = currentFilter
        self.onApply = onApply
        self.onClear = onClear
        _minRating = State(initialValue: currentFilter.minRating?.floatValue)
        _yearFrom = State(initialValue: currentFilter.year?.int32Value)
        _yearTo = State(initialValue: currentFilter.yearTo?.int32Value)
        _runtimeFilter = State(initialValue: currentFilter.runtimeFilter)
        _sortBy = State(initialValue: currentFilter.sortBy)
    }

    var body: some View {
        NavigationStack {
            List {
                Section("Sort By") {
                    ForEach([SortOption.popularityDesc, .voteAverageDesc, .voteCountDesc, .releaseDateDesc, .releaseDateAsc], id: \.self) { option in
                        Button {
                            sortBy = option
                        } label: {
                            HStack {
                                Text(option.label)
                                Spacer()
                                if sortBy == option {
                                    Image(systemName: "checkmark")
                                        .foregroundColor(SVColor.amber)
                                }
                            }
                        }
                        .foregroundColor(SVColor.onSurface)
                    }
                }

                Section("Minimum Rating") {
                    HStack {
                        Text(minRating != nil ? String(format: "%.0f+", minRating!) : "Any")
                        Spacer()
                        Stepper("", value: Binding(
                            get: { Double(minRating ?? 0) },
                            set: { minRating = $0 > 0 ? Float($0) : nil }
                        ), in: 0...9, step: 1)
                    }
                }

                Section("Runtime") {
                    ForEach([nil] + [RuntimeFilter.short_, RuntimeFilter.standard, RuntimeFilter.long_], id: \.self) { option in
                        Button {
                            runtimeFilter = option
                        } label: {
                            HStack {
                                Text(option?.label ?? "Any")
                                Spacer()
                                if runtimeFilter == option {
                                    Image(systemName: "checkmark").foregroundColor(SVColor.amber)
                                }
                            }
                        }
                        .foregroundColor(SVColor.onSurface)
                    }
                }
            }
            .navigationTitle("Filters")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Clear") {
                        onClear()
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        let filter = CatalogFilter(
                            minRating: minRating.map { KotlinFloat(value: $0) },
                            year: yearFrom.map { KotlinInt(value: $0) },
                            yearTo: yearTo.map { KotlinInt(value: $0) },
                            runtimeFilter: runtimeFilter,
                            sortBy: sortBy
                        )
                        onApply(filter)
                        dismiss()
                    }
                    .foregroundColor(SVColor.amber)
                }
            }
        }
    }
}
