import SwiftUI
import shared

struct PandaSetupScreen: View {
    @StateObject private var wrapper = PandaSetupViewModelWrapper()
    @Environment(\.dismiss) private var dismiss
    @State private var recoveryDialogShown = false
    @State private var recoveryInput = ""

    var body: some View {
        VStack(spacing: 0) {
            progressBar

            TabView(selection: Binding(
                get: { wrapper.state.currentStep },
                set: { _ in }
            )) {
                providerStep.tag(PandaSetupStep.provider)
                authStep.tag(PandaSetupStep.auth)
                sourcesStep.tag(PandaSetupStep.sources)
                usenetStep.tag(PandaSetupStep.usenet)
                qualityStep.tag(PandaSetupStep.quality)
                reviewStep.tag(PandaSetupStep.review)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .animation(.easeInOut, value: wrapper.state.currentStep)

            navigationButtons
        }
        .navigationTitle("Panda setup")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Progress

    private var progressBar: some View {
        let steps: [PandaSetupStep] = [.provider, .auth, .sources, .usenet, .quality, .review]
        let currentIndex = steps.firstIndex(of: wrapper.state.currentStep) ?? 0
        let progress = Double(currentIndex + 1) / Double(steps.count)

        return ProgressView(value: progress)
            .tint(.orange)
            .padding(.horizontal)
            .padding(.top, 8)
    }

    // MARK: - Nav buttons

    private var navigationButtons: some View {
        HStack {
            if wrapper.state.currentStep != .provider {
                Button("Back") { wrapper.previousStep() }
            } else {
                Button("Close") { dismiss() }
            }

            Spacer()

            if canAdvance {
                Button("Next") { wrapper.nextStep() }
                    .fontWeight(.semibold)
            }
        }
        .padding()
    }

    private var canAdvance: Bool {
        switch wrapper.state.currentStep {
        case .provider: return wrapper.state.selectedProvider != nil
        case .auth: return wrapper.state.authConnected
        case .sources, .usenet, .quality: return true
        case .review: return false
        default: return false
        }
    }

    // MARK: - Provider step

    private var providerStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Choose a cloud provider").font(.title2).fontWeight(.bold)
                Text("Panda will stream through your debrid account.")
                    .foregroundColor(.secondary)

                if wrapper.state.providersLoading {
                    HStack { Spacer(); ProgressView(); Spacer() }.padding(.vertical, 24)
                } else if wrapper.state.providers.isEmpty {
                    Button("Retry") { wrapper.retryLoadProviders() }
                } else {
                    ForEach(wrapper.state.providers, id: \.id) { provider in
                        providerRow(provider)
                    }
                }
            }
            .padding()
        }
    }

    private func providerRow(_ provider: PandaProvider) -> some View {
        let selected = wrapper.state.selectedProvider?.id == provider.id
        let subtitle: String = {
            if provider.id == "none" {
                return "No debrid — configure Usenet on the next steps"
            }
            return provider.authMethods.map { labelForAuth($0) }.joined(separator: " / ")
        }()
        return Button {
            wrapper.selectProvider(provider)
        } label: {
            HStack(spacing: 14) {
                Text(String(provider.name.prefix(1)).uppercased())
                    .font(.headline)
                    .frame(width: 40, height: 40)
                    .background(Color.orange.opacity(0.2))
                    .clipShape(Circle())
                VStack(alignment: .leading, spacing: 2) {
                    Text(provider.name).font(.body).fontWeight(.medium).foregroundColor(.primary)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                }
                Spacer()
                if selected {
                    Image(systemName: "checkmark.circle.fill").foregroundColor(.orange)
                }
            }
            .padding(12)
            .background(selected ? Color.orange.opacity(0.1) : Color(UIColor.secondarySystemBackground))
            .cornerRadius(12)
        }
        .buttonStyle(.plain)
    }

    private func labelForAuth(_ method: String) -> String {
        switch method {
        case "oauth": return "Browser sign-in"
        case "apikey": return "API key"
        default: return method
        }
    }

    // MARK: - Auth step

    private var authStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let provider = wrapper.state.selectedProvider {
                    Text("Connect \(provider.name)").font(.title2).fontWeight(.bold)

                    if wrapper.state.authConnected {
                        HStack {
                            Image(systemName: "checkmark.circle.fill").foregroundColor(.green)
                            Text(wrapper.state.existingCredentialDetected
                                 ? "Using existing \(provider.name) credentials"
                                 : "Connected")
                                .fontWeight(.semibold)
                        }
                    } else {
                        let supportsOAuth = provider.authMethods.contains("oauth")
                        if supportsOAuth {
                            Picker("", selection: Binding(
                                get: { wrapper.state.authMethod },
                                set: { wrapper.setAuthMethod($0) }
                            )) {
                                Text("Browser sign-in").tag("oauth")
                                Text("API key").tag("apikey")
                            }
                            .pickerStyle(.segmented)
                        }

                        if wrapper.state.authMethod == "oauth" && supportsOAuth {
                            oauthSection
                        } else {
                            apiKeySection
                        }
                    }

                    if let error = wrapper.state.error {
                        Text(error).foregroundColor(.red).font(.caption)
                    }
                }
            }
            .padding()
        }
    }

    private var oauthSection: some View {
        VStack(spacing: 12) {
            if let code = wrapper.state.deviceCode {
                Text("Enter this code in your browser:")
                    .foregroundColor(.secondary)
                Text(code.userCode)
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .foregroundColor(.orange)

                Button {
                    if let url = URL(string: code.verificationUrl) {
                        UIApplication.shared.open(url)
                    }
                    UIPasteboard.general.string = code.userCode
                } label: {
                    Label("Open browser", systemImage: "safari")
                }
                .buttonStyle(.borderedProminent)
                .tint(.orange)

                HStack {
                    ProgressView().controlSize(.small)
                    Text("Waiting for authorization…").font(.caption).foregroundColor(.secondary)
                }
            } else if wrapper.state.authLoading {
                ProgressView()
            } else {
                Button("Retry") { wrapper.startOAuth() }
            }
        }
        .frame(maxWidth: .infinity)
        .padding()
        .background(Color(UIColor.secondarySystemBackground))
        .cornerRadius(12)
        .onAppear {
            if wrapper.state.deviceCode == nil && !wrapper.state.authLoading && !wrapper.state.authConnected {
                wrapper.startOAuth()
            }
        }
    }

    private var apiKeySection: some View {
        VStack(alignment: .leading, spacing: 12) {
            SecureField("API key", text: Binding(
                get: { wrapper.state.apiKeyInput },
                set: { wrapper.setApiKeyInput($0) }
            ))
            .textFieldStyle(.roundedBorder)

            Button {
                wrapper.validateApiKey()
            } label: {
                if wrapper.state.authLoading {
                    ProgressView().controlSize(.small)
                } else {
                    Text("Validate & connect").frame(maxWidth: .infinity)
                }
            }
            .buttonStyle(.borderedProminent)
            .tint(.orange)
            .disabled(wrapper.state.apiKeyInput.isEmpty || wrapper.state.authLoading)
        }
    }

    // MARK: - Sources step

    private var sourcesStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Torrent sources").font(.title2).fontWeight(.bold)
                Text("Panda searches these indexers. Disable any you don't want.")
                    .foregroundColor(.secondary)

                ForEach(wrapper.state.sourceProviders, id: \.id) { src in
                    Toggle(isOn: Binding(
                        get: { wrapper.state.enabledSources.contains(src.id) },
                        set: { _ in wrapper.toggleSource(src.id) }
                    )) {
                        VStack(alignment: .leading) {
                            Text(src.name).fontWeight(.medium)
                            Text(src.description_).font(.caption).foregroundColor(.secondary)
                        }
                    }
                    .tint(.orange)
                }
            }
            .padding()
        }
    }

    // MARK: - Usenet step

    private var usenetStep: some View {
        Form {
            Section {
                Toggle("Enable Usenet", isOn: Binding(
                    get: { wrapper.state.enableUsenet },
                    set: { wrapper.setEnableUsenet($0) }
                ))
                .tint(.orange)
            }

            if wrapper.state.enableUsenet {
                Section(header: Text("Provider")) {
                    Picker("Provider", selection: Binding(
                        get: { wrapper.state.usenetProvider },
                        set: { wrapper.setUsenetProvider($0) }
                    )) {
                        ForEach(wrapper.state.schema.usenetProviders.filter { $0 != "none" }, id: \.self) { id in
                            Text(labelForUsenetProvider(id)).tag(id)
                        }
                    }
                    .pickerStyle(.segmented)

                    if wrapper.state.usenetProvider == "generic" {
                        TextField("Host", text: Binding(
                            get: { wrapper.state.usenetHost },
                            set: { wrapper.setUsenetHost($0) }
                        ))
                        TextField("Port", value: Binding(
                            get: { Int(wrapper.state.usenetPort) },
                            set: { wrapper.setUsenetPort(Int32($0)) }
                        ), formatter: NumberFormatter())
                    }

                    TextField("Username", text: Binding(
                        get: { wrapper.state.usenetUsername },
                        set: { wrapper.setUsenetUsername($0) }
                    ))
                    SecureField("Password", text: Binding(
                        get: { wrapper.state.usenetPassword },
                        set: { wrapper.setUsenetPassword($0) }
                    ))

                    if wrapper.state.usenetProvider == "generic" {
                        Toggle("SSL", isOn: Binding(
                            get: { wrapper.state.usenetSSL },
                            set: { wrapper.setUsenetSSL($0) }
                        ))
                    }
                }

                ForEach(Array(wrapper.state.nzbIndexers.enumerated()), id: \.offset) { item in
                    let index = item.offset
                    let row = item.element
                    Section(header: Text(wrapper.state.nzbIndexers.count > 1
                                        ? "NZB indexer #\(index + 1)"
                                        : "NZB indexer")) {
                        Picker("Indexer", selection: Binding(
                            get: { row.type },
                            set: { wrapper.setIndexerType(index, $0) }
                        )) {
                            ForEach(wrapper.state.schema.nzbIndexers, id: \.self) { id in
                                Text(labelForNzbIndexer(id)).tag(id)
                            }
                        }

                        if row.type == "custom" {
                            TextField("URL", text: Binding(
                                get: { row.url },
                                set: { wrapper.setIndexerUrl(index, $0) }
                            ))
                        }
                        if row.type != "none" {
                            SecureField("API key", text: Binding(
                                get: { row.apiKey },
                                set: { wrapper.setIndexerApiKey(index, $0) }
                            ))
                        }
                        if wrapper.state.nzbIndexers.count > 1 {
                            Button(role: .destructive) {
                                wrapper.removeIndexer(index)
                            } label: {
                                Label("Remove indexer", systemImage: "trash")
                            }
                        }
                    }
                }

                Section {
                    Button {
                        wrapper.addIndexer()
                    } label: {
                        Label("Add another indexer", systemImage: "plus.circle")
                    }
                }

                Section(header: Text("Download client")) {
                    Picker("Client", selection: Binding(
                        get: { wrapper.state.downloadClient },
                        set: { wrapper.setDownloadClient($0) }
                    )) {
                        ForEach(wrapper.state.schema.downloadClients, id: \.self) { id in
                            Text(labelForDownloadClient(id)).tag(id)
                        }
                    }

                    downloadClientFieldViews
                }

                if wrapper.state.usenetProvider == "easynews" {
                    bandwidthSaverSection
                }
            }
        }
    }

    private var bandwidthSaverSection: some View {
        let cloudClients: Set<String> = ["premiumize", "torbox", "alldebrid"]
        let hasIndexer = wrapper.state.nzbIndexers.contains { $0.type != "none" && !$0.apiKey.isEmpty }
        let hasCloudClient = cloudClients.contains(wrapper.state.downloadClient)
        let canEnable = wrapper.state.enableUsenet && hasIndexer && hasCloudClient

        return Section(
            header: Text("Bandwidth saver"),
            footer: Text(canEnable
                         ? "When the same release is on both Easynews and one of your NZB indexers, route playback through your cloud download service. Saves Easynews data."
                         : "Configure at least one NZB indexer with an API key and a cloud download client (Premiumize / TorBox / AllDebrid) to enable.")
        ) {
            Toggle("Use NZB path when available", isOn: Binding(
                get: { canEnable && wrapper.state.easynewsPreferNzb },
                set: { wrapper.setBandwidthSaver($0) }
            ))
            .disabled(!canEnable)
            .tint(.orange)
        }
    }

    @ViewBuilder
    private var downloadClientFieldViews: some View {
        let fields = wrapper.state.schema.downloadClientFields[wrapper.state.downloadClient]?.fields ?? []
        ForEach(fields, id: \.self) { field in
            switch field {
            case "url":
                TextField("URL", text: Binding(
                    get: { wrapper.state.downloadClientUrl },
                    set: { wrapper.setDownloadClientUrl($0) }
                ))
            case "username":
                TextField("User", text: Binding(
                    get: { wrapper.state.downloadClientUsername },
                    set: { wrapper.setDownloadClientUsername($0) }
                ))
            case "password":
                SecureField("Password", text: Binding(
                    get: { wrapper.state.downloadClientPassword },
                    set: { wrapper.setDownloadClientPassword($0) }
                ))
            case "apiKey":
                SecureField("API key", text: Binding(
                    get: { wrapper.state.downloadClientApiKey },
                    set: { wrapper.setDownloadClientApiKey($0) }
                ))
            default:
                EmptyView()
            }
        }
    }

    private func labelForUsenetProvider(_ id: String) -> String {
        switch id {
        case "easynews": return "Easynews"
        case "generic": return "Generic NNTP"
        default: return id.capitalized
        }
    }

    private func labelForNzbIndexer(_ id: String) -> String {
        switch id {
        case "none": return "None"
        case "nzbgeek": return "NZBgeek"
        case "scenenzbs": return "SceneNZBs"
        case "dognzb": return "DogNZB"
        case "nzbplanet": return "NZBPlanet"
        case "custom": return "Custom URL"
        default: return id.capitalized
        }
    }

    private func labelForDownloadClient(_ id: String) -> String {
        switch id {
        case "none": return "None"
        case "nzbget": return "NZBget"
        case "sabnzbd": return "SABnzbd"
        case "premiumize": return "Premiumize"
        case "torbox": return "TorBox"
        case "alldebrid": return "AllDebrid"
        default: return id.capitalized
        }
    }

    // MARK: - Quality step

    private var qualityStep: some View {
        Form {
            Section(header: Text("Maximum quality")) {
                Picker("Quality", selection: Binding(
                    get: { wrapper.state.maxQuality },
                    set: { wrapper.setMaxQuality($0) }
                )) {
                    ForEach(wrapper.state.schema.qualityOptions, id: \.self) { id in
                        Text(labelForQuality(id)).tag(id)
                    }
                }
            }
            Section(header: Text("Profile")) {
                Picker("Profile", selection: Binding(
                    get: { wrapper.state.qualityProfile },
                    set: { wrapper.setQualityProfile($0) }
                )) {
                    ForEach(wrapper.state.schema.qualityProfiles, id: \.self) { id in
                        Text(labelForQualityProfile(id)).tag(id)
                    }
                }
            }
            Section(
                header: Text("Release languages"),
                footer: Text("Pick one or more. \"Any\" clears the filter.")
            ) {
                let selected = Set(wrapper.state.releaseLanguages)
                ForEach(wrapper.state.schema.releaseLanguages, id: \.self) { id in
                    Button {
                        wrapper.toggleLanguage(id, selected: !selected.contains(id))
                    } label: {
                        HStack {
                            Text(labelForLanguage(id))
                                .foregroundColor(.primary)
                            Spacer()
                            if selected.contains(id) {
                                Image(systemName: "checkmark").foregroundColor(.orange)
                            }
                        }
                    }
                }
            }
        }
    }

    private func labelForQuality(_ id: String) -> String {
        switch id {
        case "2160p": return "4K (2160p)"
        default: return id
        }
    }

    private func labelForQualityProfile(_ id: String) -> String {
        switch id {
        case "balanced": return "Balanced"
        case "best_quality": return "Best quality"
        case "fast_start": return "Fast start"
        case "data_saver": return "Data saver"
        default: return id.replacingOccurrences(of: "_", with: " ").capitalized
        }
    }

    private func labelForLanguage(_ id: String) -> String {
        switch id {
        case "any": return "Any"
        case "english": return "English"
        case "german": return "Deutsch"
        case "spanish": return "Español"
        case "french": return "Français"
        case "italian": return "Italiano"
        case "portuguese": return "Português"
        case "turkish": return "Türkçe"
        case "japanese": return "日本語"
        case "korean": return "한국어"
        case "chinese": return "中文"
        case "hindi": return "हिन्दी"
        case "multi": return "Multi"
        default: return id.capitalized
        }
    }

    // MARK: - Review step

    private var reviewStep: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                Text("Review & save").font(.title2).fontWeight(.bold)

                reviewRow("Provider", wrapper.state.selectedProvider?.name ?? "—")
                reviewRow("Auth", wrapper.state.authConnected ? "Connected" : "Not connected")
                reviewRow("Sources", "\(wrapper.state.enabledSources.count) enabled")
                reviewRow("Max quality", wrapper.state.maxQuality)
                reviewRow("Profile", wrapper.state.qualityProfile)
                if wrapper.state.enableUsenet {
                    reviewRow("Usenet", wrapper.state.usenetProvider)
                }

                if wrapper.state.isEditMode && wrapper.state.configId != nil {
                    managementTokenSection
                }

                if wrapper.state.addonInstalled {
                    VStack(alignment: .leading) {
                        Label("Panda installed", systemImage: "checkmark.seal.fill")
                            .foregroundColor(.green)
                            .font(.headline)
                        Text("Panda is now configured and added to Torve.")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    .padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(Color.green.opacity(0.1))
                    .cornerRadius(12)

                    if let mgmtToken = wrapper.state.pendingManagementTokenDisplay,
                       !mgmtToken.isEmpty {
                        PandaManagementTokenCard(
                            token: mgmtToken,
                            notice: wrapper.state.managementTokenNotice,
                            onAcknowledge: { wrapper.acknowledgeManagementTokenDisplay() }
                        )
                    }

                    Button("Done") { dismiss() }
                        .buttonStyle(.borderedProminent)
                        .tint(.orange)
                } else {
                    if wrapper.state.isEditMode && wrapper.state.editRequiresRecovery {
                        Text(
                            "This device doesn't have a Panda management token yet. Open Manage Panda → 'I need a management token' to paste the admin-issued token before editing this config."
                        )
                        .font(.caption)
                        .foregroundColor(.red)
                        .padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(Color.red.opacity(0.08))
                        .cornerRadius(10)
                    }
                    if let saveError = wrapper.state.saveError {
                        Text(saveError).foregroundColor(.red).font(.caption)
                    }
                    Button {
                        wrapper.saveConfigAndInstall()
                    } label: {
                        if wrapper.state.isSaving {
                            ProgressView().controlSize(.small)
                        } else {
                            Text(wrapper.state.isEditMode ? "Update Panda" : "Install Panda")
                                .frame(maxWidth: .infinity)
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(.orange)
                    .disabled(wrapper.state.isSaving
                              || wrapper.state.selectedProvider == nil
                              || !wrapper.state.authConnected)
                }
            }
            .padding()
        }
    }

    private func reviewRow(_ title: String, _ value: String) -> some View {
        HStack {
            Text(title).foregroundColor(.secondary)
            Spacer()
            Text(value).fontWeight(.medium)
        }
        .padding(.vertical, 8)
    }

    private var managementTokenSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Divider()
            Text("Management token")
                .font(.headline)
            Text(
                wrapper.state.hasManagementToken
                ? "Required for editing, deleting, or rotating this Panda config."
                : "This device has no management token for this config. Paste an admin-issued token to enable edits."
            )
            .font(.caption)
            .foregroundColor(.secondary)

            if wrapper.state.hasManagementToken {
                Button("Rotate management token") { wrapper.rotateManagementToken() }
                    .buttonStyle(.bordered)
                    .disabled(wrapper.state.rotateInProgress)
                Button("Reset leaked manifest URL") { wrapper.rotateManifestUrl() }
                    .buttonStyle(.bordered)
                    .disabled(wrapper.state.rotateInProgress)
            } else {
                Button("I need a management token") {
                    recoveryInput = ""
                    recoveryDialogShown = true
                }
                .buttonStyle(.bordered)
            }

            if let url = URL(string: "https://torve.app/help.html#article:panda-management-token") {
                Link("Learn more", destination: url)
                    .foregroundColor(.orange)
            }

            if let rotErr = wrapper.state.rotateError {
                Text(rotErr).font(.caption).foregroundColor(.red)
            }
        }
        .alert("Paste management token", isPresented: $recoveryDialogShown) {
            TextField("Token", text: $recoveryInput)
                .textInputAutocapitalization(.never)
                .disableAutocorrection(true)
            Button("Validate") {
                wrapper.recoverManagementToken(recoveryInput)
            }
            .disabled(recoveryInput.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("Cancel", role: .cancel) { wrapper.clearError() }
        } message: {
            Text(
                wrapper.state.recoveryError ??
                "Paste the admin-issued management token. It validates immediately; invalid tokens aren't stored."
            )
        }
    }
}

