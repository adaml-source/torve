import SwiftUI
import shared

@main
struct TorveApp: App {

    init() {
        let transferModule = IosTransferModuleKt.buildIosTransferModule(
            engine: IOSTransferCryptoEngine()
        )
        KoinHelperKt.doInitKoin(platformModules: [IOSAppModule.create(), transferModule])
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
        }
    }
}
