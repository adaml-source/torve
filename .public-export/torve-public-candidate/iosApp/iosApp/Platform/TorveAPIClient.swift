import Foundation

/// Lightweight REST client for the Torve backend API.
/// Handles auth, account, and device APIs for iOS.
@MainActor
final class TorveAPIClient: ObservableObject {
    /// Single process-wide instance. SwiftUI views observe this via
    /// `@ObservedObject` so auth state changes (login, logout, account
    /// deletion) propagate without each screen creating its own client.
    static let shared = TorveAPIClient()

    @Published var isLoggedIn = false
    @Published var isPremium = true
    @Published var userEmail: String?

    private var accessToken: String? {
        get { UserDefaults.standard.string(forKey: "torve_access_token") }
        set { UserDefaults.standard.set(newValue, forKey: "torve_access_token") }
    }
    private var refreshToken: String? {
        get { UserDefaults.standard.string(forKey: "torve_refresh_token") }
        set { UserDefaults.standard.set(newValue, forKey: "torve_refresh_token") }
    }
    private var userId: String? {
        get { UserDefaults.standard.string(forKey: "torve_user_id") }
        set { UserDefaults.standard.set(newValue, forKey: "torve_user_id") }
    }

    private var baseURL: String {
        UserDefaults.standard.string(forKey: "sync_base_url") ?? "https://api.torve.app"
    }

    init() {
        isLoggedIn = accessToken != nil
        userEmail = UserDefaults.standard.string(forKey: "torve_user_email")
    }

    // MARK: - Auth

    func register(email: String, password: String) async throws {
        let body: [String: Any] = [
            "email": email,
            "password": password,
            "device": deviceRegistration()
        ]
        let data = try await post(path: "/auth/register", body: body, auth: false)
        let response = try JSONDecoder().decode(AuthResponse.self, from: data)
        persistAuth(response)
    }

    func login(email: String, password: String) async throws {
        let body: [String: Any] = [
            "email": email,
            "password": password,
            "device": deviceRegistration()
        ]
        let data = try await post(path: "/auth/login", body: body, auth: false)
        let response = try JSONDecoder().decode(AuthResponse.self, from: data)
        persistAuth(response)
    }

    func logout() async {
        if let token = refreshToken {
            let body: [String: Any] = ["refresh_token": token]
            _ = try? await post(path: "/auth/logout", body: body, auth: true)
        }
        clearAuth()
    }

    /// Permanently delete the authenticated user's account.
    /// Backend cascades all per-user data (devices, sessions, watch
    /// history, playlists, LAN hubs,
    /// settings) before dropping the user row. Local auth state is
    /// cleared on success.
    func deleteAccount() async throws {
        _ = try await delete(path: "/auth/account")
        clearAuth()
    }

    /// GDPR-style data export. Returns the raw JSON bytes the user
    /// can save. Caller chooses how to surface (share sheet, save
    /// dialog, …).
    func exportData() async throws -> Data {
        return try await get(path: "/me/export")
    }

    // MARK: - Deprecated access compatibility

    @available(*, deprecated, message: "Paid entitlements no longer control access.")
    func fetchEntitlements() async throws -> EntitlementState {
        isPremium = true
        return EntitlementState(user: nil, entitlements: [], premium_access: true)
    }

    @available(*, deprecated, message: "Apple purchases no longer control access.")
    func verifyApplePurchase(transactionJWS: String, productId: String) async throws -> PurchaseVerifyResponse {
        isPremium = true
        return PurchaseVerifyResponse(status: "deprecated", premium_access: true)
    }

    @available(*, deprecated, message: "Purchase restore no longer controls access.")
    func restorePurchases() async throws -> EntitlementState {
        isPremium = true
        return EntitlementState(user: nil, entitlements: [], premium_access: true)
    }

    // MARK: - Device Governance

    func getAccessState() async throws -> AccessState {
        let data = try await get(path: "/me/access-state")
        return try JSONDecoder().decode(AccessState.self, from: data)
    }

