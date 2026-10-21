import SwiftUI
import Combine
import CoreLocation
import Foundation
import Shared

/// iOS 端首屏：地面站 → 分区定位 → 卫星数据 → 实时位置（雷达） → 下一次过境。
///
/// 所有数值都来自共享 Kotlin 内核（`Shared.framework` 的 `HamKitApi`），
/// 与 Android 端调用的是同一份实现。
struct ContentView: View {

    @StateObject private var model = TrackerModel()
    @StateObject private var location = LocationProvider()

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    stationSection
                    zoneSection
                    dataSection
                    satelliteSection.id("satellite")
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
                .onAppear {
                    model.start()
                    // 首次进入主动要一次定位：定位类 App 的默认预期，
                    // 也让 CI 冒烟测试能验证 CoreLocation 链路
                    location.requestLocation()
                    scheduleSmokeScrollIfNeeded(proxy)
                }
                .onDisappear { model.stop() }
                .onReceive(location.$coordinate.dropFirst()) { coordinate in
                    guard let coordinate else { return }
                    model.updateStation(latitude: coordinate.latitude, longitude: coordinate.longitude)
                }
            }
            .navigationTitle("HamKit")
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button {
                        model.refreshTrack()
                        model.searchNextPass()
                    } label: {
                        Image(systemName: "arrow.clockwise")
                    }
                    .disabled(model.isSearchingPass)
                }
            }
        }
    }

    /// CI 冒烟测试专用：带 `-smokeScroll` 启动时，等数据就绪后自动滚到卫星区，
    /// 这样截图能覆盖雷达图与过境信息（图形界面无法用 simctl 模拟手指滑动）。
    private func scheduleSmokeScrollIfNeeded(_ proxy: ScrollViewProxy) {
        guard ProcessInfo.processInfo.arguments.contains("-smokeScroll") else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) {
            withAnimation(.easeInOut(duration: 0.4)) {
                proxy.scrollTo("satellite", anchor: .top)
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

            NavigationLink {
                SatelliteListView(
                    satellites: model.satellites,
                    selectedNorad: model.selectedNorad
                ) { record in
                    model.selectSatellite(norad: record.norad)
                }
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
                RadarView(current: position, track: model.track)
                    .frame(height: 240)
                    .padding(.vertical, 6)

                LabeledContent("仰角 / 方位") {
                    Text(String(format: "%.1f° / %.1f°", position.elevationDeg, position.azimuthDeg))
                        .monospacedDigit()
                }
                LabeledContent("斜距") {
                    Text(String(format: "%.0f km", position.rangeKm)).monospacedDigit()
                }
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

#Preview {
    ContentView()
}
