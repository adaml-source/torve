import SwiftUI
import shared

struct DebridSettingsScreen: View {
    @StateObject private var wrapper = SettingsViewModelWrapper()

    @State private var selectedProvider: Int = 0
    @State private var apiKeyInput: String = ""
    @State private var showApiKey: Bool = false

    private let providers = ["Real-Debrid", "AllDebrid", "Premiumize", "TorBox"]

    var body: some View {
        List {
            Section {
                Picker("Provider", selection: $selectedProvider) {
                    ForEach(0..<providers.count, id: \.self) { index in
                        Text(providers[index]).tag(index)
                    }
                }
            } footer: {
                Text("Select your preferred cloud debrid service. You can connect multiple providers.")
            }

            Section("API Key") {
                HStack {
                    if showApiKey {
                        TextField("Paste your API key", text: $apiKeyInput)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    } else {
                        SecureField("Paste your API key", text: $apiKeyInput)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                    }
                    Button {
                        showApiKey.toggle()
                    } label: {
                        Image(systemName: showApiKey ? "eye.slash" : "eye")
                            .foregroundColor(.secondary)
                    }
                    .buttonStyle(.plain)
                }

                Button("Save & Verify") {
                    wrapper.viewModel.setDebridApiKey(apiKey: apiKeyInput)
                    wrapper.viewModel.verifyDebridAccount()
                }
                .disabled(apiKeyInput.isEmpty)
            } footer: {
                Text("Enter your API key from your debrid service dashboard. The key will be stored securely in the system keychain.")
            }

            if wrapper.state.debridLoading {
                Section {
                    HStack {
                        ProgressView()
                        Text("Verifying...")
                            .foregroundColor(.secondary)
                            .padding(.leading, 8)
                    }
                }
            }

            if wrapper.state.debridConnected {
                Section("Status") {
                    Label("Connected", systemImage: "checkmark.circle.fill")
                        .foregroundColor(SVColor.emerald)

                    if let user = wrapper.state.debridUser {
                        HStack {
                            Text("Username")
                            Spacer()
                            Text(user.username)
                                .foregroundColor(.secondary)
                        }
                        HStack {
                            Text("Premium")
                            Spacer()
                            Text(user.premium ? "Active" : "Inactive")
                                .foregroundColor(user.premium ? SVColor.emerald : SVColor.error)
                        }
                    }
                }
            }

            if let error = wrapper.state.debridError {
                Section {
                    Label(error, systemImage: "exclamationmark.triangle.fill")
                        .foregroundColor(SVColor.error)
                }
            }

            if wrapper.state.debridConnected {
                Section {
                    Button("Disconnect", role: .destructive) {
                        apiKeyInput = ""
                        wrapper.viewModel.setDebridApiKey(apiKey: "")
                    }
                }
            }
        }
        .navigationTitle("Cloud Service")
        .onAppear {
            apiKeyInput = wrapper.state.debridApiKey
        }
    }
}
