import SwiftUI
import Shared

/// 卫星详情子页：雷达图 + 实时位置数值 + 未来过境（带提醒按钮）。
/// 对应 Android 端 SatelliteDetail / SatelliteRadar 页面；iOS 把两者合并为一页。
struct SatelliteDetailView: View {

    @ObservedObject var model: TrackerModel
    @State private var reminderMessage: String?

    var body: some View {
        List {
            Section {
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
                    Text("请先选择一颗卫星").foregroundStyle(.secondary)
                } else {
                    Text("等待第一次位置解算…").foregroundStyle(.secondary)
                }
            } header: {
                Text("卫星 · \(model.selectedSatelliteName)")
            }

            Section {
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
            } header: {
                Text("未来过境（48 小时内）")
            } footer: {
                if let reminderMessage {
                    Text(reminderMessage).foregroundStyle(Color.accentColor)
                }
            }

            Section {
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
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("卫星详情")
        .navigationBarTitleDisplayMode(.inline)
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

    private func scheduleReminder(for pass: PassSnapshot) {
        NotificationManager.shared.requestAuthorizationIfNeeded()
        let scheduled = NotificationManager.shared.schedulePassReminder(
            satelliteName: model.selectedSatelliteName,
            aosMs: pass.aosMs
        )
        reminderMessage = scheduled
            ? "已安排在 AOS 前 5 分钟提醒。"
            : "这次过境不足 5 分钟就要开始，来不及提醒了。"
    }
}
