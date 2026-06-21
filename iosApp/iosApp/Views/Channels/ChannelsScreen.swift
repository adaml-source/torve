import SwiftUI
import shared

struct ChannelsScreen: View {
    @StateObject private var wrapper = ChannelsViewModelWrapper()

    var body: some View {
        Group {
            if wrapper.state.playlists.isEmpty && !wrapper.state.isLoading {
                emptyState
            } else {
                VStack(spacing: 0) {
                    playlistSelector
                    subTabPicker
                    channelContent
                }
            }
        }
        .navigationTitle("Channels")
        .searchable(text: Binding(
            get: { wrapper.state.searchQuery },
            set: { wrapper.updateSearchQuery($0) }
        ), prompt: "Search channels...")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                HStack {
                    Button { wrapper.toggleFilterSheet() } label: {
                        Image(systemName: "line.3.horizontal.decrease.circle")
                    }
                    Button { wrapper.refreshPlaylist() } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    Button { wrapper.showAddPlaylistDialog() } label: {
                        Image(systemName: "plus")
                    }
                }
            }
        }
        .sheet(isPresented: Binding(
            get: { wrapper.state.showAddPlaylist },
            set: { if !$0 { wrapper.dismissAddPlaylistDialog() } }
        )) {
            ChannelsAddPlaylistSheet(wrapper: wrapper)
        }
        .sheet(isPresented: Binding(
            get: { wrapper.state.showCategoryManager },
            set: { if !$0 { wrapper.toggleCategoryManager() } }
        )) {
            CategoryManagerSheet(wrapper: wrapper)
        }
    }

    // MARK: - Empty State

    private var emptyState: some View {
        VStack(spacing: 16) {
            Image(systemName: "antenna.radiowaves.left.and.right")
                .font(.system(size: 60)).foregroundColor(.secondary)
            Text("No Playlists").font(.title2).fontWeight(.semibold)
            Text("Add a URL or provider playlist to get started").foregroundColor(.secondary)
            Button("Add Playlist") { wrapper.showAddPlaylistDialog() }
                .buttonStyle(.borderedProminent).tint(SVColor.amber)
        }
    }

    // MARK: - Playlist Selector

    private var playlistSelector: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(wrapper.state.playlists, id: \.id) { playlist in
                    Button { wrapper.selectPlaylist(playlist.id) } label: {
                        Text(playlist.name)
                            .padding(.horizontal, 16).padding(.vertical, 8)
                            .background(wrapper.state.selectedPlaylistId == playlist.id ? SVColor.amber : SVColor.surfaceVariant)
                            .foregroundColor(wrapper.state.selectedPlaylistId == playlist.id ? .black : SVColor.onSurface)
                            .cornerRadius(20)
                    }
                }
            }
            .padding(.horizontal).padding(.vertical, 4)
        }
    }

    // MARK: - Sub-tab Picker

    private var subTabPicker: some View {
        Picker("Tab", selection: Binding(
            get: { wrapper.state.selectedSubTab },
            set: { wrapper.selectSubTab($0) }
        )) {
            Text("Live").tag(ChannelsSubTab.live)
            Text("Favourites").tag(ChannelsSubTab.favourites)
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .padding(.vertical, 4)
    }

    // MARK: - Content

    @ViewBuilder
    private var channelContent: some View {
        if wrapper.state.isLoadingChannels {
            Spacer()
            ProgressView().tint(SVColor.amber)
            Spacer()
        } else {
            switch wrapper.state.selectedSubTab {
            case .live:
                channelList
            case .favourites:
                ChannelsFavouritesView(wrapper: wrapper)
            default:
                channelList
            }
        }
    }

    // MARK: - Channel List

    private var channelList: some View {
        List {
            ForEach(wrapper.state.categories, id: \.name) { category in
                Section {
                    if wrapper.state.expandedCategories.contains(category.name) {
                        ForEach(category.channels, id: \.channel.name) { enriched in
                            channelRow(enriched)
                        }
                    }
                } header: {
                    Button { wrapper.toggleCategoryExpanded(category.name) } label: {
                        HStack {
                            Text(category.name).font(.headline)
                            Spacer()
                            Text("\(category.channels.count)")
                                .font(.caption).foregroundColor(SVColor.onSurfaceVariant)
                            Image(systemName: wrapper.state.expandedCategories.contains(category.name) ? "chevron.up" : "chevron.down")
                        }
                    }
                    .foregroundColor(SVColor.onSurface)
                }
            }
        }
        .listStyle(.plain)
    }

    private func channelRow(_ enriched: EnrichedChannel) -> some View {
        let channel = enriched.channel
        return Button {
            wrapper.recordChannelViewed(channel)
        } label: {
            HStack(spacing: 12) {
                AsyncImage(url: URL(string: channel.tvgLogo ?? "")) { phase in
                    if case .success(let img) = phase {
                        img.resizable().scaledToFit()
                    } else {
                        Image(systemName: "tv").foregroundColor(SVColor.onSurfaceVariant)
                    }
                }
                .frame(width: 40, height: 40)
                .cornerRadius(8)

                VStack(alignment: .leading, spacing: 2) {
                    Text(channel.name).lineLimit(1)
                    if let current = enriched.currentProgramme {
                        Text(current.title)
                            .font(.caption)
                            .foregroundColor(SVColor.amber)
                            .lineLimit(1)
                    }
                }
                Spacer()
            }
        }
        .buttonStyle(.plain)
        .swipeActions(edge: .trailing) {
            Button { wrapper.toggleFavorite(channel) } label: {
                Label("Favorite", systemImage: "heart.fill")
            }
            .tint(SVColor.amber)
        }
    }
}

