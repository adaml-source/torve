import SwiftUI
import shared

struct DetailScreen: View {
    let mediaItem: MediaItem
    @State private var showStreamPicker = false
    @State private var streams: [ParsedStream] = []
    @State private var isLoadingStreams = false
    @State private var isResolving = false
    @State private var autoPlayMessage: String? = nil
    @State private var resolvedUrl: String? = nil
    @State private var streamsError: String? = nil

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Backdrop
                AsyncImage(url: URL(string: mediaItem.backdropUrl ?? mediaItem.posterUrl ?? "")) { phase in
                    switch phase {
                    case .success(let image):
                        image
                            .resizable()
                            .aspectRatio(16/9, contentMode: .fill)
                    case .failure, .empty:
                        Rectangle()
                            .fill(Color.gray.opacity(0.3))
                            .aspectRatio(16/9, contentMode: .fill)
                    @unknown default:
                        EmptyView()
                    }
                }
                .frame(height: 220)
                .clipped()

                VStack(alignment: .leading, spacing: 12) {
                    // Title
                    Text(mediaItem.title)
                        .font(.title)
                        .fontWeight(.bold)

                    // Meta info
                    HStack(spacing: 16) {
                        if let year = mediaItem.year {
                            Text(String(year.intValue))
                                .foregroundColor(.secondary)
                        }
                        if let rating = mediaItem.rating {
                            HStack(spacing: 4) {
                                Image(systemName: "star.fill")
                                    .foregroundColor(.yellow)
                                Text(String(format: "%.1f", rating.doubleValue))
                            }
                        }
                        if let runtime = mediaItem.runtime {
                            Text("\(runtime.intValue) min")
                                .foregroundColor(.secondary)
                        }
                    }
                    .font(.subheadline)

                    // Genres
                    if !mediaItem.genres.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(mediaItem.genres, id: \.id) { genre in
                                    Text(genre.name)
                                        .font(.caption)
                                        .padding(.horizontal, 12)
                                        .padding(.vertical, 6)
                                        .background(Color.blue.opacity(0.2))
                                        .cornerRadius(16)
                                }
                            }
                        }
                    }

                    // Play button
                    Button(action: {
                        // TODO: Wire to DetailViewModel.fetchStreams() via KoinHelper
                        showStreamPicker = true
                    }) {
                        HStack {
                            if isLoadingStreams || isResolving {
                                ProgressView()
                                    .tint(.white)
                                Text(isResolving ? "Resolving..." : "Finding streams...")
                            } else {
                                Image(systemName: "play.fill")
                                Text("Play")
                            }
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(Color(red: 0.91, green: 0.66, blue: 0.22)) // Amber
                        .foregroundColor(.black)
                        .cornerRadius(12)
                    }
                    .disabled(isLoadingStreams || isResolving)

                    // Auto-play message
                    if let message = autoPlayMessage {
                        HStack {
                            Image(systemName: "play.circle.fill")
                                .foregroundColor(Color(red: 0.91, green: 0.66, blue: 0.22))
                            Text(message)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        .padding(10)
                        .background(Color(.secondarySystemBackground))
                        .cornerRadius(8)
                    }

                    // Error
                    if let error = streamsError {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.red)
                    }

                    // Overview
                    if let overview = mediaItem.overview {
                        Text(overview)
                            .font(.body)
                            .foregroundColor(.secondary)
                    }
                }
                .padding(.horizontal)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .sheet(isPresented: $showStreamPicker) {
            StreamPickerView(
                streams: streams,
                isResolving: isResolving,
                onStreamSelected: { stream in
                    // TODO: Wire to DetailViewModel.resolveStream()
                    showStreamPicker = false
                },
                onDismiss: { showStreamPicker = false }
            )
        }
    }
}
