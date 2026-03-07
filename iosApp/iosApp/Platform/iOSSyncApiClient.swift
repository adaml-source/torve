import Foundation

// MARK: - Models

struct SyncDeviceRegistration: Codable {
    let installationId: String
    let deviceName: String
    let deviceType: String
    let platform: String

    enum CodingKeys: String, CodingKey {
        case installationId = "installation_id"
        case deviceName = "device_name"
        case deviceType = "device_type"
        case platform
    }
}

struct SyncRegisterRequest: Codable {
    let email: String
    let password: String
    let device: SyncDeviceRegistration
}

struct SyncLoginRequest: Codable {
    let email: String
    let password: String
    let device: SyncDeviceRegistration
}

struct SyncRefreshRequest: Codable {
    let refreshToken: String

    enum CodingKeys: String, CodingKey {
        case refreshToken = "refresh_token"
    }
}

struct SyncLogoutRequest: Codable {
    let refreshToken: String?

    enum CodingKeys: String, CodingKey {
        case refreshToken = "refresh_token"
    }
}

struct SyncUserDto: Codable {
    let id: String
    let email: String
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id, email
        case createdAt = "created_at"
    }
}

struct SyncDeviceDto: Codable, Identifiable, Equatable {
    let id: String
    let installationId: String
    let deviceName: String
    let deviceType: String
    let platform: String
    let lastSeenAt: String
    let revokedAt: String?

    enum CodingKeys: String, CodingKey {
        case id
        case installationId = "installation_id"
        case deviceName = "device_name"
        case deviceType = "device_type"
        case platform
        case lastSeenAt = "last_seen_at"
        case revokedAt = "revoked_at"
    }

    func withLastSeen(_ date: String) -> SyncDeviceDto {
        SyncDeviceDto(
            id: id, installationId: installationId,
            deviceName: deviceName, deviceType: deviceType,
            platform: platform, lastSeenAt: date, revokedAt: revokedAt
        )
    }

    func revoked(at date: String) -> SyncDeviceDto {
        SyncDeviceDto(
            id: id, installationId: installationId,
            deviceName: deviceName, deviceType: deviceType,
            platform: platform, lastSeenAt: date, revokedAt: date
        )
    }

    func unrevoked() -> SyncDeviceDto {
        SyncDeviceDto(
            id: id, installationId: installationId,
            deviceName: deviceName, deviceType: deviceType,
            platform: platform, lastSeenAt: lastSeenAt, revokedAt: nil
        )
    }
}

struct SyncTokensDto: Codable {
    let accessToken: String
    let refreshToken: String
    let tokenType: String
    let expiresIn: Int

    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case refreshToken = "refresh_token"
        case tokenType = "token_type"
        case expiresIn = "expires_in"
    }
}

struct SyncAuthResponse: Codable {
    let user: SyncUserDto
    let device: SyncDeviceDto
    let tokens: SyncTokensDto
}

struct SyncPairingCodeRequest: Codable {
    let installationId: String
    let deviceName: String
    let deviceType: String
    let platform: String

    enum CodingKeys: String, CodingKey {
        case installationId = "installation_id"
        case deviceName = "device_name"
        case deviceType = "device_type"
        case platform
    }
}

struct SyncPairingCodeResponse: Codable {
    let code: String
    let expiresAt: String

    enum CodingKeys: String, CodingKey {
        case code
        case expiresAt = "expires_at"
    }
}

struct SyncPairingClaimRequest: Codable {
    let code: String
}

struct SyncPairingClaimResponse: Codable {
    let status: String
    let device: SyncDeviceDto
}

struct SyncPairingStatusRequest: Codable {
    let code: String
    let installationId: String

    enum CodingKeys: String, CodingKey {
        case code
        case installationId = "installation_id"
    }
}

struct SyncPairingStatusResponse: Codable {
    let status: String
    let pairedDevice: SyncDeviceDto?
    let user: SyncUserDto?
    let tokens: SyncTokensDto?

    enum CodingKeys: String, CodingKey {
        case status
        case pairedDevice = "paired_device"
        case user, tokens
    }
}

struct SyncStatusMessage: Codable {
    let status: String
}

struct SyncSearchPushPayload: Codable {
    let query: String
    let filters: [String: String]
    let issuedByDeviceId: String?

    enum CodingKeys: String, CodingKey {
        case query, filters
        case issuedByDeviceId = "issued_by_device_id"
    }

