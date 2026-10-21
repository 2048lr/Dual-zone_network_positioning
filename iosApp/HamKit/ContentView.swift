import SwiftUI
import Combine
import CoreLocation
import Foundation
import Shared

/// iOS 端首屏：地面站 → 分区定位 → 卫星数据 → 实时位置 → 下一次过境。
///
/// 所有数值都来自共享 Kotlin 内核（`Shared.framework` 的 `HamKitApi`），
/// 与 Android 端调用的是同一份实现。
struct ContentView: View {

    @StateObject private var model = TrackerModel()
    @StateObject private var location = LocationProvider()
    @State private var showPicker = false

    var body: some View {
        NavigationStack {
            List {
                stationSection
                zoneSection
                dataSection
                satelliteSection
                passSection

                if let error = model.errorText {
                    Section {
                        Text(error).foregroundStyle(.red).font(.footnote)
                    }
                }

                Section {
                    Text("共享内核 v\(HamKitApi.shared.version())")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("HamKit")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        model.searchNextPass()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(model.isSearchingPass)
                }
            }
            .sheet(isPresented: $showPicker) {
                SatellitePickerView(
                    satellites: model.satellites,
                    selectedNorad: model.selectedNorad
                ) { norad in
                    model.selectSatellite(norad: norad)
                    showPicker = false
                }
            }
            .onAppear { model.start() }
            .onDisappear { model.stop() }
            .onReceive(location.$coordinate.dropFirst()) { coordinate in
                guard let coordinate else { return }
                model.updateStation(latitude: coordinate.latitude, longitude: coordinate.longitude)
            }
        }
    }

    // MARK: - 地面站

