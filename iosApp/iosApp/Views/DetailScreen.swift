import SwiftUI
import shared

struct DetailScreen: View {
    let mediaId: String
    let mediaType: String
    @StateObject private var wrapper = DetailViewModelWrapper()
    @Environment(AppRouter.self) private var router
    @State private var showStreamPicker = false
    @State private var showTrailer = false
    @State private var showRatingPicker = false

    var body: some View {
        Group {
            if wrapper.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView().tint(SVColor.amber).scaleEffect(1.2)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
            } else if let item = wrapper.state.mediaItem {
                detailContent(item)
            } else if let error = wrapper.state.error {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundColor(SVColor.error)
                    Text(error)
                        .foregroundColor(SVColor.error)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            wrapper.loadDetail(mediaType: mediaType, mediaId: mediaId)
        }
        .sheet(isPresented: $showStreamPicker) {
            StreamPickerView(
                streams: wrapper.state.streams as? [ParsedStream] ?? [],
                isResolving: wrapper.state.isResolving,
                onStreamSelected: { stream in
                    // Branch on provenance: USENET_NZBDAV rows go through
                    // the NzbDAV resolver; everything else stays on the
                    // existing debrid/addon resolve path verbatim.
                    if stream.accelerationProvenanceKind == .usenetNzbdav {
                        wrapper.selectUsenetSource(stream)
                    } else {
                        wrapper.resolveStream(stream)
                    }
                    showStreamPicker = false
                },
                onDismiss: { showStreamPicker = false }
            )
        }
        .onChange(of: showStreamPicker) { _, isVisible in
            // One-shot per false → true cycle. Fires the expanded sheet
            // warmup for Usenet rows in the current list. The shared
            // coordinator dedupes; safe even on rapid reopens.
            if isVisible { wrapper.onSourceSheetOpened() }
        }
        .sheet(isPresented: $showTrailer) {
            if let key = wrapper.state.mediaItem?.trailerKey {
                TrailerPlayerSheet(trailerUrl: "https://www.youtube.com/watch?v=\(key)")
            }
        }
        .onChange(of: wrapper.state.resolvedStream?.url) { _, url in
            if let url = url, !url.isEmpty {
                let title = wrapper.state.mediaItem?.title ?? "Video"
                wrapper.clearResolvedStream()
                router.navigate(to: .player(streamUrl: url, title: title))
            }
        }
        .onChange(of: wrapper.state.usenetPlaybackIntent?.url) { _, url in
            // Ready handoff for Usenet sources. The opaque URL is passed
            // byte-for-byte into the existing iOS player entrypoint —
            // no parsing, no normalization. The intent is cleared in
            // shared state so recomposition cannot re-launch.
            if let url = url, !url.isEmpty {
                let title = wrapper.state.mediaItem?.title ?? "Video"
                wrapper.consumeUsenetPlaybackIntent()
                router.navigate(to: .player(streamUrl: url, title: title))
            }
        }
    }

    // MARK: - Detail Content

    @ViewBuilder
    private func detailContent(_ item: MediaItem) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Backdrop
                backdropView(item)

