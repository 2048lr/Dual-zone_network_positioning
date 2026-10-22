import SwiftUI
import Combine
import CoreLocation
import Foundation
import Shared

/// iOS 端首页：照抄 Android `HomeMiuix.kt` 的卡片结构 ——
/// 时间卡（本地时间大字 + UTC + 每日一言）→ 附近过境卫星卡（top5 + 管理）→
/// 定位详情 / CW / APRS / FT8 / SSTV / QSO 入口卡。
///
/// 与 Android 的差异（均为 iOS 端尚未实现的能力，保留占位入口）：
/// - 天气卡：Android 走高德 Web 服务，iOS 内核未接入，显示「规划中」占位；
/// - 雷达图移到「卫星详情」子页（Android 在 SatelliteDetail 页展示）。
struct ContentView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject var location: LocationProvider

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                HomeTimeCard()

                NearbyPassesCard(model: model)

                EntryCard(
                    title: "定位详情",
                    summary: "定位状态与地面站坐标",
                    systemImage: "location.fill"
                ) {
                    LocationDetailView(model: model, location: location)
                }

                EntryCard(title: "CW 练习", summary: "自由练习 / 教程 / 摩斯电码编解码", systemImage: "dot.radiowaves.left.and.right") {
                    CwPracticeHomeView()
                }
                EntryCard(title: "APRS", summary: "业余无线电位置报告", systemImage: "dot.circle.and.cursorlines.rectangle") {
                    AprsMainView()
                }
                EntryCard(title: "FT8", summary: "数字模式编解码与通联", systemImage: "waveform.path.ecg") {
                    Ft8MainView()
                }
                EntryCard(title: "SSTV", summary: "慢扫描电视接收与解码", systemImage: "photo.on.rectangle.angled") {
                    SstvMainView(presetModeName: nil, autoStart: false)
                }
                EntryCard(title: "QSO 日志", summary: "通联记录与 ADIF 导出", systemImage: "book.closed") {
                    QsoLogListView()
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("HamKit")
        .onAppear {
            WeatherStore.shared.refresh(latitude: model.latitude, longitude: model.longitude)
        }
        .onReceive(model.$latitude.dropFirst()) { _ in
            WeatherStore.shared.refresh(latitude: model.latitude, longitude: model.longitude)
        }
    }
}

// MARK: - 时间卡

/// 时间 + 日期 + UTC + 每日一言，对应 Android `HomeHeaderMiuix` 的时间卡。
/// 时钟每秒刷新只在本组件内部持有，避免整个首页跟着每秒重建。
private struct HomeTimeCard: View {

    @ObservedObject private var weather = WeatherStore.shared
    @State private var now = Date()
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(Self.timeFormatter.string(from: now))
                    .font(.system(size: 34, weight: .semibold, design: .rounded))
                    .monospacedDigit()
                Spacer()
                Text(Self.dateFormatter.string(from: now))
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
            Text(Self.utcFormatter.string(from: now) + " UTC")
                .font(.footnote)
                .monospacedDigit()
                .foregroundStyle(.secondary)
            Text(weather.summary)
                .font(.caption)
                .foregroundStyle(.secondary)
            Text(DailyQuote.today())
                .font(.caption2)
                .foregroundStyle(.tertiary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .onReceive(timer) { now = $0 }
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()
    private static let utcFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm"
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()
    private static let dateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "EEE\nyyyy-MM-dd"
        f.dateFormat = "EEE  yyyy-MM-dd"
        return f
    }()
}

// MARK: - 附近过境卫星卡

/// 附近过境卫星 top5 + 「管理」入口，对应 Android `SatelliteListCardMiuix`。
private struct NearbyPassesCard: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("附近过境卫星")
                    .font(.headline)
                Spacer()
                NavigationLink(value: Route.satelliteManagement) {
                    Text("管理")
                        .font(.subheadline)
                }
            }

            if model.isSearchingPass && model.nearbyPasses.isEmpty {
                Text("正在计算…").foregroundStyle(.secondary)
            } else if model.nearbyPasses.isEmpty {
                Text("48 小时内暂无过境").foregroundStyle(.secondary)
            } else {
                ForEach(Array(model.nearbyPasses.prefix(5).enumerated()), id: \.element.norad) { _, item in
                    NavigationLink(value: Route.satelliteDetail(norad: Int32(item.norad))) {
                        HStack(alignment: .firstTextBaseline) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.name)
                                    .font(.subheadline.weight(.medium))
                                Text(item.pass.isCurrentlyVisible ? "在境" : "即将过境")
                                    .font(.caption)
                                    .foregroundStyle(item.pass.isCurrentlyVisible ? Color.green : Color.secondary)
                            }
                            Spacer()
                            VStack(alignment: .trailing, spacing: 2) {
                                Text("AOS " + Self.timeFormatter.string(from: item.pass.aosMs.date))
                                    .font(.caption)
                                    .monospacedDigit()
                                Text("最大仰角 \(String(format: "%.0f", item.pass.maxElevationDeg))°")
                                    .font(.caption)
                                    .monospacedDigit()
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }
            }

            NavigationLink(value: Route.satelliteDetail(norad: model.selectedNorad ?? 0)) {
                Text("查看雷达与详情")
                    .font(.subheadline)
            }
        }
        .padding(16)
        .background(Color(.secondarySystemGroupedBackground))
        .clipShape(RoundedRectangle(cornerRadius: 16))
    }

    private static let timeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "MM-dd HH:mm"
        return f
    }()
}

// MARK: - 通用入口卡

/// 功能入口卡，对应 Android `BasicComponent` 风格：标题 + 摘要 + 右箭头。
struct EntryCard<Destination: View>: View {

    let title: String
    let summary: String
    let systemImage: String
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        NavigationLink {
            destination()
        } label: {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .frame(width: 28)
                    .foregroundStyle(Color.accentColor)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body)
                    Text(summary)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right")
                    .font(.footnote)
                    .foregroundStyle(.tertiary)
            }
            .padding(14)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
    }
}

private extension Int64 {
    var date: Date { Date(timeIntervalSince1970: Double(self) / 1000.0) }
}

#Preview {
    NavigationStack {
        ContentView(model: TrackerModel(), location: LocationProvider())
    }
}
