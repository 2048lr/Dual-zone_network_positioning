import SwiftUI
import Shared

/// 过境提醒设置（对应 Android `reminderSettings`：总开关 + 提前量）。
final class ReminderSettingsStore: ObservableObject {

    static let shared = ReminderSettingsStore()

    static let leadOptions = [5, 10, 15, 30]

    @Published var enabled: Bool {
        didSet { defaults.set(enabled, forKey: "hamkit.reminder.enabled") }
    }
    @Published var leadMinutes: Int {
        didSet { defaults.set(leadMinutes, forKey: "hamkit.reminder.lead_minutes") }
    }

    private let defaults = UserDefaults.standard

    private init() {
        enabled = defaults.object(forKey: "hamkit.reminder.enabled") as? Bool ?? true
        let stored = defaults.object(forKey: "hamkit.reminder.lead_minutes") as? Int
        leadMinutes = stored ?? 5
    }

    var leadSeconds: TimeInterval { TimeInterval(leadMinutes * 60) }
}

/// 「设置」页：照抄 Android Settings 页结构 ——
/// 外观 / 隐私 / 关于 三个分组；iOS 端暂无外观项，先落分组骨架。
struct SettingsView: View {

    @ObservedObject var model: TrackerModel
    @ObservedObject private var reminderSettings = ReminderSettingsStore.shared

    var body: some View {
        List {
            Section("观测") {
                NavigationLink(value: Route.locationDetail) {
                    Label("定位详情", systemImage: "location")
                }
                NavigationLink(value: Route.reminderList) {
                    LabeledContent("过境提醒", value: "本地通知")
                }
            }

            Section("过境提醒") {
                Toggle("启用过境提醒", isOn: $reminderSettings.enabled)
                Picker("提前量", selection: $reminderSettings.leadMinutes) {
                    ForEach(ReminderSettingsStore.leadOptions, id: \.self) { minutes in
                        Text("提前 \(minutes) 分钟").tag(minutes)
                    }
                }
                .disabled(!reminderSettings.enabled)
            }

            Section("外观") {
                NavigationLink(value: Route.colorPalette) {
                    Label("主题配色", systemImage: "paintpalette")
                }
            }

            Section("隐私") {
                NavigationLink(value: Route.privacyPolicy) {
                    Label("隐私政策", systemImage: "hand.raised")
                }
                NavigationLink(value: Route.permissions) {
                    Label("权限管理", systemImage: "lock.shield")
                }
            }

            Section("数据源") {
                LabeledContent("TLE 源", value: "tle.hamkit.click（amateur + satnogs + active）")
                LabeledContent("轨道目录", value: "\(model.satellites.count) 颗")
                LabeledContent("业余卫星", value: "\(model.amateurSatellites.count) 颗")
                LabeledContent("更新于", value: model.lastUpdatedText)
                Button {
                    model.loadSatellites(force: true)
                } label: {
                    Label("立即刷新 TLE", systemImage: "arrow.triangle.2.circlepath")
                }
                .disabled(model.isLoadingSatellites)
            }

            Section("关于") {
                NavigationLink(value: Route.about) {
                    Label("关于 HamKit", systemImage: "info.circle")
                }
                LabeledContent("内核版本", value: HamKitApi.shared.version())
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("设置")
        .navigationBarTitleDisplayMode(.inline)
        .onChange(of: reminderSettings.enabled) { _ in
            model.applyReminderSettings()
        }
        .onChange(of: reminderSettings.leadMinutes) { _ in
            model.applyReminderSettings()
        }
        // 路由目标由 AppRootView 统一注册，避免同一 stack 重复注册 for: Route.self
    }
}

/// 「关于」页：对应 Android About 页。
struct AboutView: View {
    var body: some View {
        List {
            Section {
                LabeledContent("应用", value: "HamKit iOS")
                LabeledContent("版本", value: "0.2.0")
                LabeledContent("内核版本", value: HamKitApi.shared.version())
            }
            Section("项目") {
                LabeledContent("定位", value: "业余无线电卫星观测工具")
                LabeledContent("内核", value: "Kotlin Multiplatform（与 Android 同源）")
                LabeledContent("开源", value: "github.com/fuxue-linkong/HamKit")
            }
            Section("说明") {
                Text("iOS 端为 SwiftUI 壳，业务计算全部由共享内核提供：分区定位、SGP4/SDP4 过境计算、多普勒。")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 「隐私政策」页：对应 Android PrivacyPolicy 页（离线全文）。
struct PrivacyPolicyView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("HamKit 隐私政策（iOS）")
                    .font(.title3.weight(.semibold))
                Text("""
HamKit iOS 端不收集、不上传任何个人数据：

• 定位：仅用于本地计算卫星过境，不离开设备；
• TLE 数据：从 CelesTrak / tle.hamkit.click 匿名拉取，不含用户身份；
• 通知：过境提醒由系统本地通知发出，无服务器参与；
• 无广告、无统计 SDK、无第三方追踪。

Android 端地图/天气使用高德服务，iOS 端当前未接入地图 SDK，因此不涉及高德数据上传。
""")
                .font(.callout)
                .foregroundStyle(.secondary)
            }
            .padding()
        }
        .navigationTitle("隐私政策")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 「权限管理」页：对应 Android Permissions 页；iOS 权限由系统设置管理，这里展示状态并给跳转。
struct PermissionsView: View {

    @ObservedObject var location: LocationProvider

    var body: some View {
        List {
            Section {
                LabeledContent("定位", value: location.statusText)
                Button("打开系统设置") {
                    if let url = URL(string: UIApplication.openSettingsURLString) {
                        UIApplication.shared.open(url)
                    }
                }
            } header: {
                Text("权限状态")
            } footer: {
                Text("iOS 权限只能到系统设置里开关，HamKit 不存储这些状态。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("权限管理")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// 「主题配色」页：对应 Android ColorPalette 页；iOS 端跟随系统深浅色，先落说明页。
struct ColorPaletteView: View {
    var body: some View {
        List {
            Section {
                LabeledContent("外观", value: "跟随系统")
                LabeledContent("主题色", value: "系统强调色")
            } footer: {
                Text("Android 端的自定义配色（Miuix 主题）尚未移植到 iOS；届时会在这里提供选择。")
            }
        }
        .listStyle(.insetGrouped)
        .navigationTitle("主题配色")
        .navigationBarTitleDisplayMode(.inline)
    }
}
