import SwiftUI
import shared

struct IptvScreen: View {
    @StateObject private var wrapper = IptvViewModelWrapper()
    @State private var searchText = ""

    var body: some View {
        NavigationStack {
            Group {
                if wrapper.state.playlists.isEmpty && !wrapper.state.isLoading {
                    // Empty state
                    VStack(spacing: 16) {
                        Image(systemName: "antenna.radiowaves.left.and.right")
                            .font(.system(size: 60))
                            .foregroundColor(.secondary)
                        Text("No IPTV Playlists")
                            .font(.title2)
                            .fontWeight(.semibold)
                        Text("Add an M3U or Xtream Codes playlist to get started")
                            .foregroundColor(.secondary)
                        Button("Add Playlist") {
                            wrapper.showAddPlaylistDialog()
                        }
                        .buttonStyle(.borderedProminent)
                    }
                } else {
                    VStack(spacing: 0) {
                        // Playlist selector
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(wrapper.state.playlists, id: \.id) { playlist in
                                    Button(action: { wrapper.selectPlaylist(playlist.id) }) {
                                        Text(playlist.name)
                                            .padding(.horizontal, 16)
                                            .padding(.vertical, 8)
                                            .background(wrapper.state.selectedPlaylistId == playlist.id ? Color.blue : Color.gray.opacity(0.3))
                                            .foregroundColor(.white)
                                            .cornerRadius(20)
                                    }
                                }
                            }
                            .padding(.horizontal)
                            .padding(.vertical, 4)
                        }

                        // Sub-tab picker
                        IptvSubTabPicker(selectedTab: Binding(
                            get: { wrapper.state.selectedSubTab },
                            set: { wrapper.selectSubTab($0) }
                        ))

                        // Content based on selected tab
                        switch wrapper.state.selectedSubTab {
                        case .home:
                            IptvHomeView(
                                recentlyViewed: wrapper.state.recentlyViewedChannels as? [IptvChannel] ?? [],
                                favorites: wrapper.state.favorites as? [IptvChannel] ?? [],
                                liveCategories: wrapper.state.categories as? [IptvCategory] ?? [],
                                onChannelPlay: { channel in
                                    wrapper.recordChannelViewed(channel)
                                    // TODO: navigate to player
                                }
                            )

                        case .live:
                            IptvLiveView(
                                categories: wrapper.state.categories as? [IptvCategory] ?? [],
                                expandedCategories: Set(wrapper.state.expandedCategories as? [String] ?? []),
                                searchQuery: wrapper.state.searchQuery,
                                searchResults: wrapper.state.searchResults as? [IptvChannel] ?? [],
                                isLoading: wrapper.state.isLoadingChannels,
                                onToggleCategory: { wrapper.toggleCategoryExpanded($0) },
                                onSearchQueryChange: { wrapper.updateSearchQuery($0) },
                                onClearSearch: { wrapper.clearSearch() },
                                onChannelPlay: { channel in
                                    wrapper.recordChannelViewed(channel)
                                    // TODO: navigate to player
                                },
                                onChannelFavorite: { wrapper.toggleFavorite($0) }
                            )

                        case .movie:
                            IptvMovieView(
                                categories: wrapper.state.movieCategories as? [IptvCategory] ?? [],
                                expandedCategories: Set(wrapper.state.expandedCategories as? [String] ?? []),
                                onToggleCategory: { wrapper.toggleCategoryExpanded($0) },
                                onChannelPlay: { channel in
                                    wrapper.recordChannelViewed(channel)
                                    // TODO: navigate to player
                                },
                                onChannelFavorite: { wrapper.toggleFavorite($0) }
                            )

                        case .series:
                            IptvSeriesView(
                                categories: wrapper.state.seriesCategories as? [IptvCategory] ?? [],
                                expandedCategories: Set(wrapper.state.expandedCategories as? [String] ?? []),
                                onToggleCategory: { wrapper.toggleCategoryExpanded($0) },
                                onChannelPlay: { channel in
                                    wrapper.recordChannelViewed(channel)
                                    // TODO: navigate to player
                                },
                                onChannelFavorite: { wrapper.toggleFavorite($0) }
                            )

                        default:
                            EmptyView()
                        }
                    }
                }
            }
            .navigationTitle("IPTV")
            .searchable(text: Binding(
                get: { wrapper.state.searchQuery },
                set: { wrapper.updateSearchQuery($0) }
            ), prompt: "Search channels...")
            .toolbar {
                ToolbarItem(placement: .primaryAction) {
                    HStack {
                        Button(action: { wrapper.toggleFilterSheet() }) {
                            Image(systemName: "line.3.horizontal.decrease.circle")
                        }
                        Button(action: { wrapper.refreshPlaylist() }) {
                            Image(systemName: "arrow.clockwise")
                        }
                        Button(action: { wrapper.showAddPlaylistDialog() }) {
                            Image(systemName: "plus")
                        }
                    }
                }
            }
            .sheet(isPresented: Binding(
                get: { wrapper.state.showAddPlaylist },
                set: { if !$0 { wrapper.dismissAddPlaylistDialog() } }
            )) {
                AddPlaylistSheet(wrapper: wrapper)
            }
            .sheet(isPresented: Binding(
                get: { wrapper.state.showFilterSheet },
                set: { if !$0 { wrapper.toggleFilterSheet() } }
            )) {
                FilterSheetView(
                    activeFilter: wrapper.state.activeFilter,
                    activeSort: wrapper.state.activeSort,
                    onFilterSelected: { wrapper.setFilter($0) },
                    onSortSelected: { wrapper.setSort($0) },
                    onDismiss: { wrapper.toggleFilterSheet() }
                )
            }
        }
    }
}

struct AddPlaylistSheet: View {
    @ObservedObject var wrapper: IptvViewModelWrapper
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("Type") {
                    Picker("Playlist Type", selection: Binding(
                        get: { wrapper.state.newPlaylistType },
                        set: { wrapper.setNewPlaylistType($0) }
                    )) {
                        Text("M3U").tag("m3u")
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
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)

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
                        TextField("M3U URL", text: Binding(
                            get: { wrapper.state.newPlaylistUrl },
                            set: { wrapper.setNewPlaylistUrl($0) }
                        ))
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)

                        TextField("EPG URL (optional)", text: Binding(
                            get: { wrapper.state.newPlaylistEpgUrl },
                            set: { wrapper.setNewPlaylistEpgUrl($0) }
                        ))
                        .textInputAutocapitalization(.never)
                        .keyboardType(.URL)
                    }
                }
            }
            .navigationTitle("Add Playlist")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        wrapper.dismissAddPlaylistDialog()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        wrapper.addPlaylist()
                    }
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
