import SwiftUI
import shared

struct StreamPickerView: View {
    let streams: [ParsedStream]
    let isResolving: Bool
    let onStreamSelected: (ParsedStream) -> Void
    let onDismiss: () -> Void

    private var bestMatch: [ParsedStream] {
        streams.filter { $0.score >= 70 }
    }

    private var other: [ParsedStream] {
        streams.filter { $0.score < 70 }
    }

    var body: some View {
        NavigationStack {
            Group {
                if isResolving {
                    VStack(spacing: 12) {
                        ProgressView()
                            .scaleEffect(1.2)
                            .tint(Color(red: 0.91, green: 0.66, blue: 0.22))
                        Text("Resolving stream...")
                            .font(.subheadline)
                            .foregroundColor(.secondary)
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                } else {
                    List {
                        if !bestMatch.isEmpty {
                            Section {
                                ForEach(bestMatch, id: \.title) { stream in
                                    StreamRow(stream: stream) {
                                        onStreamSelected(stream)
                                    }
                                }
                            } header: {
                                Text("Best Match")
                                    .foregroundColor(Color(red: 0.91, green: 0.66, blue: 0.22))
                            }
                        }

                        if !other.isEmpty {
                            Section {
                                ForEach(other, id: \.title) { stream in
                                    StreamRow(stream: stream) {
                                        onStreamSelected(stream)
                                    }
                                }
                            } header: {
                                Text(bestMatch.isEmpty ? "Available Streams" : "Other Options")
                            }
                        }
                    }
                    .listStyle(.insetGrouped)
                }
            }
            .navigationTitle("Select Stream")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Text("\(streams.count) sources")
                        .font(.caption)
                        .foregroundColor(.secondary)
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { onDismiss() }
                }
            }
        }
    }
}

private struct StreamRow: View {
    let stream: ParsedStream
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 10) {
                // Score badge
                ZStack {
                    Circle()
                        .fill(scoreColor.opacity(0.2))
                        .frame(width: 32, height: 32)
                    Text("\(stream.score)")
                        .font(.system(size: 11, weight: .bold))
                        .foregroundColor(scoreColor)
                }

                // Quality badge
                Text(stream.quality)
                    .font(.caption2)
                    .fontWeight(.semibold)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 3)
                    .background(qualityColor.opacity(0.2))
                    .foregroundColor(qualityColor)
                    .cornerRadius(4)

                // Stream info. Stremio Stream.title can be multi-line — Panda
                // puts resolution/size/codec + 🗣️ language tags on the second
                // line. Split and render both so the language info stays visible.
                VStack(alignment: .leading, spacing: 3) {
                    let titleLines = stream.title.split(separator: "\n", omittingEmptySubsequences: false).map(String.init)
                    Text(titleLines.first ?? stream.title)
                        .font(.subheadline)
                        .fontWeight(.medium)
                        .lineLimit(1)
                    if titleLines.count > 1 {
                        Text(titleLines.dropFirst().joined(separator: " "))
                            .font(.caption)
                            .foregroundColor(.secondary)
                            .lineLimit(2)
                    }

                    HStack(spacing: 6) {
                        if let size = stream.size {
                            MetaChipView(text: size)
                        }
                        if let codec = stream.codec, !codec.isEmpty {
                            MetaChipView(text: codec)
                        }
                        if let hdr = stream.hdr {
                            MetaChipView(text: hdr)
                        }
                        if let audio = stream.audioCodec {
                            MetaChipView(text: audio)
                        }
                        if !stream.languages.isEmpty {
                            MetaChipView(text: "🗣 " + stream.languages.joined(separator: ", "))
                        }
                        if let seeds = stream.seeds {
                            MetaChipView(text: "\(seeds) seeds")
                        }
                    }
                }

                Spacer()

                // Addon + cached
                VStack(alignment: .trailing, spacing: 2) {
                    Text(stream.addonName)
                        .font(.caption2)
                        .foregroundColor(Color(red: 0.91, green: 0.66, blue: 0.22))

                    if stream.isCached {
                        Image(systemName: "cloud.fill")
                            .font(.system(size: 12))
                            .foregroundColor(Color(red: 0.2, green: 0.78, blue: 0.55))
                    }
                }
            }
        }
        .buttonStyle(.plain)
    }

    private var scoreColor: Color {
        if stream.score >= 80 {
            return Color(red: 0.2, green: 0.78, blue: 0.55) // Emerald
        } else if stream.score >= 60 {
            return Color(red: 0.91, green: 0.66, blue: 0.22) // Amber
        } else {
            return .gray
        }
    }

    private var qualityColor: Color {
        let q = stream.quality.uppercased()
        if q.contains("4K") || q.contains("2160") || q.contains("REMUX") {
            return Color(red: 0.91, green: 0.66, blue: 0.22)
        } else if q.contains("1080") {
            return Color(red: 0.2, green: 0.78, blue: 0.55)
        } else if q.contains("720") {
            return .blue
        } else {
            return .gray
        }
    }
}

private struct MetaChipView: View {
    let text: String

    var body: some View {
        Text(text)
            .font(.system(size: 10))
            .foregroundColor(.secondary)
    }
}
