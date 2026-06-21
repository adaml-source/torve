import SwiftUI
import shared

struct IntegrationsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var jellyfinUrl: String = ""
    @State private var jellyfinApiKey: String = ""
    @State private var plexUrl: String = ""
    @State private var plexToken: String = ""
    @State private var omdbApiKey: String = ""

    // Kodi add host
    @State private var showAddKodiSheet = false
    @State private var newKodiName: String = ""
    @State private var newKodiIp: String = ""
    @State private var newKodiPort: String = "8080"

    var body: some View {
        List {
            traktSection
            simklSection
            jellyfinSection
            plexSection
            omdbSection
            kodiSection
            // Backend-managed NzbDAV integration. The card owns its own
            // wrapper instance (StateObject) so it doesn't depend on the
            // existing SettingsViewModelWrapper plumbing.
            NzbdavSetupCard()
        }
        .navigationTitle("Integrations")
        .onAppear {
            jellyfinUrl = wrapper.state.jellyfinServerUrl
            jellyfinApiKey = wrapper.state.jellyfinApiKey
            plexUrl = wrapper.state.plexServerUrl
            plexToken = wrapper.state.plexAccessToken
            omdbApiKey = wrapper.state.omdbApiKey
        }
    }

    // MARK: - Trakt

    private var traktSection: some View {
        Section {
            if wrapper.state.traktConnected {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Label("Connected", systemImage: "checkmark.circle.fill")
                            .foregroundColor(SVColor.emerald)
                        if let user = wrapper.state.traktUser {
                            Text(user.username)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                }

                Toggle("Auto-Scrobble", isOn: Binding(
                    get: { wrapper.state.traktScrobbleEnabled },
                    set: { wrapper.viewModel.setTraktScrobbleEnabled(enabled: $0) }
                ))

                if let lastSync = wrapper.state.traktLastSyncTime?.int64Value {
                    HStack {
                        Text("Last Sync")
                        Spacer()
                        Text(formatTimestamp(lastSync))
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }

                Button {
                    wrapper.viewModel.syncTraktNow()
                } label: {
                    HStack {
                        Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
                        Spacer()
                        if wrapper.state.traktSyncing {
                            ProgressView()
                        }
                    }
                }
                .disabled(wrapper.state.traktSyncing)

                if wrapper.state.traktSyncSuccess {
                    Label("Sync complete", systemImage: "checkmark")
                        .foregroundColor(SVColor.emerald)
                        .font(.caption)
                }

                Button("Disconnect", role: .destructive) {
                    wrapper.viewModel.disconnectTrakt()
                }
            } else {
                if let deviceCode = wrapper.state.traktDeviceCode {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Go to **trakt.tv/activate** and enter:")
                            .font(.subheadline)
                        Text(deviceCode.userCode)
                            .font(.title2)
                            .fontWeight(.bold)
                            .foregroundColor(SVColor.amber)
                            .textSelection(.enabled)

                        if wrapper.state.isPollingTrakt {
                            HStack {
                                ProgressView()
                                Text("Waiting for authorization...")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .padding(.leading, 8)
                            }
                        }
                    }
                } else {
                    Button {
                        wrapper.viewModel.startTraktDeviceAuth()
                    } label: {
                        Label("Connect with Device Code", systemImage: "link")
                    }
                }

                if wrapper.state.traktLoading {
                    HStack {
                        ProgressView()
                        Text("Connecting...")
                            .foregroundColor(.secondary)
                            .padding(.leading, 8)
                    }
                }

                if let error = wrapper.state.traktError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(SVColor.error)
                        .font(.caption)
                }
            }
        } header: {
            Text("Trakt")
        } footer: {
            Text("Track your watch history, manage watchlists, and sync progress across devices via Trakt.tv.")
        }
    }

    // MARK: - SIMKL

    private var simklSection: some View {
        Section {
            if wrapper.state.simklConnected {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Label("Connected", systemImage: "checkmark.circle.fill")
                            .foregroundColor(SVColor.emerald)
                        if let user = wrapper.state.simklUser {
                            Text(user.username)
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                    }
                    Spacer()
                }

                Button("Disconnect", role: .destructive) {
                    wrapper.viewModel.disconnectSimkl()
                }
            } else {
                if let deviceCode = wrapper.state.simklDeviceCode {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Go to **simkl.com/pin** and enter:")
                            .font(.subheadline)
                        Text(deviceCode.userCode)
                            .font(.title2)
                            .fontWeight(.bold)
                            .foregroundColor(SVColor.amber)
                            .textSelection(.enabled)

                        if wrapper.state.isPollingSimkl {
                            HStack {
                                ProgressView()
                                Text("Waiting for authorization...")
                                    .font(.caption)
                                    .foregroundColor(.secondary)
                                    .padding(.leading, 8)
                            }
                        }
                    }
                } else {
                    Button {
                        wrapper.viewModel.startSimklDeviceAuth()
                    } label: {
                        Label("Connect with Device Code", systemImage: "link")
                    }
                }

                if wrapper.state.simklLoading {
                    HStack {
                        ProgressView()
                        Text("Connecting...")
                            .foregroundColor(.secondary)
                            .padding(.leading, 8)
                    }
                }

                if let error = wrapper.state.simklError {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(SVColor.error)
                        .font(.caption)
                }
            }
        } header: {
            Text("SIMKL")
        } footer: {
            Text("Sync your anime and TV show tracking with SIMKL.")
        }
    }

    // MARK: - Jellyfin

    private var jellyfinSection: some View {
        Section("Jellyfin") {
            TextField("Server URL", text: $jellyfinUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)

            SecureField("API Key", text: $jellyfinApiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button("Connect") {
                wrapper.viewModel.setJellyfinServer(url: jellyfinUrl, apiKey: jellyfinApiKey)
            }
            .disabled(jellyfinUrl.isEmpty || jellyfinApiKey.isEmpty)

            if !wrapper.state.jellyfinProfiles.isEmpty {
                ForEach(wrapper.state.jellyfinProfiles, id: \.id) { profile in
                    HStack {
                        Text(profile.name)
                        Spacer()
                        if wrapper.state.selectedJellyfinUserId == profile.id {
                            Image(systemName: "checkmark")
                                .foregroundColor(SVColor.amber)
                        }
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        wrapper.viewModel.selectJellyfinProfile(userId: profile.id)
                    }
                }
            }

            if let status = wrapper.state.jellyfinStatusMessage {
                Text(status)
                    .font(.caption)
                    .foregroundColor(.secondary)
            }
        }
    }

    // MARK: - Plex

    private var plexSection: some View {
        Section("Plex") {
            TextField("Server URL", text: $plexUrl)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)

            SecureField("Access Token", text: $plexToken)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button("Connect") {
                wrapper.viewModel.setPlexServer(url: plexUrl, token: plexToken)
            }
            .disabled(plexUrl.isEmpty || plexToken.isEmpty)

            if wrapper.state.plexConnected {
                Label("Connected", systemImage: "checkmark.circle.fill")
                    .foregroundColor(SVColor.emerald)
            }

            if wrapper.state.plexLoading {
                HStack {
                    ProgressView()
                    Text("Connecting...")
                        .foregroundColor(.secondary)
                        .padding(.leading, 8)
                }
            }

            if let error = wrapper.state.plexError {
                Label(error, systemImage: "exclamationmark.triangle.fill")
                    .foregroundColor(SVColor.error)
            }
        }
    }

    // MARK: - OMDB

    private var omdbSection: some View {
        Section {
            SecureField("OMDB API Key", text: $omdbApiKey)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button("Save & Verify") {
                wrapper.viewModel.setOmdbApiKey(apiKey: omdbApiKey)
            }
            .disabled(omdbApiKey.isEmpty)

            if wrapper.state.omdbValidating {
                HStack {
                    ProgressView()
                    Text("Validating...")
                        .foregroundColor(.secondary)
                        .padding(.leading, 8)
                }
            }

            if let result = wrapper.state.omdbValidationResult {
                Label(
                    result == "valid" ? "Key is valid" : result,
                    systemImage: result == "valid" ? "checkmark.circle.fill" : "xmark.circle.fill"
                )
                .foregroundColor(result == "valid" ? SVColor.emerald : SVColor.error)
            }
        } header: {
            Text("OMDB")
        } footer: {
            Text("OMDB provides additional ratings data (IMDb, Rotten Tomatoes, Metacritic). Get a free key at omdbapi.com.")
        }
    }

    // MARK: - Kodi

    private var kodiSection: some View {
        Section {
            if wrapper.state.kodiHosts.isEmpty {
                Text("No Kodi hosts configured")
                    .foregroundColor(.secondary)
            } else {
                ForEach(wrapper.state.kodiHosts, id: \.ip) { host in
                    HStack {
                        VStack(alignment: .leading) {
                            Text(host.name)
                                .fontWeight(.medium)
                            Text("\(host.ip):\(host.port)")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()

                        let testKey = "\(host.ip):\(host.port)"
                        if let resultBool = wrapper.state.kodiTestResult[testKey] as? KotlinBoolean {
                            Image(systemName: resultBool.boolValue ? "checkmark.circle.fill" : "xmark.circle.fill")
                                .foregroundColor(resultBool.boolValue ? SVColor.emerald : SVColor.error)
                        }

                        Button {
                            wrapper.viewModel.testKodiHost(host: host)
                        } label: {
                            Image(systemName: "antenna.radiowaves.left.and.right")
                                .foregroundColor(SVColor.amber)
                        }
                        .buttonStyle(.plain)
                    }
                    .swipeActions(edge: .trailing) {
                        Button(role: .destructive) {
                            wrapper.viewModel.removeKodiHost(host: host)
                        } label: {
                            Label("Delete", systemImage: "trash")
                        }
                    }
                }
            }

            Button {
                newKodiName = ""
                newKodiIp = ""
                newKodiPort = "8080"
                showAddKodiSheet = true
            } label: {
                Label("Add Kodi Host", systemImage: "plus")
            }
        } header: {
            Text("Kodi")
        } footer: {
            Text("Send playback to Kodi instances on your network via JSON-RPC.")
        }
        .sheet(isPresented: $showAddKodiSheet) {
            NavigationStack {
                Form {
                    TextField("Name (e.g. Living Room)", text: $newKodiName)
                    TextField("IP Address", text: $newKodiIp)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.decimalPad)
                    TextField("Port", text: $newKodiPort)
                        .keyboardType(.numberPad)
                }
                .navigationTitle("Add Kodi Host")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { showAddKodiSheet = false }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Add") {
                            let port = Int32(newKodiPort) ?? 8080
                            wrapper.viewModel.addKodiHost(name: newKodiName, ip: newKodiIp, port: port)
                            showAddKodiSheet = false
                        }
                        .disabled(newKodiName.isEmpty || newKodiIp.isEmpty)
                    }
                }
            }
            .presentationDetents([.medium])
        }
    }

    // MARK: - Helpers

    private func formatTimestamp(_ millis: Int64) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000.0)
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .short
        return formatter.string(from: date)
    }
}
