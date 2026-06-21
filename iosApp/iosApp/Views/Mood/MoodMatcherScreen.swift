import SwiftUI
import shared

struct MoodMatcherScreen: View {
    @StateObject private var wrapper = MoodMatcherViewModelWrapper()
    @Environment(AppRouter.self) private var router

    private let columns = [
        GridItem(.adaptive(minimum: 130), spacing: 12)
    ]

    var body: some View {
        ScrollView {
            if wrapper.state.selectedMood == nil {
                moodSelectionGrid
            } else {
                resultsView
            }
        }
        .navigationTitle("Mood Match")
        .toolbar {
            if wrapper.state.selectedMood != nil {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Reset") { wrapper.clearMood() }
                        .foregroundColor(SVColor.amber)
                }
            }
        }
    }

    // MARK: - Mood Selection

    private var moodSelectionGrid: some View {
        VStack(spacing: 24) {
            Text("How are you feeling?")
                .font(.title2)
                .fontWeight(.bold)
                .padding(.top, 24)

            Text("Pick a mood and we'll find the perfect watch.")
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)

            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 16)], spacing: 16) {
                ForEach(Mood.entries, id: \.name) { mood in
                    MoodCard(mood: mood) {
                        wrapper.selectMood(mood)
                    }
                }
            }
            .padding(.horizontal)
        }
    }

    // MARK: - Results

    private var resultsView: some View {
        VStack(spacing: 16) {
            if let mood = wrapper.state.selectedMood {
                HStack(spacing: 8) {
                    Text(mood.emoji)
                        .font(.title)
                    Text(mood.label)
                        .font(.title2)
                        .fontWeight(.bold)
                }
                .padding(.top, 16)
            }

            if wrapper.state.isLoading {
                VStack(spacing: 12) {
                    ProgressView()
                    Text("Finding matches...")
                        .font(.subheadline)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity)
                .padding(.top, 48)
            } else if let error = wrapper.state.error {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.system(size: 36))
                        .foregroundColor(SVColor.error)
                    Text(error)
                        .font(.subheadline)
                        .foregroundColor(SVColor.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    Button("Try Again") {
                        if let mood = wrapper.state.selectedMood {
                            wrapper.selectMood(mood)
                        }
                    }
                    .foregroundColor(SVColor.amber)
                }
                .padding(.top, 32)
            } else {
                let results = wrapper.state.results as? [MoodResult] ?? []
                if results.isEmpty {
                    Text("No matches found. Try a different mood!")
                        .font(.subheadline)
                        .foregroundColor(SVColor.onSurfaceVariant)
                        .padding(.top, 32)
                } else {
                    LazyVGrid(columns: columns, spacing: 12) {
                        ForEach(results, id: \.item.id) { result in
                            NavigationLink(value: Route.detail(
                                mediaId: result.item.id,
                                mediaType: result.item.mediaType
                            )) {
                                MoodResultCard(result: result)
                            }
                        }
                    }
                    .padding(.horizontal)
                }
            }
        }
    }
}

// MARK: - Mood Card

private struct MoodCard: View {
    let mood: Mood
    let onTap: () -> Void

    var body: some View {
        Button(action: onTap) {
            VStack(spacing: 10) {
                Text(mood.emoji)
                    .font(.system(size: 40))

                Text(mood.label)
                    .font(.subheadline)
                    .fontWeight(.semibold)
                    .foregroundColor(SVColor.onSurface)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity)
            .frame(height: 110)
            .background(
                RoundedRectangle(cornerRadius: 16)
                    .fill(SVColor.surfaceVariant)
            )
            .overlay(
                RoundedRectangle(cornerRadius: 16)
                    .stroke(SVColor.onSurfaceVariant.opacity(0.2), lineWidth: 1)
            )
        }
    }
}

// MARK: - Mood Result Card

private struct MoodResultCard: View {
    let result: MoodResult

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            AsyncImage(url: URL(string: result.item.posterUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .aspectRatio(2/3, contentMode: .fill)
                case .failure:
                    posterPlaceholder
                case .empty:
                    posterPlaceholder.overlay(ProgressView())
                @unknown default:
                    EmptyView()
                }
            }
            .cornerRadius(12)

            Text(result.item.title)
                .font(SVFont.cardTitle)
                .foregroundColor(SVColor.onSurface)
                .lineLimit(2)

            Text(result.reason)
                .font(SVFont.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
                .lineLimit(1)

            if let rating = result.item.rating {
                HStack(spacing: 2) {
                    Image(systemName: "star.fill")
                        .font(.caption2)
                        .foregroundColor(SVColor.rating)
                    Text(String(format: "%.1f", rating.doubleValue))
                        .font(.caption2)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }
        }
    }

    private var posterPlaceholder: some View {
        Rectangle()
            .fill(Color.gray.opacity(0.3))
            .aspectRatio(2/3, contentMode: .fill)
    }
}
