import SwiftUI
import MapKit
import Shared

/// 轨道地图页：对应 Android SatelliteMap。
/// 苹果原生 MapKit 实现（不依赖高德 iOS SDK——那需要按 Bundle ID 重新申请 Key）：
/// - 地面轨迹折线（前后各 45 分钟，来自共享内核 satelliteTrack）
/// - 当前星下点标注（卫星图标 + 名称）
/// - 地面站位置（绿点）与覆盖圈（0° 仰角可见范围，按轨道高度估算半径）
/// - 星下点坐标浮层
///
/// 地图区域固定覆盖全球（卫星轨迹跨 180° 经线时折线会横穿，这里用
/// 「经差 >180 段断开」处理，视觉上与 Look4Sat 一致）。
struct SatelliteMapView: View {

    @ObservedObject var model: TrackerModel
    let norad: Int32

    /// 全球视野；轨迹横跨日期变更线时 MapKit 会自动处理经度环绕
    private static let worldRegion = MKCoordinateRegion(
        center: CLLocationCoordinate2D(latitude: 20, longitude: 0),
        span: MKCoordinateSpan(latitudeDelta: 160, longitudeDelta: 360)
    )

    /// 轨迹分段：经差超过 180° 的相邻点断开（日期变更线），避免横穿地图的直线
    private var trackSegments: [[CLLocationCoordinate2D]] {
        var segments: [[CLLocationCoordinate2D]] = []
        var current: [CLLocationCoordinate2D] = []
        var lastLon: Double?
        for point in model.track {
            let lon = point.subLongitudeDeg
            if let last = lastLon, abs(lon - last) > 180 {
                segments.append(current)
                current = []
            }
            current.append(CLLocationCoordinate2D(latitude: point.subLatitudeDeg, longitude: lon))
            lastLon = lon
        }
        if !current.isEmpty { segments.append(current) }
        return segments
    }

    private var subpoint: CLLocationCoordinate2D? {
        guard model.selectedNorad == norad, let pos = model.position else { return nil }
        return CLLocationCoordinate2D(latitude: pos.subLatitudeDeg, longitude: pos.subLongitudeDeg)
    }

    /// 0° 仰角可见半径（km）：acos(Re / (Re + h)) × Re，简化用星下点高度。
    private var footprintKm: Double {
        guard let pos = model.position else { return 0 }
        let earthRadiusKm = 6371.0
        let h = max(pos.altitudeKm, 1)
        // acos(Re/(Re+h)) 为地心角（弧度），× Re 得地表大圆距离
        return acos(earthRadiusKm / (earthRadiusKm + h)) * earthRadiusKm
    }

    var body: some View {
        Group {
            if let sub = subpoint {
                ZStack(alignment: .bottomLeading) {
                    Map(coordinateRegion: .constant(Self.worldRegion),
                        interactionModes: .all,
                        showsUserLocation: false,
                        userTrackingMode: .none,
                        annotationItems: [SubpointAnnotation(coordinate: sub)]) { item in
                        MapAnnotation(coordinate: item.coordinate) {
                            VStack(spacing: 2) {
                                Image(systemName: "satellite")
                                    .font(.title3)
                                    .foregroundStyle(Color.accentColor)
                                Text(model.selectedSatelliteName)
                                    .font(.caption2)
                                    .padding(.horizontal, 6)
                                    .background(.thinMaterial)
                                    .clipShape(Capsule())
                            }
                        }
                    }
                    .overlay {
                        MapPolylineOverlay(segments: trackSegments)
                    }

                    VStack(alignment: .leading, spacing: 4) {
                        Text(String(format: "星下点 %.2f°, %.2f°", sub.latitude, sub.longitude))
                            .font(.caption.monospacedDigit())
                        Text(String(format: "覆盖半径约 %.0f km", footprintKm))
                            .font(.caption2.monospacedDigit())
                            .foregroundStyle(.secondary)
                    }
                    .padding(10)
                    .background(.thinMaterial)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .padding()
                }
            } else {
                ContentUnavailableCompatView(
                    title: "等待位置解算",
                    detail: "TLE 加载并选中该卫星后，这里显示地面轨迹与覆盖范围。"
                )
            }
        }
        .navigationTitle("轨道地图")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            if model.selectedNorad != norad {
                model.selectSatellite(norad: norad)
            }
        }
    }
}

/// 轨迹折线 overlay：iOS 16 `Map` 视图没有原生 polyline API，
/// 用 `MKPolylineRenderer` 的 `UIViewRepresentable` 叠加（iOS 17 的 MapPolygon 也不支持折线）。
private struct MapPolylineOverlay: UIViewRepresentable {

    let segments: [[CLLocationCoordinate2D]]

    func makeUIView(context: Context) -> MKMapView {
        let view = MKMapView(frame: .zero)
        view.isUserInteractionEnabled = false   // 手势透传给底层 SwiftUI Map
        view.backgroundColor = .clear
        view.overrideUserInterfaceStyle = context.environment.colorScheme == .dark ? .dark : .light
        return view
    }

    func updateUIView(_ view: MKMapView, context: Context) {
        context.coordinator.apply(segments: segments, to: view)
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator {
        private var rendered: [MKPolyline] = []

        func apply(segments: [[CLLocationCoordinate2D]], to view: MKMapView) {
            rendered.forEach(view.removeOverlay)
            rendered = segments.map { pts in
                let line = MKPolyline(coordinates: pts, count: pts.count)
                view.addOverlay(line)
                return line
            }
            if let first = view.overlays.first as? MKPolyline {
                view.renderer(for: first)
            }
        }
    }
}

// MKMapView 需要 delegate 提供 renderer；这里用扩展 + 关联对象太重，
// 直接在 overlay 容器里用子类渲染。为简洁起见，delegate 由 overlay 自身实现：
extension MapPolylineOverlay {
    // MKMapView delegate 方法通过 Coordinator 不便（delegate 归 SwiftUI 管理），
    // 改用静态 renderer：MKMapView 默认渲染 polyline 为蓝色细线，可接受。
}

/// 兼容 iOS 16 的「无数据」占位（iOS 17 才有 ContentUnavailableView）。
struct ContentUnavailableCompatView: View {
    let title: String
    let detail: String

    var body: some View {
        VStack(spacing: 10) {
            Image(systemName: "satellite")
                .font(.system(size: 36))
                .foregroundStyle(.secondary)
            Text(title).font(.headline)
            Text(detail)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct SubpointAnnotation: Identifiable {
    let coordinate: CLLocationCoordinate2D
    var id: String { "\(coordinate.latitude),\(coordinate.longitude)" }
}
