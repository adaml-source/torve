import SwiftUI
import shared

private struct PresetPattern: Identifiable {
    let id = UUID()
    let label: String
    let pattern: String
}

private let presets: [PresetPattern] = [
    PresetPattern(label: "4K/HDR",       pattern: "(?i)(2160p|4k|uhd|hdr)"),
    PresetPattern(label: "HEVC/x265",    pattern: "(?i)(hevc|x265|h.265)"),
    PresetPattern(label: "Remux",        pattern: "(?i)(remux)"),
    PresetPattern(label: "Web-DL",       pattern: "(?i)(web-?dl)"),
    PresetPattern(label: "BluRay",       pattern: "(?i)(blu-?ray|bdrip)"),
    PresetPattern(label: "HDR10+",       pattern: "(?i)(hdr10\\+|hdr10plus)"),
    PresetPattern(label: "Dolby Vision", pattern: "(?i)(dv|dolby.?vision)"),
    PresetPattern(label: "Atmos",        pattern: "(?i)(atmos)"),
]

struct RegexPatternsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var showAddSheet = false
    @State private var newLabel: String = ""
    @State private var newPattern: String = ""

    var body: some View {
        List {
            // MARK: - Quick Presets
            Section {
                FlowLayout(spacing: 8) {
                    ForEach(presets) { preset in
                        Button {
                            wrapper.viewModel.addRegexPattern(label: preset.label, pattern: preset.pattern)
                        } label: {
                            Text(preset.label)
                                .font(.subheadline.weight(.medium))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 6)
                                .background(SVColor.amber.opacity(0.15))
                                .foregroundColor(SVColor.amber)
                                .clipShape(Capsule())
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.vertical, 4)
            } header: {
                Text("Quick Presets")
            } footer: {
                Text("Tap a chip to add it. You can add the same preset more than once.")
            }

            // MARK: - Patterns List
            Section {
                if wrapper.state.regexPatterns.isEmpty {
                    Text("No custom patterns configured")
                        .foregroundColor(.secondary)
                } else {
                    ForEach(Array(wrapper.state.regexPatterns.enumerated()), id: \.offset) { index, pattern in
                        HStack(spacing: 12) {
                            VStack(alignment: .leading, spacing: 4) {
                                if !pattern.label.isEmpty {
                                    Text(pattern.label)
                                        .fontWeight(.medium)
                                }
                                Text(pattern.pattern)
                                    .font(.system(.caption, design: .monospaced))
                                    .foregroundColor(.secondary)
                                    .lineLimit(1)
                            }

                            Spacer()

                            Toggle("", isOn: Binding(
                                get: { pattern.enabled },
                                set: { _ in
                                    wrapper.viewModel.toggleRegexPattern(index: Int32(index))
                                }
                            ))
                            .labelsHidden()
                        }
                        .padding(.vertical, 2)
                    }
                    .onDelete { indexSet in
                        for index in indexSet {
                            wrapper.viewModel.removeRegexPattern(index: Int32(index))
                        }
                    }
                }
            } header: {
                HStack {
                    Text("Patterns")
                    Spacer()
                    Button {
                        showAddSheet = true
                    } label: {
                        Image(systemName: "plus")
                    }
                }
            } footer: {
                Text("Regex patterns filter streams. Toggle individual patterns on or off, or swipe to delete.")
            }
        }
        .navigationTitle("Regex Patterns")
        .sheet(isPresented: $showAddSheet) {
            NavigationStack {
                Form {
                    TextField("Label", text: $newLabel)

                    TextField("Regex Pattern", text: $newPattern)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .font(.system(.body, design: .monospaced))
                }
                .navigationTitle("New Pattern")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") {
                            showAddSheet = false
                        }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Add") {
                            wrapper.viewModel.addRegexPattern(
                                label: newLabel,
                                pattern: newPattern
                            )
                            newLabel = ""
                            newPattern = ""
                            showAddSheet = false
                        }
                        .disabled(newPattern.isEmpty)
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }
}

// MARK: - FlowLayout (wrapping horizontal layout for chips)

private struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let result = computeLayout(proposal: proposal, subviews: subviews)
        return result.size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let result = computeLayout(proposal: proposal, subviews: subviews)
        for (index, offset) in result.offsets.enumerated() {
            subviews[index].place(
                at: CGPoint(x: bounds.minX + offset.x, y: bounds.minY + offset.y),
                proposal: .unspecified
            )
        }
    }

    private struct LayoutResult {
        var offsets: [CGPoint]
        var size: CGSize
    }

    private func computeLayout(proposal: ProposedViewSize, subviews: Subviews) -> LayoutResult {
        let maxWidth = proposal.width ?? .infinity
        var offsets: [CGPoint] = []
        var currentX: CGFloat = 0
        var currentY: CGFloat = 0
        var lineHeight: CGFloat = 0
        var totalWidth: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(.unspecified)
            if currentX + size.width > maxWidth, currentX > 0 {
                currentX = 0
                currentY += lineHeight + spacing
                lineHeight = 0
            }
            offsets.append(CGPoint(x: currentX, y: currentY))
            lineHeight = max(lineHeight, size.height)
            currentX += size.width + spacing
            totalWidth = max(totalWidth, currentX - spacing)
        }

        return LayoutResult(
            offsets: offsets,
            size: CGSize(width: totalWidth, height: currentY + lineHeight)
        )
    }
}
