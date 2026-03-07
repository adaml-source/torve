import SwiftUI
import shared

@main
struct TorveApp: App {

    init() {
        KoinHelperKt.doInitKoin(platformModules: [IOSAppModule.create()])
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
