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
                    StationEditView(model: model)
                }

                EntryCard(title: "CW 练习", summary: "自由练习 / 教程 / 摩斯电码编解码（规划中）", systemImage: "dot.radiowaves.left.and.right") {
                    PlaceholderView(title: "CW 练习", detail: "iOS 端 CW 音频链路规划中，内核侧摩斯电码能力已就绪。")
                }
                EntryCard(title: "APRS", summary: "业余无线电位置报告（规划中）", systemImage: "dot.circle.and.cursorlines.rectangle") {
                    PlaceholderView(title: "APRS", detail: "APRS iGate / 地图功能规划中。")
                }
                EntryCard(title: "FT8", summary: "数字模式编解码与通联（规划中）", systemImage: "waveform.path.ecg") {
                    PlaceholderView(title: "FT8", detail: "FT8 编解码已由共享内核实现，iOS 音频链路规划中。")
                }
                EntryCard(title: "SSTV", summary: "慢扫描电视接收与解码（规划中）", systemImage: "photo.on.rectangle.angled") {
                    PlaceholderView(title: "SSTV", detail: "SSTV 解码依赖跨帧音频采集，iOS 后台音频链路规划中。")
                }
                EntryCard(title: "QSO 日志", summary: "通联记录与 ADIF 导出（规划中）", systemImage: "book.closed") {
                    PlaceholderView(title: "QSO 日志", detail: "通联日志功能规划中。")
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 12)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle("HamKit")
    }
}

// MARK: - 时间卡

/// 时间 + 日期 + UTC + 每日一言，对应 Android `HomeHeaderMiuix` 的时间卡。
/// 时钟每秒刷新只在本组件内部持有，避免整个首页跟着每秒重建。
private struct HomeTimeCard: View {

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
            Text("天气接入规划中 · 每日一言规划中")
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
                    Text("管理")
                        .font(.subheadline)
                }
            }

            if model.isSearchingPass && model.passes.isEmpty {
                Text("正在计算…").foregroundStyle(.secondary)
            } else if model.passes.isEmpty {
                Text("48 小时内暂无过境").foregroundStyle(.secondary)
            } else {
                ForEach(model.passes.prefix(5), id: \.aosMs) { pass in
                    HStack(alignment: .firstTextBaseline) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(model.selectedSatelliteName)
                                .font(.subheadline.weight(.medium))
                            Text(pass.isCurrentlyVisible ? "在境" : "即将过境")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        VStack(alignment: .trailing, spacing: 2) {
                            Text("AOS " + Self.timeFormatter.string(from: pass.aosMs.date))
                                .font(.caption)
                                .monospacedDigit()
                            Text("最大仰角 \(String(format: "%.0f", pass.maxElevationDeg))°")
                                .font(.caption)
                                .monospacedDigit()
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            NavigationLink {
                SatelliteDetailView(model: model)
            } label: {
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

/// 尚未实现功能的占位子页。
struct PlaceholderView: View {

    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 12) {
            Image(systemName: "hammer")
                .font(.system(size: 40))
                .foregroundStyle(.secondary)
            Text(title).font(.title3.weight(.semibold))
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Color(.systemGroupedBackground))
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
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
