import Foundation
import Combine
import UIKit

// MARK: - Sync Account State

struct SyncAccountState: Equatable {
    var isLoading: Bool = false
    var isAuthenticated: Bool = false
    var userId: String? = nil
    var userEmail: String? = nil
    var profileName: String? = nil
    var hasPin: Bool = false
    var deviceId: String? = nil
    var devices: [SyncDeviceDto] = []
    var pairingCode: SyncPairingCodeResponse? = nil
    var pairingStatus: String? = nil
    var wsStatus: String = "local-only"
    var recentEvents: [String] = []
    var error: String? = nil

    static func == (lhs: SyncAccountState, rhs: SyncAccountState) -> Bool {
        lhs.isLoading == rhs.isLoading &&
        lhs.isAuthenticated == rhs.isAuthenticated &&
        lhs.userId == rhs.userId &&
        lhs.deviceId == rhs.deviceId &&
        lhs.devices == rhs.devices &&
        lhs.error == rhs.error &&
        lhs.pairingCode?.code == rhs.pairingCode?.code &&
        lhs.pairingStatus == rhs.pairingStatus &&
        lhs.wsStatus == rhs.wsStatus &&
        lhs.profileName == rhs.profileName
    }
}

// MARK: - Inbound Events

enum SyncInboundEvent {
    case searchPush(query: String, filters: [String: String], issuedByDeviceId: String?)
    case playbackIntent(contentId: String, providerTarget: String, positionMs: Int64,
                        mediaType: String?, audio: String?, subtitles: String?, issuedByDeviceId: String?)
    case settingsPush(categories: [String], payloadJson: String, issuedByDeviceId: String?)
}

// MARK: - Sync Coordinator

final class iOSSyncCoordinator: ObservableObject {
    @Published var state: SyncAccountState
    let inboundEvents = PassthroughSubject<SyncInboundEvent, Never>()

    private let defaults = UserDefaults.standard
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let tokenStore = SyncTokenStore()

    private let lanServer = iOSLanSyncServer()
    private lazy var lanDiscovery = iOSLanSyncDiscovery(selfServiceNameHint: localServiceNameHint())
    private let lanHttpClient = iOSLanSyncHttpClient()
    private let webSocketManager = iOSWebSocketManager()

    private let peerQueue = DispatchQueue(label: "com.torve.sync.peers")
    private var endpointByDeviceId: [String: LanResolvedService] = [:]
    private var serviceNameByDeviceId: [String: String] = [:]
    private var serviceByName: [String: LanResolvedService] = [:]
    private var lanReady = false

    private var cancellables = Set<AnyCancellable>()

    // MARK: - Keys

    private static let prefsName = "torve_sync_local_state"
    private static let keyProfileName = "torve_local_profile_name"
    private static let keyProfilePinHash = "torve_local_profile_pin_hash"
    private static let keySelfDeviceId = "torve_self_device_id"
    private static let keyDevicesJson = "torve_paired_devices_json"
    private static let keyPendingPairCode = "torve_pending_pair_code"
    private static let keyPendingPairExpiresAt = "torve_pending_pair_expires_at"

    private static let eventSearchPush = "SEARCH_PUSH"
    private static let eventPlaybackIntent = "PLAYBACK_INTENT"
    private static let eventSettingsPush = "SETTINGS_PUSH"

    // MARK: - Init

    init() {
        self.state = SyncAccountState()
        self.state = buildInitialState()
        setupLanServer()
        startLanTransport()
        setupLanDiscoveryListeners()

        Task { await loadDevices() }
    }

    // MARK: - Installation ID

    func installationId() -> String {
        if let existing = defaults.string(forKey: "torve_installation_id"), !existing.isEmpty {
            return existing
        }
        let generated = UUID().uuidString
        defaults.set(generated, forKey: "torve_installation_id")
        return generated
    }

    // MARK: - Local Profile

