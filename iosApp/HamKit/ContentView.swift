import SwiftUI
import Combine
import CoreLocation
import Foundation
import Shared

/// iOS 端首屏：地面站 → 分区定位 → 卫星数据 → 实时位置（雷达） → 未来过境。
///
/// 所有数值都来自共享 Kotlin 内核（`Shared.framework` 的 `HamKitApi`），
/// 与 Android 端调用的是同一份实现。
struct ContentView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider

    @State private var showReminderAlert = false
    @State private var reminderMessage = ""

    var body: some View {
        ScrollViewReader { proxy in
            List {
                stationSection
                zoneSection
                dataSection
                satelliteSection.id("satellite")
                passSection.id("passes")

                if let error = model.errorText {
                    Section {
                        Text(error).foregroundStyle(.red).font(.footnote)
                    }
                }
            }
            .listStyle(.insetGrouped)
            .onAppear {
                // 生命周期由 AppRootView 统一管理；这里只挂 CI 冒烟滚动钩子
                scheduleSmokeScrollIfNeeded(proxy)
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
        .alert("过境提醒", isPresented: $showReminderAlert) {
            Button("好", role: .cancel) {}
        } message: {
            Text(reminderMessage)
        }
    }

    /// CI 冒烟测试专用：带 `-smokeScroll` 启动时，等数据就绪后自动滚到卫星区，
    /// 这样截图能覆盖雷达图与过境信息（图形界面无法用 simctl 模拟手指滑动）。
    private func scheduleSmokeScrollIfNeeded(_ proxy: ScrollViewProxy) {
        let arguments = ProcessInfo.processInfo.arguments
        let target: String?
        if arguments.contains("-smokeScrollPass") {
            target = "passes"
        } else if arguments.contains("-smokeScroll") {
            target = "satellite"
        } else {
            target = nil
        }
        guard let target else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 12) {
            withAnimation(.easeInOut(duration: 0.4)) {
                proxy.scrollTo(target, anchor: .top)
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
                    selectedNorad: model.selectedNorad,
                    favorites: model.favorites,
                    onSelect: { record in
                        model.selectSatellite(norad: record.norad)
                    },
                    onToggleFavorite: { norad in
                        model.toggleFavorite(norad)
                    }
                )
            } label: {
                LabeledContent("当前卫星", value: model.selectedSatelliteName)
            }
            .disabled(model.satellites.isEmpty)

            Button {
                if let norad = model.selectedNorad {
                    model.toggleFavorite(norad)
                }
            } label: {
                Label(
                    model.isSelectedFavorite ? "取消收藏" : "收藏该卫星",
                    systemImage: model.isSelectedFavorite ? "star.fill" : "star"
                )
            }
            .disabled(model.selectedNorad == nil)

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

    // MARK: - 未来过境

    private var passSection: some View {
        Section("未来过境（48 小时内）") {
            if model.isSearchingPass {
                HStack(spacing: 10) {
                    ProgressView()
                    Text("正在用 SGP4 搜索…").foregroundStyle(.secondary)
                }
            } else if model.passes.isEmpty {
                Text("48 小时内没有完整过境（或该卫星对本站不可见）")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(model.passes, id: \.aosMs) { pass in
                    PassRow(
                        pass: pass,
                        satelliteName: model.selectedSatelliteName,
                        onRemind: { scheduleReminder(for: pass) }
                    )
                }
            }
        }
    }

    private func scheduleReminder(for pass: PassSnapshot) {
        NotificationManager.shared.requestAuthorizationIfNeeded()
        let scheduled = NotificationManager.shared.schedulePassReminder(
            satelliteName: model.selectedSatelliteName,
            aosMs: pass.aosMs
        )
        reminderMessage = scheduled
            ? "已安排在 AOS 前 5 分钟提醒（\(Self.timeFormatter.string(from: pass.aosMs.date)) 过境）。"
            : "这次过境不足 5 分钟就要开始，来不及提醒了。"
        showReminderAlert = true
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
    NavigationStack {
        ContentView(model: TrackerModel(), location: LocationProvider())
    }
}
