import SwiftUI
import shared

/// Credential-first setup hub for iOS. Renders four intent cards
/// (Debrid / IPTV / Plex+Jellyfin / Usenet) with traffic-light status
/// pills, a "Ready to watch" summary banner, and per-intent
/// Set-up / Validate / Reset buttons.
///
/// The hub does not host credential entry forms — each "Set up" deep-links
/// into the matching legacy step (Debrid/IPTV) or settings surface (the
/// other two) so we don't duplicate per-intent UIs. The hub's job is to
/// track per-intent progress and surface the aggregated verdict.
struct SetupIntentHubScreen: View {
    @StateObject private var wrapper = SetupWizardCoordinatorWrapper()
    let onOpenDebridSetup: () -> Void
    let onOpenIptvSetup: () -> Void
    let onOpenPlexJellyfinSetup: () -> Void
    let onOpenUsenetSetup: () -> Void
    let onUseGuidedWizard: () -> Void
    let onContinueToApp: () -> Void

    private let intents: [SetupIntent] = [.debrid, .iptv, .plexJellyfin, .usenet]

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Pick what you'd like to set up.")
                    .font(.title2)
                    .fontWeight(.bold)
                Text("Complete one path or all four — the hub remembers progress between launches.")
                    .font(.subheadline)
                    .foregroundColor(SVColor.onSurfaceVariant)

                summaryBanner

                ForEach(intents, id: \.self) { intent in
                    SetupIntentRow(
                        intent: intent,
                        state: wrapper.state[intent] ?? wrapper.snapshot(intent),
                        onSetUp: { route(for: intent) },
                        onValidate: { wrapper.validate(intent) },
                        onReset: { wrapper.reset(intent) }
                    )
                }

                HStack {
                    Button("Use guided wizard instead") { onUseGuidedWizard() }
                        .foregroundColor(SVColor.onSurfaceVariant)
                    Spacer()
                    Button(wrapper.summary.canStartWatching ? "Continue" : "Set up at least one path") {
                        onContinueToApp()
                    }
                    .fontWeight(.semibold)
                    .foregroundColor(SVColor.amber)
                    .disabled(!wrapper.summary.canStartWatching)
                }
                .padding(.top, 4)
            }
            .padding()
        }
        .navigationTitle("Setup")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { wrapper.load() }
    }

    private func route(for intent: SetupIntent) {
        switch intent {
        case .debrid: onOpenDebridSetup()
        case .iptv: onOpenIptvSetup()
        case .plexJellyfin:
            wrapper.beginIntent(intent)
            onOpenPlexJellyfinSetup()
        case .usenet:
            wrapper.beginIntent(intent)
            onOpenUsenetSetup()
        default: break
        }
    }

    @ViewBuilder
    private var summaryBanner: some View {
        let summary = wrapper.summary
        let (title, container, content) = bannerStyle(for: summary)
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(.headline)
                .foregroundColor(content)
            Text(summaryDetail(summary))
                .font(.caption)
                .foregroundColor(content.opacity(0.85))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(container)
        .cornerRadius(12)
    }

    private func bannerStyle(for summary: ReadyToWatchSummary) -> (String, Color, Color) {
        if summary.canStartWatching && summary.attentionCount == 0 {
            let count = Int(summary.ready.count)
            return ("Ready to watch — \(count) path\(count == 1 ? "" : "s") green.", SVColor.amber.opacity(0.15), SVColor.amber)
        } else if summary.canStartWatching {
            return ("Ready to watch — \(summary.ready.count) ready, \(summary.attentionCount) need attention.", Color.orange.opacity(0.15), Color.orange)
        } else if !summary.invalid.isEmpty {
            let count = Int(summary.invalid.count)
            return ("Setup incomplete — \(count) path\(count == 1 ? "" : "s") need fixing.", Color.red.opacity(0.15), Color.red)
        } else {
            return ("Pick a path to start.", Color.gray.opacity(0.15), SVColor.onSurfaceVariant)
        }
    }

    private func summaryDetail(_ summary: ReadyToWatchSummary) -> String {
        var parts: [String] = []
        if !summary.ready.isEmpty {
            parts.append("Ready: " + summary.ready.map { intentShortName($0) }.joined(separator: ", "))
        }
        if !summary.warnings.isEmpty {
            parts.append("Warnings: " + summary.warnings.map { intentShortName($0) }.joined(separator: ", "))
        }
        if !summary.invalid.isEmpty {
            parts.append("Fix: " + summary.invalid.map { intentShortName($0) }.joined(separator: ", "))
        }
        return parts.isEmpty ? "No paths configured yet." : parts.joined(separator: " • ")
    }

    private func intentShortName(_ intent: SetupIntent) -> String {
        switch intent {
        case .debrid: return "Debrid"
        case .iptv: return "IPTV"
        case .plexJellyfin: return "Plex/Jellyfin"
        case .usenet: return "Usenet"
        default: return ""
        }
    }
}

private struct SetupIntentRow: View {
    let intent: SetupIntent
    let state: SetupIntentState
    let onSetUp: () -> Void
    let onValidate: () -> Void
    let onReset: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(intent.title).font(.headline)
                    Text(intent.tagline).font(.caption).foregroundColor(SVColor.onSurfaceVariant)
                }
                Spacer()
                StatusPill(status: state.status)
            }
            if let msg = state.message, !msg.isEmpty {
                Text(msg).font(.caption2).foregroundColor(SVColor.onSurfaceVariant)
            }
            HStack(spacing: 8) {
                Button(state.status == .ready ? "Edit" : "Set up", action: onSetUp)
                    .buttonStyle(.borderedProminent)
                Button("Validate", action: onValidate)
                    .buttonStyle(.bordered)
                    .disabled(state.status == .validating)
                if state.status != .notStarted {
                    Button("Reset", action: onReset).buttonStyle(.borderless)
                }
                if state.status == .validating {
                    ProgressView().scaleEffect(0.7)
                }
            }
        }
        .padding(12)
        .background(Color(white: 0.12))
        .cornerRadius(12)
    }
}

private struct StatusPill: View {
    let status: SetupIntentStatus
    var body: some View {
        let (label, container, content) = style(for: status)
        Text(label)
            .font(.caption2)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(container)
            .foregroundColor(content)
            .clipShape(Capsule())
    }

    private func style(for status: SetupIntentStatus) -> (String, Color, Color) {
        switch status {
        case .ready: return ("Ready", SVColor.amber.opacity(0.2), SVColor.amber)
        case .needsAttention: return ("Attention", Color.orange.opacity(0.2), Color.orange)
        case .invalid: return ("Fix", Color.red.opacity(0.2), Color.red)
        case .validating: return ("Checking", Color.blue.opacity(0.2), Color.blue)
        case .inProgress: return ("In progress", Color.gray.opacity(0.2), Color.white)
        case .notStarted: return ("Not started", Color.gray.opacity(0.2), SVColor.onSurfaceVariant)
        default: return ("?", Color.gray.opacity(0.2), Color.white)
        }
    }
}
