import Foundation
import shared
import Security

final class IOSKeychainSecretStore: IntegrationSecretStore, SecureStorage {

    private let serviceName = "com.torve.secrets"

    // MARK: - IntegrationSecretStore

    func put(key: IntegrationSecretKey, value: String) async throws {
        putString(key: key.name, value: value)
    }

    func get(key: IntegrationSecretKey) async throws -> String? {
        return getString(key: key.name)
    }

    func remove(key: IntegrationSecretKey) async throws {
        removeKey(key: key.name)
    }

    // MARK: - SecureStorage

    func getString(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        guard status == errSecSuccess, let data = result as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    func putString(key: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        removeKey(key: key)
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key,
            kSecValueData as String: data,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
        ]
        SecItemAdd(query as CFDictionary, nil)
    }

    func removeKey(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: serviceName,
            kSecAttrAccount as String: key
        ]
        SecItemDelete(query as CFDictionary)
    }
}
