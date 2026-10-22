import SwiftUI
import Shared

/// 卫星管理页：对应 Android `SatelliteManagementScreen`。
/// 所有筛选/排序/组装都交给 `SatelliteStore`，本视图只负责渲染。
struct SatelliteManagementView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider
    @ObservedObject var categoryManager = SatelliteCategoryManager.shared
    @ObservedObject private var weather = WeatherStore.shared
    @ObservedObject private var store = SatelliteStore.shared
    @State private var now = Date()
    @State private var expanded: Set<Int32> = []

    private let ticker = Timer.publish(every: 5, on: .main, in: .common).autoconnect()

    init(model: TrackerModel, location: LocationProvider) {
        self.model = model
        self.location = location
    }

    var body: some View {
        List {
            overviewSection

            // 两个入口必须是**各自独立的行**。
            // 之前把两个 NavigationLink 并排塞进同一个 HStack（同一行）里，
            // 一次点击会触发两次 push（分类 + 筛选），返回时顺序就乱了。
            Section {
                NavigationLink(value: Route.satelliteFilter) {
                    Label("筛选卫星", systemImage: "line.3.horizontal.decrease")
                }
                NavigationLink(value: Route.satelliteCategoryManager) {
                    Label("分类", systemImage: "folder")
                }
            }

            Section {
                if store.visibleItems.isEmpty {
                    Text(store.filter.isActive ? "没有符合筛选条件的卫星" : "没有卫星数据")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.visibleItems) { item in
                    satelliteCard(item)
                }
            }
        }
        .listStyle(.plain)
        .navigationTitle("卫星详情")
        .navigationBarTitleDisplayMode(.large)
        .onReceive(ticker) { now = $0 }
        .onAppear {
            WeatherStore.shared.refresh(latitude: model.latitude, longitude: model.longitude)
        }
    }

    // MARK: - 顶部概览（搜索 / 统计 / 入口 / 状态）

    private var overviewSection: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("搜索卫星名称或编号", text: $store.filter.nameQuery)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    if !store.filter.nameQuery.isEmpty {
                        Button {
                            store.filter.nameQuery = ""
                        } label: {
                            Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
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

                HStack(spacing: 0) {
                    statItem("\(store.totalCount)", "共 \(store.totalCount) 颗")
                    statItem("\(store.categorizedCount)", "已分类")
                    statItem("\(store.categoryCount)", "分类数")
                }

                HStack(spacing: 24) {
                    Text("筛选与分类入口在下方")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                if !store.filter.categoryIds.isEmpty {
                    HStack(spacing: 6) {
                        Text("已按分类筛选").font(.footnote).foregroundStyle(.secondary)
                        ForEach(Array(store.filter.categoryIds), id: \.self) { id in
                            Button {
                                store.filter.categoryIds.remove(id)
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
                        Text(weather.city.isEmpty ? location.statusText : weather.city)
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
                        Text(model.lastUpdatedText).font(.subheadline.monospacedDigit())
                        Text(model.dataStatusText).font(.footnote).foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.vertical, 6)
        }
        .listRowSeparator(.hidden)
    }

    private func statItem(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title2.weight(.semibold)).monospacedDigit().foregroundStyle(Color.accentColor)
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    // MARK: - 卫星卡片

    private func satelliteCard(_ item: SatelliteListItem) -> some View {
        let categoryNames = item.categoryIds
            .compactMap { categoryManager.snapshot.category(byId: $0)?.name }
            .sorted()
        let hasCategory = !categoryNames.isEmpty

        return VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top) {
                NavigationLink(value: Route.satelliteDetail(norad: item.record.norad)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.record.name).font(.headline).lineLimit(1).foregroundStyle(.primary)
                        Text(String(format: "#%05d", item.record.norad))
                            .font(.caption2).foregroundStyle(.secondary)
                    }
                }
                .buttonStyle(.plain)
                Spacer()
                HStack(spacing: 12) {
                    if let pass = item.pass {
                        Text("\(Int(pass.maxElevationDeg.rounded()))°")
                            .font(.subheadline.weight(.semibold)).monospacedDigit()
                    }
                    NavigationLink(value: Route.satelliteAssignment(norad: item.record.norad)) {
                        Image(systemName: hasCategory ? "folder.fill" : "folder")
                    }
                    Button {
                        categoryManager.toggleReminder(norad: item.record.norad)
                    } label: {
                        Image(systemName: item.reminderEnabled ? "bell.fill" : "bell")
                            .foregroundStyle(item.reminderEnabled ? Color.accentColor : Color.secondary)
                    }
                    .buttonStyle(.borderless)
                    Button {
                        if expanded.contains(item.record.norad) {
                            expanded.remove(item.record.norad)
                        } else {
                            expanded.insert(item.record.norad)
                        }
                    } label: {
                        Image(systemName: expanded.contains(item.record.norad) ? "chevron.up" : "chevron.down")
                            .foregroundStyle(.secondary)
                    }
                    .buttonStyle(.borderless)
                }
                .font(.callout)
            }

            if let status = item.amsatStatus {
                chip(status, color: status.localizedCaseInsensitiveContains("HEARD") ? .green : .secondary)
            }

            if !item.modes.isEmpty || hasCategory || !item.radios.isEmpty {
                HStack(spacing: 6) {
                    chip("CelesTrak", color: .blue)
                    ForEach(item.modes.prefix(4), id: \.self) { mode in
                        chip(mode, color: .secondary)
                    }
                    ForEach(categoryNames.prefix(3), id: \.self) { name in
                        chip(name, color: .accentColor)
                    }
                    if categoryNames.count > 3 {
                        chip("+\(categoryNames.count - 3)", color: .accentColor)
                    }
                    if !item.radios.isEmpty {
                        let active = item.radios.filter { $0.isActive }.count
                        Label("\(active)/\(item.radios.count)", systemImage: "antenna.radiowaves.left.and.right")
                            .font(.caption2)
                            .padding(.horizontal, 7)
                            .padding(.vertical, 3)
                            .background((active > 0 ? Color.accentColor : Color.secondary).opacity(0.14))
                            .foregroundStyle(active > 0 ? Color.accentColor : Color.secondary)
                            .clipShape(Capsule())
                    }
                }
            }

            if expanded.contains(item.record.norad) {
                if !item.radios.isEmpty {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(Array(item.radios.enumerated()), id: \.offset) { _, radio in
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
                    NavigationLink(value: Route.satelliteDetail(norad: item.record.norad)) {
                        Label("详情", systemImage: "info.circle")
                    }
                    NavigationLink(value: Route.satelliteRadar(norad: item.record.norad)) {
                        Label("雷达", systemImage: "dot.scope")
                    }
                    NavigationLink(value: Route.satelliteMap(norad: item.record.norad)) {
                        Label("地图", systemImage: "map")
                    }
                }
                .font(.subheadline)
            }

            timeBadge(item.pass)
        }
        .padding(14)
        .background(hasCategory ? Color.accentColor.opacity(0.08) : Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(
            RoundedRectangle(cornerRadius: 12)
                .stroke(item.isVisible ? Color.accentColor : Color.clear, lineWidth: 1.5)
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
    private func timeBadge(_ pass: PassSnapshot?) -> some View {
        if let pass {
            if pass.isCurrentlyVisible {
                HStack {
                    badge("出境", Self.dateFormatter.string(from: pass.losMs.date))
                    Spacer()
                    badge("剩余", remainingText(until: pass.losMs))
                }
            } else {
                badge("AOS", Self.dateFormatter.string(from: pass.aosMs.date))
            }
        } else {
            badge("无过境窗口", "48 小时内不可见")
        }
    }

    private func badge(_ label: String, _ value: String) -> some View {
        HStack(spacing: 6) {
            Text(label).font(.caption2).foregroundStyle(.secondary)
            Text(value).font(.caption.monospacedDigit()).foregroundStyle(Color.accentColor)
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
