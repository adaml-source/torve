import SwiftUI
import shared

struct IptvSubTabPicker: View {
    @Binding var selectedTab: IptvSubTab

    private let tabs: [(IptvSubTab, String)] = [
        (.home, "HOME"),
        (.live, "LIVE"),
        (.movie, "MOVIE"),
        (.series, "SERIES"),
    ]

    var body: some View {
        Picker("Tab", selection: $selectedTab) {
            ForEach(tabs, id: \.0) { tab, label in
                Text(label).tag(tab)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .padding(.vertical, 4)
    }
}
