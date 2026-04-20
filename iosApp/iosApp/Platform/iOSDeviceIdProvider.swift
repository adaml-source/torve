import Foundation
import UIKit
import shared

final class IOSDeviceIdProvider: DeviceIdProvider {
    func getDeviceId() -> String {
        return UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString
    }

    func getStableDeviceId() -> String? {
        return UIDevice.current.identifierForVendor?.uuidString
    }
}