// MARK: - Add Playlist Sheet

struct ChannelsAddPlaylistSheet: View {
    @ObservedObject var wrapper: ChannelsViewModelWrapper
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Type") {
                    Picker("Playlist Type", selection: Binding(
                        get: { wrapper.state.newPlaylistType },
                        set: { wrapper.setNewPlaylistType($0) }
                    )) {
                        Text("URL").tag("m3u")
                        Text("Xtream Codes").tag("xtream")
                    }
                    .pickerStyle(.segmented)
                }

                Section("Playlist Details") {
                    TextField("Playlist Name", text: Binding(
                        get: { wrapper.state.newPlaylistName },
                        set: { wrapper.setNewPlaylistName($0) }
                    ))

                    if wrapper.state.newPlaylistType == "xtream" {
                        TextField("Server URL", text: Binding(
                            get: { wrapper.state.newXtreamServer },
                            set: { wrapper.setNewXtreamServer($0) }
                        ))
                        .textInputAutocapitalization(.never).keyboardType(.URL)

                        TextField("Username", text: Binding(
                            get: { wrapper.state.newXtreamUsername },
                            set: { wrapper.setNewXtreamUsername($0) }
                        ))
                        .textInputAutocapitalization(.never)

                        TextField("Password", text: Binding(
                            get: { wrapper.state.newXtreamPassword },
                            set: { wrapper.setNewXtreamPassword($0) }
                        ))
                        .textInputAutocapitalization(.never)
                    } else {
                        TextField("Playlist URL", text: Binding(
                            get: { wrapper.state.newPlaylistUrl },
                            set: { wrapper.setNewPlaylistUrl($0) }
                        ))
                        .textInputAutocapitalization(.never).keyboardType(.URL)

                        TextField("Guide URL (optional)", text: Binding(
                            get: { wrapper.state.newPlaylistEpgUrl },
                            set: { wrapper.setNewPlaylistEpgUrl($0) }
                        ))
                        .textInputAutocapitalization(.never).keyboardType(.URL)
                    }
                }
            }
            .navigationTitle("Add Playlist")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { wrapper.dismissAddPlaylistDialog() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") { wrapper.addPlaylist() }
                        .disabled(!canAdd)
                }
            }
        }
    }

    private var canAdd: Bool {
        let s = wrapper.state
        if s.newPlaylistName.isEmpty { return false }
        if s.newPlaylistType == "xtream" {
            return !s.newXtreamServer.isEmpty && !s.newXtreamUsername.isEmpty && !s.newXtreamPassword.isEmpty
        }
        return !s.newPlaylistUrl.isEmpty
    }
}
