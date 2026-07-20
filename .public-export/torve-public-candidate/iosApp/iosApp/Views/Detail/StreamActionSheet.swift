import SwiftUI
import shared

struct StreamActionSheet: View {
    let stream: ParsedStream
    let onPlay: () -> Void
    let onDownload: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                Section {
                    // Stream info header
                    VStack(alignment: .leading, spacing: 6) {
                        Text(stream.title)
                            .font(.headline)
                            .lineLimit(2)

                        HStack(spacing: 8) {
                            qualityBadge
                            if let size = stream.size {
                                Text(size)
                                    .font(.caption)
                                    .foregroundColor(SVColor.onSurfaceVariant)
                            }
                            if let codec = stream.codec, !codec.isEmpty {
                                Text(codec)
                                    .font(.caption)
                                    .foregroundColor(SVColor.onSurfaceVariant)
                            }
                            if let hdr = stream.hdr {
                                Text(hdr)
                                    .font(.caption)
                                    .padding(.horizontal, 6)
                                    .padding(.vertical, 2)
                                    .background(SVColor.amber.opacity(0.2))
                                    .foregroundColor(SVColor.amber)
                                    .cornerRadius(4)
                            }
                        }

                        HStack(spacing: 8) {
                            Text(stream.addonName)
                                .font(.caption)
                                .foregroundColor(SVColor.amber)
                            if stream.isCached {
                                HStack(spacing: 3) {
                                    Image(systemName: "cloud.fill")
                                        .font(.system(size: 10))
                                    Text("Cached")
                                        .font(.caption)
                                }
                                .foregroundColor(SVColor.emerald)
                            }
                        }
                    }
                    .listRowBackground(SVColor.surfaceVariant)
                }

                Section {
                    Button {
                        onPlay()
                        dismiss()
                    } label: {
                        Label("Play Now", systemImage: "play.fill")
                            .foregroundColor(SVColor.onSurface)
                    }

                    Button {
                        onDownload()
                        dismiss()
                    } label: {
                        Label("Download", systemImage: "arrow.down.circle")
                            .foregroundColor(SVColor.onSurface)
                    }
                }
            }
            .navigationTitle("Stream Options")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundColor(SVColor.amber)
                }
            }
        }
        .presentationDetents([.medium])
    }

    @ViewBuilder
    private var qualityBadge: some View {
        let q = stream.quality.uppercased()
        let color: Color = {
            if q.contains("4K") || q.contains("2160") || q.contains("REMUX") {
                return SVColor.amber
            } else if q.contains("1080") {
                return SVColor.emerald
            } else if q.contains("720") {
                return .blue
            } else {
                return .gray
            }
        }()

        Text(stream.quality)
            .font(.caption2)
            .fontWeight(.semibold)
            .padding(.horizontal, 6)
            .padding(.vertical, 3)
            .background(color.opacity(0.2))
            .foregroundColor(color)
            .cornerRadius(4)
    }
}
