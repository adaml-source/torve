import SwiftUI

struct PrivacyPolicyScreen: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("Privacy Policy")
                    .font(.title)
                    .fontWeight(.bold)

                Text("Last updated: March 2026")
                    .font(.caption)
                    .foregroundColor(.secondary)

                Group {
                    sectionText(
                        title: "Overview",
                        body: "Torve is committed to protecting your privacy. This policy explains what data we collect, how we use it, and your rights regarding your personal information."
                    )

                    sectionText(
                        title: "Data We Collect",
                        body: """
                        Torve collects minimal data to provide its services:

                        - Account credentials (encrypted and stored locally or in your device keychain)
                        - Viewing preferences and playback settings
                        - Watch history and progress (stored locally on your device)
                        - IPTV playlist URLs and favorites (stored locally)
                        - Addon/extension configuration
                        """
                    )

                    sectionText(
                        title: "Data We Do NOT Collect",
                        body: """
                        - We do not track your viewing habits
                        - We do not sell or share your data with third parties
                        - We do not use advertising SDKs
                        - We do not store your API keys on our servers
                        - The iOS version of Torve does not include any analytics or crash reporting SDKs
                        """
                    )

                    sectionText(
                        title: "Third-Party Services",
                        body: "Torve integrates with third-party services (Trakt, SIMKL, debrid providers, TMDB, OMDB) at your direction. Data shared with these services is governed by their respective privacy policies. Torve acts only as a client and does not proxy or store your interactions with these services."
                    )

                    sectionText(
                        title: "Local Storage",
                        body: "All sensitive data (API keys, tokens, credentials) is stored in the iOS Keychain or Android Keystore. Non-sensitive preferences are stored in local app storage. No data is transmitted to Torve servers."
                    )

                    sectionText(
                        title: "iCloud Sync",
                        body: "If you enable iCloud sync, your preferences and non-sensitive settings may be stored in your private iCloud container. This data is encrypted by Apple and accessible only to you."
                    )

                    sectionText(
                        title: "Your Rights",
                        body: """
                        You have the right to:
                        - Export all your data via the Backup feature
                        - Delete all your data by uninstalling the app
                        - Disconnect any third-party service at any time
                        - Opt out of iCloud sync
                        """
                    )

                    sectionText(
                        title: "Contact",
                        body: "For privacy-related questions or concerns, contact us at:\nprivacy@torve.app"
                    )
                }
            }
            .padding()
        }
        .navigationTitle("Privacy Policy")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func sectionText(title: String, body: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title)
                .font(.headline)
            Text(body)
                .font(.body)
                .foregroundColor(.secondary)
        }
    }
}
