# iosApp —— HamKit iOS 壳工程

这里是 HamKit 的 iOS 外壳（SwiftUI），业务计算全部调用仓库根目录的 KMP 模块
`:shared`（`Shared.framework`），Swift 侧不重复实现任何定位 / 轨道算法。

> 现状：**可运行的演示壳**，已实现「分区定位 + TLE 网络拉取 + 卫星列表页 + Look4Sat 风格雷达 + 实时位置 + 下一次过境」。
> TLE 与 Android `SatelliteDataSource` 同源：`tle.hamkit.click` 的
> `amateur.3le` + `satnogs.3le` + `active.3le`（1.6 万+ 颗）按 NORAD 合并去重，
> 失败自动回落到 CelesTrak 官方；网络全失败时使用内置 ISS TLE 兜底。
> 「附近过境」只对业余子集（amateur + satnogs）预测，全量目录用于卫星管理与筛选。
> 尚未接入：AMSAT 状态、收藏、提醒。
> 完整迁移路线见 [docs/IOS_ADAPTATION.md](../docs/IOS_ADAPTATION.md)。

## 目录

```
iosApp/
├── project.yml            # XcodeGen 工程描述（不提交 .xcodeproj）
├── HamKit/
│   ├── HamKitApp.swift    # @main
│   ├── ContentView.swift  # 首屏：地面站 / 分区 / 卫星数据 / 雷达 / 过境
│   ├── SatelliteListView.swift  # 卫星列表页（可搜索）
│   ├── RadarView.swift    # 极坐标雷达（Canvas 绘制）
│   ├── TrackerModel.swift # 调 Shared.framework 的 ViewModel
│   ├── LocationProvider.swift
│   ├── Networking.swift   # URLSession 实现共享内核的 HttpTransport
│   └── Info.plist         # 含定位权限说明
└── README.md
```

## 前置条件（必须在 macOS 上）

- macOS 14+ / Xcode 16+
- JDK 17+（Gradle 侧，Android Studio 自带的即可）
- [XcodeGen](https://github.com/yonaskolb/XcodeGen)：`brew install xcodegen`
- 首次构建会下载 Kotlin/Native 编译器与 Apple 平台依赖（约 1–2 GB），需要网络

## 构建步骤

```bash
# 1. 生成 Xcode 工程
cd iosApp
xcodegen generate

# 2. 打开
open HamKit.xcodeproj

# 3. 选择模拟器（Apple Silicon）后 Cmd+R
```

> 天气与逆地理编码直接走高德 Web 服务，Key 写死在 `HamKit/AmapKey.swift`
> （与 Android 端同一把；随 App 分发，安全性依赖高德控制台配额/用量监控）。

Xcode 的 Run Script 阶段会自动执行：

```bash
cd "$SRCROOT/.."
./gradlew :shared:embedAndSignAppleFrameworkForXcode
```

也可以先在命令行验证 framework 能否链接（排查 Kotlin/Native 问题更快）：

```bash
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64
# 产物：shared/build/bin/iosSimulatorArm64/debugFramework/Shared.framework
```

真机调试需要：Xcode → Signing & Capabilities → 选择自己的 Team；
`PRODUCT_BUNDLE_IDENTIFIER` 改成你自己的（默认 `click.hamkit.ios`）。

### 权限

`Info.plist` 已声明：

- `NSLocationWhenInUseUsageDescription`：分区定位与卫星过境
- `NSMicrophoneUsageDescription`：CW / FT8 / SSTV / HF Fax 接收解码
- `NSPhotoLibraryAddUsageDescription`：保存解码出的 SSTV / HF Fax 图片

首次使用对应功能时系统会弹窗请求权限；音频只在本机解码，不上传。

## 未签名 IPA（没有开发者账号怎么装）

本项目没有 Apple Developer 账号（$99/年，项目无收入），所以**不提供 App Store 或 TestFlight**。
CI 会额外构建一份**未签名真机 IPA**（artifact：`HamKit-unsigned-ipa-<run_id>`），
结构是标准的 `Payload/HamKit.app`，附带 SHA-256 校验值。

它**不能双击安装**，需要用工具用自己的 Apple ID 重签：

1. 电脑装 [Sideloadly](https://sideloadly.io/) 或 [AltStore](https://altstore.io/)；
2. 用自己的 Apple ID 重签并安装 —— 免费账号签名 **7 天有效**，到期重签一次，App 数据不会丢；
3. 有付费开发者证书的，用它重签一次即可长期使用；
4. TrollStore 只支持特定系统版本，不保证可用。

> 不要使用来历不明的「企业证书」重签：证书被吊销后 App 会直接打不开。
> 未签名 IPA 只从本仓库 Releases / Actions artifact 下载。

## 已知限制

- 只在 Apple Silicon 模拟器与 iOS 16+ 上验证目标；Intel 模拟器（`iosX64`）未配置，
  需要时在 `shared/build.gradle.kts` 里加 `iosX64()`。
- 首次启动若完全没有网络、也没有缓存，会显示内置的 2026-08-09 ISS TLE 示例；
  SGP4 外推几十天后过境时间会有偏差，联网刷新后即消失。
- 尚未实现：后台定位、地图、音频（SSTV/FT8/CW/APRS）、设置页与单位切换。
