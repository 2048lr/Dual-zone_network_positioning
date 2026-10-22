import Foundation
import Network
import Combine
import Shared

/// APRS-IS TCP 客户端：对应 Android `AprsConnection`。
///
/// 用 Network.framework 的 `NWConnection` 建原始 TCP（APRS-IS 不是 WebSocket/HTTP）；
/// 只负责收发与行拆分，包解析交给共享内核 `Aprs.parsePacket`。
final class AprsClient {

    private var connection: NWConnection?
    private let queue = DispatchQueue(label: "click.hamkit.aprs")

    var onLine: ((String) -> Void)?
    var onState: ((String) -> Void)?

    var isConnected: Bool {
        connection?.state == .ready
    }

    func connect(host: String, port: UInt16, login: String) {
        disconnect()
        guard let nwPort = NWEndpoint.Port(rawValue: port) else {
            onState?("端口无效")
            return
        }
        let connection = NWConnection(host: NWEndpoint.Host(host), port: nwPort, using: .tcp)
        self.connection = connection
        connection.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                self?.onState?("已连接 \(host):\(port)")
                self?.send(login)
                self?.receiveLoop(on: connection)
            case .failed(let error):
                self?.onState?("连接失败：\(error.localizedDescription)")
                self?.disconnect()
            case .cancelled:
                self?.onState?("已断开")
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    func send(_ line: String) {
        guard let connection else { return }
        let data = Data((line + "\r\n").utf8)
        connection.send(content: data, completion: .contentProcessed { _ in })
    }

    func disconnect() {
        connection?.cancel()
        connection = nil
    }

    private func receiveLoop(on connection: NWConnection) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 16 * 1024) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty, let text = String(data: data, encoding: .utf8) {
                // 按行拆分；跨包的半行由 APRS-IS 的 \r\n 边界近似（保留最后一段）
                for line in text.components(separatedBy: "\r\n") where !line.isEmpty {
                    self.onLine?(line)
                }
            }
            if let error {
                self.onState?("接收失败：\(error.localizedDescription)")
                return
            }
            if isComplete {
                self.onState?("连接已关闭")
                return
            }
            self.receiveLoop(on: connection)
        }
    }
}

/// APRS 设置与运行状态（UserDefaults 持久化，对应 Android `AprsConfig`）。
struct AprsConfig: Codable, Equatable {
    var callsign: String = ""
    var ssid: String = ""
    var passcode: String = ""
    var server: String = "china.aprs2.net"
    var port: Int = 14580
    var symbolTable: String = "/"
    var symbolCode: String = ">"
    var comment: String = "HamKit"
    var transmitInterval: Int = 60
    var useCompression: Bool = true
    var enableTransmit: Bool = false
    var enableReceive: Bool = true
    var filter: String = ""
    var licenseConfirmed: Bool = false

    var fullCallsign: String {
        ssid.isEmpty ? callsign : "\(callsign)-\(ssid)"
    }

    var isReadOnly: Bool { passcode.trimmingCharacters(in: .whitespaces).isEmpty }

    var effectivePasscode: String {
        isReadOnly ? "-1" : passcode.trimmingCharacters(in: .whitespaces)
    }
}

/// 收到的 APRS 站点。
struct AprsStation: Identifiable, Codable {
    var id = UUID()
    let callsign: String
    var latitude: Double
    var longitude: Double
    var symbolTable: String
    var symbolCode: String
    var comment: String
    var altitudeMeters: Double
    var course: Int
    var speedKnots: Int
    var lastHeard: Date = Date()
}

/// APRS 消息（收发共用）。
struct AprsChatMessage: Identifiable, Codable {
    var id = UUID()
    let source: String
    let destination: String
    var body: String
    let timestamp: Date
    let isOutgoing: Bool
    var isAcknowledged: Bool = false
}

/// APRS 全局状态：设置、站点、消息、连接状态。
final class AprsStore: ObservableObject {

    static let shared = AprsStore()

    @Published var config: AprsConfig {
        didSet { save() }
    }
    @Published private(set) var stations: [AprsStation] = []
    @Published private(set) var messages: [AprsChatMessage] = []
    @Published private(set) var statusText: String = "未连接"
    @Published private(set) var isConnected = false

    private let client = AprsClient()
    private let defaults = UserDefaults.standard
    private static let key = "hamkit.aprs.config"
    private let dataURL: URL = {
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return docs.appendingPathComponent("APRS.json")
    }()

