import Foundation
import Network
import Combine

// MARK: - LAN Models

struct LanHelloResponse: Codable {
    let status: String
    let device: SyncDeviceDto

    init(status: String = "ok", device: SyncDeviceDto) {
        self.status = status
        self.device = device
    }
}

struct LanPairClaimRequest: Codable {
    let code: String
    let sourceDevice: SyncDeviceDto

    enum CodingKeys: String, CodingKey {
        case code
        case sourceDevice = "source_device"
    }
}

struct LanPairClaimResponse: Codable {
    let status: String
    let device: SyncDeviceDto?
    let message: String?

    init(status: String, device: SyncDeviceDto? = nil, message: String? = nil) {
        self.status = status
        self.device = device
        self.message = message
    }
}

struct LanEventEnvelope: Codable {
    let eventId: String
    let eventType: String
    let sourceDeviceId: String
    let targetDeviceId: String
    let payload: AnyCodable

    enum CodingKeys: String, CodingKey {
        case eventId = "event_id"
        case eventType = "event_type"
        case sourceDeviceId = "source_device_id"
        case targetDeviceId = "target_device_id"
        case payload
    }
}

struct LanStatusResponse: Codable {
    let status: String
    let message: String?

    init(status: String = "ok", message: String? = nil) {
        self.status = status
        self.message = message
    }
}

struct LanResolvedService: Equatable {
    let serviceName: String
    let host: String
    let port: Int
}

// MARK: - LAN Sync Discovery (Bonjour)

final class iOSLanSyncDiscovery: NSObject, ObservableObject {
    private static let serviceType = "_torve-sync._tcp."
    private static let domain = "local."

    private let selfServiceNameHint: String
    private var netService: NetService?
    private var browser: NetServiceBrowser?
    private var resolving: [NetService] = []

    @Published var discoveredServices: [String: LanResolvedService] = [:]
    let onServiceResolved = PassthroughSubject<LanResolvedService, Never>()
    let onServiceLost = PassthroughSubject<String, Never>()
    let onError = PassthroughSubject<String, Never>()

    private var registeredServiceName: String?

    init(selfServiceNameHint: String) {
        self.selfServiceNameHint = String(selfServiceNameHint.prefix(63))
        super.init()
    }

    func start(port: Int) {
        registerService(port: port)
        startDiscovery()
    }

    func stop() {
        browser?.stop()
        browser = nil
        netService?.stop()
        netService = nil
        resolving.forEach { $0.stop() }
        resolving.removeAll()
        discoveredServices.removeAll()
        registeredServiceName = nil
    }

    // MARK: - Register

    private func registerService(port: Int) {
        let service = NetService(
            domain: Self.domain,
            type: Self.serviceType,
            name: selfServiceNameHint,
            port: Int32(port)
        )
        service.delegate = self
        service.publish()
        netService = service
    }

    // MARK: - Discovery

    private func startDiscovery() {
        let b = NetServiceBrowser()
        b.delegate = self
        b.searchForServices(ofType: Self.serviceType, inDomain: Self.domain)
        browser = b
    }
}

extension iOSLanSyncDiscovery: NetServiceBrowserDelegate {
    func netServiceBrowser(_ browser: NetServiceBrowser, didFind service: NetService, moreComing: Bool) {
        let ownName = registeredServiceName ?? selfServiceNameHint
        guard service.name != ownName else { return }
        service.delegate = self
        resolving.append(service)
        service.resolve(withTimeout: 5.0)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didRemove service: NetService, moreComing: Bool) {
        discoveredServices.removeValue(forKey: service.name)
        resolving.removeAll { $0 == service }
        onServiceLost.send(service.name)
    }

    func netServiceBrowser(_ browser: NetServiceBrowser, didNotSearch errorDict: [String: NSNumber]) {
        onError.send("Bonjour discovery failed: \(errorDict)")
    }
}

extension iOSLanSyncDiscovery: NetServiceDelegate {
    func netServiceDidPublish(_ sender: NetService) {
        registeredServiceName = sender.name
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        onError.send("Bonjour registration failed: \(errorDict)")
    }