    func createLocalProfile(profileName: String, pin: String?) {
        let normalizedName = profileName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !normalizedName.isEmpty else {
            state.error = "Profile name is required."
            return
        }
        if let pin = pin, !pin.isEmpty {
            let pinRegex = try? NSRegularExpression(pattern: "^[0-9]{4,8}$")
            let range = NSRange(pin.startIndex..., in: pin)
            if pinRegex?.firstMatch(in: pin, range: range) == nil {
                state.error = "PIN must be 4 to 8 digits."
                return
            }
        }
        let hashedPin = (pin?.isEmpty == false) ? hashPin(pin!) : nil
        defaults.set(normalizedName, forKey: Self.keyProfileName)
        defaults.set(hashedPin, forKey: Self.keyProfilePinHash)

        let selfDeviceId = getOrCreateSelfDeviceId()
        var devices = readDevices()
        devices = ensureSelfDevice(devices, selfDeviceId: selfDeviceId)
        persistDevices(devices)

        state = SyncAccountState(
            isLoading: false,
            isAuthenticated: true,
            userId: localUserId(),
            userEmail: normalizedName,
            profileName: normalizedName,
            hasPin: hashedPin != nil,
            deviceId: selfDeviceId,
            devices: devices,
            pairingStatus: "Local profile ready. Pair TVs on this Wi-Fi network.",
            wsStatus: onlineTransportStatus(),
            error: nil
        )
    }

    func clearLocalProfile() {
        let selfDeviceId = getOrCreateSelfDeviceId()
        let selfDevice = ensureSelfDevice([], selfDeviceId: selfDeviceId)
        persistDevices(selfDevice)

        defaults.removeObject(forKey: Self.keyProfileName)
        defaults.removeObject(forKey: Self.keyProfilePinHash)
        defaults.removeObject(forKey: Self.keyPendingPairCode)
        defaults.removeObject(forKey: Self.keyPendingPairExpiresAt)
        tokenStore.clear()

        state = SyncAccountState(
            isLoading: false,
            isAuthenticated: false,
            deviceId: selfDeviceId,
            devices: selfDevice,
            pairingStatus: "Local mode active.",
            wsStatus: onlineTransportStatus()
        )
    }

    func login(email: String, password: String) {
        let fallback = email.trimmingCharacters(in: .whitespacesAndNewlines)
            .components(separatedBy: "@").first ?? "Torve User"
        let name = fallback.isEmpty ? "Torve User" : fallback
        let pinRegex = try? NSRegularExpression(pattern: "^[0-9]{4,8}$")
        let range = NSRange(password.startIndex..., in: password)
        let maybePin = pinRegex?.firstMatch(in: password, range: range) != nil ? password : nil
        createLocalProfile(profileName: name, pin: maybePin)
    }

    func register(email: String, password: String) {
        login(email: email, password: password)
    }

    func logout() {
        clearLocalProfile()
    }

    // MARK: - Devices

    func loadDevices() async {
        let selfDeviceId = getOrCreateSelfDeviceId()
        var devices = readDevices()
        devices = ensureSelfDevice(devices, selfDeviceId: selfDeviceId)
        persistDevices(devices)

        await MainActor.run {
            state.isLoading = false
            state.devices = devices
            state.deviceId = selfDeviceId
            state.wsStatus = onlineTransportStatus()
            state.error = nil
        }
    }

    func refreshDevices() {
        Task {
            await loadDevices()
            await refreshKnownPeers()
        }
    }

    func revokeDevice(_ deviceId: String) {
        guard deviceId != state.deviceId else {
            state.error = "This device cannot revoke itself."
            return
        }
        let updated = state.devices.map { device in
            device.id == deviceId ? device.revoked(at: utcNow()) : device
        }
        peerQueue.sync {
            endpointByDeviceId.removeValue(forKey: deviceId)
            serviceNameByDeviceId.removeValue(forKey: deviceId)
        }
        persistDevices(updated)
        state.devices = updated
        state.error = nil
    }

    func targetDevices(includeSelf: Bool = false) -> [SyncDeviceDto] {
        let selfId = state.deviceId
        let reachable: Set<String> = peerQueue.sync { Set(endpointByDeviceId.keys) }
        return state.devices.filter { device in
            let notRevoked = device.revokedAt == nil
            let isSelf = device.id == selfId
            let isReachable = device.id == selfId || reachable.contains(device.id)
            return notRevoked && isReachable && (includeSelf || !isSelf)
        }
    }

