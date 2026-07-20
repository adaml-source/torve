import Foundation
import shared
import Security

final class IOSKeychainSecretStore: IntegrationSecretStore, SecureStorage {

    private let serviceName = "com.torve.secrets"

    // MARK: - IntegrationSecretStore

    func put(key: IntegrationSecretKey, value: String, subKey: String?) async throws {
        putString(key: storageKey(key: key, subKey: subKey), value: value)
    }

    func get(key: IntegrationSecretKey, subKey: String?) async throws -> String? {
        return getString(key: storageKey(key: key, subKey: subKey))
    }

    func remove(key: IntegrationSecretKey, subKey: String?) async throws {
        removeKey(key: storageKey(key: key, subKey: subKey))
    }

    // Scoped entries are stored under a composite keychain account so multiple
    // per-owner secrets (e.g. PANDA_MANAGEMENT_TOKEN keyed by config_id) coexist
    // without clobbering the legacy single-value slot.
    private func storageKey(key: IntegrationSecretKey, subKey: String?) -> String {
        if let sub = subKey, !sub.isEmpty {
            return "\(key.name):\(sub)"
        }
        return key.name
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
