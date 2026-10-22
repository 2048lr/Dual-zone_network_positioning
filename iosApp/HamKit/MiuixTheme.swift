import SwiftUI

/// Miuix（MIUI / HyperOS 设计语言）主题，对齐 Android 端的 `MiuixTheme`。
///
/// Android 侧用的是 `top.yukonga.miuix.kmp` 的 `ThemeController`（Monet 动态取色），
/// iOS 没有对应的取色器，所以这里固化一套 Miuix 语义色：
/// - 页面底色与卡片分层（浅色 `#F7F7F7` / 白色卡片；深色纯黑 / `#1A1A1A` 卡片）；
/// - 主色是 MIUI 蓝 `#3482FF`（深色下提亮到 `#4C9BFF`，保证对比度）；
/// - 文字三级：正文 / 次要 / 三级，以及分隔线、填充色；
/// - 圆角统一 16（大卡片）/ 10（小控件），全部用 continuous 圆角。
///
/// 用法：
/// ```swift
/// .background(MiuixTheme.background)
/// card.miuixCard()
/// ```
enum MiuixTheme {

    // MARK: - 颜色（自动跟随系统深浅色）

    static let background = dynamic(light: 0xF7F7F7, dark: 0x000000)
    static let card = dynamic(light: 0xFFFFFF, dark: 0x1A1A1A)
    static let cardElevated = dynamic(light: 0xFFFFFF, dark: 0x242424)
    static let fill = dynamic(light: 0xF0F0F0, dark: 0x2A2A2A)

    static let primary = dynamic(light: 0x3482FF, dark: 0x4C9BFF)
    static let onPrimary = dynamic(light: 0xFFFFFF, dark: 0xFFFFFF)

    static let text = dynamic(light: 0x000000, dark: 0xFFFFFF)
    static let textSecondary = dynamic(light: 0x808080, dark: 0x9A9A9A)
    static let textTertiary = dynamic(light: 0xA6A6A6, dark: 0x6E6E6E)

    static let divider = dynamic(light: 0xE6E6E6, dark: 0x2E2E2E)
    static let success = dynamic(light: 0x2BA471, dark: 0x4CC38A)
    static let danger = dynamic(light: 0xE5484D, dark: 0xFF6369)

    // MARK: - 尺寸

    /// 大卡片圆角
    static let radius: CGFloat = 16
    /// 小控件圆角（chip / 徽章）
    static let smallRadius: CGFloat = 10
    static let cardPadding: CGFloat = 16

    // MARK: - 工具

    /// 由一个浅色 + 一个深色十六进制值构造动态色。
    static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { trait in
            trait.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
        })
    }
}

extension UIColor {
    /// 0xRRGGBB → UIColor（alpha 固定 1）。
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green: CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hex & 0xFF) / 255.0,
            alpha: 1.0
        )
    }
}

/// Miuix 卡片：`MiuixTheme.card` 底 + 连续圆角 + 标准内边距。
struct MiuixCardModifier: ViewModifier {

    var padding: CGFloat = MiuixTheme.cardPadding
    var radius: CGFloat = MiuixTheme.radius
    var background: Color = MiuixTheme.card

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(background)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

extension View {

    /// 套一层 Miuix 卡片外观。
    func miuixCard(
        padding: CGFloat = MiuixTheme.cardPadding,
        radius: CGFloat = MiuixTheme.radius,
        background: Color = MiuixTheme.card
    ) -> some View {
        modifier(MiuixCardModifier(padding: padding, radius: radius, background: background))
    }

    /// 页面级外观：Miuix 底色 + 全局主色（导航栏返回箭头、按钮、链接都跟着变）。
    func miuixScreen() -> some View {
        self
            .tint(MiuixTheme.primary)
            .background(MiuixTheme.background)
    }
}
