import SwiftUI
import Shared

/// iOS 端根视图：底部 Tab（首页 / 工具 / 设置），对应 Android 的
/// 「Home + 功能子页面 + 设置」结构。
///
/// 全局只持有一份 `TrackerModel` 与 `LocationProvider`，三个 Tab 共享，
/// 避免 TLE 重复拉取、定位重复请求（Android 端由 MainViewModel 承担同样职责）。
struct AppRootView: View {

    @StateObject private var model = TrackerModel()
    @StateObject private var location = LocationProvider()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            NavigationStack {
                ContentView(model: model, location: location)
                    .navigationDestination(for: Route.self) { route in
                        RouteDestination(route: route, model: model, location: location)
                    }
            }
            .tabItem {
                Label("首页", systemImage: "house.fill")
            }

            NavigationStack {
                ToolsView(model: model)
                    .navigationDestination(for: Route.self) { route in
                        RouteDestination(route: route, model: model, location: location)
                    }
            }
            .tabItem {
                Label("工具", systemImage: "wrench.and.screwdriver.fill")
            }

            NavigationStack {
                SettingsView(model: model)
                    .navigationDestination(for: Route.self) { route in
                        RouteDestination(route: route, model: model, location: location)
                    }
            }
            .tabItem {
                Label("设置", systemImage: "gearshape.fill")
            }
        }
        .onAppear {
            // 数据层必须在启动时就接上：之前 SatelliteStore.configure 只在
            // 「卫星管理」页的 init 里调用，导致筛选页 / 分类页 / 详情页在首次
            // 进入管理页之前全是空数据——表现就是「必须退出再进一次才看得到」。
            SatelliteStore.shared.configure(model: model)
            model.start()
            // 首次进入主动要一次定位：定位类 App 的默认预期，
            // 也让 CI 冒烟测试能验证 CoreLocation 链路
            location.requestLocation()
        }
        .onReceive(location.$coordinate.dropFirst()) { coordinate in
            guard let coordinate else { return }
            model.updateStation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        }
        .onChange(of: scenePhase) { phase in
            switch phase {
            case .background:
                // 申请后台提醒刷新；音频会话保持激活以尽量延续接收
                BackgroundReminderScheduler.shared.schedule()
            case .active:
                AudioSession.refreshIfNeeded()
                BackgroundReminderScheduler.shared.schedule()
            default:
                break
            }
        }
    }
}

#Preview {
    AppRootView()
}
