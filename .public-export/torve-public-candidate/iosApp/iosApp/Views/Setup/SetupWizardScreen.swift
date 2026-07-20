import SwiftUI
import shared

struct SetupWizardScreen: View {
    @StateObject private var wrapper = SetupWizardViewModelWrapper()
    @Environment(\.dismiss) private var dismiss

    /// Modes for the iOS setup screen. Default is the credential-first
    /// hub (matching desktop / Android). The legacy linear wizard stays
    /// reachable via the hub's "Use guided wizard instead" link.
    private enum Mode { case hub, guided }
    @State private var mode: Mode = .hub

    /// Invoked when the user taps "Continue" on the hub or "Start
    /// streaming" at the end of the guided flow. Root navigation passes
    /// a closure that pops the wizard off the nav stack.
    var onComplete: (() -> Void)? = nil

    /// Routes Plex/Jellyfin "Set up" tap to an actionable surface.
    /// Default is `Route.integrations`-driven nav; ContentView wires it.
    var onOpenIntegrations: (() -> Void)? = nil

    /// Routes Usenet "Set up" tap to Panda multi-step setup. ContentView
    /// wires `Route.pandaSetup`.
    var onOpenPandaSetup: (() -> Void)? = nil

    var body: some View {
        Group {
            switch mode {
            case .hub:
                SetupIntentHubScreen(
                    onOpenDebridSetup: {
                        wrapper.viewModel.jumpToStep(step: SetupStep.debrid)
                        mode = .guided
                    },
                    onOpenIptvSetup: {
                        wrapper.viewModel.jumpToStep(step: SetupStep.channels)
                        mode = .guided
                    },
                    onOpenPlexJellyfinSetup: {
                        // Plex/Jellyfin lives under Settings →
                        // Integrations on iOS. The hub view marks the
                        // intent IN_PROGRESS via beginIntent before
                        // calling this closure; ContentView pushes
                        // Route.integrations on the active nav stack.
                        onOpenIntegrations?()
                    },
                    onOpenUsenetSetup: {
                        // Usenet → Panda multi-step setup. ContentView
                        // pushes Route.pandaSetup on the active stack.
                        onOpenPandaSetup?()
                    },
                    onUseGuidedWizard: { mode = .guided },
                    onContinueToApp: { onComplete?() }
                )
            case .guided:
                guidedFlow
            }
        }
        .navigationTitle("Setup")
        .navigationBarTitleDisplayMode(.inline)
        .interactiveDismissDisabled()
    }

    private var guidedFlow: some View {
        VStack(spacing: 0) {
            HStack {
                Button("← Back to hub") { mode = .hub }
                    .foregroundColor(SVColor.onSurfaceVariant)
                Spacer()
            }
            .padding(.horizontal)
            .padding(.top, 8)

            // Progress indicator
            progressBar

            // Step content
            TabView(selection: Binding(
                get: { wrapper.state.currentStep },
                set: { _ in }
            )) {
                welcomeStep.tag(SetupStep.welcome)
                termsStep.tag(SetupStep.terms)
                debridStep.tag(SetupStep.debrid)
                traktStep.tag(SetupStep.trakt)
                qualityStep.tag(SetupStep.quality)
                channelsStep.tag(SetupStep.channels)
                doneStep.tag(SetupStep.done)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut, value: wrapper.state.currentStep)

            // Navigation buttons
            if wrapper.state.currentStep != .done {
                navigationButtons
            }
        }
    }

    // MARK: - Progress Bar

    private var progressBar: some View {
        let steps: [SetupStep] = [.welcome, .terms, .debrid, .trakt, .quality, .channels, .done]
        let currentIndex = steps.firstIndex(of: wrapper.state.currentStep) ?? 0
        let progress = Double(currentIndex) / Double(steps.count - 1)

        return ProgressView(value: progress)
            .tint(SVColor.amber)
            .padding(.horizontal)
            .padding(.top, 8)
    }

    // MARK: - Navigation Buttons

    private var navigationButtons: some View {
        HStack {
            if wrapper.state.currentStep != .welcome {
                Button("Back") {
                    wrapper.previousStep()
                }
                .foregroundColor(SVColor.onSurfaceVariant)
            }

            Spacer()

            if canSkip {
                Button("Skip") {
                    wrapper.skipStep()
                }
                .foregroundColor(SVColor.onSurfaceVariant)
            }

            Button("Next") {
                wrapper.nextStep()
            }
            .fontWeight(.semibold)
            .foregroundColor(SVColor.amber)
            .disabled(!canProceed)
        }
        .padding()
    }

    private var canSkip: Bool {
        let step = wrapper.state.currentStep
        return step == .debrid || step == .trakt || step == .channels
    }

    private var canProceed: Bool {
        switch wrapper.state.currentStep {
        case .terms:
            return wrapper.state.termsAccepted
        default:
            return true
        }
    }

    // MARK: - Welcome

