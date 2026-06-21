import SwiftUI
import shared

struct StreamGroupsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var showAddSheet = false
    @State private var newGroupName: String = ""
    @State private var newGroupPattern: String = ""
    @State private var newGroupPriority: Int = 99
    @State private var showResetConfirmation = false

    /// Groups sorted by ascending priority (lower number = higher priority).
    private var sortedGroups: [(offset: Int, element: StreamGroup)] {
        wrapper.state.streamGroups
            .enumerated()
            .sorted { $0.element.priority < $1.element.priority }
            .map { (offset: $0.offset, element: $0.element) }
    }

    var body: some View {
        List {
            Section {
                if wrapper.state.streamGroups.isEmpty {
                    Text("No stream groups configured")
                        .foregroundColor(.secondary)
                } else {
                    ForEach(sortedGroups, id: \.element.name) { item in
                        let originalIndex = item.offset
                        let group = item.element

                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(group.name)
                                        .fontWeight(.medium)
                                        .opacity(group.enabled ? 1.0 : 0.5)
                                    Spacer()
                                    Text("Priority \(group.priority)")
                                        .font(.caption)
                                        .padding(.horizontal, 8)
                                        .padding(.vertical, 2)
                                        .background(Color.accentColor.opacity(0.15))
                                        .foregroundColor(.accentColor)
                                        .clipShape(Capsule())
                                }
                                Text(group.matchPattern)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                                    .opacity(group.enabled ? 1.0 : 0.5)
                            }

                            Toggle("", isOn: Binding(
                                get: { group.enabled },
                                set: { _ in
                                    wrapper.viewModel.toggleStreamGroup(index: Int32(originalIndex))
                                }
                            ))
                            .labelsHidden()
                        }
                        .padding(.vertical, 2)
                        .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                            Button(role: .destructive) {
                                wrapper.viewModel.removeStreamGroup(index: Int32(originalIndex))
                            } label: {
                                Label("Delete", systemImage: "trash")
                            }
                        }
                    }
                }
            } header: {
                HStack {
                    Text("Groups")
                    Spacer()
                    Button {
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            } footer: {
                Text("Stream groups organize search results by matching regex patterns. Lower priority numbers appear first.")
            }

            Section {
                Button(role: .destructive) {
                    showResetConfirmation = true
                } label: {
                    HStack {
                        Spacer()
                        Text("Reset to Defaults")
                        Spacer()
                    }
                }
            }
        }
        .navigationTitle("Stream Groups")
        .confirmationDialog("Reset stream groups to defaults?", isPresented: $showResetConfirmation, titleVisibility: .visible) {
            Button("Reset", role: .destructive) {
                wrapper.viewModel.resetStreamGroups()
            }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $showAddSheet) {
            NavigationStack {
                Form {
                    TextField("Group Name", text: $newGroupName)

                    TextField("Match Pattern (regex)", text: $newGroupPattern)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))

                    Stepper("Priority: \(newGroupPriority)", value: $newGroupPriority, in: 0...999)
                }
                .navigationTitle("New Group")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            resetAddFields()
                            showAddSheet = false
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Add") {
                            if !newGroupName.isEmpty && !newGroupPattern.isEmpty {
                                wrapper.viewModel.addStreamGroup(
                                    name: newGroupName,
                                    matchPattern: newGroupPattern,
                                    priority: Int32(newGroupPriority)
                                )
                            }
                            resetAddFields()
                            showAddSheet = false
                        }
                        .disabled(newGroupName.isEmpty || newGroupPattern.isEmpty)
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    private func resetAddFields() {
        newGroupName = ""
        newGroupPattern = ""
        newGroupPriority = 99
    }
}
