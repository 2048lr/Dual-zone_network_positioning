import Foundation
import Combine
import Shared

/// iOS 壳的 ViewModel：
/// 地面站 → 分区定位 → CelesTrak TLE 拉取 → 卫星实时位置 → 下一次过境。
///
/// 所有计算走共享 Kotlin 内核（`Shared.framework` 的 `HamKitApi`），Swift 只负责
/// 交互状态与网络实现（见 `Networking.swift`）。
final class TrackerModel: ObservableObject {

    // MARK: - 地面站

    @Published var latitude: Double = 39.9042
    @Published var longitude: Double = 116.4074
    @Published var altitudeM: Double = 50

    // MARK: - 分区

    @Published private(set) var zone: ZoneSnapshot?

    // MARK: - 卫星数据（CelesTrak）

    @Published private(set) var satellites: [TleRecord] = []
    @Published private(set) var selectedNorad: Int32?
    @Published private(set) var dataStatusText: String = "尚未加载"
    @Published private(set) var lastUpdatedText: String = "—"
    @Published private(set) var isLoadingSatellites = false

    // MARK: - 实时

    @Published private(set) var position: PositionSnapshot?
    /// 雷达图用的轨迹（前后各 45 分钟，60 秒步长）
    @Published private(set) var track: [PositionSnapshot] = []
    /// 未来 48 小时的前 3 次过境
    @Published private(set) var passes: [PassSnapshot] = []
    /// 附近过境卫星：每颗卫星的下一次过境（在境优先、按 AOS 排序），对齐 Android 逻辑
    @Published private(set) var nearbyPasses: [Shared.SatelliteNextPass] = []
    @Published private(set) var isSearchingPass = false
    @Published private(set) var favorites: Set<Int32> = []
    /// SatNOGS 转发器：NORAD → 该星全部转发器（含 inactive）
    @Published private(set) var transmittersByNorad: [Int32: [Transmitter]] = [:]
    @Published private(set) var errorText: String?

    private var timer: Timer?
    private var passRequestId = 0
    private var nearbyRequestId = 0
    private var nearbyFingerprint = ""
    private var trackRequestId = 0
    private var tickCount = 0

    private let cache = TleDiskCache()
    private static let favoritesKey = "hamkit.favorites"

    var isSelectedFavorite: Bool {
        guard let norad = selectedNorad else { return false }
        return favorites.contains(norad)
    }

    init() {
        // 网络实现必须在第一次 fetchTles 之前注入
        HamKitApi.shared.installHttpTransport(transport: URLSessionTransport())
        favorites = Set((UserDefaults.standard.array(forKey: Self.favoritesKey) as? [Int] ?? []).map { Int32($0) })
    }

    // MARK: - 派生状态

    var selectedSatellite: TleRecord? {
        guard let norad = selectedNorad else { return nil }
        return satellites.first { $0.norad == norad }
    }

    var selectedSatelliteName: String {
        selectedSatellite?.name ?? "未选择卫星"
    }

    /// 列表页用：NORAD → 下一次过境（来自跨星预测，含当前在境）
    var passByNorad: [Int32: PassSnapshot] {
        var map: [Int32: PassSnapshot] = [:]
        for item in nearbyPasses {
            map[Int32(item.norad)] = item.pass
        }
        // 当前选中卫星的单星三次过境以 model.passes 为准（更精确）
        if let norad = selectedNorad, let first = passes.first {
            map[norad] = first
        }
        return map
    }

    private var tleLines: [String] {
        selectedSatellite?.lines ?? []
    }

    // MARK: - 生命周期