    init(query: String, filters: [String: String] = [:], issuedByDeviceId: String? = nil) {
        self.query = query
        self.filters = filters
        self.issuedByDeviceId = issuedByDeviceId
    }
}

struct SyncSearchPushRequest: Codable {
    let targetDeviceId: String
    let payload: SyncSearchPushPayload

    enum CodingKeys: String, CodingKey {
        case targetDeviceId = "target_device_id"
        case payload
    }
}

struct SyncPlaybackIntentPayload: Codable {
    let contentId: String
    let providerTarget: String
    let positionMs: Int64
    let mediaType: String?
    let audio: String?
    let subtitles: String?
    let issuedByDeviceId: String?

    enum CodingKeys: String, CodingKey {
        case contentId = "content_id"
        case providerTarget = "provider_target"
        case positionMs = "position_ms"
        case mediaType = "media_type"
        case audio, subtitles
        case issuedByDeviceId = "issued_by_device_id"
    }

    init(
        contentId: String, providerTarget: String, positionMs: Int64,
        mediaType: String? = nil, audio: String? = nil,
        subtitles: String? = nil, issuedByDeviceId: String? = nil
    ) {
        self.contentId = contentId
        self.providerTarget = providerTarget
        self.positionMs = positionMs
        self.mediaType = mediaType
        self.audio = audio
        self.subtitles = subtitles
        self.issuedByDeviceId = issuedByDeviceId
    }
}

struct SyncPlaybackIntentRequest: Codable {
    let targetDeviceId: String
    let payload: SyncPlaybackIntentPayload

    enum CodingKeys: String, CodingKey {
        case targetDeviceId = "target_device_id"
        case payload
    }
}

struct SyncEventDispatchResponse: Codable {
    let status: String
    let eventId: String
    let targetDeviceId: String
    let eventType: String

    enum CodingKeys: String, CodingKey {
        case status
        case eventId = "event_id"
        case targetDeviceId = "target_device_id"
        case eventType = "event_type"
    }
}

struct SyncWatchStateReportRequest: Codable {
    let contentId: String
    let provider: String
    let positionMs: Int64

    enum CodingKeys: String, CodingKey {
        case contentId = "content_id"
        case provider
        case positionMs = "position_ms"
    }
}

struct SyncWatchStateReportResponse: Codable {
    let status: String
    let reportedAt: String

    enum CodingKeys: String, CodingKey {
        case status
        case reportedAt = "reported_at"
    }
}

struct SyncSettingsPushPayload: Codable {
    let categories: [String]
    let payloadJson: String
    let issuedByDeviceId: String?

    enum CodingKeys: String, CodingKey {
        case categories
        case payloadJson = "payload_json"
        case issuedByDeviceId = "issued_by_device_id"
    }

    init(categories: [String], payloadJson: String, issuedByDeviceId: String? = nil) {
        self.categories = categories
        self.payloadJson = payloadJson
        self.issuedByDeviceId = issuedByDeviceId
    }
}

// MARK: - Token Store

final class SyncTokenStore {
    private static let accessTokenKey = "torve_sync_access_token"
    private static let refreshTokenKey = "torve_sync_refresh_token"

    private let keychain: IOSKeychainSecretStore

    init(keychain: IOSKeychainSecretStore = IOSKeychainSecretStore()) {
        self.keychain = keychain
    }

    var accessToken: String? {
        get { keychain.getString(key: SyncTokenStore.accessTokenKey) }
        set {
            if let value = newValue {
                keychain.putString(key: SyncTokenStore.accessTokenKey, value: value)
            } else {
                keychain.removeKey(key: SyncTokenStore.accessTokenKey)
            }
        }
    }

    var refreshToken: String? {
        get { keychain.getString(key: SyncTokenStore.refreshTokenKey) }
        set {
            if let value = newValue {
                keychain.putString(key: SyncTokenStore.refreshTokenKey, value: value)
            } else {
                keychain.removeKey(key: SyncTokenStore.refreshTokenKey)
            }
        }
    }

    func clear() {
        accessToken = nil
        refreshToken = nil
    }
}

// MARK: - API Client

final class iOSSyncApiClient {
    private let baseURL: String
    private let session: URLSession
    private let encoder: JSONEncoder
    private let decoder: JSONDecoder

    init(
        baseURL: String = "https://sync.torve.co",
        session: URLSession = .shared
    ) {
        self.baseURL = baseURL
        self.session = session
        self.encoder = JSONEncoder()
        self.decoder = JSONDecoder()
    }

