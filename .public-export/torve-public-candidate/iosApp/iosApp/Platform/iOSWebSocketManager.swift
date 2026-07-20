import Foundation
import Combine

// MARK: - WebSocket Models

struct SyncWsRegisterMessage: Codable {
    let type: String
    let deviceId: String

    enum CodingKeys: String, CodingKey {
        case type
        case deviceId = "device_id"
    }

    init(deviceId: String) {
        self.type = "register"
        self.deviceId = deviceId
    }
}

struct SyncWsAckMessage: Codable {
    let type: String
    let eventId: String

    enum CodingKeys: String, CodingKey {
        case type
        case eventId = "event_id"
    }

    init(eventId: String) {
        self.type = "ack"
        self.eventId = eventId
    }
}

struct SyncWsEventEnvelope: Codable {
    let type: String
    let event: SyncWsEventPayload?
    let message: String?
}

struct SyncWsEventPayload: Codable {
    let id: String
    let type: String
    let payload: AnyCodable
    let createdAt: String

    enum CodingKeys: String, CodingKey {
        case id, type, payload
        case createdAt = "created_at"
    }
}

/// Lightweight wrapper for arbitrary JSON values used in WebSocket payloads.
struct AnyCodable: Codable {
    let value: Any

    init(_ value: Any) {
        self.value = value
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        if let dict = try? container.decode([String: AnyCodable].self) {
            value = dict.mapValues { $0.value }
        } else if let array = try? container.decode([AnyCodable].self) {
            value = array.map { $0.value }
        } else if let str = try? container.decode(String.self) {
            value = str
        } else if let int = try? container.decode(Int64.self) {
            value = int
        } else if let double = try? container.decode(Double.self) {
            value = double
        } else if let bool = try? container.decode(Bool.self) {
            value = bool
        } else {
            value = NSNull()
        }
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        if let dict = value as? [String: Any] {
            let wrapped = dict.mapValues { AnyCodable($0) }
            try container.encode(wrapped)
        } else if let array = value as? [Any] {
            let wrapped = array.map { AnyCodable($0) }
            try container.encode(wrapped)
        } else if let str = value as? String {
            try container.encode(str)
        } else if let int = value as? Int64 {
            try container.encode(int)
        } else if let int = value as? Int {
            try container.encode(int)
        } else if let double = value as? Double {
            try container.encode(double)
        } else if let bool = value as? Bool {
            try container.encode(bool)
        } else {
            try container.encodeNil()
        }
    }

    /// Attempt to decode the stored value as a specific Decodable type.
    func decode<T: Decodable>(_ type: T.Type) -> T? {
        guard let data = try? JSONSerialization.data(withJSONObject: value),
              let result = try? JSONDecoder().decode(type, from: data) else {
            return nil
        }
        return result
    }
}

// MARK: - Realtime Events

enum SyncRealtimeEvent {
    case connecting
    case connected
    case disconnected
    case error(String)
    case message(eventId: String, eventType: String, payload: Any)
}

// MARK: - WebSocket Manager

final class iOSWebSocketManager: ObservableObject {
    @Published var connectionState: WebSocketConnectionState = .disconnected

    private let wsBaseURL: String
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()
    private var webSocketTask: URLSessionWebSocketTask?
    private var running = false
    private var reconnectTask: Task<Void, Never>?

    let events = PassthroughSubject<SyncRealtimeEvent, Never>()

    init(wsBaseURL: String = "wss://sync.torve.co/ws") {
        self.wsBaseURL = wsBaseURL
    }

    func start(accessTokenProvider: @escaping () -> String?, deviceIdProvider: @escaping () -> String?) {
        guard !running else { return }
        running = true

        reconnectTask = Task { [weak self] in
            var backoffMs: UInt64 = 1_000
            while let self = self, self.running, !Task.isCancelled {
                guard let accessToken = accessTokenProvider(),
                      let deviceId = deviceIdProvider(),
                      !accessToken.isEmpty, !deviceId.isEmpty else {
                    try? await Task.sleep(nanoseconds: 1_500_000_000)
                    continue
                }

                await MainActor.run {
                    self.connectionState = .connecting
                }
                self.events.send(.connecting)

                do {
                    try await self.connectAndListen(accessToken: accessToken, deviceId: deviceId)
                } catch {
                    self.events.send(.error(error.localizedDescription))
                }

                await MainActor.run {
                    self.connectionState = .disconnected
                }
                self.events.send(.disconnected)

                try? await Task.sleep(nanoseconds: backoffMs * 1_000_000)
                backoffMs = min(backoffMs * 2, 60_000)
            }
        }
    }

    func stop() {
        running = false
        reconnectTask?.cancel()
        reconnectTask = nil
        webSocketTask?.cancel(with: .goingAway, reason: nil)
        webSocketTask = nil
        connectionState = .disconnected
    }

    // MARK: - Private

    private func connectAndListen(accessToken: String, deviceId: String) async throws {
        guard var urlComponents = URLComponents(string: wsBaseURL) else { return }
        urlComponents.queryItems = [URLQueryItem(name: "token", value: accessToken)]
        guard let url = urlComponents.url else { return }

        let task = URLSession.shared.webSocketTask(with: url)
        self.webSocketTask = task
        task.resume()

        // Send register message
        let registerMsg = SyncWsRegisterMessage(deviceId: deviceId)
        let registerData = try encoder.encode(registerMsg)
        let registerString = String(data: registerData, encoding: .utf8) ?? ""
        try await task.send(.string(registerString))

        await MainActor.run {
            self.connectionState = .connected
        }
        events.send(.connected)

        // Listen loop
        while running && !Task.isCancelled {
            let message = try await task.receive()
            switch message {
            case .string(let text):
                handleMessage(text, task: task)
            case .data(let data):
                if let text = String(data: data, encoding: .utf8) {
                    handleMessage(text, task: task)
                }
            @unknown default:
                break
            }
        }

        task.cancel(with: .goingAway, reason: nil)
    }

    private func handleMessage(_ text: String, task: URLSessionWebSocketTask) {
        guard let data = text.data(using: .utf8),
              let envelope = try? decoder.decode(SyncWsEventEnvelope.self, from: data) else {
            return
        }

        switch envelope.type {
        case "event":
            guard let event = envelope.event else { return }
            events.send(.message(
                eventId: event.id,
                eventType: event.type,
                payload: event.payload.value
            ))
            // Send ack
            let ack = SyncWsAckMessage(eventId: event.id)
            if let ackData = try? encoder.encode(ack),
               let ackString = String(data: ackData, encoding: .utf8) {
                task.send(.string(ackString)) { _ in }
            }
        case "ready":
            events.send(.connected)
        case "error":
            events.send(.error(envelope.message ?? "Realtime error"))
        default:
            break
        }
    }
}

enum WebSocketConnectionState {
    case disconnected
    case connecting
    case connected
}
