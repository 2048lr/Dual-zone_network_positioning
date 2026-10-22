import Foundation
import UserNotifications
import Shared

/// 过境提醒：AOS 前 5 分钟发一条本地通知。
///
/// 用本地通知（`UNUserNotificationCenter`）而不是远程推送：
/// 过境时间完全可以在本地算出来，不需要服务器，也不需要推送证书。
final class NotificationManager {

    static let shared = NotificationManager()

    private let center = UNUserNotificationCenter.current()
    private var didRequestAuthorization = false

    /// 只在用户第一次点「提醒」时申请权限，避免一启动就弹窗。
    func requestAuthorizationIfNeeded() {
        guard !didRequestAuthorization else { return }
        didRequestAuthorization = true
        center.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// 为一次过境安排提醒；AOS 已过（或不足提前量）时忽略。
    @discardableResult
    func schedulePassReminder(
        satelliteName: String,
        aosMs: Int64,
        leadSeconds: TimeInterval = 300
    ) -> Bool {
        let aosDate = Date(timeIntervalSince1970: Double(aosMs) / 1000.0)
        let fireDate = aosDate.addingTimeInterval(-leadSeconds)
        guard fireDate > Date() else { return false }

        let content = UNMutableNotificationContent()
        content.title = "\(satelliteName) 即将过境"
        content.body = "约 \(Int(leadSeconds / 60)) 分钟后 AOS，打开 HamKit 查看方位与仰角。"
        content.sound = .default

        let trigger = UNTimeIntervalNotificationTrigger(
            timeInterval: max(1, fireDate.timeIntervalSinceNow),
            repeats: false
        )
        let request = UNNotificationRequest(
            identifier: "pass-\(aosMs)",
            content: content,
            trigger: trigger
        )
        center.add(request)
        return true
    }

    /// 清掉所有过境提醒（关闭提醒、改提前量、重新排期前都会先调它）。
    func cancelPassReminders() {
        center.getPendingNotificationRequests { [weak self] requests in
            let identifiers = requests.map(\.identifier).filter { $0.hasPrefix("pass-") }
            guard !identifiers.isEmpty else { return }
            self?.center.removePendingNotificationRequests(withIdentifiers: identifiers)
        }
    }
}
