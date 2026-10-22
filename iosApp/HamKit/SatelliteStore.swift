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
    /// 是否属于业余子集（satnogs + amateur）。非业余星只有目录信息，不参与过境预测。
    let isAmateur: Bool

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
/// - TLE：`TrackerModel.satellites`（amateur + satnogs + active 合并的全量目录）
/// - 业余标记：`TrackerModel.amateurSatellites`（只有它们参与过境预测）
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
    /// NORAD → 共享目录里的静态模式。全量目录 1.6 万颗，每次 rebuild 都跨一次
    /// Kotlin 边界太浪费；目录本身是静态的，按 NORAD 缓存足够。
    private var catalogModesCache: [Int32: [String]] = [:]

    private init() {}

    /// 由根视图在启动时注入数据引擎（只执行一次）。
    func configure(model: TrackerModel) {
        guard self.model == nil else { return }
        self.model = model

        Publishers.MergeMany(
            model.$satellites.map { _ in () }.eraseToAnyPublisher(),
            model.$amateurSatellites.map { _ in () }.eraseToAnyPublisher(),
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

    private func catalogModes(of norad: Int32) -> [String] {
        if let cached = catalogModesCache[norad] { return cached }
        let modes = HamKitApi.shared.satelliteModes(norad: norad)
            .split(separator: ",")
            .map(String.init)
        catalogModesCache[norad] = modes
        return modes
    }

    // MARK: - 组装

    private func rebuild() {
        let passes = passMap
        let snapshot = categoryManager.snapshot
        let amateurNorads = Set((model?.amateurSatellites ?? []).map { $0.norad })

        // 分类被删除后，筛选条件里可能还留着它的 id；不清掉的话列表会被
        // 一个不存在的分类筛成空，看起来就像「筛选/分类乱套」。
        let knownCategoryIds = Set(snapshot.categories.map { $0.id })
        if !filter.categoryIds.isSubset(of: knownCategoryIds) {
            filter.categoryIds = filter.categoryIds.intersection(knownCategoryIds)
        }

        let newItems = satellites.map { record in
            let radios = radiosFor[record.norad] ?? []
            // 模式：优先转发器实际模式，其次共享目录里的静态模式
            let radioModes = Set(radios.map { $0.mode }.filter { !$0.isEmpty })
            let modes = radioModes.isEmpty ? catalogModes(of: record.norad) : Array(radioModes)
            return SatelliteListItem(
                record: record,
                pass: passes[record.norad],
                radios: radios,
                modes: modes,
                amsatStatus: amsatStatusFor(record.norad),
                categoryIds: snapshot.categoryIds(of: record.norad),
                reminderEnabled: snapshot.reminderFlags.contains(record.norad),
                isAmateur: amateurNorads.contains(record.norad)
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

    /// 已分类 → 在境 → 有活跃转发器 → AOS 升序 → NORAD 升序（兜底键）
    private func sorted(_ list: [SatelliteListItem]) -> [SatelliteListItem] {
        list.sorted { lhs, rhs in
            let lhsCat = !lhs.categoryIds.isEmpty
            let rhsCat = !rhs.categoryIds.isEmpty
            if lhsCat != rhsCat { return lhsCat }
            if lhs.isVisible != rhs.isVisible { return lhs.isVisible }
            if lhs.hasActiveRadio != rhs.hasActiveRadio { return lhs.hasActiveRadio }
            let lhsAos = lhs.pass?.aosMs ?? Int64.max
            let rhsAos = rhs.pass?.aosMs ?? Int64.max
            if lhsAos != rhsAos { return lhsAos < rhsAos }
            // Swift 的 sort 不是稳定排序：全量目录 1.6 万颗里绝大多数字段相同，
            // 没有兜底键时每次 rebuild 的相对顺序都可能变，看起来就像列表在乱跳。
            return lhs.record.norad < rhs.record.norad
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