    // MARK: - Send Events

    func sendSearchPush(targetDeviceId: String, query: String, filters: [String: String] = [:]) async -> Result<String, Error> {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .failure(SyncCoordinatorError.blankInput("Query is blank"))
        }
        guard state.isAuthenticated else {
            return .failure(SyncCoordinatorError.notAuthenticated)
        }
        guard let target = state.devices.first(where: { $0.id == targetDeviceId && $0.revokedAt == nil }) else {
            return .failure(SyncCoordinatorError.targetNotPaired)
        }
        let eventId = UUID().uuidString
        appendRecentEvent("search_push:\(eventId):\(target.deviceName):\(trimmed)")

        if target.id == state.deviceId {
            inboundEvents.send(.searchPush(query: trimmed, filters: filters, issuedByDeviceId: state.deviceId))
            return .success(eventId)
        }
        guard let endpoint: LanResolvedService = peerQueue.sync(execute: { endpointByDeviceId[target.id] }) else {
            return .failure(SyncCoordinatorError.targetNotReachable)
        }
        let event = LanEventEnvelope(
            eventId: eventId,
            eventType: Self.eventSearchPush,
            sourceDeviceId: state.deviceId ?? "",
            targetDeviceId: target.id,
            payload: AnyCodable(encodeToDictionary(SyncSearchPushPayload(query: trimmed, filters: filters, issuedByDeviceId: state.deviceId)))
        )
        let result = await lanHttpClient.sendEvent(service: endpoint, event: event)
        switch result {
        case .success(let response) where response.status == "ok":
            return .success(eventId)
        case .success(let response):
            return .failure(SyncCoordinatorError.remoteError(response.message ?? "Failed to send search push"))
        case .failure(let error):
            return .failure(error)
        }
    }

    func sendPlaybackIntent(
        targetDeviceId: String, contentId: String, providerTarget: String,
        positionMs: Int64, mediaType: String? = nil, audio: String? = nil, subtitles: String? = nil
    ) async -> Result<String, Error> {
        let trimmed = contentId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .failure(SyncCoordinatorError.blankInput("Content id is blank"))
        }
        guard state.isAuthenticated else {
            return .failure(SyncCoordinatorError.notAuthenticated)
        }
        guard let target = state.devices.first(where: { $0.id == targetDeviceId && $0.revokedAt == nil }) else {
            return .failure(SyncCoordinatorError.targetNotPaired)
        }
        let eventId = UUID().uuidString
        let safePos = max(positionMs, 0)
        appendRecentEvent("playback_intent:\(eventId):\(target.deviceName):\(trimmed)@\(safePos)")

        if target.id == state.deviceId {
            inboundEvents.send(.playbackIntent(
                contentId: trimmed, providerTarget: providerTarget, positionMs: safePos,
                mediaType: mediaType, audio: audio, subtitles: subtitles, issuedByDeviceId: state.deviceId
            ))
            return .success(eventId)
        }
        guard let endpoint: LanResolvedService = peerQueue.sync(execute: { endpointByDeviceId[target.id] }) else {
            return .failure(SyncCoordinatorError.targetNotReachable)
        }
        let payload = SyncPlaybackIntentPayload(
            contentId: trimmed, providerTarget: providerTarget, positionMs: safePos,
            mediaType: mediaType, audio: audio, subtitles: subtitles, issuedByDeviceId: state.deviceId
        )
        let event = LanEventEnvelope(
            eventId: eventId,
            eventType: Self.eventPlaybackIntent,
            sourceDeviceId: state.deviceId ?? "",
            targetDeviceId: target.id,
            payload: AnyCodable(encodeToDictionary(payload))
        )
        let result = await lanHttpClient.sendEvent(service: endpoint, event: event)
        switch result {
        case .success(let response) where response.status == "ok":
            return .success(eventId)
        case .success(let response):
            return .failure(SyncCoordinatorError.remoteError(response.message ?? "Failed to transfer playback"))
        case .failure(let error):
            return .failure(error)
        }
    }

    func sendSettingsPush(targetDeviceId: String, categories: [String], payloadJson: String) async -> Result<String, Error> {
        guard !categories.isEmpty else {
            return .failure(SyncCoordinatorError.blankInput("No categories selected"))
        }
        guard state.isAuthenticated else {
            return .failure(SyncCoordinatorError.notAuthenticated)
        }
        guard let target = state.devices.first(where: { $0.id == targetDeviceId && $0.revokedAt == nil }) else {
            return .failure(SyncCoordinatorError.targetNotPaired)
        }
        let eventId = UUID().uuidString
        appendRecentEvent("settings_push:\(eventId):\(target.deviceName):\(categories.joined(separator: ","))")

        if target.id == state.deviceId {
            inboundEvents.send(.settingsPush(categories: categories, payloadJson: payloadJson, issuedByDeviceId: state.deviceId))
            return .success(eventId)
        }
        guard let endpoint: LanResolvedService = peerQueue.sync(execute: { endpointByDeviceId[target.id] }) else {
            return .failure(SyncCoordinatorError.targetNotReachable)
        }
        let payload = SyncSettingsPushPayload(categories: categories, payloadJson: payloadJson, issuedByDeviceId: state.deviceId)
        let event = LanEventEnvelope(
            eventId: eventId,
            eventType: Self.eventSettingsPush,
            sourceDeviceId: state.deviceId ?? "",
            targetDeviceId: target.id,
            payload: AnyCodable(encodeToDictionary(payload))
        )
        let result = await lanHttpClient.sendEvent(service: endpoint, event: event)
        switch result {
        case .success(let response) where response.status == "ok":
            return .success(eventId)
        case .success(let response):
            return .failure(SyncCoordinatorError.remoteError(response.message ?? "Failed to push settings"))
        case .failure(let error):
            return .failure(error)
        }
    }

    func reportWatchState(contentId: String, provider: String, positionMs: Int64) -> Result<Void, Error> {
        let trimmed = contentId.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .failure(SyncCoordinatorError.blankInput("Content id is blank"))
        }
        guard state.isAuthenticated else {
            return .failure(SyncCoordinatorError.notAuthenticated)
        }
        appendRecentEvent("watch_state:\(trimmed):\(provider):\(max(positionMs, 0))")
        return .success(())
    }

    // MARK: - Pairing

    func claimPairingCode(_ code: String) {
        let normalized = normalizePairingCode(code)
        guard !normalized.isEmpty else {
            state.error = "Enter a valid pairing code."
            return
        }
        guard state.isAuthenticated else {
            state.error = "Create a local profile before pairing TVs."
            return
        }
        state.isLoading = true
        state.error = nil

        Task {
            let discovered: [LanResolvedService] = peerQueue.sync { Array(serviceByName.values) }
            guard !discovered.isEmpty else {
                await MainActor.run {
                    state.isLoading = false
                    state.error = "No Torve TVs discovered on local Wi-Fi."
                }
                return
            }
            let selfDevice = buildSelfDevice(getOrCreateSelfDeviceId())
            let request = LanPairClaimRequest(code: normalized, sourceDevice: selfDevice)

            var pairedDevice: SyncDeviceDto?
            var pairedService: LanResolvedService?
            for service in discovered {
                if case .success(let response) = await lanHttpClient.claimPairingCode(service: service, request: request) {
                    if response.status == "paired", let device = response.device {
                        pairedDevice = device
                        pairedService = service
                        break
                    }
                }
            }
            guard let pairedDevice = pairedDevice, let pairedService = pairedService else {
                await MainActor.run {
                    state.isLoading = false
                    state.error = "Pairing code not found on local Wi-Fi."
                }
                return
            }
            peerQueue.sync {
                endpointByDeviceId[pairedDevice.id] = pairedService
                serviceNameByDeviceId[pairedDevice.id] = pairedService.serviceName
            }
            let updated = upsertDevice(state.devices, candidate: pairedDevice.withLastSeen(utcNow()).unrevoked())
            persistDevices(updated)

            await MainActor.run {
                state.isLoading = false
                state.devices = updated
                state.error = nil
                state.pairingStatus = "Paired \(pairedDevice.deviceName) on local Wi-Fi."
                state.wsStatus = onlineTransportStatus()
            }
            appendRecentEvent("pair_claimed:\(pairedDevice.deviceName)")
        }
    }

    func startTvPairingFlow() {
        state.isLoading = true
        state.error = nil
        let code = generatePairingCode()
        let expiresAt = utcAt(Date().addingTimeInterval(600))

        defaults.set(code, forKey: Self.keyPendingPairCode)
        defaults.set(expiresAt, forKey: Self.keyPendingPairExpiresAt)

        state.isLoading = false
        state.pairingCode = SyncPairingCodeResponse(code: code, expiresAt: expiresAt)
        state.pairingStatus = "Open Torve on your phone and enter this code to pair over local Wi-Fi."
        state.wsStatus = onlineTransportStatus()
    }

    func clearError() {
        state.error = nil
    }

    // MARK: - LAN Transport

    private func setupLanServer() {
        lanServer.selfDeviceProvider = { [weak self] in
            guard let self = self else {
                return SyncDeviceDto(id: "unknown", installationId: "", deviceName: "iOS", deviceType: "mobile", platform: "ios", lastSeenAt: "", revokedAt: nil)
            }
            return self.buildSelfDevice(self.getOrCreateSelfDeviceId())
        }
        lanServer.onPairClaim = { [weak self] request in
            self?.handleInboundPairClaim(request) ?? LanPairClaimResponse(status: "error", message: "Not ready")
        }
        lanServer.onInboundEvent = { [weak self] event in
            self?.handleInboundLanEvent(event) ?? LanStatusResponse(status: "error", message: "Not ready")
        }
    }

    private func startLanTransport() {
        do {
            try lanServer.startIfNeeded()
            let port = lanServer.getPort()
            lanDiscovery.start(port: port)
            lanReady = true
            state.wsStatus = onlineTransportStatus()
        } catch {
            lanReady = false
            state.wsStatus = "local-lan-error"
            state.error = "Local sync transport failed: \(error.localizedDescription)"
        }
    }

    private func setupLanDiscoveryListeners() {
        lanDiscovery.onServiceResolved
            .sink { [weak self] service in
                self?.onServiceResolved(service)
            }
            .store(in: &cancellables)

        lanDiscovery.onServiceLost
            .sink { [weak self] serviceName in
                self?.onServiceLost(serviceName)
            }
            .store(in: &cancellables)

        lanDiscovery.onError
            .sink { [weak self] message in
                DispatchQueue.main.async {
                    self?.state.error = message
                    self?.state.wsStatus = self?.onlineTransportStatus() ?? "local-only"
                }
            }
            .store(in: &cancellables)
    }

    private func onServiceResolved(_ service: LanResolvedService) {
        peerQueue.sync {
            serviceByName[service.serviceName] = service
        }
        Task {
            guard case .success(let hello) = await lanHttpClient.fetchHello(service: service) else { return }
            let remote = hello.device
            let selfId = getOrCreateSelfDeviceId()
            guard remote.id != selfId else { return }

            peerQueue.sync {
                endpointByDeviceId[remote.id] = service
                serviceNameByDeviceId[remote.id] = service.serviceName
            }

            await MainActor.run {
                if state.devices.contains(where: { $0.id == remote.id }) {
                    let updated = state.devices.map { existing in
                        if existing.id == remote.id {
                            return SyncDeviceDto(
                                id: existing.id, installationId: remote.installationId,
                                deviceName: remote.deviceName, deviceType: remote.deviceType,
                                platform: remote.platform, lastSeenAt: utcNow(), revokedAt: nil
                            )
                        }
                        return existing
                    }
                    persistDevices(updated)
                    state.devices = updated
                    state.wsStatus = onlineTransportStatus()
                }
            }
        }
    }

    private func onServiceLost(_ serviceName: String) {
        var lostIds: [String] = []
        peerQueue.sync {
            serviceByName.removeValue(forKey: serviceName)
            for (deviceId, sName) in serviceNameByDeviceId where sName == serviceName {
                endpointByDeviceId.removeValue(forKey: deviceId)
                lostIds.append(deviceId)
            }
            for id in lostIds {
                serviceNameByDeviceId.removeValue(forKey: id)
            }
        }
        if !lostIds.isEmpty {
            appendRecentEvent("peer_offline:\(lostIds.joined(separator: ","))")
        }
    }

    private func refreshKnownPeers() async {
        let discovered: [LanResolvedService] = peerQueue.sync { Array(serviceByName.values) }
        for service in discovered {
            if case .success(let hello) = await lanHttpClient.fetchHello(service: service) {
                let remote = hello.device
                guard remote.id != getOrCreateSelfDeviceId() else { continue }
                peerQueue.sync {
                    endpointByDeviceId[remote.id] = service
                    serviceNameByDeviceId[remote.id] = service.serviceName
                }
            }
        }
        await MainActor.run {
            state.wsStatus = onlineTransportStatus()
        }
    }

    // MARK: - Inbound Handlers

    private func handleInboundPairClaim(_ request: LanPairClaimRequest) -> LanPairClaimResponse {
        guard let active = state.pairingCode else {
            return LanPairClaimResponse(status: "invalid_code", message: "No active pairing code.")
        }
        if isPairingExpired(active.expiresAt) {
            DispatchQueue.main.async {
                self.state.pairingCode = nil
                self.state.pairingStatus = "Pairing code expired. Generate a new code."
            }
            return LanPairClaimResponse(status: "expired", message: "Pairing code expired.")
        }
        guard normalizePairingCode(request.code) == normalizePairingCode(active.code) else {
            return LanPairClaimResponse(status: "invalid_code", message: "Pairing code mismatch.")
        }
        let source = request.sourceDevice.withLastSeen(utcNow()).unrevoked()
        let updated = upsertDevice(state.devices, candidate: source)
        persistDevices(updated)

        defaults.removeObject(forKey: Self.keyPendingPairCode)
        defaults.removeObject(forKey: Self.keyPendingPairExpiresAt)

        DispatchQueue.main.async {
            self.state.devices = updated
            self.state.pairingCode = nil
            self.state.pairingStatus = "Paired with \(source.deviceName)."
            self.state.wsStatus = self.onlineTransportStatus()
            self.state.error = nil
        }
        appendRecentEvent("pair_accepted:\(source.deviceName)")
        return LanPairClaimResponse(
            status: "paired",
            device: buildSelfDevice(getOrCreateSelfDeviceId()),
            message: "paired"
        )
    }

    private func handleInboundLanEvent(_ event: LanEventEnvelope) -> LanStatusResponse {
        let selfId = state.deviceId ?? getOrCreateSelfDeviceId()
        guard event.targetDeviceId == selfId else {
            return LanStatusResponse(status: "ignored", message: "Not target device.")
        }

        switch event.eventType {
        case Self.eventSearchPush:
            guard let payload = decodePayload(SyncSearchPushPayload.self, from: event.payload) else {
                return LanStatusResponse(status: "bad_request", message: "Invalid search push payload")
            }
            inboundEvents.send(.searchPush(
                query: payload.query,
                filters: payload.filters,
                issuedByDeviceId: payload.issuedByDeviceId ?? event.sourceDeviceId
            ))
            appendRecentEvent("search_received:\(event.eventId):\(payload.query)")
            return LanStatusResponse(status: "ok")

        case Self.eventPlaybackIntent:
            guard let payload = decodePayload(SyncPlaybackIntentPayload.self, from: event.payload) else {
                return LanStatusResponse(status: "bad_request", message: "Invalid playback intent payload")
            }
            inboundEvents.send(.playbackIntent(
                contentId: payload.contentId,
                providerTarget: payload.providerTarget,
                positionMs: payload.positionMs,
                mediaType: payload.mediaType,
                audio: payload.audio,
                subtitles: payload.subtitles,
                issuedByDeviceId: payload.issuedByDeviceId ?? event.sourceDeviceId
            ))
            appendRecentEvent("playback_received:\(event.eventId):\(payload.contentId)")
            return LanStatusResponse(status: "ok")

        case Self.eventSettingsPush:
            guard let payload = decodePayload(SyncSettingsPushPayload.self, from: event.payload) else {
                return LanStatusResponse(status: "bad_request", message: "Invalid settings push payload")
            }
            inboundEvents.send(.settingsPush(
                categories: payload.categories,
                payloadJson: payload.payloadJson,
                issuedByDeviceId: payload.issuedByDeviceId ?? event.sourceDeviceId
            ))
            appendRecentEvent("settings_received:\(event.eventId):\(payload.categories.joined(separator: ","))")
            return LanStatusResponse(status: "ok")

        default:
            return LanStatusResponse(status: "bad_request", message: "Unsupported event type \(event.eventType)")
        }
    }

    // MARK: - State Builders

    private func buildInitialState() -> SyncAccountState {
        let profileName = defaults.string(forKey: Self.keyProfileName)?.trimmingCharacters(in: .whitespacesAndNewlines)
        let hasPin = !(defaults.string(forKey: Self.keyProfilePinHash) ?? "").isEmpty
        let selfDeviceId = getOrCreateSelfDeviceId()
        var devices = readDevices()
        devices = ensureSelfDevice(devices, selfDeviceId: selfDeviceId)
        persistDevices(devices)

        let pendingCode = defaults.string(forKey: Self.keyPendingPairCode)
        let pendingExpires = defaults.string(forKey: Self.keyPendingPairExpiresAt)
        let pendingPairing: SyncPairingCodeResponse?
        if let code = pendingCode, !code.isEmpty,
           let expires = pendingExpires, !expires.isEmpty,
           !isPairingExpired(expires) {
            pendingPairing = SyncPairingCodeResponse(code: code, expiresAt: expires)
        } else {
            defaults.removeObject(forKey: Self.keyPendingPairCode)
            defaults.removeObject(forKey: Self.keyPendingPairExpiresAt)
            pendingPairing = nil
        }

        let authenticated = profileName != nil && !profileName!.isEmpty
        return SyncAccountState(
            isLoading: false,
            isAuthenticated: authenticated,
            userId: authenticated ? localUserId() : nil,
            userEmail: profileName,
            profileName: profileName,
            hasPin: hasPin,
            deviceId: selfDeviceId,
            devices: devices,
            pairingCode: pendingPairing,
            pairingStatus: pendingPairing != nil ? "Pairing code \(pendingPairing!.code)" : (authenticated ? nil : "Local mode active."),
            wsStatus: onlineTransportStatus()
        )
    }

    // MARK: - Device Helpers

    private func getOrCreateSelfDeviceId() -> String {
        if let existing = defaults.string(forKey: Self.keySelfDeviceId), !existing.isEmpty {
            return existing
        }
        let generated = "self-\(installationId())"
        defaults.set(generated, forKey: Self.keySelfDeviceId)
        return generated
    }

    private func readDevices() -> [SyncDeviceDto] {
        guard let data = defaults.data(forKey: Self.keyDevicesJson) else { return [] }
        return (try? decoder.decode([SyncDeviceDto].self, from: data)) ?? []
    }

    private func persistDevices(_ devices: [SyncDeviceDto]) {
        if let data = try? encoder.encode(devices) {
            defaults.set(data, forKey: Self.keyDevicesJson)
        }
    }

    private func ensureSelfDevice(_ devices: [SyncDeviceDto], selfDeviceId: String) -> [SyncDeviceDto] {
        let selfDevice = buildSelfDevice(selfDeviceId)
        if devices.contains(where: { $0.id == selfDeviceId }) {
            return devices.map { existing in
                if existing.id == selfDeviceId {
                    return SyncDeviceDto(
                        id: selfDevice.id, installationId: selfDevice.installationId,
                        deviceName: selfDevice.deviceName, deviceType: selfDevice.deviceType,
                        platform: selfDevice.platform, lastSeenAt: utcNow(), revokedAt: existing.revokedAt
                    )
                }
                return existing
            }
        } else {
            return [selfDevice] + devices
        }
    }

    private func upsertDevice(_ devices: [SyncDeviceDto], candidate: SyncDeviceDto) -> [SyncDeviceDto] {
        if devices.contains(where: { $0.id == candidate.id }) {
            return devices.map { $0.id == candidate.id ? candidate : $0 }
        } else {
            return devices + [candidate]
        }
    }

    private func buildSelfDevice(_ selfDeviceId: String) -> SyncDeviceDto {
        let model = UIDevice.current.model
        let isIPad = model.lowercased().contains("ipad")
        let deviceType = isIPad ? "tablet" : "mobile"
        return SyncDeviceDto(
            id: selfDeviceId,
            installationId: installationId(),
            deviceName: UIDevice.current.name,
            deviceType: deviceType,
            platform: "ios",
            lastSeenAt: utcNow(),
            revokedAt: nil
        )
    }

    // MARK: - Helpers

    private func appendRecentEvent(_ entry: String) {
        let history = ["\(utcNow()):\(entry)"] + Array(state.recentEvents.prefix(19))
        DispatchQueue.main.async {
            self.state.recentEvents = history
            self.state.error = nil
            self.state.wsStatus = self.onlineTransportStatus()
        }
    }

    private func normalizePairingCode(_ raw: String) -> String {
        String(raw.uppercased().filter { $0.isLetter || $0.isNumber }.prefix(6))
    }

    private func generatePairingCode() -> String {
        let alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return String((0..<6).map { _ in alphabet.randomElement()! })
    }

    private func isPairingExpired(_ expiresAtIso: String) -> Bool {
        guard let date = parseUtc(expiresAtIso) else { return true }
        return date < Date()
    }

    private func hashPin(_ pin: String) -> String {
        guard let data = pin.data(using: .utf8) else { return "" }
        var hash = [UInt8](repeating: 0, count: 32)
        _ = data.withUnsafeBytes { bytes in
            CC_SHA256(bytes.baseAddress, CC_LONG(data.count), &hash)
        }
        return hash.map { String(format: "%02x", $0) }.joined()
    }

    private func localUserId() -> String { "local-\(installationId())" }

    private func localServiceNameHint() -> String {
        let suffix = String(installationId().replacingOccurrences(of: "-", with: "").suffix(8))
        return "Torve-\(suffix)"
    }

    private func onlineTransportStatus() -> String {
        lanReady ? "local-lan-online" : "local-only"
    }

    private func utcNow() -> String { utcAt(Date()) }

    private func utcAt(_ date: Date) -> String {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime]
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.string(from: date)
    }

    private func parseUtc(_ iso: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        formatter.timeZone = TimeZone(identifier: "UTC")
        return formatter.date(from: iso) ?? {
            let alt = ISO8601DateFormatter()
            alt.formatOptions = [.withInternetDateTime]
            alt.timeZone = TimeZone(identifier: "UTC")
            return alt.date(from: iso)
        }()
    }

    private func encodeToDictionary<T: Encodable>(_ value: T) -> [String: Any] {
        guard let data = try? encoder.encode(value),
              let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return [:]
        }
        return dict
    }

    private func decodePayload<T: Decodable>(_ type: T.Type, from payload: AnyCodable) -> T? {
        guard let data = try? JSONSerialization.data(withJSONObject: payload.value) else { return nil }
        return try? decoder.decode(type, from: data)
    }
}

// MARK: - Errors

enum SyncCoordinatorError: LocalizedError {
    case blankInput(String)
    case notAuthenticated
    case targetNotPaired
    case targetNotReachable
    case remoteError(String)

    var errorDescription: String? {
        switch self {
        case .blankInput(let msg): return msg
        case .notAuthenticated: return "Create a local profile first."
        case .targetNotPaired: return "Target device is not paired."
        case .targetNotReachable: return "Target TV is not reachable on local Wi-Fi."
        case .remoteError(let msg): return msg
        }
    }
}

// MARK: - CommonCrypto bridge

import CommonCrypto
