import SwiftUI
import shared

final class MdbListViewModelWrapper: ObservableObject {
    let viewModel: MdbListViewModel
    @Published var state: MdbListUiState

    private var collector: Closeable?

    init() {
        self.viewModel = KoinViewModelFactory.mdbListViewModel()
        self.state = viewModel.state.value as! MdbListUiState
        self.collector = nil
        collector = FlowCollectorHelper.shared.collect(flow: viewModel.state) { [weak self] newState in
            DispatchQueue.main.async {
                if let s = newState as? MdbListUiState { self?.state = s }
            }
        }
    }

    deinit { collector?.close() }
}

struct MdbListSettingsScreen: View {
    @StateObject private var settingsWrapper = SettingsViewModelWrapper()
    @StateObject private var mdbWrapper = MdbListViewModelWrapper()

    @State private var apiKeyInput: String = ""
    @State private var searchQuery: String = ""

    var body: some View {
        List {
            apiKeySection
            savedListsSection
            searchSection
            topListsSection
        }
        .navigationTitle("MDBList")
        .onAppear {
            apiKeyInput = mdbWrapper.state.apiKey
        }
    }

    // MARK: - API Key

    private var apiKeySection: some View {
        Section {
            SecureField("MDBList API Key", text: $apiKeyInput)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()

            Button("Save Key") {
                settingsWrapper.viewModel.setMdblistApiKey(apiKey: apiKeyInput)
                mdbWrapper.viewModel.refreshApiKey()
            }
            .disabled(apiKeyInput.isEmpty)
        } header: {
            Text("API Key")
        } footer: {
            Text("Get your API key at mdblist.com/preferences. Required to browse and search lists.")
        }
    }

    // MARK: - Saved Lists

    private var savedListsSection: some View {
        Section("My Lists") {
            if mdbWrapper.state.savedLists.isEmpty {
                Text("No lists added yet")
                    .foregroundColor(.secondary)
            } else {
                ForEach(mdbWrapper.state.savedLists, id: \.listId) { list in
                    HStack {
                        Text(list.name)
                        Spacer()
                        Toggle("", isOn: Binding(
                            get: { list.enabled },
                            set: { mdbWrapper.viewModel.toggleList(listId: list.listId, enabled: $0) }
                        ))
                        .labelsHidden()
                    }
                }
                .onDelete { indexSet in
                    for index in indexSet {
                        let list = mdbWrapper.state.savedLists[index]
                        mdbWrapper.viewModel.removeList(listId: list.listId)
                    }
                }
            }
        }
    }

    // MARK: - Search

    private var searchSection: some View {
        Section("Search Lists") {
            HStack {
                TextField("Search MDBList...", text: $searchQuery)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .onSubmit {
                        mdbWrapper.viewModel.setSearchQuery(query: searchQuery)
                        mdbWrapper.viewModel.search()
                    }

                if mdbWrapper.state.isSearching {
                    ProgressView()
                } else {
                    Button {
                        mdbWrapper.viewModel.setSearchQuery(query: searchQuery)
                        mdbWrapper.viewModel.search()
                    } label: {
                        Image(systemName: "magnifyingglass")
                    }
                    .disabled(searchQuery.isEmpty)
                }
            }

            ForEach(mdbWrapper.state.searchResults, id: \.id) { result in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(result.name)
                            .fontWeight(.medium)
                        Text("\(result.itemCount) items")
                            .font(.caption)
                            .foregroundColor(.secondary)
                    }
                    Spacer()
                    Button {
                        mdbWrapper.viewModel.addList(listId: result.id, name: result.name)
                    } label: {
                        Image(systemName: "plus.circle")
                            .foregroundColor(SVColor.amber)
                    }
                    .buttonStyle(.plain)
                }
            }

            if let error = mdbWrapper.state.error {
                Text(error)
                    .font(.caption)
                    .foregroundColor(SVColor.error)
            }
        }
    }

    // MARK: - Top Lists

    private var topListsSection: some View {
        Section("Popular Lists") {
            if mdbWrapper.state.isLoadingTop {
                HStack {
                    Spacer()
                    ProgressView()
                    Spacer()
                }
            } else if mdbWrapper.state.topLists.isEmpty {
                Text("Enter your API key to browse popular lists")
                    .foregroundColor(.secondary)
            } else {
                ForEach(mdbWrapper.state.topLists, id: \.id) { list in
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(list.name)
                                .fontWeight(.medium)
                            Text("\(list.itemCount) items")
                                .font(.caption)
                                .foregroundColor(.secondary)
                        }
                        Spacer()
                        Button {
                            mdbWrapper.viewModel.addList(listId: list.id, name: list.name)
                        } label: {
                            Image(systemName: "plus.circle")
                                .foregroundColor(SVColor.amber)
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}
