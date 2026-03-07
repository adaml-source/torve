import SwiftUI
import shared

struct IptvSubTabPicker: View {
    @Binding var selectedTab: ChannelsSubTab

    var body: some View {
        Picker("Tab", selection: $selectedTab) {
            Text("LIVE").tag(ChannelsSubTab.live)
            Text("FAVOURITES").tag(ChannelsSubTab.favourites)
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .padding(.vertical, 4)
    }
}
