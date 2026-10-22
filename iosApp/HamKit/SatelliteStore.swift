import Foundation
import Combine
import Shared

/// 卫星列表项：对齐 Android `SatelliteListItem`。
/// 一颗卫星的**全部展示数据**在这里组装好，视图不再自己拼。
struct SatelliteListItem: Identifiable {
    let record: TleRecord
    let pass: PassSnapshot?
    let radios: [Transmitter]
    let modes: [String]
    let amsatStatus: String?
    let categoryIds: Set<String>
    let reminderEnabled: Bool

    var id: Int32 { record.norad }
    var isVisible: Bool { pass?.isCurrentlyVisible ?? false }
    var hasActiveRadio: Bool { radios.contains { $0.isActive } }
}

/// 卫星筛选条件：字段与 Android `SatelliteFilter` 一一对应。
struct SatelliteFilter {
    var nameQuery = ""
    var modes: Set<String> = []
    var categoryIds: Set<String> = []
    var onlyUpcoming = false
    var onlyInPass = false
    var onlyAmsat = false

    var isActive: Bool {
        !nameQuery.trimmingCharacters(in: .whitespaces).isEmpty
            || !modes.isEmpty || !categoryIds.isEmpty
            || onlyUpcoming || onlyInPass || onlyAmsat
    }

    static let selectableModes = ["FM", "SSTV", "DSTAR", "CW", "USB", "LSB"]
}

/// 卫星业务层：对齐 Android `MainViewModel` 的卫星状态 + 列表排序/筛选。
///
/// 数据来源：
/// - TLE：`TrackerModel.satellites`（active 全量）
/// - 转发器：`TrackerModel.transmittersByNorad`（SatNOGS）
/// - 状态：`TrackerModel.amsatStatusByName`（AMSAT summary）
/// - 过境：`TrackerModel.passByNorad`（跨星预测）
/// - 分类/提醒：`SatelliteCategoryManager`
final class SatelliteStore: ObservableObject {

    @Published var filter = SatelliteFilter()
    @Published private(set) var items: [SatelliteListItem] = []

    static let shared = SatelliteStore()

    private var model: TrackerModel?
    private let categoryManager = SatelliteCategoryManager.shared
    private var cancellables: Set<AnyCancellable> = []

    private init() {}

    /// 由根视图在启动时注入数据引擎（只执行一次）。
    func configure(model: TrackerModel) {
        guard self.model == nil else { return }
        self.model = model

        Publishers.MergeMany(
            model.$satellites.map { _ in () }.eraseToAnyPublisher(),
            model.$transmittersByNorad.map { _ in () }.eraseToAnyPublisher(),
            model.$amsatStatusByName.map { _ in () }.eraseToAnyPublisher(),
            model.$nearbyPasses.map { _ in () }.eraseToAnyPublisher(),
            categoryManager.$snapshot.map { _ in () }.eraseToAnyPublisher()
        )
        .debounce(for: .milliseconds(80), scheduler: RunLoop.main)
        .sink { [weak self] in self?.rebuild() }
        .store(in: &cancellables)

        rebuild()
    }

    private var satellites: [TleRecord] { model?.satellites ?? [] }
    private var passMap: [Int32: PassSnapshot] { model?.passByNorad ?? [:] }
    private var radiosFor: [Int32: [Transmitter]] { model?.transmittersByNorad ?? [:] }
    private var amsatStatusFor: (Int32) -> String? {
        { [weak self] norad in self?.model?.amsatStatus(norad: norad) }
    }

    // MARK: - 组装

    private func rebuild() {
        let passes = passMap
        let snapshot = categoryManager.snapshot
        let newItems = satellites.map { record in
            let radios = radiosFor[record.norad] ?? []
            // 模式：优先转发器实际模式，其次共享目录里的静态模式
            let radioModes = Set(radios.map { $0.mode }.filter { !$0.isEmpty })
            let catalogModes = HamKitApi.shared.satelliteModes(norad: record.norad)
                .split(separator: ",")
                .map(String.init)
            let modes = radioModes.isEmpty ? catalogModes : Array(radioModes)
            return SatelliteListItem(
                record: record,
                pass: passes[record.norad],
                radios: radios,
                modes: modes,
                amsatStatus: amsatStatusFor(record.norad),
                categoryIds: snapshot.categoryIds(of: record.norad),
                reminderEnabled: snapshot.reminderFlags.contains(record.norad)
            )
        }
        items = newItems
    }

    // MARK: - 筛选 + 排序（对齐 Android）

    var visibleItems: [SatelliteListItem] {
        guard filter.isActive else { return sorted(items) }
        let keyword = filter.nameQuery.trimmingCharacters(in: .whitespaces)
        let filtered = items.filter { item in
            if !keyword.isEmpty,
               !item.record.name.localizedCaseInsensitiveContains(keyword),
               !String(item.record.norad).contains(keyword) {
                return false
            }
            if !filter.modes.isEmpty {
                let matched = filter.modes.contains { mode in
                    mode.isEmpty ? item.modes.isEmpty : item.modes.contains(mode)
                }
                if !matched { return false }
            }
            if !filter.categoryIds.isEmpty,
               item.categoryIds.isDisjoint(with: filter.categoryIds) {
                return false
            }
            if filter.onlyInPass && !item.isVisible { return false }
            if filter.onlyUpcoming && (item.pass == nil || item.isVisible) { return false }
            if filter.onlyAmsat && item.amsatStatus == nil { return false }
            return true
        }
        return sorted(filtered)
    }

    /// 已分类 → 在境 → 有活跃转发器 → AOS 升序
    private func sorted(_ list: [SatelliteListItem]) -> [SatelliteListItem] {
        list.sorted { lhs, rhs in
            let lhsCat = !lhs.categoryIds.isEmpty
            let rhsCat = !rhs.categoryIds.isEmpty
            if lhsCat != rhsCat { return lhsCat }
            if lhs.isVisible != rhs.isVisible { return lhs.isVisible }
            if lhs.hasActiveRadio != rhs.hasActiveRadio { return lhs.hasActiveRadio }
            return (lhs.pass?.aosMs ?? Int64.max) < (rhs.pass?.aosMs ?? Int64.max)
        }
    }

    // MARK: - 查询

    func item(norad: Int32) -> SatelliteListItem? {
        items.first { $0.record.norad == norad }
    }

    var totalCount: Int { items.count }
    var categorizedCount: Int { items.filter { !$0.categoryIds.isEmpty }.count }
    var categoryCount: Int { categoryManager.snapshot.categories.count }

    func toggleFavorite(_ norad: Int32) {
        model?.toggleFavorite(norad)
    }

    func isFavorite(_ norad: Int32) -> Bool {
        model?.favorites.contains(norad) ?? false
    }
}
