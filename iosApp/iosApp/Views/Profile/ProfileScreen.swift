import SwiftUI
import shared

struct ProfileScreen: View {
    @StateObject private var wrapper = ProfileViewModelWrapper()
    @State private var showCreateSheet = false
    @State private var newProfileName = ""
    @State private var newAvatarIndex: Int32 = 0
    @State private var pinInput = ""

    private let avatarIcons = [
        "person.circle.fill",
        "person.crop.circle.fill",
        "star.circle.fill",
        "heart.circle.fill",
        "bolt.circle.fill",
        "flame.circle.fill",
        "leaf.circle.fill",
        "moon.circle.fill",
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                // Active profile header
                if let active = wrapper.state.activeProfile {
                    activeProfileHeader(active)
                }

                // Quick links
                quickLinks

                // All profiles
                profilesList

                // Add profile button
                Button(action: { showCreateSheet = true }) {
                    Label("Add Profile", systemImage: "plus.circle.fill")
                        .frame(maxWidth: .infinity)
                        .padding()
                        .background(SVColor.surfaceVariant)
                        .foregroundColor(SVColor.amber)
                        .cornerRadius(12)
                }
                .padding(.horizontal)
            }
            .padding(.vertical)
        }
        .navigationTitle("Profiles")
        .sheet(isPresented: $showCreateSheet) {
            createProfileSheet
        }
        .sheet(isPresented: Binding(
            get: { wrapper.state.pinPromptProfileId != nil },
            set: { if !$0 { wrapper.dismissPinPrompt() } }
        )) {
            pinPromptSheet
        }
        .sheet(isPresented: Binding(
            get: { wrapper.state.editingProfile != nil },
            set: { if !$0 { wrapper.dismissEditDialog() } }
        )) {
            if let editing = wrapper.state.editingProfile {
                editProfileSheet(editing)
            }
        }
        .alert("Error", isPresented: Binding(
            get: { wrapper.state.error != nil },
            set: { if !$0 { wrapper.clearError() } }
        )) {
            Button("OK") { wrapper.clearError() }
        } message: {
            Text(wrapper.state.error ?? "")
        }
    }

    // MARK: - Active Profile Header

    private func activeProfileHeader(_ profile: UserProfile) -> some View {
        VStack(spacing: 12) {
            Image(systemName: avatarIcons[safeAvatarIndex(profile.avatarIndex)])
                .font(.system(size: 64))
                .foregroundColor(SVColor.amber)

            Text(profile.name)
                .font(.title2)
                .fontWeight(.bold)

            if let rating = profile.maxContentRating {
                Text("Content Rating: \(rating.label)")
                    .font(.caption)
                    .foregroundColor(SVColor.onSurfaceVariant)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 4)
                    .background(Capsule().fill(SVColor.surfaceVariant))
            }

            if profile.pin != nil {
                HStack(spacing: 4) {
                    Image(systemName: "lock.fill")
                        .font(.caption)
                    Text("PIN Protected")
                        .font(.caption)
                }
                .foregroundColor(SVColor.onSurfaceVariant)
            }
        }
        .padding()
    }

    // MARK: - Quick Links

    private var quickLinks: some View {
        HStack(spacing: 16) {
            NavigationLink(value: Route.stats) {
                QuickLinkButton(icon: "chart.bar.fill", label: "Stats")
            }
            NavigationLink(value: Route.watchlist) {
                QuickLinkButton(icon: "bookmark.fill", label: "Watchlist")
            }
            NavigationLink(value: Route.downloads) {
                QuickLinkButton(icon: "arrow.down.circle.fill", label: "Downloads")
            }
        }
        .padding(.horizontal)
    }

    // MARK: - Profiles List

    private var profilesList: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Profiles")
                .font(SVFont.sectionTitle)
                .padding(.horizontal)

            let profiles = wrapper.state.profiles as? [UserProfile] ?? []
            ForEach(profiles, id: \.id) { profile in
                profileRow(profile)
            }
        }
    }

    private func profileRow(_ profile: UserProfile) -> some View {
        HStack(spacing: 12) {
            Image(systemName: avatarIcons[safeAvatarIndex(profile.avatarIndex)])
                .font(.system(size: 32))
                .foregroundColor(profile.isActive ? SVColor.amber : SVColor.onSurfaceVariant)

            VStack(alignment: .leading, spacing: 2) {
                HStack {
                    Text(profile.name)
                        .font(.headline)
                    if profile.isActive {
                        Text("Active")
                            .font(.caption2)
                            .fontWeight(.semibold)
                            .padding(.horizontal, 6)
                            .padding(.vertical, 2)
                            .background(SVColor.amber.opacity(0.2))
                            .foregroundColor(SVColor.amber)
                            .cornerRadius(4)
                    }
                }
                if profile.pin != nil {
                    Label("PIN", systemImage: "lock.fill")
                        .font(.caption2)
                        .foregroundColor(SVColor.onSurfaceVariant)
                }
            }

            Spacer()

            // Edit button
            Button { wrapper.showEditDialog(profile: profile) } label: {
                Image(systemName: "pencil.circle")
                    .font(.system(size: 22))
                    .foregroundColor(SVColor.onSurfaceVariant)
            }

            // Switch button (if not active)
            if !profile.isActive {
                Button { wrapper.switchProfile(id: profile.id) } label: {
                    Text("Switch")
                        .font(.caption)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(SVColor.amber.opacity(0.15))
                        .foregroundColor(SVColor.amber)
                        .cornerRadius(8)
                }
            }
        }
        .padding(.horizontal)
        .padding(.vertical, 8)
    }

    // MARK: - Create Profile Sheet

    private var createProfileSheet: some View {
        NavigationStack {
            Form {
                Section("Profile Name") {
                    TextField("Name", text: $newProfileName)
                }

                Section("Avatar") {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 16) {
                            ForEach(0..<avatarIcons.count, id: \.self) { index in
                                Button {
                                    newAvatarIndex = Int32(index)
                                } label: {
                                    Image(systemName: avatarIcons[index])
                                        .font(.system(size: 36))
                                        .foregroundColor(newAvatarIndex == Int32(index) ? SVColor.amber : SVColor.onSurfaceVariant)
                                        .padding(8)
                                        .background(
                                            Circle()
                                                .stroke(newAvatarIndex == Int32(index) ? SVColor.amber : Color.clear, lineWidth: 2)
                                        )
                                }
                            }
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("New Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        showCreateSheet = false
                        newProfileName = ""
                        newAvatarIndex = 0
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") {
                        guard !newProfileName.trimmingCharacters(in: .whitespaces).isEmpty else { return }
                        wrapper.createProfile(name: newProfileName, avatarIndex: newAvatarIndex)
                        showCreateSheet = false
                        newProfileName = ""
                        newAvatarIndex = 0
                    }
                    .disabled(newProfileName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - PIN Prompt Sheet

    private var pinPromptSheet: some View {
        NavigationStack {
            VStack(spacing: 24) {
                Image(systemName: "lock.fill")
                    .font(.system(size: 48))
                    .foregroundColor(SVColor.amber)

                Text("Enter PIN")
                    .font(.title2)
                    .fontWeight(.bold)

                SecureField("PIN", text: $pinInput)
                    .textFieldStyle(.roundedBorder)
                    .keyboardType(.numberPad)
                    .frame(width: 200)
                    .multilineTextAlignment(.center)

                if let pinError = wrapper.state.pinError {
                    Text(pinError)
                        .font(.caption)
                        .foregroundColor(SVColor.error)
                }

                Button("Confirm") {
                    if let profileId = wrapper.state.pinPromptProfileId {
                        wrapper.verifyPinAndSwitch(profileId: profileId, pin: pinInput)
                        pinInput = ""
                    }
                }
                .padding(.horizontal, 32)
                .padding(.vertical, 12)
                .background(SVColor.amber)
                .foregroundColor(.black)
                .cornerRadius(12)

                Spacer()
            }
            .padding(.top, 32)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        wrapper.dismissPinPrompt()
                        pinInput = ""
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }

    // MARK: - Edit Profile Sheet

    private func editProfileSheet(_ profile: UserProfile) -> some View {
        EditProfileSheet(
            profile: profile,
            avatarIcons: avatarIcons,
            onSave: { name, pin, rating in
                wrapper.updateProfileName(id: profile.id, name: name)
                wrapper.setProfilePin(id: profile.id, pin: pin)
                wrapper.setContentRating(id: profile.id, rating: rating)
                wrapper.dismissEditDialog()
            },
            onDelete: {
                wrapper.deleteProfile(id: profile.id)
                wrapper.dismissEditDialog()
            },
            onDismiss: { wrapper.dismissEditDialog() }
        )
    }

    // MARK: - Helpers

    private func safeAvatarIndex(_ index: Int32) -> Int {
        let i = Int(index)
        return (i >= 0 && i < avatarIcons.count) ? i : 0
    }
}

// MARK: - Quick Link Button

private struct QuickLinkButton: View {
    let icon: String
    let label: String

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: icon)
                .font(.system(size: 22))
                .foregroundColor(SVColor.amber)
            Text(label)
                .font(.caption)
                .foregroundColor(SVColor.onSurface)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(
            RoundedRectangle(cornerRadius: 12)
                .fill(SVColor.surfaceVariant)
        )
    }
}

// MARK: - Edit Profile Sheet

private struct EditProfileSheet: View {
    let profile: UserProfile
    let avatarIcons: [String]
    let onSave: (String, String?, ContentRating?) -> Void
    let onDelete: () -> Void
    let onDismiss: () -> Void

    @State private var name: String
    @State private var pin: String
    @State private var selectedRating: ContentRating?
    @State private var showDeleteConfirm = false

    init(
        profile: UserProfile,
        avatarIcons: [String],
        onSave: @escaping (String, String?, ContentRating?) -> Void,
        onDelete: @escaping () -> Void,
        onDismiss: @escaping () -> Void
    ) {
        self.profile = profile
        self.avatarIcons = avatarIcons
        self.onSave = onSave
        self.onDelete = onDelete
        self.onDismiss = onDismiss
        _name = State(initialValue: profile.name)
        _pin = State(initialValue: profile.pin ?? "")
        _selectedRating = State(initialValue: profile.maxContentRating)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Name") {
                    TextField("Profile Name", text: $name)
                }

                Section("PIN Lock") {
                    SecureField("PIN (optional)", text: $pin)
                        .keyboardType(.numberPad)
                }

                Section("Content Rating") {
                    Picker("Max Rating", selection: $selectedRating) {
                        Text("None").tag(ContentRating?.none)
                        Text("G").tag(ContentRating?.some(.g))
                        Text("PG").tag(ContentRating?.some(.pg))
                        Text("PG-13").tag(ContentRating?.some(.pg13))
                        Text("R").tag(ContentRating?.some(.r))
                        Text("NC-17").tag(ContentRating?.some(.nc17))
                    }
                }

                Section {
                    Button(role: .destructive) {
                        showDeleteConfirm = true
                    } label: {
                        Label("Delete Profile", systemImage: "trash")
                    }
                }
            }
            .navigationTitle("Edit Profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { onDismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        let pinValue = pin.trimmingCharacters(in: .whitespaces).isEmpty ? nil : pin
                        onSave(name, pinValue, selectedRating)
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .alert("Delete Profile?", isPresented: $showDeleteConfirm) {
                Button("Delete", role: .destructive) { onDelete() }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This cannot be undone.")
            }
        }
    }
}
