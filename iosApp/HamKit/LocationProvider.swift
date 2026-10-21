import Foundation
import CoreLocation

/// 极简定位封装：只取「当前坐标」，不做缓存与后台定位。
///
/// 对应 Android 侧 `LocationHelper` 的最小子集。真正的 iOS 版本还应补上：
/// 权限被拒的引导、精度筛选、超时兜底、以及 `CLLocationManager` 的
/// 「定位不可用（模拟器 / 飞行模式）」分支。
final class LocationProvider: NSObject, ObservableObject, CLLocationManagerDelegate {

    @Published private(set) var coordinate: CLLocationCoordinate2D?
    @Published private(set) var statusText: String = "尚未请求"

    private let manager = CLLocationManager()

    override init() {
        super.init()
        manager.delegate = self
        manager.desiredAccuracy = kCLLocationAccuracyHundredMeters
    }

    /// 请求前台定位权限并发起一次定位。
    func requestLocation() {
        switch manager.authorizationStatus {
        case .notDetermined:
            statusText = "等待授权"
            manager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse, .authorizedAlways:
            statusText = "定位中…"
            manager.requestLocation()
        case .denied, .restricted:
            statusText = "定位权限被拒绝，请到「设置 → HamKit → 位置」开启"
        @unknown default:
            statusText = "定位状态未知"
        }
    }

    // MARK: - CLLocationManagerDelegate

    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        switch manager.authorizationStatus {
        case .authorizedWhenInUse, .authorizedAlways:
            statusText = "定位中…"
            manager.requestLocation()
        case .denied, .restricted:
            statusText = "定位权限被拒绝"
        case .notDetermined:
            statusText = "等待授权"
        @unknown default:
            statusText = "定位状态未知"
        }
    }

    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let last = locations.last else { return }
        coordinate = last.coordinate
        statusText = String(format: "%.4f, %.4f（±%.0fm）", last.coordinate.latitude, last.coordinate.longitude, last.horizontalAccuracy)
    }

    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        statusText = "定位失败：\(error.localizedDescription)"
    }
}
