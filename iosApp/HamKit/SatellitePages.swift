import SwiftUI
import MapKit
import Shared

/// 卫星链路全部子页：管理（列表）/ 筛选 / 详情 / 雷达 / 轨道地图 / 提醒列表 / 定位详情。
/// 对应 Android 的 satellite、reminder、location 三个 screen 目录。

// MARK: - 卫星管理（列表）

// MARK: - 卫星筛选

/// 卫星筛选页：对应 Android SatelliteFilter —— 按收藏/名称搜索过滤，选中即设为当前卫星。
struct SatelliteFilterView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var filter = SatelliteFilterModel.shared
    @ObservedObject var categoryManager = SatelliteCategoryManager.shared
    @Environment(\.dismiss) private var dismiss

    @State private var query = ""
    @State private var modes: Set<String> = []
    @State private var categoryIds: Set<String> = []
    @State private var onlyInPass = false
    @State private var onlyUpcoming = false
    @State private var onlyAmsat = false

    var body: some View {
        List {
            Section("名称 / 编号") {
                TextField("搜索卫星名称或 NORAD", text: $query)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }

            Section("工作模式") {
                ForEach(SatelliteFilterModel.selectableModes, id: \.self) { mode in
                    Button {
                        if modes.contains(mode) { modes.remove(mode) } else { modes.insert(mode) }
                    } label: {
                        HStack {
                            Text(mode).foregroundStyle(.primary)
                            Spacer()
                            if modes.contains(mode) {
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
                Button {
                    if modes.contains("") { modes.remove("") } else { modes.insert("") }
                } label: {
                    HStack {
                        Text("未知模式").foregroundStyle(.primary)
                        Spacer()
                        if modes.contains("") {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                        }
                    }
                }
            }

            Section("分类") {
                if categoryManager.snapshot.sortedCategories.isEmpty {
                    Text("还没有分类，先到「分类管理」新建。").foregroundStyle(.secondary)
                }
                ForEach(categoryManager.snapshot.sortedCategories) { category in
                    Button {
                        if categoryIds.contains(category.id) {
                            categoryIds.remove(category.id)
                        } else {
                            categoryIds.insert(category.id)
                        }
                    } label: {
                        HStack {
                            Text(category.name).foregroundStyle(.primary)
                            Spacer()
                            if categoryIds.contains(category.id) {
                                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            }

            Section("状态") {
                Toggle("仅当前在境", isOn: $onlyInPass)
                Toggle("仅即将入境", isOn: $onlyUpcoming)
                Toggle("仅有 AMSAT 状态报告", isOn: $onlyAmsat)
            }

            Section {
                Button("应用筛选") { apply() }
                Button("清除全部", role: .destructive) {
                    filter.reset()
                    query = ""
                    modes = []
                    categoryIds = []
                    onlyInPass = false
                    onlyUpcoming = false
                    onlyAmsat = false
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("筛选卫星")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            query = filter.query
            modes = filter.modes
            categoryIds = filter.categoryIds
            onlyInPass = filter.onlyInPass
            onlyUpcoming = filter.onlyUpcoming
            onlyAmsat = filter.onlyAmsat
        }
    }

    private func apply() {
        filter.query = query
        filter.modes = modes
        filter.categoryIds = categoryIds
        filter.onlyInPass = onlyInPass
        filter.onlyUpcoming = onlyUpcoming
        filter.onlyAmsat = onlyAmsat
        dismiss()
    }
}

// MARK: - 卫星详情

/// 卫星详情页：对应 Android SatelliteDetail —— 数值面板 + 收藏 + 提醒 + 去雷达/地图。
struct SatelliteDetailView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    @State private var reminderMessage: String?

    var body: some View {
        // 路由带进来的 norad 是唯一事实来源：进入页面即切换当前卫星，
        // 详情数值（仰角/方位/星下点…）全部读 model.position，切换后自动重算。
        let record = model.satellites.first { $0.norad == norad }
        List {
            Section {
                if let record {
                    LabeledContent("NORAD", value: "\(record.norad)")
                    if !record.lines.isEmpty {
                        LabeledContent("TLE 历元") {
                            Text(Self.epochFormatter.string(from: Self.epoch(fromLine1: record.line1) ?? Date()))
                                .monospacedDigit()
                        }
                    }
                }

                if let position = model.position, model.selectedNorad == norad {
                    LabeledContent("仰角 / 方位") {
                        Text(String(format: "%.1f° / %.1f°", position.elevationDeg, position.azimuthDeg)).monospacedDigit()
                    }
                    LabeledContent("斜距") { Text(String(format: "%.0f km", position.rangeKm)).monospacedDigit() }
                    LabeledContent("多普勒") { Text(String(format: "%+.0f m/s", position.rangeRateMps)).monospacedDigit() }
                    LabeledContent("星下点") { Text(String(format: "%.2f°, %.2f°", position.subLatitudeDeg, position.subLongitudeDeg)).monospacedDigit() }
                    LabeledContent("轨道高度") { Text(String(format: "%.0f km", position.altitudeKm)).monospacedDigit() }
                    LabeledContent("状态") {
                        Text(position.isAboveHorizon ? "在境" : "地平线以下")
                            .foregroundStyle(position.isAboveHorizon ? Color.green : Color.secondary)
                    }
                } else if record != nil {
                    Text("等待位置解算…").foregroundStyle(.secondary)
                } else {
                    Text("TLE 未加载或该星不在当前数据源中").foregroundStyle(.secondary)
                }
            } header: {
                Text(record?.name ?? "NORAD \(norad)")
            }
            Section("卫星信息") {
                if let status = model.amsatStatus(norad: norad) {
                    LabeledContent("AMSAT 状态", value: status)
                }
                let radios = model.transmittersByNorad[norad] ?? []
                if !radios.isEmpty {
                    LabeledContent("转发器", value: "\(radios.filter { $0.isActive }.count) 活跃 / \(radios.count) 总数")
                }
            }

            Section("入口") {
                NavigationLink(value: Route.satelliteRadar(norad: norad)) {
                    Label("雷达图", systemImage: "dot.scope")
                }
                NavigationLink(value: Route.satelliteMap(norad: norad)) {
                    Label("轨道地图", systemImage: "map")
                }
                NavigationLink(value: Route.reminderList) {
                    Label("过境提醒", systemImage: "bell")
                }
            }

            Section {
                Button {
                    model.toggleFavorite(norad)
                } label: {
                    Label(
                        model.favorites.contains(norad) ? "取消收藏" : "收藏该卫星",
                        systemImage: model.favorites.contains(norad) ? "star.fill" : "star"
                    )
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle(record?.name ?? "卫星详情")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            // 路由进入：把当前卫星切到本页 norad，触发位置/轨迹/过境全量重算
            if model.selectedNorad != norad {
                model.selectSatellite(norad: norad)
            }
        }
        .onReceive(model.$selectedNorad) { _ in }
    }

    // TLE line1 的历元：列 19-32（YYDDD.DDDD…），两行制。
    private static let epochFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "yyyy-MM-dd HH:mm"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    private static func epoch(fromLine1 line1: String) -> Date? {
        let chars = Array(line1)
        guard chars.count >= 32 else { return nil }
        let yy = Int(String(chars[18...19])) ?? 0
        let year = yy < 57 ? 2000 + yy : 1900 + yy
        guard let dayOfYear = Double(String(chars[20...31])) else { return nil }
        return Calendar(identifier: .gregorian)
            .date(from: DateComponents(timeZone: TimeZone(identifier: "UTC"), year: year))?
            .addingTimeInterval((dayOfYear - 1) * 86_400)
    }
}

// MARK: - 雷达图

/// 雷达页：对应 Android SatelliteRadar —— 全屏极坐标雷达。
struct SatelliteRadarView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    var body: some View {
        VStack(spacing: 16) {
            if let position = model.position, model.selectedNorad == norad {
                RadarView(current: position, track: model.track)
                    .frame(maxHeight: 360)
                    .padding()
                HStack {
                    Text("仰角 \(String(format: "%.1f°", position.elevationDeg))")
                    Text("方位 \(String(format: "%.1f°", position.azimuthDeg))")
                }
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
            } else {
                Text("等待位置解算…").foregroundStyle(.secondary)
            }
            Spacer()
        }
        .navigationTitle("雷达图")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if model.selectedNorad != norad {
                model.selectSatellite(norad: norad)
            }
        }
    }
}

// MARK: - 过境提醒列表

/// 提醒列表页：对应 Android ReminderList —— 列出当前卫星未来过境并可一键设提醒。
struct ReminderListView: View {

    @ObservedObject var model: TrackerModel
    @State private var scheduledIds: Set<Int64> = []
    @State private var message: String?

    var body: some View {
        List {
            Section {
                if model.passes.isEmpty {
                    Text(model.isSearchingPass ? "正在搜索…" : "48 小时内没有过境")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.passes, id: \.aosMs) { pass in
                        HStack {
                            PassRow(pass: pass, satelliteName: model.selectedSatelliteName, onRemind: {})
                            Spacer()
                            Button {
                                let settings = ReminderSettingsStore.shared
                                let ok = NotificationManager.shared.schedulePassReminder(
                                    satelliteName: model.selectedSatelliteName,
                                    aosMs: pass.aosMs,
                                    leadSeconds: settings.leadSeconds
                                )
                                if ok {
                                    scheduledIds.insert(pass.aosMs)
                                    NotificationManager.shared.requestAuthorizationIfNeeded()
                                    message = "已设提醒（AOS 前 \(settings.leadMinutes) 分钟）"
                                } else {
                                    message = "这次过境来不及提醒"
                                }
                            } label: {
                                Image(systemName: scheduledIds.contains(pass.aosMs) ? "bell.fill" : "bell")
                            }
                            .buttonStyle(.borderless)
                        }
                    }
                }
            } header: {
                Text("当前卫星 · \(model.selectedSatelliteName)")
            } footer: {
                if let message { Text(message).foregroundStyle(Color.accentColor) }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("过境提醒")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    model.searchNextPass()
                } label: {
                    Image(systemName: "arrow.clockwise")
                }
            }
        }
    }
}

// MARK: - 过境行（首页 / 详情 / 提醒列表共用）

/// 单条过境：AOS 时间 + 在境徽标 + 最大仰角/方位，对应 Android 过境列表行。
struct PassRow: View {

    let pass: PassSnapshot
    let satelliteName: String
    let onRemind: () -> Void

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(Self.timeFormatter.string(from: pass.aosMs.date))
                    .font(.headline)
                    .monospacedDigit()
                if pass.isCurrentlyVisible {
                    Text("在境")
                        .font(.caption2)
                        .padding(.horizontal, 6)
                        .padding(.vertical, 2)
                        .background(Color.green.opacity(0.15))
                        .foregroundStyle(.green)
                        .clipShape(Capsule())
                }
                Spacer()
                Button(action: onRemind) {
                    Image(systemName: "bell")
                        .foregroundStyle(Color.accentColor)
                }
                .buttonStyle(.borderless)
                .accessibilityLabel("为这次过境设置提醒")
            }
            Text("LOS \(Self.timeFormatter.string(from: pass.losMs.date)) · 最大仰角 \(String(format: "%.1f°", pass.maxElevationDeg)) · \(pass.durationSeconds / 60) 分 \(pass.durationSeconds % 60) 秒")
                .font(.footnote)
                .foregroundStyle(.secondary)
            Text("方位 \(pass.aosAzimuthDeg)° → \(pass.losAzimuthDeg)°")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .padding(.vertical, 2)
    }
}

private extension Int64 {
    var date: Date { Date(timeIntervalSince1970: Double(self) / 1000.0) }
}

// MARK: - 手动坐标编辑

/// 手动编辑地面站坐标：对应 Android 定位详情里的「手动输入」。
/// 保存后走与自动定位相同的 `updateStation` 链路，分区 / 雷达 / 过境全量重算。
struct StationEditView: View {

    @ObservedObject var model: TrackerModel
    @Environment(\.dismiss) private var dismiss

    @State private var latitudeText: String = ""
    @State private var longitudeText: String = ""
    @State private var altitudeText: String = ""
    @State private var errorMessage: String?

    var body: some View {
        Form {
            Section {
                TextField("纬度（-90 ~ 90）", text: $latitudeText)
                    .keyboardType(.numbersAndPunctuation)
                TextField("经度（-180 ~ 180）", text: $longitudeText)
                    .keyboardType(.numbersAndPunctuation)
                TextField("海拔（米）", text: $altitudeText)
                    .keyboardType(.numbersAndPunctuation)
            } header: {
                Text("地面站坐标")
            } footer: {
                if let errorMessage {
                    Text(errorMessage).foregroundStyle(.red)
                }
            }

            Section {
                Button("保存并重算") { save() }
            }
        }
        .navigationTitle("地面站")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if latitudeText.isEmpty {
                latitudeText = String(format: "%.4f", model.latitude)
                longitudeText = String(format: "%.4f", model.longitude)
                altitudeText = String(format: "%.0f", model.altitudeM)
            }
        }
    }

    private func save() {
        guard let lat = Double(latitudeText), lat >= -90, lat <= 90,
              let lon = Double(longitudeText), lon >= -180, lon <= 180 else {
            errorMessage = "纬度或经度格式不对"
            return
        }
        let altitude = Double(altitudeText) ?? 0
        model.altitudeM = altitude
        model.updateStation(latitude: lat, longitude: lon)
        dismiss()
    }
}

// MARK: - 定位详情

/// 定位详情页：对应 Android LocationDetail —— 坐标展示 + 手动编辑 + 地图。
struct LocationDetailView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider

    var body: some View {
        List {
            Section("当前定位") {
                LabeledContent("来源") { Text(location.statusText) }
                LabeledContent("纬度") { Text(String(format: "%.4f°", model.latitude)).monospacedDigit() }
                LabeledContent("经度") { Text(String(format: "%.4f°", model.longitude)).monospacedDigit() }
                LabeledContent("海拔") { Text(String(format: "%.0f m", model.altitudeM)).monospacedDigit() }
                Button {
                    location.requestLocation()
                } label: {
                    Label("重新定位", systemImage: "location.fill")
                }
            }

            Section("分区") {
                LabeledContent("Maidenhead") { Text(model.zone?.grid ?? "—").monospaced() }
                LabeledContent("CQ 分区", value: zoneText(model.zone?.cqZone))
                LabeledContent("ITU 分区", value: zoneText(model.zone?.ituZone))
            }

            Section {
                NavigationLink {
                    StationEditView(model: model)
                } label: {
                    Label("手动修改坐标", systemImage: "pencil")
                }
            }

            Section {
                Map(coordinateRegion: .constant(MKCoordinateRegion(
                    center: CLLocationCoordinate2D(latitude: model.latitude, longitude: model.longitude),
                    span: MKCoordinateSpan(latitudeDelta: 10, longitudeDelta: 10)
                )))
                .frame(height: 220)
                .clipShape(RoundedRectangle(cornerRadius: 12))
                .listRowInsets(EdgeInsets())
            } header: {
                Text("位置地图（MapKit）")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("定位详情")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func zoneText(_ value: Int32?) -> String {
        guard let value, value > 0 else { return "未知" }
        return "\(value)"
    }
}