    func getDevices() async throws -> DeviceList {
        let data = try await get(path: "/me/devices")
        return try JSONDecoder().decode(DeviceList.self, from: data)
    }

    func activateCurrentDevice() async throws -> DeviceActivateResult {
        let data = try await post(path: "/me/devices/activate-current", body: [:], auth: true)
        return try JSONDecoder().decode(DeviceActivateResult.self, from: data)
    }

    func removeDevice(deviceId: String) async throws -> DeviceRemoveResult {
        let data = try await post(path: "/me/devices/\(deviceId)/remove", body: [:], auth: true)
        return try JSONDecoder().decode(DeviceRemoveResult.self, from: data)
    }

    // MARK: - Networking

    private func get(path: String) async throws -> Data {
        var request = URLRequest(url: URL(string: baseURL + path)!)
        request.httpMethod = "GET"
        if let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        try checkResponse(response, data: data)
        return data
    }

    private func post(path: String, body: [String: Any], auth: Bool) async throws -> Data {
        var request = URLRequest(url: URL(string: baseURL + path)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if auth, let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, response) = try await URLSession.shared.data(for: request)
        try checkResponse(response, data: data)
        return data
    }

    private func delete(path: String) async throws -> Data {
        var request = URLRequest(url: URL(string: baseURL + path)!)
        request.httpMethod = "DELETE"
        if let token = accessToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        try checkResponse(response, data: data)
        return data
    }

    private func checkResponse(_ response: URLResponse, data: Data) throws {
        guard let http = response as? HTTPURLResponse else { return }
        if http.statusCode == 401 {
            clearAuth()
            throw APIError.unauthorized
        }
        guard (200...299).contains(http.statusCode) else {
            let detail = (try? JSONDecoder().decode(ErrorDetail.self, from: data))?.messageForUser ?? "Request failed"
            throw APIError.serverError(code: http.statusCode, message: detail)
        }
    }

    private func persistAuth(_ response: AuthResponse) {
        accessToken = response.tokens.access_token
        refreshToken = response.tokens.refresh_token
        userId = response.user.id
        userEmail = response.user.email
        isLoggedIn = true
        UserDefaults.standard.set(response.user.email, forKey: "torve_user_email")
    }

    private func clearAuth() {
        accessToken = nil
        refreshToken = nil
        userId = nil
        userEmail = nil
        isLoggedIn = false
        UserDefaults.standard.removeObject(forKey: "torve_user_email")
    }

    private func deviceRegistration() -> [String: String] {
        [
            "installation_id": UIDevice.current.identifierForVendor?.uuidString ?? UUID().uuidString,
            "device_name": UIDevice.current.name,
            "device_type": UIDevice.current.userInterfaceIdiom == .pad ? "tablet" : "phone",
            "platform": "ios"
        ]
    }
}

// MARK: - Models

enum APIError: LocalizedError {
    case unauthorized
    case serverError(code: Int, message: String)

    var errorDescription: String? {
        switch self {
        case .unauthorized: return "Session expired. Please log in again."
        case .serverError(_, let message): return message
        }
    }
}

struct ErrorDetail: Decodable {
    let detail: Detail

    var messageForUser: String? {
        if detail.code == "device_cap_reached" {
            if let maxDevices = detail.maxDevices {
                return "You have reached your \(maxDevices)-device limit. Remove an existing device to continue."
            }
            return "Device limit reached. Remove an existing device to continue."
        }
        return detail.message
    }

    enum Detail: Decodable {
        case text(String)
        case object(code: String?, message: String?, maxDevices: Int?)
        case validation(String)

        var code: String? {
            if case let .object(code, _, _) = self { return code }
            return nil
        }

        var message: String? {
            switch self {
            case .text(let value): return value
            case .object(_, let message, _): return message
            case .validation(let value): return value
            }
        }

        var maxDevices: Int? {
            if case let .object(_, _, maxDevices) = self { return maxDevices }
            return nil
        }