    private init() {
        if let data = defaults.data(forKey: Self.key),
           let decoded = try? JSONDecoder().decode(AprsConfig.self, from: data) {
            config = decoded
        } else {
            config = AprsConfig()
        }

        if let data = try? Data(contentsOf: dataURL),
           let stored = try? JSONDecoder().decode(Persisted.self, from: data) {
            stations = stored.stations
            messages = stored.messages
        }

        client.onState = { [weak self] text in
            DispatchQueue.main.async {
                self?.statusText = text
                self?.isConnected = self?.client.isConnected ?? false
            }
        }
        client.onLine = { [weak self] line in
            self?.handle(line: line)
        }
    }

    private struct Persisted: Codable {
        var stations: [AprsStation]
        var messages: [AprsChatMessage]
    }

    private func save() {
        if let data = try? JSONEncoder().encode(config) {
            defaults.set(data, forKey: Self.key)
        }
    }

    private func saveData() {
        let payload = Persisted(stations: stations, messages: messages)
        if let data = try? JSONEncoder().encode(payload) {
            try? data.write(to: dataURL, options: .atomic)
        }
    }

    func connect() {
        guard !config.callsign.isEmpty else {
            statusText = "请先填写呼号"
            return
        }
        guard config.licenseConfirmed else {
            statusText = "请先在设置中确认持照与责任声明"
            return
        }
        let login = Aprs.formatLogin(
            callsign: config.callsign,
            ssid: config.ssid,
            passcode: config.effectivePasscode,
            version: "HamKit-iOS"
        )
        statusText = "正在连接 \(config.server):\(config.port)…"
        client.connect(host: config.server, port: UInt16(config.port), login: login)
        if !config.filter.isEmpty {
            client.send("#filter \(config.filter)")
        }
    }

    func disconnect() {
        client.disconnect()
        isConnected = false
        statusText = "未连接"
    }

    func sendMessage(destination: String, body: String) {
        guard isConnected else {
            statusText = "未连接，无法发送"
            return
        }
        let payload = Aprs.formatMessage(destination: destination, message: body, msgNumber: "")
        client.send("\(config.fullCallsign)>APRS,TCPIP*:\(payload)")
        messages.append(
            AprsChatMessage(
                source: config.fullCallsign,
                destination: destination,
                body: body,
                timestamp: Date(),
                isOutgoing: true
            )
        )
        saveData()
    }

    func sendPosition(latitude: Double, longitude: Double) {
        guard isConnected, config.enableTransmit, !config.isReadOnly else { return }
        let payload = Aprs.formatPosition(
            latitude: latitude,
            longitude: longitude,
            symbolTable: config.symbolTable,
            symbolCode: config.symbolCode,
            comment: config.comment,
            compressed: config.useCompression
        )
        client.send("\(config.fullCallsign)>APRS,TCPIP*:\(payload)")
    }

    private func handle(line: String) {
        if line.hasPrefix("#") {
            // APRS-IS 服务器注释：登录成功提示
            if line.contains("logresp") && line.contains("verified") {
                DispatchQueue.main.async {
                    self.statusText = line.contains("unverified")
                        ? "已连接（只读：未填写 passcode）"
                        : "已连接并验证"
                    self.isConnected = true
                }
            }
            return
        }
        let parsed = Aprs.parsePacket(packet: line)
        guard parsed.type != "unsupported" else { return }

        DispatchQueue.main.async {
            if parsed.type == "position", parsed.hasPosition {
                let station = AprsStation(
                    callsign: parsed.source,
                    latitude: parsed.latitude,
                    longitude: parsed.longitude,
                    symbolTable: parsed.symbolTable,
                    symbolCode: parsed.symbolCode,
                    comment: parsed.comment,
                    altitudeMeters: parsed.altitudeMeters,
                    course: parsed.course,
                    speedKnots: parsed.speedKnots
                )
                if let index = self.stations.firstIndex(where: { $0.callsign == station.callsign }) {
                    self.stations[index] = station
                } else {
                    self.stations.append(station)
                }
            } else if parsed.type == "message" {
                self.messages.append(
                    AprsChatMessage(
                        source: parsed.source,
                        destination: parsed.destination,
                        body: parsed.body,
                        timestamp: Date(),
                        isOutgoing: false
                    )
                )
            }
            self.saveData()
        }
    }
}
