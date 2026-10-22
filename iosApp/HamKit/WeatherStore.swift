import Foundation
import Combine
import Shared

/// 天气状态：高德 Web 服务（逆地理编码 + 实时 + 4 天预报），30 分钟缓存。
/// Key 从 Info.plist 的 `AMapWebKey` 读取（构建时由 `AMAP_WEB_KEY` 注入）。
final class WeatherStore: ObservableObject {

    static let shared = WeatherStore()

    @Published private(set) var city: String = ""
    @Published private(set) var now: WeatherNow?
    @Published private(set) var daily: [WeatherDay] = []
    @Published private(set) var error: String?
    @Published private(set) var isLoading = false

    private var lastFetch: Date?
    private var lastLatitude: Double?
    private var lastLongitude: Double?

    private var apiKey: String {
        (Bundle.main.object(forInfoDictionaryKey: "AMapWebKey") as? String) ?? ""
    }

    private init() {}

    /// 位置变化或手动刷新时调用；30 分钟内且坐标未变则跳过。
    func refresh(latitude: Double, longitude: Double, force: Bool = false) {
        if !force,
           let lastFetch,
           Date().timeIntervalSince(lastFetch) < 30 * 60,
           let lastLatitude, let lastLongitude,
           abs(lastLatitude - latitude) < 0.01,
           abs(lastLongitude - longitude) < 0.01 {
            return
        }
        guard !isLoading else { return }
        isLoading = true
        error = nil

        // 优先 Apple 原生 WeatherKit + CLGeocoder；未授权/失败再回退高德 Web。
        Task {
            if let native = await NativeWeather.current(latitude: latitude, longitude: longitude) {
                let cityName = await NativeWeather.cityName(latitude: latitude, longitude: longitude)
                await MainActor.run {
                    self.city = cityName ?? self.city
                    self.now = WeatherNow(
                        temp: String(format: "%.1f", native.temperatureC),
                        text: native.condition,
                        windDir: native.windDirection,
                        windPower: String(format: "%.0f", native.windKph),
                        humidity: "\(native.humidityPercent)",
                        reportTime: Self.reportFormatter.string(from: Date())
                    )
                    self.daily = []
                    self.error = nil
                    self.isLoading = false
                    self.lastFetch = Date()
                    self.lastLatitude = latitude
                    self.lastLongitude = longitude
                }
                return
            }

            let key = apiKey
            guard !key.isEmpty else {
                await MainActor.run {
                    self.isLoading = false
                    self.error = "WeatherKit 不可用，且未配置高德 Web 服务 Key"
                }
                return
            }
            HamKitApi.shared.fetchWeather(
                latitude: latitude,
                longitude: longitude,
                apiKey: key
            ) { [weak self] snapshot in
                DispatchQueue.main.async {
                    guard let self else { return }
                    self.isLoading = false
                    if let info = snapshot.info {
                        self.city = info.cityName
                        self.now = info.now
                        self.daily = info.daily
                        self.error = nil
                        self.lastFetch = Date()
                        self.lastLatitude = latitude
                        self.lastLongitude = longitude
                    } else {
                        self.error = snapshot.error ?? "天气获取失败"
                    }
                }
            }
        }
    }

    private static let reportFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    /// 首页展示用的一行摘要。
    var summary: String {
        guard let now else {
            if let error { return error }
            return isLoading ? "天气加载中…" : "天气不可用"
        }
        var parts: [String] = []
        if !city.isEmpty { parts.append(city) }
        parts.append("\(now.temp)°C")
        parts.append(now.text)
        if !now.windDir.isEmpty {
            parts.append(now.windDir + (now.windPower.isEmpty ? "" : " \(now.windPower)级"))
        }
        if !now.humidity.isEmpty { parts.append("湿度 \(now.humidity)%") }
        return parts.joined(separator: " · ")
    }
}

/// 每日一言（本地列表，Android 端为内置文案；iOS 版先内置少量并随机）。
enum DailyQuote {
    private static let quotes = [
        "73，愿传播常在。",
        "天线越好，运气越好。",
        "听见不等于通联，通联需要耐心。",
        "QRP 也能通全球。",
        "守听是美德。",
        "传播来了，别错过。",
        "每一次 CQ 都是一次可能。",
        "把噪声当地址，把信号当朋友。",
    ]

    static func today() -> String {
        let day = Calendar.current.ordinality(of: .day, in: .year, for: Date()) ?? 1
        return quotes[day % quotes.count]
    }
}
