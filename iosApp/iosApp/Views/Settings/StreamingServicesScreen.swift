import SwiftUI
import shared

private struct StreamingServiceDef: Identifiable {
    let id: Int32
    let name: String
    let brandColor: Color

    var tmdbProviderId: Int32 { id }
}

private let allStreamingServices: [StreamingServiceDef] = [
    StreamingServiceDef(id: 8,    name: "Netflix",          brandColor: Color(red: 0.90, green: 0.04, blue: 0.08)),
    StreamingServiceDef(id: 9,    name: "Prime Video",      brandColor: Color(red: 0.00, green: 0.66, blue: 0.88)),
    StreamingServiceDef(id: 337,  name: "Disney+",          brandColor: Color(red: 0.07, green: 0.24, blue: 0.81)),
    StreamingServiceDef(id: 350,  name: "Apple TV+",        brandColor: .black),
    StreamingServiceDef(id: 1899, name: "Max",              brandColor: Color(red: 0.00, green: 0.17, blue: 0.91)),
    StreamingServiceDef(id: 15,   name: "Hulu",             brandColor: Color(red: 0.11, green: 0.91, blue: 0.51)),
    StreamingServiceDef(id: 531,  name: "Paramount+",       brandColor: Color(red: 0.00, green: 0.39, blue: 1.00)),
    StreamingServiceDef(id: 386,  name: "Peacock",          brandColor: .black),
    StreamingServiceDef(id: 283,  name: "Crunchyroll",      brandColor: Color(red: 0.96, green: 0.46, blue: 0.13)),
    StreamingServiceDef(id: 11,   name: "Mubi",             brandColor: Color(red: 0.00, green: 0.11, blue: 0.24)),
    StreamingServiceDef(id: 43,   name: "Starz",            brandColor: .black),
    StreamingServiceDef(id: 37,   name: "Showtime",         brandColor: .red),
    StreamingServiceDef(id: 380,  name: "BritBox",          brandColor: Color(red: 0.02, green: 0.21, blue: 0.38)),
    StreamingServiceDef(id: 258,  name: "Criterion",        brandColor: .black),
    StreamingServiceDef(id: 73,   name: "Tubi",             brandColor: Color(red: 0.97, green: 0.52, blue: 0.00)),
    StreamingServiceDef(id: 300,  name: "Pluto TV",         brandColor: Color(red: 0.00, green: 0.00, blue: 0.20)),
    StreamingServiceDef(id: 613,  name: "Freevee",          brandColor: Color(red: 0.22, green: 0.71, blue: 0.29)),
    StreamingServiceDef(id: 190,  name: "Curiosity Stream", brandColor: Color(red: 0.09, green: 0.64, blue: 0.72)),
    StreamingServiceDef(id: 439,  name: "Shudder",          brandColor: Color(red: 0.00, green: 0.04, blue: 1.00)),
    StreamingServiceDef(id: 30,   name: "WOW",              brandColor: Color(red: 0.12, green: 0.12, blue: 0.12)),
    StreamingServiceDef(id: 298,  name: "RTL+",             brandColor: Color(red: 0.89, green: 0.00, blue: 0.06)),
    StreamingServiceDef(id: 421,  name: "Joyn",             brandColor: Color(red: 0.10, green: 0.90, blue: 0.75)),
    StreamingServiceDef(id: 551,  name: "MagentaTV",        brandColor: Color(red: 0.89, green: 0.00, blue: 0.45)),
]

struct StreamingServicesScreen: View {
    @StateObject private var homeWrapper = HomeViewModelWrapper()
    @StateObject private var settingsWrapper = SettingsViewModelWrapper()

    @State private var enabledIds: Set<Int32> = []
    @State private var providerLogos: [Int32: String] = [:]

    private var enabledCollector: Closeable?
    private var logosCollector: Closeable?

    var body: some View {
        List {
            Section {
                Picker("Region", selection: Binding(
                    get: { settingsWrapper.state.regionCode },
                    set: { settingsWrapper.viewModel.setRegionCode(value: $0) }
                )) {
                    Text("United States").tag("US")
                    Text("United Kingdom").tag("GB")
                    Text("Germany").tag("DE")
                    Text("France").tag("FR")
                    Text("Canada").tag("CA")
                    Text("Australia").tag("AU")
                    Text("Japan").tag("JP")
                    Text("South Korea").tag("KR")
                    Text("Spain").tag("ES")
                    Text("Italy").tag("IT")
                    Text("Brazil").tag("BR")
                    Text("Mexico").tag("MX")
                    Text("India").tag("IN")
                    Text("Turkey").tag("TR")
                }
            } footer: {
                Text("Select your region to see accurate availability information for streaming services.")
            }

            Section {
                ForEach(allStreamingServices) { service in
                    let isEnabled = enabledIds.contains(service.tmdbProviderId)
                    HStack(spacing: 14) {
                        // Brand color logo card
                        ZStack {
                            RoundedRectangle(cornerRadius: 10)
                                .fill(service.brandColor)

                            if let logoUrl = providerLogos[service.tmdbProviderId],
                               let url = URL(string: logoUrl) {
                                AsyncImage(url: url) { image in
                                    image.resizable().aspectRatio(contentMode: .fit)
                                } placeholder: {
                                    Text(service.name)
                                        .font(.caption2)
                                        .fontWeight(.bold)
                                        .foregroundColor(.white)
                                        .lineLimit(1)
                                }
                                .padding(6)
                            } else {
                                Text(service.name)
                                    .font(.caption2)
                                    .fontWeight(.bold)
                                    .foregroundColor(.white)
                                    .lineLimit(1)
                            }
                        }
                        .frame(width: 80, height: 50)

                        Text(service.name)
                            .fontWeight(.medium)

                        Spacer()

                        Toggle("", isOn: Binding(
                            get: { isEnabled },
                            set: { newValue in
                                homeWrapper.viewModel.toggleStreamingService(
                                    providerId: service.tmdbProviderId,
                                    enabled: newValue
                                )
                                if newValue {
                                    enabledIds.insert(service.tmdbProviderId)
                                } else {
                                    enabledIds.remove(service.tmdbProviderId)
                                }
                            }
                        ))
                        .labelsHidden()
                        .tint(SVColor.amber)
                    }
                }
            } header: {
                Text("Your Services")
            } footer: {
                Text("Choose which services appear on your home screen. Content from these services will be available to browse and play through Torve.\n\nData provided by TMDB.")
            }
        }
        .navigationTitle("Streaming Services")
        .onAppear {
            loadState()
        }
    }

    private func loadState() {
        if let ids = homeWrapper.viewModel.enabledServiceIds.value as? Set<KotlinInt> {
            enabledIds = Set(ids.map { $0.int32Value })
        }
        if let logos = homeWrapper.viewModel.providerLogos.value as? [KotlinInt: NSString] {
            providerLogos = Dictionary(uniqueKeysWithValues: logos.map { ($0.key.int32Value, $0.value as String) })
        }
        homeWrapper.viewModel.refreshProviderLogos()
    }
}
