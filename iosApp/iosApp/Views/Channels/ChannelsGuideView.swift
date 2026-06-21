import SwiftUI
import shared

struct ChannelsGuideView: View {
    @ObservedObject var wrapper: ChannelsViewModelWrapper

    var body: some View {
        Group {
            if wrapper.state.isLoadingGuide {
                VStack {
                    Spacer()
                    ProgressView("Loading guide...")
                        .tint(SVColor.amber)
                    Spacer()
                }
            } else if let error = wrapper.state.guideError {
                VStack(spacing: 12) {
                    Image(systemName: "exclamationmark.triangle")
                        .font(.largeTitle)
                        .foregroundColor(SVColor.error)
                    Text(error)
                        .foregroundColor(SVColor.onSurfaceVariant)
                        .multilineTextAlignment(.center)
                    Button("Retry") { wrapper.retryGuideLoad() }
                        .buttonStyle(.borderedProminent)
                        .tint(SVColor.amber)
                }
                .padding()
            } else {
                List {
                    ForEach(wrapper.state.guideChannels, id: \.channel.name) { enriched in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(enriched.channel.name)
                                .font(.headline)
                            if let current = enriched.currentProgramme {
                                Text("Now: \(current.title)")
                                    .font(.caption)
                                    .foregroundColor(SVColor.amber)
                            }
                            if let next = enriched.nextProgramme {
                                Text("Next: \(next.title)")
                                    .font(.caption)
                                    .foregroundColor(SVColor.onSurfaceVariant)
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
                .listStyle(.plain)
            }
        }
    }
}
