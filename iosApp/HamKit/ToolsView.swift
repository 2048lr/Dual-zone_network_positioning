import SwiftUI
import Shared

/// 「工具」Tab：功能入口列表，对应 Android 端 Home 页的功能区。
/// 每个入口点进独立子页面；尚未实现的功能标「规划中」，不阻塞导航骨架落地。
struct ToolsView: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        NavigationStack {
            List {
                Section("过境观测") {
                    NavigationLink {
                        PassListView(model: model)
                    } label: {
                        Label("过境列表", systemImage: "clock.badge.checkmark")
                    }

                    NavigationLink {
                        FavoritesView(model: model)
                    } label: {
                        Label("收藏卫星", systemImage: "star.fill")
                    }
                    .badge(model.favorites.isEmpty ? 0 : model.favorites.count)
                }

                Section("数据") {
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
                        LabeledContent("卫星列表", value: "\(model.satellites.count)")
                    }

                    NavigationLink {
                        AboutCoreView()
                    } label: {
                        Label("关于内核", systemImage: "cpu")
                    }
                }

                Section {
                    Label("地图 · APRS · SSTV · FT8 · CW 规划中", systemImage: "hammer")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } header: {
                    Text("后续版本")
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("工具")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// 收藏卫星列表：复用共享内核里的 NORAD 过滤逻辑，选中即切到首页雷达。
struct FavoritesView: View {

    @ObservedObject var model: TrackerModel

    private var favoriteRecords: [TleRecord] {
        model.satellites.filter { model.favorites.contains($0.norad) }
    }

    var body: some View {
        List {
            if favoriteRecords.isEmpty {
                Text("还没有收藏卫星；在卫星列表或首页点亮星标即可。")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(favoriteRecords, id: \.norad) { record in
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
                    }
                    .contentShape(Rectangle())
                    .onTapGesture {
                        model.selectSatellite(norad: record.norad)
                    }
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("收藏卫星")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 共享内核信息页：版本与能力说明，替代旧版塞在首页底部的脚注。
struct AboutCoreView: View {
    var body: some View {
        List {
            LabeledContent("内核版本", value: HamKitApi.shared.version())
            Section("说明") {
                Text("业务计算（分区定位、SGP4/SDP4、过境搜索、多普勒）全部由 Kotlin Multiplatform 共享内核提供，Android 与 iOS 调用同一份实现。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("关于内核")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 过境列表子页面：把首页的「未来过境」区块抽成独立页，之后可加「全部卫星」「按提醒筛选」。
struct PassListView: View {

    @ObservedObject var model: TrackerModel
    @State private var reminderMessage: String?

    var body: some View {
        List {
            Section {
                LabeledContent("地面站", value: String(format: "%.3f°, %.3f°", model.latitude, model.longitude))
                LabeledContent("当前卫星", value: model.selectedSatelliteName)
            } header: {
                Text("搜索条件")
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
                            onRemind: {
                                NotificationManager.shared.requestAuthorizationIfNeeded()
                                let scheduled = NotificationManager.shared.schedulePassReminder(
                                    satelliteName: model.selectedSatelliteName,
                                    aosMs: pass.aosMs
                                )
                                reminderMessage = scheduled
                                    ? "已安排在 AOS 前 5 分钟提醒。"
                                    : "这次过境不足 5 分钟就要开始，来不及提醒了。"
                            }
                        )
                    }
                }
            } header: {
                Text("未来 48 小时")
            } footer: {
                if let reminderMessage {
                    Text(reminderMessage)
                        .foregroundStyle(Color.accentColor)
                }
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("过境列表")
        .navigationBarTitleDisplayMode(.inline)
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
    }
}

/// 单条过境：从首页抽出来，两个页面共用，避免渲染逻辑漂移。
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