    private var welcomeStep: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "play.rectangle.fill")
                .font(.system(size: 72))
                .foregroundColor(SVColor.amber)

            Text("Welcome to Torve")
                .font(.largeTitle)
                .fontWeight(.bold)
                .multilineTextAlignment(.center)

            Text("Browse. Pick. Watch.\nOne app. Every device.")
                .font(.title3)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)

            Spacer()
        }
        .padding()
    }

    // MARK: - Terms

    private var termsStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "doc.text.fill")
                .font(.system(size: 48))
                .foregroundColor(SVColor.amber)
                .padding(.top, 32)

            Text("Terms of Use")
                .font(.title2)
                .fontWeight(.bold)

            ScrollView {
                Text("""
                Torve is a media player. It does not host, store, or distribute any media content. \
                Users are responsible for ensuring they have legal access to any content they play.

                By using this app, you agree to:
                - Use the app only with content you have the legal right to access
                - Not use the app for piracy or copyright infringement
                - Accept that the developers are not responsible for user content

                This app connects to third-party services (debrid, Trakt, addons) at your discretion. \
                Your API keys and credentials are stored locally on your device.
                """)
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)
                .padding(.horizontal)
            }

            Toggle("I accept the Terms of Use", isOn: Binding(
                get: { wrapper.state.termsAccepted },
                set: { wrapper.setTermsAccepted($0) }
            ))
            .tint(SVColor.amber)
            .padding(.horizontal)

            Spacer()
        }
    }

    // MARK: - Debrid

    private var debridStep: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "server.rack")
                    .font(.system(size: 48))
                    .foregroundColor(SVColor.amber)
                    .padding(.top, 32)

                Text("Cloud Service")
                    .font(.title2)
                    .fontWeight(.bold)

                Text("Connect a debrid service to enable high-speed cached streams.")
                    .font(.subheadline)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                // Provider picker
                Picker("Provider", selection: Binding(
                    get: { wrapper.state.debridProvider },
                    set: { wrapper.setDebridProvider($0) }
                )) {
                    Text("Real-Debrid").tag(DebridServiceType.realDebrid)
                    Text("AllDebrid").tag(DebridServiceType.allDebrid)
                    Text("Premiumize").tag(DebridServiceType.premiumize)
                    Text("TorBox").tag(DebridServiceType.torbox)
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                // API Key input
                VStack(alignment: .leading, spacing: 8) {
                    Text("API Key")
                        .font(.subheadline)
                        .fontWeight(.medium)

                    SecureField("Paste your API key", text: Binding(
                        get: { wrapper.state.debridApiKey },
                        set: { wrapper.setDebridApiKey($0) }
                    ))
                    .textFieldStyle(.roundedBorder)
                }
                .padding(.horizontal)

                if wrapper.state.debridConnected {
                    Label("Connected", systemImage: "checkmark.circle.fill")
                        .foregroundColor(SVColor.emerald)
                        .font(.headline)
                } else {
                    Button(action: { wrapper.connectDebrid() }) {
                        HStack {
                            if wrapper.state.debridLoading {
                                ProgressView()
                                    .tint(.black)
                            }
                            Text("Connect")
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(SVColor.amber)
                        .foregroundColor(.black)
                        .cornerRadius(12)
                    }
                    .disabled(wrapper.state.debridApiKey.isEmpty || wrapper.state.debridLoading)
                    .padding(.horizontal)
                }

                if let error = wrapper.state.debridError {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(SVColor.error)
                }

                Spacer()
            }
        }
    }

    // MARK: - Trakt

    private var traktStep: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "list.bullet")
                    .font(.system(size: 48))
                    .foregroundColor(SVColor.amber)
                    .padding(.top, 32)

                Text("Trakt.tv")
                    .font(.title2)
                    .fontWeight(.bold)

                Text("Sync your watchlist, history, and ratings with Trakt.")
                    .font(.subheadline)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                if wrapper.state.traktConnected {
                    VStack(spacing: 8) {
                        Label("Connected", systemImage: "checkmark.circle.fill")
                            .foregroundColor(SVColor.emerald)
                            .font(.headline)
                        if let username = wrapper.state.traktUsername {
                            Text("Signed in as \(username)")
                                .font(.subheadline)
                                .foregroundColor(SVColor.onSurfaceVariant)
                        }
                    }
                } else if let code = wrapper.state.traktDeviceCode {
                    VStack(spacing: 12) {
                        Text("Go to")
                            .font(.subheadline)
                            .foregroundColor(SVColor.onSurfaceVariant)
                        Text(code.verificationUrl)
                            .font(.headline)
                            .foregroundColor(SVColor.amber)
                        Text("and enter code:")
                            .font(.subheadline)
                            .foregroundColor(SVColor.onSurfaceVariant)
                        Text(code.userCode)
                            .font(.system(size: 32, weight: .bold, design: .monospaced))
                            .foregroundColor(SVColor.onSurface)
                            .padding()
                            .background(
                                RoundedRectangle(cornerRadius: 12)
                                    .fill(SVColor.surfaceVariant)
                            )

                        ProgressView("Waiting for authorization...")
                    }
                    .padding()
                } else {
                    Button(action: { wrapper.startTraktAuth() }) {
                        HStack {
                            if wrapper.state.traktLoading {
                                ProgressView()
                                    .tint(.black)
                            }
                            Text("Connect with Trakt")
                        }
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(SVColor.amber)
                        .foregroundColor(.black)
                        .cornerRadius(12)
                    }
                    .disabled(wrapper.state.traktLoading)
                    .padding(.horizontal)
                }

                if let error = wrapper.state.traktError {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(SVColor.error)
                }

                Spacer()
            }
        }
    }

    // MARK: - Quality

    private var qualityStep: some View {
        VStack(spacing: 20) {
            Image(systemName: "slider.horizontal.3")
                .font(.system(size: 48))
                .foregroundColor(SVColor.amber)
                .padding(.top, 32)

            Text("Stream Quality")
                .font(.title2)
                .fontWeight(.bold)

            Text("Set your preferred maximum stream quality.")
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Picker("Max Quality", selection: Binding(
                get: { wrapper.state.maxQuality },
                set: { wrapper.setMaxQuality($0) }
            )) {
                Text("4K Remux").tag(StreamQuality.remux4k)
                Text("4K").tag(StreamQuality.uhd4k)
                Text("1080p").tag(StreamQuality.fhd1080p)
                Text("720p").tag(StreamQuality.hd720p)
                Text("480p").tag(StreamQuality.sd480p)
            }
            .pickerStyle(.wheel)
            .frame(height: 150)

            Toggle("Cached streams only", isOn: Binding(
                get: { wrapper.state.cachedOnly },
                set: { wrapper.setCachedOnly($0) }
            ))
            .tint(SVColor.amber)
            .padding(.horizontal)

            Text("Cached streams start instantly. Uncached streams may take longer to begin.")
                .font(.caption)
                .foregroundColor(SVColor.onSurfaceVariant)
                .padding(.horizontal)

            Spacer()
        }
    }

    // MARK: - Channels

    private var channelsStep: some View {
        ScrollView {
            VStack(spacing: 20) {
                Image(systemName: "antenna.radiowaves.left.and.right")
                    .font(.system(size: 48))
                    .foregroundColor(SVColor.amber)
                    .padding(.top, 32)

                Text("Channels")
                    .font(.title2)
                    .fontWeight(.bold)

                Text("Add an M3U or Xtream Codes playlist for live TV channels.")
                    .font(.subheadline)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                // Playlist type
                Picker("Type", selection: Binding(
                    get: { wrapper.state.channelPlaylistType },
                    set: { wrapper.setChannelPlaylistType($0) }
                )) {
                    Text("M3U").tag("m3u")
                    Text("Xtream").tag("xtream")
                }
                .pickerStyle(.segmented)
                .padding(.horizontal)

                if wrapper.state.channelPlaylistType == "m3u" {
                    m3uFields
                } else {
                    xtreamFields
                }

                Spacer()
            }
        }
    }

    private var m3uFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Playlist Name", text: Binding(
                get: { wrapper.state.channelPlaylistName },
                set: { wrapper.setChannelPlaylistName($0) }
            ))
            .textFieldStyle(.roundedBorder)

            TextField("M3U URL", text: Binding(
                get: { wrapper.state.channelPlaylistUrl },
                set: { wrapper.setChannelPlaylistUrl($0) }
            ))
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)
        }
        .padding(.horizontal)
    }

    private var xtreamFields: some View {
        VStack(alignment: .leading, spacing: 12) {
            TextField("Server URL", text: Binding(
                get: { wrapper.state.channelXtreamServer },
                set: { wrapper.setChannelXtreamServer($0) }
            ))
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)
            .keyboardType(.URL)

            TextField("Username", text: Binding(
                get: { wrapper.state.channelXtreamUsername },
                set: { wrapper.setChannelXtreamUsername($0) }
            ))
            .textFieldStyle(.roundedBorder)
            .textInputAutocapitalization(.never)

            SecureField("Password", text: Binding(
                get: { wrapper.state.channelXtreamPassword },
                set: { wrapper.setChannelXtreamPassword($0) }
            ))
            .textFieldStyle(.roundedBorder)
        }
        .padding(.horizontal)
    }

    // MARK: - Done

    private var doneStep: some View {
        VStack(spacing: 24) {
            Spacer()

            Image(systemName: "checkmark.seal.fill")
                .font(.system(size: 72))
                .foregroundColor(SVColor.emerald)

            Text("You're All Set!")
                .font(.largeTitle)
                .fontWeight(.bold)

            Text("Start exploring movies, TV shows, and live channels.")
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)
                .multilineTextAlignment(.center)
                .padding(.horizontal)

            Button(action: {
                wrapper.completeSetup()
                // Prefer the explicit onComplete from root nav; fall back
                // to dismiss() so deep-linked entries (e.g. opening the
                // wizard from Settings) still close cleanly.
                if let cb = onComplete { cb() } else { dismiss() }
            }) {
                Text("Get Started")
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(SVColor.amber)
                    .foregroundColor(.black)
                    .cornerRadius(12)
                    .fontWeight(.semibold)
            }
            .padding(.horizontal, 32)

            Spacer()
        }
    }
}
