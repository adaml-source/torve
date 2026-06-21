import SwiftUI
import shared

struct PersonScreen: View {
    let personId: Int32
    @StateObject private var wrapper = PersonViewModelWrapper()
    @Environment(AppRouter.self) private var router

    var body: some View {
        Group {
            if wrapper.state.isLoading {
                VStack {
                    Spacer()
                    ProgressView().tint(SVColor.amber).scaleEffect(1.2)
                    Spacer()
                }
                .frame(maxWidth: .infinity)
            } else if let error = wrapper.state.error {
                VStack(spacing: 12) {
                    Image(systemName: "person.crop.circle.badge.exclamationmark")
                        .font(.largeTitle)
                        .foregroundColor(SVColor.error)
                    Text(error)
                        .foregroundColor(SVColor.error)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }
            } else {
                personContent()
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .navigationTitle(wrapper.state.personName)
        .onAppear { wrapper.loadPerson(personId: personId) }
    }

    // MARK: - Content

    @ViewBuilder
    private func personContent() -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                // Profile header
                profileHeader()

                // Biography
                if !wrapper.state.biography.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "Biography")
                        Text(wrapper.state.biography)
                            .font(.body)
                            .foregroundColor(SVColor.onSurfaceVariant)
                            .padding(.horizontal)
                    }
                }

                // Filmography
                let credits = wrapper.state.credits as? [MediaItem] ?? []
                if !credits.isEmpty {
                    VStack(alignment: .leading, spacing: 8) {
                        SectionHeader(title: "Filmography")
                        HorizontalShelf(items: credits) { item in
                            let mt = item.type == .series ? "tv" : "movie"
                            router.navigate(to: .detail(mediaId: item.id, mediaType: mt))
                        }
                    }
                }
            }
            .padding(.vertical)
        }
    }

    // MARK: - Profile Header

    @ViewBuilder
    private func profileHeader() -> some View {
        HStack(alignment: .top, spacing: 16) {
            // Profile image
            AsyncImage(url: URL(string: wrapper.state.profileUrl ?? "")) { phase in
                switch phase {
                case .success(let image):
                    image
                        .resizable()
                        .scaledToFill()
                default:
                    Circle()
                        .fill(SVColor.surfaceVariant)
                        .overlay {
                            Text(String(wrapper.state.personName.prefix(1)))
                                .font(.title)
                                .foregroundColor(SVColor.onSurfaceVariant)
                        }
                }
            }
            .frame(width: 100, height: 100)
            .clipShape(Circle())

            // Info
            VStack(alignment: .leading, spacing: 6) {
                Text(wrapper.state.personName)
                    .font(.title2)
                    .fontWeight(.bold)
                    .foregroundColor(SVColor.onSurface)

                if !wrapper.state.knownFor.isEmpty {
                    HStack(spacing: 4) {
                        Image(systemName: "star.circle.fill")
                            .font(.caption)
                            .foregroundColor(SVColor.amber)
                        Text(wrapper.state.knownFor)
                            .font(.subheadline)
                            .foregroundColor(SVColor.onSurfaceVariant)
                    }
                }

                let credits = wrapper.state.credits as? [MediaItem] ?? []
                if !credits.isEmpty {
                    Text("\(credits.count) credits")
                        .font(.caption)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }

            Spacer()
        }
        .padding(.horizontal)
    }
}
