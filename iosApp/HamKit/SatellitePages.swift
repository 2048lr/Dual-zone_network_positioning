import SwiftUI
import MapKit
import Shared

/// 卫星链路全部子页：管理（列表）/ 筛选 / 详情 / 雷达 / 轨道地图 / 提醒列表 / 定位详情。
/// 对应 Android 的 satellite、reminder、location 三个 screen 目录。

// MARK: - 卫星管理（列表）

/// 卫星管理页：对应 Android SatelliteManagement —— 完整列表 + 筛选入口 + 收藏分组。
struct SatelliteManagementView: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        List {
            Section {
                NavigationLink(value: Route.satelliteFilter) {
                    Label("筛选卫星", systemImage: "line.3.horizontal.decrease.circle")
                }
            }

            if !model.favorites.isEmpty {
                Section("收藏") {
                    ForEach(model.satellites.filter { model.favorites.contains($0.norad) }, id: \.norad) { record in
                        satelliteRow(record)
                    }
                }
            }

            Section("全部卫星（\(model.satellites.count)）") {
                ForEach(model.satellites, id: \.norad) { record in
                    satelliteRow(record)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("卫星管理")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: .constant(""), prompt: "搜索（去筛选页）")
        .toolbar {
            ToolbarItem(placement: .navigationBarTrailing) {
                Button {
                    model.loadSatellites(force: true)
                } label: {
                    Image(systemName: "arrow.triangle.2.circlepath")
                }
                .disabled(model.isLoadingSatellites)
            }
        }
    }

    private func satelliteRow(_ record: TleRecord) -> some View {
        NavigationLink(value: Route.satelliteDetail(norad: record.norad)) {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text(record.name)
                    Text("NORAD \(record.norad)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if record.norad == model.selectedNorad {
                    Image(systemName: "checkmark.circle.fill")
                        .foregroundStyle(Color.accentColor)
                }
                Image(systemName: model.favorites.contains(record.norad) ? "star.fill" : "star")
                    .foregroundStyle(model.favorites.contains(record.norad) ? Color.yellow : Color.secondary)
            }
        }
        .swipeActions(edge: .trailing) {
            Button {
                model.toggleFavorite(record.norad)
            } label: {
                Label("收藏", systemImage: "star")
            }
            .tint(.yellow)
        }
    }
}

// MARK: - 卫星筛选

/// 卫星筛选页：对应 Android SatelliteFilter —— 按收藏/名称搜索过滤，选中即设为当前卫星。
struct SatelliteFilterView: View {

    @ObservedObject var model: TrackerModel
    @State private var query = ""
    @State private var favoritesOnly = false

    private var filtered: [TleRecord] {
        var list = model.satellites
        if favoritesOnly { list = list.filter { model.favorites.contains($0.norad) } }
        let keyword = query.trimmingCharacters(in: .whitespaces)
        if !keyword.isEmpty {
            list = list.filter {
                $0.name.localizedCaseInsensitiveContains(keyword)
                    || String($0.norad).contains(keyword)
            }
        }
        return list
    }

    var body: some View {
        List {
            Section {
                Toggle("只看收藏", isOn: $favoritesOnly)
            }
            Section("结果（\(filtered.count)）") {
                ForEach(filtered, id: \.norad) { record in
                    Button {
                        model.selectSatellite(norad: record.norad)
                    } label: {
                        HStack {
                            Text(record.name)
                                .foregroundStyle(.primary)
                            Spacer()
                            if record.norad == model.selectedNorad {
                                Image(systemName: "checkmark")
                                    .foregroundStyle(Color.accentColor)
                            }
                        }
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("筛选卫星")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "名称或 NORAD")
    }
}

// MARK: - 卫星详情

/// 卫星详情页：对应 Android SatelliteDetail —— 数值面板 + 收藏 + 提醒 + 去雷达/地图。
struct SatelliteDetailView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    @State private var reminderMessage: String?

    var body: some View {
        List {
            Section {
                if let position = model.position {
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
                } else {
                    Text("等待位置解算…").foregroundStyle(.secondary)
                }
            } header: {
                Text(model.selectedSatelliteName)
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
                    Label(model.isSelectedFavorite ? "取消收藏" : "收藏该卫星",
                          systemImage: model.isSelectedFavorite ? "star.fill" : "star")
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("卫星详情")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - 雷达图

/// 雷达页：对应 Android SatelliteRadar —— 全屏极坐标雷达。
struct SatelliteRadarView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    var body: some View {
        VStack(spacing: 16) {
            if let position = model.position {
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
    }
}

// MARK: - 轨道地图（MapKit 替代高德）

/// 轨道地图页：对应 Android SatelliteMap。iOS 端用 MapKit 展示星下点 + 轨迹；
/// 高德 iOS SDK 需要按 Bundle ID 单独申请 Key，这里先不做。
struct SatelliteMapView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    private var trackCoordinates: [CLLocationCoordinate2D] {
        model.track.map {
            CLLocationCoordinate2D(latitude: $0.subLatitudeDeg, longitude: $0.subLongitudeDeg)
        }
    }

    private var subpoint: CLLocationCoordinate2D? {
        model.position.map {
            CLLocationCoordinate2D(latitude: $0.subLatitudeDeg, longitude: $0.subLongitudeDeg)
        }
    }

    var body: some View {
        Group {
            if let sub = subpoint {
                Map(coordinateRegion: .constant(MKCoordinateRegion(
                    center: sub,
                    span: MKCoordinateSpan(latitudeDelta: 120, longitudeDelta: 160)
                )),
                interactionModes: .all,
                showsUserLocation: false,
                userTrackingMode: .none,
                annotationItems: [SubpointAnnotation(coordinate: sub)]) { item in
                    MapAnnotation(coordinate: item.coordinate) {
                        VStack {
                            Image(systemName: "satellite")
                                .font(.title3)
                            Text(model.selectedSatelliteName)
                                .font(.caption2)
                                .padding(.horizontal, 6)
                                .background(.thinMaterial)
                                .clipShape(Capsule())
                        }
                    }
                }
                .overlay(alignment: .bottomLeading) {
                    Text(String(format: "星下点 %.2f°, %.2f°", sub.latitude, sub.longitude))
                        .font(.caption.monospacedDigit())
                        .padding(8)
                        .background(.thinMaterial)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .padding()
                }
            } else {
                Text("等待位置解算…").foregroundStyle(.secondary)
            }
        }
        .navigationTitle("轨道地图")
        .navigationBarTitleDisplayMode(.inline)
    }
}

private struct SubpointAnnotation: Identifiable {
    let coordinate: CLLocationCoordinate2D
    var id: String { "\(coordinate.latitude),\(coordinate.longitude)" }
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
                                let ok = NotificationManager.shared.schedulePassReminder(
                                    satelliteName: model.selectedSatelliteName,
                                    aosMs: pass.aosMs
                                )
                                if ok {
                                    scheduledIds.insert(pass.aosMs)
                                    NotificationManager.shared.requestAuthorizationIfNeeded()
                                    message = "已设提醒（AOS 前 5 分钟）"
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
