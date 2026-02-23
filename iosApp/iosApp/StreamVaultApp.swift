import SwiftUI
import shared

@main
struct StreamVaultApp: App {

    init() {
        // Initialize Koin DI
        KoinHelperKt.doInitKoin()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