                VStack(alignment: .leading, spacing: 12) {
                    // Title & tagline
                    Text(item.title)
                        .font(.title)
                        .fontWeight(.bold)
                        .foregroundColor(SVColor.onSurface)

                    if let tagline = item.tagline, !tagline.isEmpty {
                        Text(tagline)
                            .font(.subheadline)
                            .italic()
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }

                    // Meta row
                    metaRow(item)

                    // Genres
                    genreChips(item)

                    // Action buttons
                    actionButtons(item)

                    // Auto-play message
                    if let message = wrapper.state.autoPlayMessage {
                        HStack {
                            Image(systemName: "play.circle.fill")
                                .foregroundColor(SVColor.amber)
                            Text(message)
                                .font(.caption)
                                .foregroundColor(SVColor.onSurfaceVariant)
                        }
                        .padding(10)
                        .background(SVColor.surfaceVariant)
                        .cornerRadius(8)
                    }

                    // Streams error
                    if let error = wrapper.state.streamsError {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(SVColor.error)
                    }

                    // Resolve error
                    if let error = wrapper.state.resolveError {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(SVColor.error)
                    }

                    // Overview
                    if let overview = item.overview, !overview.isEmpty {
                        Text(overview)
                            .font(.body)
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }

                    // Multi-source ratings
                    if let ratings = item.ratings {
                        MultiRatingPills(
                            imdb: ratings.imdbScore.map { Double($0.floatValue) },
                            tmdb: ratings.tmdbScore.map { Double($0.floatValue) },
                            rt: ratings.rottenTomatoesScore.map { Int($0.int32Value) },
                            rtAudience: ratings.rtAudienceScore.map { Int($0.int32Value) }
                        )
                    }

                    // Director
                    if let director = item.director, !director.isEmpty {
                        HStack(spacing: 4) {
                            Text("Director:")
                                .font(.subheadline)
                                .foregroundColor(SVColor.onSurfaceVariant)
                            if let directorId = item.directorId {
                                Button {
                                    router.navigate(to: .person(personId: directorId.int32Value))
                                } label: {
                                    Text(director)
                                        .font(.subheadline)
                                        .fontWeight(.medium)
                                        .foregroundColor(SVColor.amber)
                                }
                            } else {
                                Text(director)
                                    .font(.subheadline)
                                    .fontWeight(.medium)
                            }
                        }
                    }

                    // Cast
                    if !item.cast.isEmpty {
                        castSection(item)
                    }

                    // Season/Episode selector (TV only)
                    if item.type == .series {
                        EpisodeSelector(wrapper: wrapper)
                    }

                    // User rating
                    ratingSection()

                    // Similar
                    if !(wrapper.state.similar as? [MediaItem] ?? []).isEmpty {
                        similarSection()
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    // MARK: - Backdrop

    @ViewBuilder
    private func backdropView(_ item: MediaItem) -> some View {
        ZStack(alignment: .bottomLeading) {
            AsyncImage(url: URL(string: item.backdropUrl ?? item.posterUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(16/9, contentMode: .fill)
                case .failure, .empty:
                    Rectangle()
                        .fill(SVColor.surfaceVariant)
                        .aspectRatio(16/9, contentMode: .fill)
                @unknown default:
                    EmptyView()
                }
            }
            .frame(height: 220)
            .clipped()

            // Gradient overlay
            LinearGradient(
                colors: [.clear, SVColor.obsidian.opacity(0.8)],
                startPoint: .center,
                endPoint: .bottom
            )
            .frame(height: 220)

            // Trailer button
            if item.trailerKey != nil {
                Button {
                    showTrailer = true
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "play.rectangle.fill")
                        Text("Trailer")
                            .font(.caption)
                            .fontWeight(.medium)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                    .background(.ultraThinMaterial)
                    .cornerRadius(8)
                }
                .padding(12)
            }
        }
    }

    // MARK: - Meta Row

    @ViewBuilder
    private func metaRow(_ item: MediaItem) -> some View {
        HStack(spacing: 16) {
            if let year = item.year {
                Text(String(year.int32Value))
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
            if let rating = item.rating {
                HStack(spacing: 4) {
                    Image(systemName: "star.fill")
                        .foregroundColor(SVColor.rating)
                    Text(String(format: "%.1f", rating.doubleValue))
                }
            }
            if let runtime = item.runtime {
                Text("\(runtime.int32Value) min")
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
            if let status = item.status, !status.isEmpty {
                Text(status)
                    .font(.caption)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(SVColor.surfaceVariant)
                    .cornerRadius(8)
                    .foregroundColor(SVColor.onSurfaceVariant)
            }
        }
        .font(.subheadline)
    }

    // MARK: - Genre Chips

    @ViewBuilder
    private func genreChips(_ item: MediaItem) -> some View {
        if !item.genres.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(item.genres, id: \.id) { genre in
                        Text(genre.name)
                            .font(.caption)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(SVColor.amber.opacity(0.2))
                            .foregroundColor(SVColor.amber)
                            .cornerRadius(16)
                    }
                }
            }
        }
    }

