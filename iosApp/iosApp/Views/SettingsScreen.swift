import SwiftUI
import shared

struct SettingsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var showClearCacheConfirmation = false

    var body: some View {
        List {
            quickLinksSection
            accountSection
            contentSection
            servicesSection
            playbackSection
            aiSection
            appearanceSection
            dataSection
            storageSection
            aboutSection
        }
        .navigationTitle("Settings")
        .confirmationDialog("Clear Cache?", isPresented: $showClearCacheConfirmation) {
            Button("Clear", role: .destructive) {
                wrapper.viewModel.clearCache()
            }
        } message: {
            Text("This will clear all cached images and metadata. Your saved data will not be affected.")
        }
    }

    // MARK: - Quick Links

    private var quickLinksSection: some View {
        Section {
            HStack(spacing: 12) {
                NavigationLink(value: Route.profileTab) {
                    quickLinkCard(icon: "person.crop.circle", title: "Profile")
                }
                .buttonStyle(.plain)

                NavigationLink(value: Route.legal) {
                    quickLinkCard(icon: "checkmark.seal", title: "Free Software")
                }
                .buttonStyle(.plain)
            }
            .listRowInsets(EdgeInsets(top: 8, leading: 16, bottom: 4, trailing: 16))
            .listRowBackground(Color.clear)

            HStack(spacing: 12) {
                NavigationLink(value: Route.downloads) {
                    quickLinkCard(icon: "arrow.down.circle", title: "Downloads")
                }
                .buttonStyle(.plain)

                NavigationLink(value: Route.calendar) {
                    quickLinkCard(icon: "calendar", title: "Calendar")
                }
                .buttonStyle(.plain)
            }
            .listRowInsets(EdgeInsets(top: 4, leading: 16, bottom: 8, trailing: 16))
            .listRowBackground(Color.clear)
        }
    }

    private func quickLinkCard(icon: String, title: String) -> some View {
        HStack {
            Image(systemName: icon)
                .foregroundColor(SVColor.amber)
            Text(title)
                .font(.subheadline)
                .fontWeight(.medium)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 10)
        .background(Color(.tertiarySystemBackground))
        .cornerRadius(10)
    }

    // MARK: - Account

    private var accountSection: some View {
        Section("Account") {
            NavigationLink(value: Route.account) {
                Label("Account & Sync", systemImage: "person.crop.circle")
            }
            NavigationLink(value: Route.devices) {
                Label("Devices", systemImage: "laptopcomputer.and.iphone")
            }
            NavigationLink(value: Route.manageDevices) {
                Label("Manage Devices", systemImage: "desktopcomputer")
            }
            // Recovery card surfaces only when 2+ transferable provider
            // categories have no local credentials. Self-hides otherwise.
            // Its "Receive credentials" button is a NavigationLink to
            // Route.transferReceive — works inside this NavigationStack.
            RestoreSetupRecoveryCard()
            NavigationLink(value: Route.transferSend) {
                Label("Send credentials to another device", systemImage: "square.and.arrow.up.on.square")
            }
            NavigationLink(value: Route.transferReceive) {
                Label("Receive credentials from another device", systemImage: "square.and.arrow.down.on.square")
            }
            NavigationLink(value: Route.transferDiagnostics) {
                Label("Transfer diagnostics", systemImage: "stethoscope")
            }
        }
    }

    // MARK: - Content

    private var contentSection: some View {
        Section("Content") {
            NavigationLink(value: Route.addonManager) {
                Label("Content Sources", systemImage: "puzzlepiece.extension")
            }
            NavigationLink(value: Route.homeLayout) {
                Label("Home Layout", systemImage: "square.grid.2x2")
            }
            NavigationLink(value: Route.streamingServices) {
                Label("Streaming Services", systemImage: "play.tv")
            }
            NavigationLink(value: Route.customSectionEditor) {
                Label("Custom Sections", systemImage: "rectangle.stack")
            }
            NavigationLink(value: Route.cardStyleSettings) {
                Label("Card Style", systemImage: "rectangle.portrait")
            }
        }
    }

    // MARK: - Services

    private var servicesSection: some View {
        Section("Services") {
            NavigationLink(value: Route.pandaSetup) {
                Label("Panda (guided setup)", systemImage: "wand.and.stars")
            }
            NavigationLink(value: Route.traktSettings) {
                HStack {
                    Label("Trakt.tv", systemImage: "list.bullet")
                    Spacer()
                    statusDot(wrapper.state.traktConnected)
                }
            }
            NavigationLink(value: Route.simklSettings) {
                HStack {
                    Label("SIMKL", systemImage: "chart.bar")
                    Spacer()
                    statusDot(wrapper.state.simklConnected)
                }
            }
            NavigationLink(value: Route.integrations) {
                Label("Integrations", systemImage: "link")
            }
            NavigationLink(value: Route.mdbListSettings) {
                Label("MDBList", systemImage: "list.star")
            }
        }
    }

    // MARK: - Playback

    private var playbackSection: some View {
        Section("Playback") {
            NavigationLink(value: Route.playbackSettings) {
                Label("Playback", systemImage: "play.circle")
            }
            NavigationLink(value: Route.streamGroups) {
                Label("Stream Groups", systemImage: "folder")
            }
            NavigationLink(value: Route.regexPatterns) {
                Label("Regex Patterns", systemImage: "textformat.abc")
            }
            NavigationLink(value: Route.ratingSettings) {
                Label("Ratings", systemImage: "star.leadinghalf.filled")
            }
        }
    }

    // MARK: - AI Features

    private var aiSection: some View {
        Section {
            Picker("AI Provider", selection: Binding(
                get: { wrapper.state.aiProvider.name },
                set: { _ in }
            )) {
                Text("Claude").tag("CLAUDE")
                Text("ChatGPT").tag("CHATGPT")
                Text("Gemini").tag("GEMINI")
                Text("Perplexity").tag("PERPLEXITY")
                Text("DeepSeek").tag("DEEPSEEK")
            }

            HStack {
                Text("API Key")
                Spacer()
                if wrapper.state.activeAiApiKey.isEmpty {
                    Text("Not configured")
                        .font(.caption)
                        .foregroundColor(.secondary)
                } else {
                    Text("Configured")
                        .font(.caption)
                        .foregroundColor(SVColor.emerald)
                }
            }
        } header: {
            Text("AI Features")
        } footer: {
            Text("AI powers smart search, mood matcher, and custom section creation. Configure your API key in the relevant provider's settings.")
        }
    }

    // MARK: - Appearance

    private var appearanceSection: some View {
        Section("Appearance") {
            Picker("Theme", selection: Binding(
                get: { wrapper.state.themeMode },
                set: { wrapper.viewModel.setThemeMode(mode: $0) }
            )) {
                Text("System").tag(ThemeMode.system)
                Text("Light").tag(ThemeMode.light)
                Text("Dark").tag(ThemeMode.dark)
            }

            Picker("Language", selection: Binding(
                get: { wrapper.state.appLanguage },
                set: { wrapper.viewModel.setAppLanguage(language: $0) }
            )) {
                Text("English").tag(AppLanguage.english)
                Text("German").tag(AppLanguage.german)
                Text("French").tag(AppLanguage.french)
                Text("Spanish").tag(AppLanguage.spanish)
                Text("Italian").tag(AppLanguage.italian)
                Text("Portuguese").tag(AppLanguage.portuguese)
                Text("Turkish").tag(AppLanguage.turkish)
            }
        }
    }

    // MARK: - Data

    private var dataSection: some View {
        Section("Data") {
            NavigationLink(value: Route.backupSync) {
                Label("Backup & Sync", systemImage: "arrow.triangle.2.circlepath")
            }
        }
    }

    // MARK: - Storage

    private var storageSection: some View {
        Section {
            Button {
                showClearCacheConfirmation = true
            } label: {
                HStack {
                    Label("Clear Cache", systemImage: "trash")
                    Spacer()
                    if wrapper.state.cacheCleared {
                        Text("Cleared")
                            .font(.caption)
                            .foregroundColor(SVColor.emerald)
                    }
                }
            }
        } header: {
            Text("Storage")
        } footer: {
            Text("Clears cached images and metadata. Your saved data, downloads, and preferences are not affected.")
        }
    }

    // MARK: - About

    private var aboutSection: some View {
        Section("About") {
            NavigationLink(value: Route.diagnostics) {
                Label("Diagnostics", systemImage: "stethoscope")
            }
            NavigationLink(value: Route.privacyPolicy) {
                Label("Privacy Policy", systemImage: "hand.raised")
            }
            NavigationLink(value: Route.legal) {
                Label("Legal", systemImage: "doc.text")
            }
            HStack {
                Text("Version")
                Spacer()
                Text(Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0")
                    .foregroundColor(.secondary)
            }
        }
    }

    // MARK: - Helpers

    private func statusDot(_ connected: Bool) -> some View {
        Circle()
            .fill(connected ? SVColor.emerald : Color.secondary.opacity(0.3))
            .frame(width: 8, height: 8)
    }
}
