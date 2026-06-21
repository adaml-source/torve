import SwiftUI

struct LegalScreen: View {
    var body: some View {
        List {
            Section("Licenses") {
                NavigationLink {
                    licenseDetail(
                        title: "Torve",
                        text: "Torve is licensed under AGPL-3.0-or-later.\n\nTorve is free software. There are no subscriptions, no paid tiers, no premium features, and no purchase required. Donations are optional and never unlock features."
                    )
                } label: {
                    Label("Torve", systemImage: "doc.text")
                }

                NavigationLink {
                    licenseDetail(
                        title: "Kotlin Multiplatform",
                        text: "Apache License 2.0\n\nCopyright JetBrains s.r.o.\n\nLicensed under the Apache License, Version 2.0. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0"
                    )
                } label: {
                    Label("Kotlin Multiplatform", systemImage: "chevron.left.forwardslash.chevron.right")
                }

                NavigationLink {
                    licenseDetail(
                        title: "Ktor",
                        text: "Apache License 2.0\n\nCopyright JetBrains s.r.o.\n\nLicensed under the Apache License, Version 2.0."
                    )
                } label: {
                    Label("Ktor", systemImage: "network")
                }

                NavigationLink {
                    licenseDetail(
                        title: "Koin",
                        text: "Apache License 2.0\n\nCopyright Arnaud Giuliani, Laurent Music.\n\nLicensed under the Apache License, Version 2.0."
                    )
                } label: {
                    Label("Koin", systemImage: "shippingbox")
                }

                NavigationLink {
                    licenseDetail(
                        title: "SQLDelight",
                        text: "Apache License 2.0\n\nCopyright Square, Inc. / CashApp.\n\nLicensed under the Apache License, Version 2.0."
                    )
                } label: {
                    Label("SQLDelight", systemImage: "cylinder")
                }

                NavigationLink {
                    licenseDetail(
                        title: "Coil",
                        text: "Apache License 2.0\n\nCopyright Coil Contributors.\n\nLicensed under the Apache License, Version 2.0."
                    )
                } label: {
                    Label("Coil", systemImage: "photo")
                }
            }

            Section("Data Sources") {
                dataSourceRow(
                    name: "TMDB",
                    description: "Movie and TV show metadata provided by The Movie Database (TMDB). This product uses the TMDB API but is not endorsed or certified by TMDB."
                )
                dataSourceRow(
                    name: "Trakt",
                    description: "Watch tracking and list management powered by Trakt.tv."
                )
                dataSourceRow(
                    name: "OMDB",
                    description: "Additional ratings data from the Open Movie Database."
                )
                dataSourceRow(
                    name: "MDBList",
                    description: "Curated media lists from MDBList.com."
                )
            }

            Section("Disclaimer") {
                Text("Torve does not host, store, or distribute any media content. It acts solely as a client application that connects to user-configured third-party services. Users are responsible for ensuring compliance with applicable laws in their jurisdiction.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }

            Section("Copyright") {
                Text("Copyright 2024-2026 Torve. Licensed under AGPL-3.0-or-later.")
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
        .navigationTitle("Legal")
    }

    private func licenseDetail(title: String, text: String) -> some View {
        ScrollView {
            Text(text)
                .font(.system(.body, design: .monospaced))
                .padding()
        }
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func dataSourceRow(name: String, description: String) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(name)
                .fontWeight(.medium)
            Text(description)
                .font(.caption)
                .foregroundColor(.secondary)
        }
        .padding(.vertical, 2)
    }
}