    // MARK: - Action Buttons

    @ViewBuilder
    private func actionButtons(_ item: MediaItem) -> some View {
        HStack(spacing: 12) {
            // Play button
            Button {
                if item.type == .series {
                    wrapper.playNextEpisode()
                } else {
                    wrapper.fetchStreams()
                }
                showStreamPicker = true
            } label: {
                HStack {
                    if wrapper.state.isLoadingStreams || wrapper.state.isResolving {
                        ProgressView().tint(.black)
                        Text(wrapper.state.isResolving ? "Resolving..." : "Finding streams...")
                            .font(.subheadline)
                    } else {
                        Image(systemName: "play.fill")
                        Text(item.type == .series ? "Play Next" : "Play")
                            .font(.subheadline)
                            .fontWeight(.semibold)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 14)
                .background(SVColor.amber)
                .foregroundColor(.black)
                .cornerRadius(12)
            }
            .disabled(wrapper.state.isLoadingStreams || wrapper.state.isResolving)

            // Bookmark / Library
            Button {
                // TODO: Wire toggleLibrary when shared layer supports it
            } label: {
                Image(systemName: wrapper.state.isInLibrary ? "bookmark.fill" : "bookmark")
                    .font(.title3)
                    .padding(14)
                    .background(SVColor.surfaceVariant)
                    .foregroundColor(wrapper.state.isInLibrary ? SVColor.amber : SVColor.onSurface)
                    .cornerRadius(12)
            }

            // Watched toggle
            Button {
                wrapper.toggleWatched()
            } label: {
                Image(systemName: wrapper.state.isMarkedWatched ? "eye.fill" : "eye")
                    .font(.title3)
                    .padding(14)
                    .background(SVColor.surfaceVariant)
                    .foregroundColor(wrapper.state.isMarkedWatched ? SVColor.emerald : SVColor.onSurface)
                    .cornerRadius(12)
            }
        }
    }

    // MARK: - Cast Section

    @ViewBuilder
    private func castSection(_ item: MediaItem) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title: "Cast")
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 12) {
                    ForEach(item.cast, id: \.id) { person in
                        Button {
                            router.navigate(to: .person(personId: person.id))
                        } label: {
                            CastAvatar(
                                name: person.name,
                                character: person.character,
                                imageUrl: person.profileUrl
                            )
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal)
            }
        }
    }

    // MARK: - Rating Section

    @ViewBuilder
    private func ratingSection() -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("Your Rating")
                    .font(SVFont.sectionTitle)
                    .foregroundColor(SVColor.onSurface)
                Spacer()
                if let rating = wrapper.state.userRating {
                    Button {
                        wrapper.setUserRating(nil)
                    } label: {
                        Text("Clear")
                            .font(.caption)
                            .foregroundColor(SVColor.amber)
                    }
                    Text("\(rating.int32Value)/10")
                        .font(.subheadline)
                        .fontWeight(.semibold)
                        .foregroundColor(SVColor.amber)
                } else {
                    Text("Not rated")
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }

            HStack(spacing: 4) {
                ForEach(1..<11) { i in
                    let currentRating = wrapper.state.userRating?.int32Value ?? 0
                    Button {
                        wrapper.setUserRating(Int32(i))
                    } label: {
                        Image(systemName: i <= currentRating ? "star.fill" : "star")
                            .font(.system(size: 20))
                            .foregroundColor(i <= currentRating ? SVColor.rating : SVColor.surfaceVariant)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Similar Section

    @ViewBuilder
    private func similarSection() -> some View {
        let similar = wrapper.state.similar as? [MediaItem] ?? []
        VStack(alignment: .leading, spacing: 8) {
            SectionHeader(title: "Similar")
            HorizontalShelf(items: similar) { similarItem in
                let mt = similarItem.type == .series ? "tv" : "movie"
                router.navigate(to: .detail(mediaId: similarItem.id, mediaType: mt))
            }
        }
    }
}
