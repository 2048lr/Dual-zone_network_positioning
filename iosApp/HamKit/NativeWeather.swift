import Foundation
import CoreLocation
import WeatherKit

/// Apple 原生天气（WeatherKit）+ 原生逆地理编码（CLGeocoder）。
///
/// WeatherKit 需要付费开发者账号并开启 WeatherKit capability；未授权时
/// `WeatherService` 会抛错，由 `WeatherStore` 回退到高德 Web 服务。
struct NativeWeatherNow {
    let temperatureC: Double
    let condition: String
    let humidityPercent: Int
    let windKph: Double
    let windDirection: String
}

enum NativeWeather {

    static func current(latitude: Double, longitude: Double) async -> NativeWeatherNow? {
        let location = CLLocation(latitude: latitude, longitude: longitude)
        do {
            let weather = try await WeatherService.shared.weather(for: location, including: .current)
            let windKph = weather.wind.speed.converted(to: .kilometersPerHour).value
            let degrees = weather.wind.direction.converted(to: .degrees).value
            return NativeWeatherNow(
                temperatureC: weather.temperature.converted(to: .celsius).value,
                condition: weather.condition.description,
                humidityPercent: Int((weather.humidity * 100).rounded()),
                windKph: windKph,
                windDirection: directionName(degrees)
            )
        } catch {
            return nil
        }
    }

    static func cityName(latitude: Double, longitude: Double) async -> String? {
        let location = CLLocation(latitude: latitude, longitude: longitude)
        do {
            let placemarks = try await CLGeocoder().reverseGeocodeLocation(location)
            let place = placemarks.first
            return place?.locality ?? place?.administrativeArea
        } catch {
            return nil
        }
    }

    private static func directionName(_ degrees: Double) -> String {
        let names = ["北", "东北", "东", "东南", "南", "西南", "西", "西北"]
        let index = Int(((degrees + 22.5) / 45.0).rounded()) % 8
        return names[index]
    }
}