    func start() {
        guard timer == nil else { return }
        refreshZone()
        restoreCachedSatellites()
        loadSatellites(force: false)
        loadTransmitters()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0, repeats: true) { [weak self] _ in
            self?.tickPosition()
        }
    }

    /// 拉取 SatNOGS 转发器（列表卡片的模式/数量徽章与展开区依赖它）。
    /// 与 Android 一致：失败静默降级，不影响卫星列表。
    func loadTransmitters() {
        HamKitApi.shared.fetchTransmitters { [weak self] snapshot in
            guard let self, snapshot.error == nil else { return }
            var grouped: [Int32: [Transmitter]] = [:]
            for transmitter in snapshot.transmitters {
                grouped[Int32(transmitter.norad), default: []].append(transmitter)
            }
            DispatchQueue.main.async {
                self.transmittersByNorad = grouped
            }
        }
    }

    /// 冷启动先上磁盘缓存，界面立刻有数据；网络结果回来后再替换。
    private func restoreCachedSatellites() {
        guard let cached = cache.load() else { return }
        satellites = cached.records
        let date = Date(timeIntervalSince1970: Double(cached.fetchedAtMs) / 1000)
        lastUpdatedText = Self.timeFormatter.string(from: date)
        dataStatusText = "本地缓存 · \(cached.records.count) 颗（刷新中…）"
        selectDefaultIfNeeded()
        tickPosition()
        refreshTrack()
        searchNextPass()
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    /// 地面站变化后重算全部分区与过境。
    func updateStation(latitude: Double, longitude: Double) {
        self.latitude = latitude
        self.longitude = longitude
        refreshZone()
        tickPosition()
        refreshTrack()
        searchNextPass()
    }

    // MARK: - TLE 拉取

    /// 拉取 CelesTrak `active` 全量源（1.6 万+ 颗）；6 小时内命中内核缓存。
    func loadSatellites(force: Bool) {
        guard !isLoadingSatellites else { return }
        isLoadingSatellites = true
        dataStatusText = force ? "正在从 CelesTrak 拉取…" : "加载中…"

        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        // 与 Android 对齐：用 CelesTrak `active` 全量源（1.6 万+ 颗），
        // 而不是 amateur 分组（约 100 颗）。跨星过境预测在后台队列，
        // 每颗只算 1 次下一次过境，全量也算得动。
        HamKitApi.shared.fetchTles(group: "active", forceRefresh: force, nowMs: nowMs) { [weak self] snapshot in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isLoadingSatellites = false
                // 完全离线（首次启动且无网络）时用内置 ISS TLE 兜底，
                // 保证首屏永远有内容；一旦联网成功就会被真实数据替换。
                self.satellites = snapshot.records.isEmpty ? [Self.fallbackTle] : snapshot.records

                if snapshot.fetchedAtMs > 0 {
                    let date = Date(timeIntervalSince1970: Double(snapshot.fetchedAtMs) / 1000)
                    self.lastUpdatedText = Self.timeFormatter.string(from: date)
                } else if snapshot.records.isEmpty {
                    self.lastUpdatedText = "内置示例"
                }

                if let error = snapshot.error {
                    self.dataStatusText = snapshot.records.isEmpty
                        ? "离线 · 内置示例 TLE（\(error)）"
                        : "离线缓存 · \(snapshot.records.count) 颗（\(error)）"
                } else {
                    self.dataStatusText = (snapshot.fromCache ? "缓存" : "已更新")
                        + " · \(snapshot.records.count) 颗"
                }

                self.selectDefaultIfNeeded()
                self.tickPosition()
                self.refreshTrack()
                self.searchNextPass()

                // 只有真正来自网络的新数据才写盘
                if snapshot.error == nil && !snapshot.records.isEmpty {
                    self.cache.save(records: snapshot.records, fetchedAtMs: snapshot.fetchedAtMs)
                }
            }
        }
    }

    func selectSatellite(norad: Int32) {
        guard norad != selectedNorad else { return }
        selectedNorad = norad
        tickPosition()
        refreshTrack()
        searchNextPass()
    }

    private func selectDefaultIfNeeded() {
        if let norad = selectedNorad, satellites.contains(where: { $0.norad == norad }) {
            return
        }
        // 优先 ISS（NORAD 25544），否则第一颗
        selectedNorad = satellites.first(where: { $0.norad == 25544 })?.norad
            ?? satellites.first?.norad
    }

    // MARK: - 共享内核调用

    private func refreshZone() {
        do {
            zone = try HamKitApi.shared.zone(latitude: latitude, longitude: longitude)
            errorText = nil
        } catch {
            zone = nil
            errorText = "坐标非法：\(error.localizedDescription)"
        }
    }

    private func tickPosition() {
        let tle = tleLines
        guard !tle.isEmpty else {
            position = nil
            return
        }
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        position = HamKitApi.shared.satellitePosition(
            tle: tle,
            latitude: latitude,
            longitude: longitude,
            altitudeM: altitudeM,
            atMs: nowMs
        )

        // 轨迹不用每秒重算：每 30 秒刷新一次即可
        tickCount += 1
        if tickCount % 30 == 0 {
            refreshTrack()
        }
    }

    /// 计算雷达图轨迹（前后各 45 分钟、60 秒步长，约 91 个点）。
    /// 放在后台队列，避免选星瞬间卡顿。
    func refreshTrack() {
        let tle = tleLines
        guard !tle.isEmpty else {
            track = []
            return
        }
        trackRequestId += 1
        let requestId = trackRequestId
        let lat = latitude
        let lon = longitude
        let alt = altitudeM

        DispatchQueue.global(qos: .utility).async { [weak self] in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let points = HamKitApi.shared.satelliteTrack(
                tle: tle,
                latitude: lat,
                longitude: lon,
                altitudeM: alt,
                fromMs: nowMs - 45 * 60 * 1000,
                toMs: nowMs + 45 * 60 * 1000,
                stepSeconds: 60
            )
            DispatchQueue.main.async {
                guard let self, requestId == self.trackRequestId else { return }
                self.track = points
            }
        }
    }

    /// 48 小时过境搜索约 600 次传播计算，放到后台队列避免卡住 UI。
    /// 用请求序号而不是布尔守卫：快速连续切星时，旧请求结果按序号丢弃，
    /// 新星总能拿到属于自己的搜索。
    func searchNextPass() {
        let tle = tleLines
        guard !tle.isEmpty else { return }

        isSearchingPass = true
        passRequestId += 1
        let requestId = passRequestId
        let lat = latitude
        let lon = longitude
        let alt = altitudeM

        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let result = HamKitApi.shared.nextPasses(
                tle: tle,
                latitude: lat,
                longitude: lon,
                altitudeM: alt,
                fromMs: nowMs,
                searchHours: 48,
                maxCount: 3
            )
            DispatchQueue.main.async {
                guard let self, requestId == self.passRequestId else { return }
                self.passes = result
                self.isSearchingPass = false
            }
        }
        // 跨星预测独立跑（结果与当前选中星无关），不阻塞单星过境
        searchNearbyPasses()
    }

    /// 附近过境卫星：跨星各算下一次过境（对齐 Android SatellitePredictor）。
    /// 数据源或地面站没变时跳过；有新请求时旧结果按序号丢弃。
    func searchNearbyPasses() {
        let snapshot = satellites
        guard !snapshot.isEmpty else { return }
        let fingerprint = "\(snapshot.count)-\(snapshot.first?.norad ?? 0)-\(Int(latitude * 100))-\(Int(longitude * 100))"
        if fingerprint == nearbyFingerprint { return }
        nearbyFingerprint = fingerprint

        nearbyRequestId += 1
        let requestId = nearbyRequestId
        let lat = latitude
        let lon = longitude
        let alt = altitudeM

        DispatchQueue.global(qos: .utility).async { [weak self] in
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            let nearby = HamKitApi.shared.nextPassesAcrossSatellites(
                tles: snapshot,
                latitude: lat,
                longitude: lon,
                altitudeM: alt,
                fromMs: nowMs,
                searchHours: 48,
                maxCount: 300
            )
            DispatchQueue.main.async {
                guard let self, requestId == self.nearbyRequestId else { return }
                self.nearbyPasses = nearby
            }
        }
    }

    // MARK: - 收藏

    func toggleFavorite(_ norad: Int32) {
        if favorites.contains(norad) {
            favorites.remove(norad)
        } else {
            favorites.insert(norad)
        }
        UserDefaults.standard.set(favorites.map { Int($0) }, forKey: Self.favoritesKey)
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm"
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter
    }()

    /// 离线兜底：2026-08-09 拉取的 ISS TLE。
    /// 只在「从未成功联网」时使用，联网后会立刻被 CelesTrak 数据替换。
    private static let fallbackTle = TleRecord(
        name: "ISS (ZARYA) · 内置",
        norad: 25544,
        line1: "1 25544U 98067A   26220.50489838  .00004539  00000+0  89319-4 0  9991",
        line2: "2 25544  51.6323  41.1734 0007358  25.6275 334.5077 15.49385107579869"
    )
}
