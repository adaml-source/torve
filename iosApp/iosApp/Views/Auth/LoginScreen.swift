import SwiftUI
import shared

struct LoginScreen: View {
    @Environment(AppRouter.self) private var router

    @State private var isRegisterMode = false
    @State private var email = ""
    @State private var password = ""
    @State private var displayName = ""
    @State private var error: String?
    @State private var isLoading = false

    private let authClient: AuthClient = {
        let koin = KoinHelper.shared.getKoin()
        return koin.get(objCClass: AuthClient.self) as! AuthClient
    }()

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                Spacer().frame(height: 60)

                // MARK: - Header

                Image(systemName: "play.rectangle.fill")
                    .font(.system(size: 60))
                    .foregroundColor(SVColor.amber)

                Spacer().frame(height: 12)

                Text("Torve")
                    .font(.largeTitle)
                    .fontWeight(.bold)
                    .foregroundColor(SVColor.amber)

                Spacer().frame(height: 8)

                Text(isRegisterMode ? "Create Account" : "Sign In")
                    .font(.headline)
                    .foregroundColor(SVColor.onSurface)

                Spacer().frame(height: 32)

                // MARK: - Form fields

                VStack(spacing: 12) {
                    if isRegisterMode {
                        TextField("Display Name", text: $displayName)
                            .textFieldStyle(.roundedBorder)
                            .textInputAutocapitalization(.words)
                            .autocorrectionDisabled()
                    }

                    TextField("Email", text: $email)
                        .textFieldStyle(.roundedBorder)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.emailAddress)
                        .onChange(of: email) { _, _ in error = nil }

                    SecureField("Password", text: $password)
                        .textFieldStyle(.roundedBorder)
                        .onChange(of: password) { _, _ in error = nil }
                }
                .padding(.horizontal)

                // MARK: - Error

                if let error {
                    Text(error)
                        .font(.caption)
                        .foregroundColor(SVColor.error)
                        .padding(.top, 8)
                        .padding(.horizontal)
                        .multilineTextAlignment(.center)
                }

                Spacer().frame(height: 24)

                // MARK: - Primary action

                Button {
                    performAuth()
                } label: {
                    Group {
                        if isLoading {
                            ProgressView()
                                .tint(.black)
                        } else {
                            Text(isRegisterMode ? "Create Account" : "Sign In")
                                .fontWeight(.semibold)
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding()
                    .background(canSubmit ? SVColor.amber : SVColor.amber.opacity(0.4))
                    .foregroundColor(.black)
                    .cornerRadius(12)
                }
                .disabled(!canSubmit)
                .padding(.horizontal)

                Spacer().frame(height: 8)

                // MARK: - Mode toggle

                Button {
                    withAnimation(.easeInOut(duration: 0.2)) {
                        isRegisterMode.toggle()
                        error = nil
                    }
                } label: {
                    Text(isRegisterMode
                         ? "Already have an account? Sign In"
                         : "Don't have an account? Register")
                        .font(.subheadline)
                        .foregroundColor(SVColor.amber)
                }
                .padding(.top, 4)

                Spacer().frame(height: 16)

                // MARK: - Skip

                Button {
                    router.popToRoot()
                } label: {
                    Text("Continue as Guest")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .overlay(
                            RoundedRectangle(cornerRadius: 12)
                                .stroke(SVColor.onSurfaceVariant, lineWidth: 1)
                        )
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
                .padding(.horizontal)

                Spacer().frame(height: 40)
            }
        }
        .background(SVColor.obsidian.ignoresSafeArea())
        .navigationTitle(isRegisterMode ? "Create Account" : "Sign In")
        .navigationBarTitleDisplayMode(.inline)
    }

    // MARK: - Helpers

    private var canSubmit: Bool {
        !isLoading && !email.isEmpty && !password.isEmpty
    }

    private func performAuth() {
        guard canSubmit else { return }
        isLoading = true
        error = nil

        Task {
            let result: AuthResult
            if isRegisterMode {
                let name = displayName.trimmingCharacters(in: .whitespacesAndNewlines)
                result = try await authClient.register(
                    email: email,
                    password: password,
                    displayName: name.isEmpty ? nil : name
                )
            } else {
                result = try await authClient.login(email: email, password: password)
            }

            await MainActor.run {
                isLoading = false
                if result.success {
                    router.popToRoot()
                } else {
                    self.error = result.error ?? "An unknown error occurred"
                }
            }
        }
    }
}
