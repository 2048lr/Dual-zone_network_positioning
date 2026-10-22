import Foundation
import Combine
import Shared

/// 卫星筛选条件：与 Android `SatelliteFilter` 字段一一对应。
/// 管理页与筛选页共享同一份状态，返回即生效。
final class SatelliteFilterModel: ObservableObject {

    static let shared = SatelliteFilterModel()

    @Published var query: String = ""
    @Published var modes: Set<String> = []
    @Published var categoryIds: Set<String> = []
    @Published var onlyUpcoming = false
    @Published var onlyInPass = false
    @Published var onlyAmsat = false

    /// 可选模式：与 Android 筛选页一致（转发器常用模式 + 未知）
    static let selectableModes = ["FM", "SSTV", "DSTAR", "CW", "USB", "LSB"]

    var isActive: Bool {
        !query.trimmingCharacters(in: .whitespaces).isEmpty
            || !modes.isEmpty
            || !categoryIds.isEmpty
            || onlyUpcoming || onlyInPass || onlyAmsat
    }

    func reset() {
        query = ""
        modes = []
        categoryIds = []
        onlyUpcoming = false
        onlyInPass = false
        onlyAmsat = false
    }

    /// 应用筛选。
    /// - Parameter membership: NORAD → 分类 id 集合
    /// - Parameter passByNorad: NORAD → 下一次过境（用于在境/即将入境判断）
    /// - Parameter amsatNorads: 有 AMSAT 状态报告的 NORAD 集合
    func apply(
        to satellites: [TleRecord],
        membership: [Int32: Set<String>],
        passByNorad: [Int32: PassSnapshot],
        amsatNorads: Set<Int32>
    ) -> [TleRecord] {
        guard isActive else { return satellites }
        let keyword = query.trimmingCharacters(in: .whitespaces)
        return satellites.filter { record in
            if !keyword.isEmpty,
               !record.name.localizedCaseInsensitiveContains(keyword),
               !String(record.norad).contains(keyword) {
                return false
            }

            if !modes.isEmpty {
                let satelliteModes = Set(
                    HamKitApi.shared.satelliteModes(norad: Int(record.norad))
                        .split(separator: ",")
                        .map(String.init)
                )
                let matched = modes.contains { mode in
                    mode.isEmpty ? satelliteModes.isEmpty : satelliteModes.contains(mode)
                }
                if !matched { return false }
            }

            if !categoryIds.isEmpty {
                let assigned = membership[record.norad] ?? []
                if assigned.isDisjoint(with: categoryIds) { return false }
            }

            let pass = passByNorad[record.norad]
            if onlyInPass && pass?.isCurrentlyVisible != true { return false }
            if onlyUpcoming && (pass == nil || pass?.isCurrentlyVisible == true) { return false }

            if onlyAmsat && !amsatNorads.contains(record.norad) { return false }

            return true
        }
    }
}
