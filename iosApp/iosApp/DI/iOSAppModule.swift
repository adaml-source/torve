import Foundation
import shared

/// Creates the Koin module with iOS-specific platform bindings.
enum IOSAppModule {

    static func create() -> Koin_coreModule {
        let module = Koin_coreModule(createdAtStart: false)

        // DatabaseDriverFactory (no-arg on iOS)
        module.single(qualifier: nil, createdAtStart: false) { (_, _) -> AnyObject in
            DatabaseDriverFactory() as AnyObject
        }

        // IntegrationSecretStore + SecureStorage
        module.single(qualifier: nil, createdAtStart: false) { (_, _) -> AnyObject in
            IOSKeychainSecretStore() as AnyObject
        }

        // DeviceIdProvider
        module.single(qualifier: nil, createdAtStart: false) { (_, _) -> AnyObject in
            IOSDeviceIdProvider() as AnyObject
        }

        return module
    }
}