        init(from decoder: Decoder) throws {
            let container = try decoder.singleValueContainer()
            if let text = try? container.decode(String.self) {
                self = .text(text)
                return
            }
            if let object = try? container.decode(StructuredDetail.self) {
                self = .object(
                    code: object.code,
                    message: object.message ?? object.msg ?? object.code,
                    maxDevices: object.max_devices ?? object.device_limit
                )
                return
            }
            if let validations = try? container.decode([ValidationDetail].self) {
                let message = validations.compactMap(\.msg).joined(separator: "; ")
                self = .validation(message.isEmpty ? "Validation error" : message)
                return
            }
            self = .text("Request failed")
        }
    }

    private struct StructuredDetail: Decodable {
        let code: String?
        let message: String?
        let msg: String?
        let max_devices: Int?
        let device_limit: Int?
    }

    private struct ValidationDetail: Decodable {
        let msg: String?
    }
}

struct AuthResponse: Decodable {
    let user: UserInfo
    let tokens: Tokens

    struct UserInfo: Decodable { let id: String; let email: String }
    struct Tokens: Decodable { let access_token: String; let refresh_token: String; let expires_in: Int }
}

struct EntitlementState: Decodable {
    let user: AuthResponse.UserInfo?
    let entitlements: [Entitlement]
    let premium_access: Bool

    struct Entitlement: Decodable {
        let key: String
        let status: String
        let source_store: String
        let starts_at: String
        let ends_at: String?
    }
}

struct PurchaseVerifyResponse: Decodable {
    let status: String
    let premium_access: Bool
}

struct AccessState: Decodable {
    let user: AuthResponse.UserInfo?
    let premium: PremiumState?
    let device: DeviceState?
    let device_limit: Int?
    let device_cap_override: Int?
    let active_device_count: Int?
    let is_device_activated: Bool?
    let device_block_reason: String?
    let legacy_device_limit: DeviceLimitState?

    enum CodingKeys: String, CodingKey {
        case user
        case premium
        case device
        case device_limit
        case device_cap_override
        case active_device_count
        case is_device_activated
        case device_block_reason
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        user = try container.decodeIfPresent(AuthResponse.UserInfo.self, forKey: .user)
        premium = try container.decodeIfPresent(PremiumState.self, forKey: .premium)
        device = try container.decodeIfPresent(DeviceState.self, forKey: .device)
        device_cap_override = try container.decodeIfPresent(Int.self, forKey: .device_cap_override)
        active_device_count = try container.decodeIfPresent(Int.self, forKey: .active_device_count)
        is_device_activated = try container.decodeIfPresent(Bool.self, forKey: .is_device_activated)
        device_block_reason = try container.decodeIfPresent(String.self, forKey: .device_block_reason)
        device_limit = try? container.decode(Int.self, forKey: .device_limit)
        legacy_device_limit = try? container.decode(DeviceLimitState.self, forKey: .device_limit)
    }

    struct PremiumState: Decodable {
        let has_entitlement: Bool
        let premium_access: Bool
        let reason: String
    }

    struct DeviceState: Decodable {
        let id: String
        let name: String
        let is_active: Bool
        let active_device_count: Int
        let max_active_devices: Int
        let platform: String
        let device_type: String
    }

    struct DeviceLimitState: Decodable {
        let cap_reached: Bool
        let swaps_remaining: Int
        let stale_devices_pruned: Int
        let active_devices: [DeviceList.ManagedDevice]?
    }
}

struct DeviceList: Decodable {
    let devices: [ManagedDevice]
    let active_count: Int
    let max_active: Int
    let swaps_remaining: Int

    struct ManagedDevice: Decodable, Identifiable {
        let id: String
        let device_name: String
        let device_type: String
        let platform: String
        let is_current: Bool
        let is_active: Bool
        let last_seen_at: String
        let first_seen_at: String
    }
}

struct DeviceActivateResult: Decodable {
    let activated: Bool
    let reason: String
    let active_device_count: Int
    let device_limit: Int?
    let max_devices: Int?
    let device_cap_override: Int?
}

struct DeviceRemoveResult: Decodable {
    let removed: Bool
    let reason: String
    let swaps_remaining: Int
}