    // MARK: - Auth

    func register(payload: SyncRegisterRequest) async throws -> SyncAuthResponse {
        try await post("\(baseURL)/auth/register", body: payload)
    }

    func login(payload: SyncLoginRequest) async throws -> SyncAuthResponse {
        try await post("\(baseURL)/auth/login", body: payload)
    }

    func refresh(refreshToken: String) async throws -> SyncAuthResponse {
        try await post("\(baseURL)/auth/refresh", body: SyncRefreshRequest(refreshToken: refreshToken))
    }

    func logout(accessToken: String, refreshToken: String?) async throws -> SyncStatusMessage {
        try await post(
            "\(baseURL)/auth/logout",
            body: SyncLogoutRequest(refreshToken: refreshToken),
            bearerToken: accessToken
        )
    }

    // MARK: - Pairing

    func createPairingCode(payload: SyncPairingCodeRequest) async throws -> SyncPairingCodeResponse {
        try await post("\(baseURL)/pairing/code", body: payload)
    }

    func checkPairingStatus(payload: SyncPairingStatusRequest) async throws -> SyncPairingStatusResponse {
        try await post("\(baseURL)/pairing/status", body: payload)
    }

    func claimPairingCode(accessToken: String, code: String) async throws -> SyncPairingClaimResponse {
        try await post(
            "\(baseURL)/pairing/claim",
            body: SyncPairingClaimRequest(code: code),
            bearerToken: accessToken
        )
    }

    // MARK: - Devices

    func getDevices(accessToken: String) async throws -> [SyncDeviceDto] {
        try await get("\(baseURL)/devices", bearerToken: accessToken)
    }

    func revokeDevice(accessToken: String, deviceId: String) async throws -> SyncStatusMessage {
        try await post(
            "\(baseURL)/devices/\(deviceId)/revoke",
            body: Optional<String>.none,
            bearerToken: accessToken
        )
    }

    // MARK: - Events

    func sendSearchPush(accessToken: String, payload: SyncSearchPushRequest) async throws -> SyncEventDispatchResponse {
        try await post("\(baseURL)/events/search_push", body: payload, bearerToken: accessToken)
    }

    func sendPlaybackIntent(accessToken: String, payload: SyncPlaybackIntentRequest) async throws -> SyncEventDispatchResponse {
        try await post("\(baseURL)/events/playback_intent", body: payload, bearerToken: accessToken)
    }

    func reportWatchState(accessToken: String, payload: SyncWatchStateReportRequest) async throws -> SyncWatchStateReportResponse {
        try await post("\(baseURL)/watch_state/report", body: payload, bearerToken: accessToken)
    }

    // MARK: - Private HTTP helpers

    private func get<R: Decodable>(_ url: String, bearerToken: String? = nil) async throws -> R {
        guard let requestURL = URL(string: url) else {
            throw SyncApiError.invalidURL
        }
        var request = URLRequest(url: requestURL)
        request.httpMethod = "GET"
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = bearerToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
        return try decoder.decode(R.self, from: data)
    }

    private func post<B: Encodable, R: Decodable>(
        _ url: String,
        body: B?,
        bearerToken: String? = nil
    ) async throws -> R {
        guard let requestURL = URL(string: url) else {
            throw SyncApiError.invalidURL
        }
        var request = URLRequest(url: requestURL)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let token = bearerToken {
            request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if let body = body {
            request.httpBody = try encoder.encode(body)
        }
        let (data, response) = try await session.data(for: request)
        try validateResponse(response, data: data)
        return try decoder.decode(R.self, from: data)
    }

    private func validateResponse(_ response: URLResponse, data: Data) throws {
        guard let httpResponse = response as? HTTPURLResponse else {
            throw SyncApiError.invalidResponse
        }
        guard (200...299).contains(httpResponse.statusCode) else {
            let body = String(data: data, encoding: .utf8) ?? ""
            throw SyncApiError.httpError(statusCode: httpResponse.statusCode, body: body)
        }
    }
}

enum SyncApiError: LocalizedError {
    case invalidURL
    case invalidResponse
    case httpError(statusCode: Int, body: String)

    var errorDescription: String? {
        switch self {
        case .invalidURL: return "Invalid URL"
        case .invalidResponse: return "Invalid server response"
        case .httpError(let code, let body):
            return "HTTP \(code): \(body.prefix(200))"
        }
    }
}
