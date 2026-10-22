import SwiftUI
import Shared

/// 「设置」Tab：对应 Android 端 Settings / About / PrivacyPolicy 的入口结构。
/// 目前只读展示；后续把收藏清理、提醒开关、单位切换做成可编辑项。
struct SettingsView: View {

    @ObservedObject var model: TrackerModel

    var body: some View {
        NavigationStack {
            List {
                Section("观测") {
                    NavigationLink {
                        StationEditView(model: model)
                    } label: {
                        Label("地面站坐标", systemImage: "location")
                    }
                    .badge(locationBadge)

                    NavigationLink {
                        FavoritesView(model: model)
                    } label: {
                        LabeledContent("收藏卫星", value: "\(model.favorites.count)")
                    }
                }

                Section("数据源") {
                    LabeledContent("TLE 分组", value: "amateur (CelesTrak)")
                    LabeledContent("更新于", value: model.lastUpdatedText)
                    Button {
                        model.loadSatellites(force: true)
                    } label: {
                        Label("立即刷新 TLE", systemImage: "arrow.triangle.2.circlepath")
                    }
                    .disabled(model.isLoadingSatellites)
                }

                Section("关于") {
                    NavigationLink {
                        AboutCoreView()
                    } label: {
                        Label("关于内核", systemImage: "cpu")
                    }
                    LabeledContent("版本", value: HamKitApi.shared.version())
                }
            }
            .listStyle(.insetGrouped)
            .navigationTitle("设置")
            .navigationBarTitleDisplayMode(.inline)
        }
    }

    private var locationBadge: String {
        model.zone?.grid ?? "—"
    }
}

/// 手动编辑地面站坐标：对应 Android 的定位详情页里「手动输入」能力。
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
