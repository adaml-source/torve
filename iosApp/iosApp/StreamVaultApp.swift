import SwiftUI
import shared

@main
struct StreamVaultApp: App {

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
