import SwiftUI
import Shared

/// 卫星管理页：照抄 Android `SatelliteManagementScreen`。
///
/// 结构：搜索 → 说明 → 统计（总数/已分类/分类数）→ 筛选与分类入口 →
/// 获取定位 / 更新卫星源状态 → 卫星卡片列表（模式、分类徽章、俯仰角、
/// 提醒铃铛、LOS/AOS 时间与倒计时）。
struct SatelliteManagementView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider
    @ObservedObject var categoryManager = SatelliteCategoryManager.shared
    @ObservedObject var filter = SatelliteFilterModel.shared

    @State private var now = Date()
    @State private var expanded: Set<Int32> = []

    private let ticker = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    // MARK: - 过滤与排序

    private var categorizedNumbers: Set<Int32> {
        Set(categoryManager.snapshot.membership.keys)
    }

    private var amsatNorads: Set<Int32> {
        Set(model.satellites.lazy.filter { model.amsatStatus(norad: $0.norad) != nil }.map { $0.norad })
    }

    private var filtered: [TleRecord] {
        filter.apply(
            to: model.satellites,
            membership: categoryManager.snapshot.membership,
            passByNorad: model.passByNorad,
            amsatNorads: amsatNorads
        )
    }

    /// 排序与 Android 一致：已分类优先 → 在境优先 → AOS 升序（无过境排最后）
    private var sorted: [TleRecord] {
        let passes = model.passByNorad
        return filtered.sorted { lhs, rhs in
            let lhsCat = categorizedNumbers.contains(lhs.norad)
            let rhsCat = categorizedNumbers.contains(rhs.norad)
            if lhsCat != rhsCat { return lhsCat }
            let lhsVisible = passes[lhs.norad]?.isCurrentlyVisible ?? false
            let rhsVisible = passes[rhs.norad]?.isCurrentlyVisible ?? false
            if lhsVisible != rhsVisible { return lhsVisible }
            return (passes[lhs.norad]?.aosMs ?? Int64.max) < (passes[rhs.norad]?.aosMs ?? Int64.max)
        }
    }

    private var categorizedCount: Int {
        model.satellites.lazy.filter { categorizedNumbers.contains($0.norad) }.count
    }

    var body: some View {
        List {
            overviewSection
            Section {
                ForEach(sorted, id: \.norad) { record in
                    satelliteCard(record)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("卫星详情")
        .navigationBarTitleDisplayMode(.large)
        .onReceive(ticker) { now = $0 }
    }

    // MARK: - 顶部概览

    private var overviewSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                // 搜索
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass")
                        .foregroundStyle(.secondary)
                    TextField("搜索卫星名称或编号", text: $filter.query)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if !filter.query.isEmpty {
                        Button {
                            filter.query = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill")
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                    }
                }
                .padding(10)
                .background(Color(.secondarySystemBackground))
                .clipShape(RoundedRectangle(cornerRadius: 10))

                Text("点击卫星可归入自定义分类。已归入分类的卫星在列表中高亮显示，可用「分类」按钮管理与筛选。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                // 统计
                HStack(spacing: 0) {
                    statItem(value: "\(model.satellites.count)", label: "共 \(model.satellites.count) 颗")
                    statItem(value: "\(categorizedCount)", label: "已分类")
                    statItem(value: "\(categoryManager.snapshot.categories.count)", label: "分类数")
                }

                // 筛选 / 分类
                HStack(spacing: 24) {
                    NavigationLink(value: Route.satelliteFilter) {
                        Label("筛选卫星", systemImage: "line.3.horizontal.decrease")
                    }
                    NavigationLink(value: Route.satelliteCategoryManager) {
                        Label("分类", systemImage: "folder")
                    }
                }
                .font(.subheadline)

                if !filter.categoryIds.isEmpty {
                    HStack(spacing: 6) {
                        Text("已按分类筛选")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                        ForEach(Array(filter.categoryIds), id: \.self) { id in
                            Button {
                                filter.categoryIds.remove(id)
                            } label: {
                                HStack(spacing: 4) {
                                    Text(categoryManager.snapshot.category(byId: id)?.name ?? id)
                                    Image(systemName: "xmark")
                                }
                                .font(.caption2)
                                .padding(.horizontal, 7)
                                .padding(.vertical, 3)
                                .background(Color.accentColor.opacity(0.15))
                                .clipShape(Capsule())
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }

                Divider()

                // 定位 / 卫星源状态
                HStack(alignment: .top, spacing: 10) {
                    Button {
                        location.requestLocation()
                    } label: {
                        Text("获取定位")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color.accentColor)
                            .foregroundStyle(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 9))
                    }
                    .buttonStyle(.borderless)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(Self.timeFormatter.string(from: now))
                            .font(.subheadline.monospacedDigit())
                        Text(location.statusText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                HStack(alignment: .top, spacing: 10) {
                    Button {
                        model.loadSatellites(force: true)
                    } label: {
                        Text("更新卫星源")
                            .font(.subheadline.weight(.medium))
                            .padding(.horizontal, 14)
                            .padding(.vertical, 8)
                            .background(Color.accentColor)
                            .foregroundStyle(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 9))
                    }
                    .buttonStyle(.borderless)
                    .disabled(model.isLoadingSatellites)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(model.lastUpdatedText)
                            .font(.subheadline.monospacedDigit())
                        Text(model.dataStatusText)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.vertical, 6)
        }
        .listRowSeparator(.hidden)
    }

    private func statItem(value: String, label: String) -> some View {
        VStack(spacing: 2) {
            Text(value)
                .font(.title2.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(Color.accentColor)
            Text(label)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - 卫星卡片

    private func satelliteCard(_ record: TleRecord) -> some View {
        let pass = model.passByNorad[record.norad]
        let categoryIds = categoryManager.snapshot.categoryIds(of: record.norad)
        let categoryNames = categoryIds.compactMap { categoryManager.snapshot.category(byId: $0)?.name }.sorted()
        let reminderOn = categoryManager.snapshot.reminderFlags.contains(record.norad)
        let modes = HamKitApi.shared.satelliteModes(norad: record.norad)
            .split(separator: ",")
            .map(String.init)
        let radios = model.transmittersByNorad[record.norad] ?? []
        let activeRadios = radios.filter { $0.isActive }.count
        let status = model.amsatStatus(norad: record.norad)
        let hasCategory = !categoryNames.isEmpty
        let inPass = pass?.isCurrentlyVisible ?? false

        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                NavigationLink(value: Route.satelliteDetail(norad: record.norad)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(record.name)
                            .font(.headline)
                            .lineLimit(1)
                            .foregroundStyle(.primary)
                        Text(String(format: "#%05d", record.norad))
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                Spacer()
                HStack(spacing: 12) {
                    if let pass {
                        Text("\(Int(pass.maxElevationDeg.rounded()))°")
                            .font(.subheadline.weight(.semibold))
                            .monospacedDigit()
                    }
                    NavigationLink(value: Route.satelliteAssignment(norad: record.norad)) {
                        Image(systemName: hasCategory ? "folder.fill" : "folder")
                    }
                    Button {
                        categoryManager.toggleReminder(norad: record.norad)
                    } label: {
                        Image(systemName: reminderOn ? "bell.fill" : "bell")
                            .foregroundStyle(reminderOn ? Color.accentColor : Color.secondary)
                    }
                    .buttonStyle(.borderless)
                    Button {
                        if expanded.contains(record.norad) {
                            expanded.remove(record.norad)
                        } else {
                            expanded.insert(record.norad)
                        }
                    } label: {
                        Image(systemName: expanded.contains(record.norad) ? "chevron.up" : "chevron.down")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                }
                .font(.callout)
            }

            // 状态 + 模式 + 分类 + 转发器数量 chip
            if let status {
                chip(status, color: status.localizedCaseInsensitiveContains("HEARD") ? .green : .secondary)
            }
            if !modes.isEmpty || hasCategory || !radios.isEmpty {
                HStack(spacing: 6) {
                    ForEach(modes.prefix(4), id: \.self) { mode in
                        chip(mode, color: .secondary)
                    }
                    ForEach(categoryNames.prefix(3), id: \.self) { name in
                        chip(name, color: .accentColor)
                    }
                    if categoryNames.count > 3 {
                        chip("+\(categoryNames.count - 3)", color: .accentColor)
                    }
                    if !radios.isEmpty {
                        Label("\(activeRadios)/\(radios.count)", systemImage: "antenna.radiowaves.left.and.right")
                            .font(.caption2)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background((activeRadios > 0 ? Color.accentColor : Color.secondary).opacity(0.14))
                            .foregroundStyle(activeRadios > 0 ? Color.accentColor : Color.secondary)
                            .clipShape(Capsule())
                    }
                }
            }

            // 展开区：转发器列表 + 详情/雷达/地图入口
            if expanded.contains(record.norad) {
                if !radios.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(radios.enumerated()), id: \.offset) { _, radio in
                            HStack(alignment: .top) {
                                VStack(alignment: .leading, spacing: 2) {
                                    Text((radio.inverted ? "INV: " : "") + (radio.name.isEmpty ? "未命名转发器" : radio.name))
                                        .font(.caption.weight(.medium))
                                    Text(Self.frequencyText(radio))
                                        .font(.caption2.monospacedDigit())
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Text(radio.status)
                                    .font(.caption2)
                                    .foregroundStyle(radio.isActive ? Color.green : Color.secondary)
                            }
                        }
                    }
                    .padding(10)
                    .background(Color(.tertiarySystemFill))
                    .clipShape(RoundedRectangle(cornerRadius: 9))
                }

                HStack(spacing: 16) {
                    NavigationLink(value: Route.satelliteDetail(norad: record.norad)) {
                        Label("详情", systemImage: "info.circle")
                    }
                    NavigationLink(value: Route.satelliteRadar(norad: record.norad)) {
                        Label("雷达", systemImage: "dot.scope")
                    }
                    NavigationLink(value: Route.satelliteMap(norad: record.norad)) {
                        Label("地图", systemImage: "map")
                    }
                }
                .font(.subheadline)
            }

            // 时间徽章
            timeBadge(pass: pass)
        }
        .padding(14)
        .background(hasCategory ? Color.accentColor.opacity(0.08) : Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(inPass ? Color.accentColor : Color.clear, lineWidth: 1.5)
        )
        .listRowSeparator(.hidden)
        .listRowInsets(EdgeInsets(top: 5, leading: 12, bottom: 5, trailing: 12))
    }

    private func chip(_ text: String, color: Color) -> some View {
        Text(text)
            .font(.caption2)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(color.opacity(0.14))
            .foregroundStyle(color)
            .clipShape(Capsule())
    }

    @ViewBuilder
    private func timeBadge(pass: PassSnapshot?) -> some View {
        if let pass {
            if pass.isCurrentlyVisible {
                HStack {
                    badge(label: "出境", value: Self.dateFormatter.string(from: pass.losMs.date))
                    Spacer()
                    badge(label: "剩余", value: remainingText(until: pass.losMs))
                }
            } else {
                badge(label: "AOS", value: Self.dateFormatter.string(from: pass.aosMs.date))
            }
        } else {
            badge(label: "无过境窗口", value: "48 小时内不可见")
        }
    }

    private func badge(label: String, value: String) -> some View {
        HStack(spacing: 6) {
            Text(label)
                .font(.caption2)
                .foregroundStyle(.secondary)
            Text(value)
                .font(.caption.monospacedDigit())
                .foregroundStyle(Color.accentColor)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(Color(.tertiarySystemFill))
        .clipShape(RoundedRectangle(cornerRadius: 7))
    }

    private func remainingText(until ms: Int64) -> String {
        let seconds = max(0, Int(ms.date.timeIntervalSince(now)))
        return "\(seconds / 60)分\(seconds % 60)秒"
    }

    /// "RX 145.800 MHz  TX 437.800 MHz  FM"（与 Android 转发器行一致）
    private static func frequencyText(_ radio: Transmitter) -> String {
        var parts: [String] = []
        if radio.downlinkHz > 0 {
            parts.append(String(format: "RX %.3f MHz", Double(radio.downlinkHz) / 1_000_000))
        }
        if radio.uplinkHz > 0 {
            parts.append(String(format: "TX %.3f MHz", Double(radio.uplinkHz) / 1_000_000))
        }
        if !radio.mode.isEmpty { parts.append(radio.mode) }
        return parts.joined(separator: "  ")
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        return f
    }()

    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f
    }()
}

private extension Int64 {
    var date: Date { Date(timeIntervalSince1970: Double(self) / 1000.0) }
}
