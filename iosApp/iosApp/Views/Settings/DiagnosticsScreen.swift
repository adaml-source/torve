import SwiftUI
import shared

struct DiagnosticsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var showCopiedToast = false

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
    }

    private var buildNumber: String {
        Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
    }

    private var deviceModel: String {
        var systemInfo = utsname()
        uname(&systemInfo)
        let machine = withUnsafePointer(to: &systemInfo.machine) {
            $0.withMemoryRebound(to: CChar.self, capacity: 1) {
                String(cString: $0)
            }
        }
        return machine
    }

    private var systemVersion: String {
        "\(UIDevice.current.systemName) \(UIDevice.current.systemVersion)"
    }

    private var diagnosticsText: String {
        let s = wrapper.state
        let aiKeyConfigured = !s.activeAiApiKey.isEmpty
        let jellyfinConfigured = !s.jellyfinApiKey.isEmpty
        let mdblistConfigured = !s.mdblistApiKey.isEmpty

        let settings: [(String, String)] = [
            ("traktConnected", "\(s.traktConnected)"),
            ("debridConnected", "\(s.debridConnected)"),
            ("debridProvider", s.debridProvider.name),
            ("connectedDebridCount", "\(s.connectedDebridProviders.count)"),
            ("simklConnected", "\(s.simklConnected)"),
            ("jellyfinConfigured", "\(jellyfinConfigured)"),
            ("jellyfinUrl", s.jellyfinServerUrl.isEmpty ? "" : s.jellyfinServerUrl),
            ("plexConnected", "\(s.plexConnected)"),
            ("plexUrl", s.plexServerUrl.isEmpty ? "" : s.plexServerUrl),
            ("regionCode", s.regionCode),
            ("traktLastSync", s.traktLastSyncTime.map { "\($0.int64Value)" } ?? ""),
            ("availabilityLastSync", s.availabilityLastSyncTime.map { "\($0.int64Value)" } ?? ""),
            ("libraryOverlayLastSync", s.libraryOverlayLastSyncTime.map { "\($0.int64Value)" } ?? ""),
            ("ratingProviders", (s.ratingPrefs.enabledProviders as? [AnyObject] ?? []).map { "\($0)" }.joined(separator: ",")),
            ("ratingPillStyle", s.ratingPrefs.pillStyle.name),
            ("ratingPillPosition", s.ratingPrefs.pillPosition.name),
            ("maxRatingsOnCard", "\(s.ratingPrefs.maxRatingsOnCard)"),
            ("cardStylePresets", "\(s.cardStylePresets.count)"),
            ("globalDefaultPreset", s.globalDefaultPresetId ?? "default"),
            ("regexPatterns", "\(s.regexPatterns.count)"),
            ("streamGroups", "\(s.streamGroups.count)"),
            ("kodiHosts", "\(s.kodiHosts.count)"),
            ("aiProvider", s.aiProvider.name),
            ("aiKeyConfigured", "\(aiKeyConfigured)"),
            ("mdblistConfigured", "\(mdblistConfigured)"),
            ("maxQuality", s.maxQuality.name),
            ("minQuality", s.minQuality.name),
            ("maxFileSizeMb", s.maxFileSizeMb.map { "\($0.int32Value)" } ?? "unlimited"),
            ("cachedOnly", "\(s.cachedOnly)"),
            ("codecPreference", s.codecPreference.name),
            ("hdrMode", s.hdrMode.name),
            ("traktScrobble", "\(s.traktScrobbleEnabled)"),
            ("dedupeResults", "\(s.dedupeResults)"),
            ("theme", s.themeMode.name),
            ("language", s.appLanguage.code),
        ]
        let settingsLine = settings.map { "\($0.0)=\($0.1)" }.joined(separator: ";")
        return """
        Torve diagnostics
        appVersion=\(appVersion)
        buildType=release
        device=\(deviceModel)
        system=\(systemVersion)
        settings=\(settingsLine)
        """
    }

    var body: some View {
        List {
            // 1. Device & App Info
            Section {
                diagRow("App", value: appVersion)
                diagRow("Build", value: buildNumber)
                diagRow("Device", value: deviceModel)
                diagRow("System", value: systemVersion)
                diagRow("Region", value: wrapper.state.regionCode)
            } header: {
                Text("Device & App Info")
            }

            // 2. Service Health
            Section {
                statusRow("Cloud", connected: wrapper.state.debridConnected)
                statusRow("Trakt", connected: wrapper.state.traktConnected)
                statusRow("SIMKL", connected: wrapper.state.simklConnected)
                statusRow("Jellyfin", connected: !wrapper.state.jellyfinApiKey.isEmpty)
                statusRow("Plex", connected: wrapper.state.plexConnected)
                statusRow("AI (\(wrapper.state.aiProvider.name))", connected: !wrapper.state.activeAiApiKey.isEmpty)
                statusRow("MDBList", connected: !wrapper.state.mdblistApiKey.isEmpty)
            } header: {
                Text("Service Health")
            }

            // 3. Sync Timestamps
            Section {
                diagRow("Trakt last sync", value: formatOptionalTimestamp(wrapper.state.traktLastSyncTime))
                diagRow("Availability last sync", value: formatOptionalTimestamp(wrapper.state.availabilityLastSyncTime))
                diagRow("Library overlay last sync", value: formatOptionalTimestamp(wrapper.state.libraryOverlayLastSyncTime))
            } header: {
                Text("Sync Status")
            }

            // 4. Content & Configuration
            Section {
                diagRow("Card style presets", value: "\(wrapper.state.cardStylePresets.count)")
                diagRow("Global default preset", value: wrapper.state.globalDefaultPresetId ?? "default")
                diagRow("Regex patterns", value: "\(wrapper.state.regexPatterns.count) active")
                diagRow("Stream groups", value: "\(wrapper.state.streamGroups.count)")
                diagRow("Kodi devices", value: "\(wrapper.state.kodiHosts.count)")
                diagRow("AI provider", value: wrapper.state.aiProvider.name)
                diagRow("AI key configured", value: wrapper.state.activeAiApiKey.isEmpty ? "No" : "Yes")
                diagRow("Cloud provider", value: wrapper.state.debridProvider.name)
                diagRow("Connected cloud services", value: "\(wrapper.state.connectedDebridProviders.count)")
            } header: {
                Text("Content & Configuration")
            }

            // 5. Stream Preferences
            Section {
                diagRow("Max quality", value: wrapper.state.maxQuality.name)
                diagRow("Min quality", value: wrapper.state.minQuality.name)
                if let maxSize = wrapper.state.maxFileSizeMb?.int32Value {
                    diagRow("Max file size", value: "\(maxSize) MB")
                } else {
                    diagRow("Max file size", value: "No limit")
                }
                diagRow("Cached only", value: "\(wrapper.state.cachedOnly)")
                diagRow("Codec preference", value: wrapper.state.codecPreference.name)
                diagRow("HDR mode", value: wrapper.state.hdrMode.name)
                diagRow("Dedupe results", value: "\(wrapper.state.dedupeResults)")
            } header: {
                Text("Stream Preferences")
            }

            // 6. Ratings Configuration
            Section {
                let prefs = wrapper.state.ratingPrefs
                let providers = (prefs.enabledProviders as? [AnyObject] ?? []).map { "\($0)" }.joined(separator: ", ")
                diagRow("Enabled providers", value: providers.isEmpty ? "None" : providers)
                diagRow("Pill style", value: prefs.pillStyle.name)
                diagRow("Pill position", value: prefs.pillPosition.displayName)
                diagRow("Max ratings on card", value: "\(prefs.maxRatingsOnCard)")
                diagRow("Show on detail page", value: "\(prefs.showRatingsOnDetailPage)")
                diagRow("Torve on cards", value: "\(prefs.showTorveScoreOnCards)")
                diagRow("Landscape ratings", value: "\(prefs.allowRatingsOnLandscapeCards)")
            } header: {
                Text("Rating Configuration")
            }

            // 7. Integrations Detail
            Section {
                diagRow("Jellyfin URL", value: wrapper.state.jellyfinServerUrl.isEmpty ? "Not configured" : wrapper.state.jellyfinServerUrl)
                diagRow("Jellyfin profiles", value: "\(wrapper.state.jellyfinProfiles.count)")
                diagRow("Plex URL", value: wrapper.state.plexServerUrl.isEmpty ? "Not configured" : wrapper.state.plexServerUrl)
                diagRow("Plex connected", value: "\(wrapper.state.plexConnected)")
                diagRow("Trakt scrobble", value: "\(wrapper.state.traktScrobbleEnabled)")
                diagRow("Theme", value: wrapper.state.themeMode.name)
                diagRow("Language", value: wrapper.state.appLanguage.displayName)
            } header: {
                Text("Integrations Detail")
            }

            // 8. Raw Diagnostics
            Section {
                Text(diagnosticsText)
                    .font(.caption2)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .textSelection(.enabled)
            } header: {
                Text("Raw Diagnostics")
            }

            // 9. Actions
            Section {
                Button {
                    shareDiagnostics()
                } label: {
                    Label("Share Diagnostics", systemImage: "square.and.arrow.up")
                        .foregroundColor(SVColor.amber)
                }

                Button {
                    UIPasteboard.general.string = diagnosticsText
                    showCopiedToast = true
                    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
                        showCopiedToast = false
                    }
                } label: {
                    Label("Copy Diagnostics", systemImage: "doc.on.doc")
                        .foregroundColor(SVColor.amber)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("Diagnostics")
        .overlay(alignment: .bottom) {
            if showCopiedToast {
                Text("Copied to clipboard")
                    .font(.subheadline)
                    .fontWeight(.medium)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(.ultraThinMaterial)
                    .clipShape(Capsule())
                    .padding(.bottom, 24)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                    .animation(.easeInOut, value: showCopiedToast)
            }
        }
    }

    // MARK: - Row Helpers

    private func diagRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label)
                .foregroundColor(SVColor.onSurfaceVariant)
            Spacer()
            Text(value)
                .foregroundColor(SVColor.onSurface)
                .fontWeight(.medium)
        }
        .font(.subheadline)
    }

    private func statusRow(_ label: String, connected: Bool) -> some View {
        HStack {
            Text(label)
                .font(.subheadline)
                .foregroundColor(SVColor.onSurfaceVariant)
            Spacer()
            Text(connected ? "Connected" : "Not configured")
                .font(.caption)
                .fontWeight(connected ? .semibold : .regular)
                .padding(.horizontal, 8)
                .padding(.vertical, 2)
                .background(connected ? SVColor.emerald.opacity(0.2) : Color.secondary.opacity(0.2))
                .foregroundColor(connected ? SVColor.emerald : .secondary)
                .clipShape(Capsule())
        }
    }

    // MARK: - Timestamp Formatting

    private func formatOptionalTimestamp(_ millis: KotlinLong?) -> String {
        guard let millis = millis else { return "Never" }
        return formatTimestamp(millis.int64Value)
    }

    private func formatTimestamp(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateFormat = "MMM d, HH:mm"
        return formatter.string(from: date)
    }

    // MARK: - Actions

    private func shareDiagnostics() {
        let activityVC = UIActivityViewController(
            activityItems: [diagnosticsText],
            applicationActivities: nil
        )
        if let windowScene = UIApplication.shared.connectedScenes.first as? UIWindowScene,
           let rootVC = windowScene.windows.first?.rootViewController {
            // Handle iPad popover
            if let popover = activityVC.popoverPresentationController {
                popover.sourceView = rootVC.view
                popover.sourceRect = CGRect(
                    x: rootVC.view.bounds.midX,
                    y: rootVC.view.bounds.midY,
                    width: 0,
                    height: 0
                )
                popover.permittedArrowDirections = []
            }
            rootVC.present(activityVC, animated: true)
        }
    }
}