    func netServiceDidResolveAddress(_ sender: NetService) {
        let ownName = registeredServiceName ?? selfServiceNameHint
        guard sender.name != ownName else { return }
        guard sender.port > 0 else { return }
        guard let hostAddress = resolveHostAddress(sender) else { return }

        let resolved = LanResolvedService(
            serviceName: sender.name,
            host: hostAddress,
            port: sender.port
        )
        discoveredServices[sender.name] = resolved
        onServiceResolved.send(resolved)
        resolving.removeAll { $0 == sender }
    }

    func netService(_ sender: NetService, didNotResolve errorDict: [String: NSNumber]) {
        resolving.removeAll { $0 == sender }
    }

    private func resolveHostAddress(_ service: NetService) -> String? {
        guard let addresses = service.addresses else { return nil }
        for addressData in addresses {
            let count = addressData.count
            guard count >= MemoryLayout<sockaddr>.size else { continue }
            let family = addressData.withUnsafeBytes { $0.load(as: sockaddr.self).sa_family }
            if family == UInt8(AF_INET) {
                var addr = addressData.withUnsafeBytes { $0.load(as: sockaddr_in.self) }
                var buffer = [CChar](repeating: 0, count: Int(INET_ADDRSTRLEN))
                inet_ntop(AF_INET, &addr.sin_addr, &buffer, socklen_t(INET_ADDRSTRLEN))
                return String(cString: buffer)
            }
        }
        return nil
    }
}

// MARK: - LAN HTTP Server (NWListener)

final class iOSLanSyncServer {
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var listener: NWListener?

    var selfDeviceProvider: (() -> SyncDeviceDto)?
    var onPairClaim: ((LanPairClaimRequest) -> LanPairClaimResponse)?
    var onInboundEvent: ((LanEventEnvelope) -> LanStatusResponse)?

    var port: Int {
        listener?.port?.rawValue.hashValue ?? 0
    }

    var actualPort: UInt16 {
        listener?.port?.rawValue ?? 0
    }

