import SwiftUI
import Shared

/// iOS 端根视图：底部三个 Tab（首页 / 工具 / 设置），对应 Android 端的
/// 「Home + 功能子页面 + 设置」结构。
///
/// 全局只持有一份 `TrackerModel` 与 `LocationProvider`，三个 Tab 共享，
/// 避免 TLE 重复拉取、定位重复请求（Android 端由 MainViewModel 承担同样职责）。
struct AppRootView: View {

    @StateObject private var model = TrackerModel()
    @StateObject private var location = LocationProvider()

    var body: some View {
        TabView {
            NavigationStack {
                ContentView(model: model, location: location)
            }
            .tabItem {
                Label("首页", systemImage: "house.fill")
            }

            ToolsView(model: model)
                .tabItem {
                    Label("工具", systemImage: "wrench.and.screwdriver.fill")
                }

            SettingsView(model: model)
                .tabItem {
                    Label("设置", systemImage: "gearshape.fill")
                }
        }
        .onAppear {
            model.start()
            // 首次进入主动要一次定位：定位类 App 的默认预期，
            // 也让 CI 冒烟测试能验证 CoreLocation 链路
            location.requestLocation()
        }
        .onReceive(location.$coordinate.dropFirst()) { coordinate in
            guard let coordinate else { return }
            model.updateStation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        }
    }
}

#Preview {
    AppRootView()
}
