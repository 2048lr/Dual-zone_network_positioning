import SwiftUI
import Shared

/// 单星详情页：照抄 Android `SatelliteDetailScreen` 的卡片结构 ——
/// 实时大数字 → 过境倒计时 → 多普勒上下行 → 转发器列表 → 日照/星下点/相位 →
/// SSTV 接收入口 → 雷达/地图按钮。
struct SatelliteDetailScreen: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    @State private var now = Date()
    private let ticker = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    private var record: TleRecord? {
        model.satellites.first { $0.norad == norad }
    }

    private var position: PositionSnapshot? {
        model.selectedNorad == norad ? model.position : nil
    }

    private var radios: [Transmitter] {
        model.transmittersByNorad[norad] ?? []
    }

    private var activePass: PassSnapshot? {
        let passes = model.selectedNorad == norad ? model.passes : []
        return passes.first { $0.isCurrentlyVisible } ?? passes.first
    }

    private var eclipse: Bool {
        guard let position else { return false }
        return Self.isEclipsed(
            latitude: position.subLatitudeDeg,
            longitude: position.subLongitudeDeg,
            date: now
        )
    }

    private var supportsSstv: Bool {
        norad == 25544 || radios.contains { $0.mode.localizedCaseInsensitiveContains("SSTV") }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 12) {
                headerCard

                if let position {
                    livePanel(position)
                } else {
                    card {
                        Text("等待位置解算…")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }

                countdownCard
                dopplerCard
                transceiverCard

                // 归类与提醒是独立入口（与 Android 一致）：列表行只负责进详情，
                // 避免同一行两个 NavigationLink 触发双跳转。
                card {
                    NavigationLink(value: Route.satelliteAssignment(norad: norad)) {
                        HStack {
                            Label("归类与提醒", systemImage: "folder")
                                .foregroundStyle(.primary)
                            Spacer()
                            Image(systemName: "chevron.right")
                                .font(.footnote)
                                .foregroundStyle(.tertiary)
                        }
                    }
                    .buttonStyle(.plain)
                }

                card {
                    row("日照状态", eclipse ? "蚀中（地影）" : "日照中",
                        color: eclipse ? .red : .green)
                    if let position {
                        divider
                        row("星下点", String(format: "%.2f°, %.2f°",
                                             position.subLatitudeDeg, position.subLongitudeDeg))
                        divider
                        row("轨道相位", "—")
                    }
                }

                if supportsSstv {
                    sstvCard
                }

                HStack(spacing: 12) {
                    NavigationLink(value: Route.satelliteRadar(norad: norad)) {
                        Label("打开雷达图", systemImage: "dot.scope")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.accentColor)
                            .foregroundStyle(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                    NavigationLink(value: Route.satelliteMap(norad: norad)) {
                        Label("打开地图", systemImage: "map")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 10)
                            .background(Color.accentColor)
                            .foregroundStyle(.white)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                    }
                }
            }
            .padding(12)
        }
        .background(Color(.systemGroupedBackground))
        .navigationTitle(record?.name ?? "卫星详情")
        .navigationBarTitleDisplayMode(.inline)
        .onReceive(ticker) { now = $0 }
        .onAppear {
            if model.selectedNorad != norad {
                model.selectSatellite(norad: norad)
            }
        }
    }

    // MARK: - 卡片

    private var headerCard: some View {
        card {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(record?.name ?? "NORAD \(norad)")
                        .font(.headline)
                    Text(String(format: "#%05d", norad))
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if let status = model.amsatStatus(norad: norad) {
                    Text(status)
                        .font(.caption2)
                        .padding(.horizontal, 7)
                        .padding(.vertical, 3)
                        .background(status.localizedCaseInsensitiveContains("HEARD")
                                    ? Color.green.opacity(0.15)
                                    : Color.secondary.opacity(0.15))
                        .foregroundStyle(status.localizedCaseInsensitiveContains("HEARD")
                                         ? .green : .secondary)
                        .clipShape(Capsule())
                }
                Button {
                    model.toggleFavorite(norad)
                } label: {
                    Image(systemName: model.favorites.contains(norad) ? "star.fill" : "star")
                        .foregroundStyle(model.favorites.contains(norad) ? .yellow : .secondary)
                }
                .buttonStyle(.borderless)
            }
        }
    }

    private func livePanel(_ p: PositionSnapshot) -> some View {
        card {
            HStack(alignment: .top) {
                bigNumber("仰角", String(format: "%.1f°", p.elevationDeg))
                Spacer()
                bigNumber("方位", String(format: "%.1f°", p.azimuthDeg))
            }
            divider
            HStack(alignment: .top) {
                smallNumber("斜距", String(format: "%.0f km", p.rangeKm))
                Spacer()
                smallNumber("径向速度", String(format: "%+.0f m/s", p.rangeRateMps))
                Spacer()
                smallNumber("高度", String(format: "%.0f km", p.altitudeKm))
            }
        }
    }

    private var countdownCard: some View {
        card {
            if let pass = activePass {
                row("过境倒计时", countdownText(pass))
                divider
                row("最大仰角", String(format: "%.0f°", pass.maxElevationDeg))
            } else {
                row("过境倒计时", "48 小时内无过境")
            }
        }
    }

    private var dopplerCard: some View {
        card {
            let doppler = dopplerFrequencies
            row("下行频率", doppler.downlink)
            divider
            row("上行频率", doppler.uplink)
        }
    }

    private var transceiverCard: some View {
        card {
            row("转发器（\(radios.filter { $0.isActive }.count)/\(radios.count) 活跃）", "")
            if radios.isEmpty {
                divider
                Text("暂无转发器数据")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                ForEach(Array(radios.enumerated()), id: \.offset) { _, radio in
                    divider
                    VStack(alignment: .leading, spacing: 3) {
                        HStack {
                            Text((radio.inverted ? "INV: " : "") + (radio.name.isEmpty ? "未命名转发器" : radio.name))
                                .font(.subheadline.weight(.medium))
                            Spacer()
                            Text(radio.status)
                                .font(.caption2)
                                .foregroundStyle(radio.isActive ? .green : .secondary)
                        }
                        Text(Self.frequencyText(radio))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private var sstvCard: some View {
        card {
            row("SSTV 图像传输", "该卫星支持")
            Text(norad == 25544
                 ? "ARISS 活动通常在 145.800 MHz 使用 PD-120，单帧约 126 秒。"
                 : "该卫星的转发器标注了 SSTV 模式，可进入解码器接收图像。")
                .font(.caption)
                .foregroundStyle(.secondary)
            NavigationLink(
                value: Route.sstvMain(
                    presetModeName: norad == 25544 ? "PD_120" : nil,
                    autoStart: false
                )
            ) {
                Text("接收 SSTV 图像")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 9)
                    .background(Color.accentColor)
                    .foregroundStyle(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 9))
            }
        }
    }

    // MARK: - 组件

    private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8, content: content)
            .padding(14)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Color(.secondarySystemGroupedBackground))
            .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var divider: some View {
        Divider()
    }

    private func row(_ label: String, _ value: String, color: Color = .primary) -> some View {
        HStack {
            Text(label).font(.subheadline)
            Spacer()
            Text(value).font(.subheadline.monospacedDigit()).foregroundStyle(color)
        }
    }

    private func bigNumber(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.system(size: 30, weight: .semibold, design: .rounded)).monospacedDigit()
            Text(label).font(.caption).foregroundStyle(.secondary)
        }
    }

    private func smallNumber(_ label: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value).font(.subheadline.weight(.semibold)).monospacedDigit()
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
    }

    private func countdownText(_ pass: PassSnapshot) -> String {
        let target = pass.isCurrentlyVisible ? pass.losMs : pass.aosMs
        let seconds = Int(Double(target) / 1000 - now.timeIntervalSince1970)
        if seconds <= 0 { return pass.isCurrentlyVisible ? "即将出境" : "即将 AOS" }
        let h = seconds / 3600
        let m = (seconds % 3600) / 60
        let s = seconds % 60
        return h > 0 ? "\(h)时\(m)分\(s)秒" : "\(m)分\(s)秒"
    }

    private var dopplerFrequencies: (downlink: String, uplink: String) {
        guard let position, let radio = radios.first(where: { $0.downlinkHz > 0 || $0.uplinkHz > 0 }) else {
            return ("数据不可用", "数据不可用")
        }
        let c = 299_792_458.0
        let v = position.rangeRateMps
        func corrected(_ base: Int64) -> String? {
            guard base > 0 else { return nil }
            let hz = Double(base) - Double(base) * v / c
            return String(format: "%.3f MHz", hz / 1_000_000)
        }
        return (corrected(radio.downlinkHz) ?? "数据不可用",
                corrected(radio.uplinkHz) ?? "数据不可用")
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

    /// 近似地影判定：星下点太阳高度角 < 0 视为在阴影中。
    private static func isEclipsed(latitude: Double, longitude: Double, date: Date) -> Bool {
        let calendar = Calendar(identifier: .gregorian)
        let dayOfYear = calendar.ordinality(of: .day, in: .year, for: date) ?? 1
        let comps = calendar.dateComponents([.hour, .minute, .second], from: date)
        let utcHour = Double(comps.hour ?? 0) + Double(comps.minute ?? 0) / 60
            + Double(comps.second ?? 0) / 3600

        let declination = -23.44 * cos(2 * .pi * Double(dayOfYear + 10) / 365.0) * .pi / 180
        let hourAngle = (utcHour + longitude / 15.0 - 12.0) * 15.0 * .pi / 180
        let lat = latitude * .pi / 180
        let elevation = asin(sin(lat) * sin(declination)
                             + cos(lat) * cos(declination) * cos(hourAngle))
        return elevation < 0
    }
}