    private var stationSection: some View {
        Section("地面站") {
            LabeledContent("纬度") {
                Text(String(format: "%.4f°", model.latitude)).monospacedDigit()
            }
            LabeledContent("经度") {
                Text(String(format: "%.4f°", model.longitude)).monospacedDigit()
            }
            LabeledContent("海拔") {
                Text(String(format: "%.0f m", model.altitudeM)).monospacedDigit()
            }
            Button {
                location.requestLocation()
            } label: {
                Label("使用当前位置", systemImage: "location.fill")
            }
            Text(location.statusText)
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
    }

    // MARK: - 分区定位

    private var zoneSection: some View {
        Section("分区定位") {
            LabeledContent("Maidenhead") {
                Text(model.zone?.grid ?? "—")
                    .monospaced()
                    .fontWeight(.semibold)
            }
            LabeledContent("CQ 分区", value: zoneText(model.zone?.cqZone))
            LabeledContent("ITU 分区", value: zoneText(model.zone?.ituZone))
        }
    }

    // MARK: - 卫星数据

    private var dataSection: some View {
        Section("卫星数据 · CelesTrak") {
            LabeledContent("状态") {
                Text(model.dataStatusText)
                    .font(.footnote)
                    .multilineTextAlignment(.trailing)
            }
            LabeledContent("更新于", value: model.lastUpdatedText)

            Button {
                showPicker = true
            } label: {
                LabeledContent("当前卫星", value: model.selectedSatelliteName)
            }
            .disabled(model.satellites.isEmpty)

            Button {
                model.loadSatellites(force: true)
            } label: {
                Label("刷新 TLE", systemImage: "arrow.triangle.2.circlepath")
            }
            .disabled(model.isLoadingSatellites)

            if model.isLoadingSatellites {
                HStack(spacing: 8) {
                    ProgressView()
                    Text("正在拉取…").foregroundStyle(.secondary).font(.footnote)
                }
            }
        }
    }

    // MARK: - 卫星实时位置

    private var satelliteSection: some View {
        Section("卫星 · \(model.selectedSatelliteName)") {
            if let position = model.position {
                HStack(spacing: 16) {
                    CompassView(azimuthDeg: position.azimuthDeg)
                        .frame(width: 76, height: 76)

                    VStack(alignment: .leading, spacing: 6) {
                        Text(String(format: "仰角 %.1f°", position.elevationDeg))
                            .font(.headline)
                        Text(String(format: "方位 %.1f°", position.azimuthDeg))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        Text(String(format: "斜距 %.0f km", position.rangeKm))
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(.vertical, 4)

                LabeledContent("多普勒径向速度") {
                    Text(String(format: "%+.0f m/s", position.rangeRateMps)).monospacedDigit()
                }
                LabeledContent("星下点") {
                    Text(String(format: "%.2f°, %.2f°", position.subLatitudeDeg, position.subLongitudeDeg))
                        .monospacedDigit()
                }
                LabeledContent("轨道高度") {
                    Text(String(format: "%.0f km", position.altitudeKm)).monospacedDigit()
                }
                LabeledContent("状态") {
                    Text(position.isAboveHorizon ? "在境" : "地平线以下")
                        .foregroundStyle(position.isAboveHorizon ? Color.green : Color.secondary)
                }
            } else if model.selectedSatellite == nil {
                Text("请先在上方选择一颗卫星").foregroundStyle(.secondary)
            } else {
                Text("等待第一次位置解算…").foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - 过境

    private var passSection: some View {
        Section("下一次过境（48 小时内）") {
            if model.isSearchingPass {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("正在用 SGP4 搜索…").foregroundStyle(.secondary)
                }
            } else if let pass = model.nextPass {
                LabeledContent("AOS") { Text(Self.timeFormatter.string(from: pass.aosMs.date)).monospacedDigit() }
                LabeledContent("LOS") { Text(Self.timeFormatter.string(from: pass.losMs.date)).monospacedDigit() }
                LabeledContent("最大仰角") {
                    Text(String(format: "%.1f°", pass.maxElevationDeg)).monospacedDigit()
                }
                LabeledContent("过境时长") {
                    Text("\(pass.durationSeconds / 60) 分 \(pass.durationSeconds % 60) 秒").monospacedDigit()
                }
                LabeledContent("方位") {
                    Text("\(pass.aosAzimuthDeg)° → \(pass.losAzimuthDeg)°").monospacedDigit()
                }
                if pass.isCurrentlyVisible {
                    Text("卫星当前已在境，以上为本次过境的剩余时间")
                        .font(.footnote)
                        .foregroundStyle(.orange)
                }
            } else {
                Text("48 小时内没有完整过境（或该卫星对本站不可见）")
                    .foregroundStyle(.secondary)
            }
        }
    }

    // MARK: - 工具

    private func zoneText(_ value: Int32?) -> String {
        guard let value, value > 0 else { return "未知" }
        return "\(value)"
    }

    private static let timeFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.dateFormat = "MM-dd HH:mm:ss"
        formatter.locale = Locale(identifier: "zh_CN")
        return formatter
    }()
}

private extension Int64 {
    var date: Date { Date(timeIntervalSince1970: Double(self) / 1000.0) }
}

/// 极简罗盘：指针指向卫星方位角（0° = 正北）。
private struct CompassView: View {
    let azimuthDeg: Double

    var body: some View {
        ZStack {
            Circle().strokeBorder(Color.secondary.opacity(0.35), lineWidth: 1)
            Circle().fill(Color.secondary.opacity(0.12))
            Capsule()
                .fill(Color.accentColor)
                .frame(width: 3, height: 30)
                .offset(y: -15)
                .rotationEffect(.degrees(azimuthDeg))
            Circle().fill(Color.accentColor).frame(width: 6, height: 6)
        }
        .accessibilityLabel("方位角 \(Int(azimuthDeg)) 度")
    }
}

/// 卫星选择器：CelesTrak amateur 分组通常有一百多颗，用独立列表比 Menu 稳。
struct SatellitePickerView: View {
    let satellites: [TleRecord]
    let selectedNorad: Int32?
    let onSelect: (Int32) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    private var filtered: [TleRecord] {
        guard !query.isEmpty else { return satellites }
        return satellites.filter {
            $0.name.localizedCaseInsensitiveContains(query)
                || String($0.norad).contains(query)
        }
    }

    var body: some View {
        NavigationStack {
            List(filtered, id: \.norad) { record in
                Button {
                    onSelect(record.norad)
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(record.name).foregroundStyle(.primary)
                            Text("NORAD \(record.norad)")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if record.norad == selectedNorad {
                            Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .buttonStyle(.plain)
            }
            .searchable(text: $query, prompt: "搜索名称或 NORAD")
            .navigationTitle("选择卫星")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
        }
    }
}

#Preview {
    ContentView()
}