    func startIfNeeded() throws {
        guard listener == nil else { return }
        let parameters = NWParameters.tcp
        let nwListener = try NWListener(using: parameters, on: .any)
        nwListener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                break
            case .failed(let error):
                print("[LanSyncServer] Listener failed: \(error)")
                self?.listener = nil
            default:
                break
            }
        }
        nwListener.newConnectionHandler = { [weak self] connection in
            self?.handleConnection(connection)
        }
        nwListener.start(queue: .global(qos: .utility))
        self.listener = nwListener
    }

    func stop() {
        listener?.cancel()
        listener = nil
    }

    func getPort() -> Int {
        Int(listener?.port?.rawValue ?? 0)
    }

    // MARK: - Connection Handler

    private func handleConnection(_ connection: NWConnection) {
        connection.start(queue: .global(qos: .utility))
        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] content, _, _, error in
            guard let self = self, let data = content else {
                connection.cancel()
                return
            }
            guard let requestString = String(data: data, encoding: .utf8) else {
                self.sendResponse(connection: connection, statusCode: 400, body: LanStatusResponse(status: "bad_request", message: "Invalid encoding"))
                return
            }
            self.routeRequest(requestString, connection: connection)
        }
    }

    private func routeRequest(_ raw: String, connection: NWConnection) {
        let lines = raw.split(separator: "\r\n", omittingEmptySubsequences: false)
        guard let requestLine = lines.first else {
            sendResponse(connection: connection, statusCode: 400, body: LanStatusResponse(status: "bad_request"))
            return
        }
        let parts = requestLine.split(separator: " ")
        guard parts.count >= 2 else {
            sendResponse(connection: connection, statusCode: 400, body: LanStatusResponse(status: "bad_request"))
            return
        }
        let method = String(parts[0]).uppercased()
        let path = String(parts[1])

        // Extract body (after blank line)
        var bodyString = ""
        if let blankIdx = lines.firstIndex(where: { $0.isEmpty }) {
            let bodyLines = lines.dropFirst(blankIdx.advanced(by: 1))
            bodyString = bodyLines.joined(separator: "\r\n")
        }

        switch (method, path) {
        case ("GET", "/sync/hello"):
            guard let device = selfDeviceProvider?() else {
                sendResponse(connection: connection, statusCode: 500, body: LanStatusResponse(status: "error", message: "No device info"))
                return
            }
            let response = LanHelloResponse(device: device)
            sendResponse(connection: connection, statusCode: 200, body: response)

        case ("POST", "/sync/pair/claim"):
            guard let bodyData = bodyString.data(using: .utf8),
                  let request = try? decoder.decode(LanPairClaimRequest.self, from: bodyData),
                  let handler = onPairClaim else {
                sendResponse(connection: connection, statusCode: 400, body: LanStatusResponse(status: "bad_request", message: "Invalid payload"))
                return
            }
            let response = handler(request)
            let statusCode = response.status == "paired" ? 200 : 401
            sendResponse(connection: connection, statusCode: statusCode, body: response)

        case ("POST", "/sync/event"):
            guard let bodyData = bodyString.data(using: .utf8),
                  let event = try? decoder.decode(LanEventEnvelope.self, from: bodyData),
                  let handler = onInboundEvent else {
                sendResponse(connection: connection, statusCode: 400, body: LanStatusResponse(status: "bad_request", message: "Invalid payload"))
                return
            }
            let response = handler(event)
            let statusCode = response.status == "ok" ? 200 : 400
            sendResponse(connection: connection, statusCode: statusCode, body: response)

        default:
            sendResponse(connection: connection, statusCode: 404, body: LanStatusResponse(status: "not_found", message: "Unknown endpoint"))
        }
    }

    private func sendResponse<T: Encodable>(connection: NWConnection, statusCode: Int, body: T) {
        guard let bodyData = try? encoder.encode(body) else {
            connection.cancel()
            return
        }
        let statusText: String = {
            switch statusCode {
            case 200: return "OK"
            case 400: return "Bad Request"
            case 401: return "Unauthorized"
            case 404: return "Not Found"
            default: return "Internal Server Error"
            }
        }()
        var header = "HTTP/1.1 \(statusCode) \(statusText)\r\n"
        header += "Content-Type: application/json\r\n"
        header += "Content-Length: \(bodyData.count)\r\n"
        header += "Connection: close\r\n"
        header += "\r\n"
        var responseData = Data(header.utf8)
        responseData.append(bodyData)
        connection.send(content: responseData, completion: .contentProcessed { _ in
            connection.cancel()
        })
    }
}

// MARK: - LAN HTTP Client

final class iOSLanSyncHttpClient {
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private let session: URLSession

    init() {
        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = 3
        config.timeoutIntervalForResource = 5
        self.session = URLSession(configuration: config)
    }

    func fetchHello(service: LanResolvedService) async -> Result<LanHelloResponse, Error> {
        await performRequest(service: service, path: "/sync/hello", method: "GET")
    }

    func claimPairingCode(service: LanResolvedService, request: LanPairClaimRequest) async -> Result<LanPairClaimResponse, Error> {
        await performRequest(service: service, path: "/sync/pair/claim", method: "POST", body: request)
    }

    func sendEvent(service: LanResolvedService, event: LanEventEnvelope) async -> Result<LanStatusResponse, Error> {
        await performRequest(service: service, path: "/sync/event", method: "POST", body: event)
    }

    // MARK: - Private

    private func performRequest<B: Encodable, R: Decodable>(
        service: LanResolvedService,
        path: String,
        method: String,
        body: B? = Optional<String>.none
    ) async -> Result<R, Error> {
        let host = service.host.contains(":") ? "[\(service.host)]" : service.host
        guard let url = URL(string: "http://\(host):\(service.port)\(path)") else {
            return .failure(SyncApiError.invalidURL)
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        if let body = body {
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try? encoder.encode(body)
        }
        do {
            let (data, response) = try await session.data(for: request)
            if let httpResponse = response as? HTTPURLResponse, !(200...299).contains(httpResponse.statusCode) {
                let responseBody = String(data: data, encoding: .utf8) ?? ""
                return .failure(SyncApiError.httpError(statusCode: httpResponse.statusCode, body: responseBody))
            }
            let decoded = try decoder.decode(R.self, from: data)
            return .success(decoded)
        } catch {
            return .failure(error)
        }
    }
}
