# iosApp —— HamKit iOS 壳工程

这里是 HamKit 的 iOS 外壳（SwiftUI），业务计算全部调用仓库根目录的 KMP 模块
`:shared`（`Shared.framework`），Swift 侧不重复实现任何定位 / 轨道算法。

> 现状：**可运行的演示壳**，已实现「分区定位 + TLE 网络拉取 + 卫星切换 + 实时位置 + 下一次过境」。
> TLE 优先从 `https://tle.hamkit.click/tle/amateur.3le`（与 Android 同源）拉取，
> 失败自动回落到 CelesTrak 官方；网络全失败时使用内置 ISS TLE 兜底。
> 尚未接入：AMSAT 状态、收藏、雷达图、提醒。
> 完整迁移路线见 [docs/IOS_ADAPTATION.md](../docs/IOS_ADAPTATION.md)。

## 目录

```
iosApp/
├── project.yml            # XcodeGen 工程描述（不提交 .xcodeproj）
├── HamKit/
│   ├── HamKitApp.swift    # @main
│   ├── ContentView.swift  # 首屏：地面站 / 分区 / 卫星数据 / 卫星 / 过境
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

## 已知限制

- 只在 Apple Silicon 模拟器与 iOS 16+ 上验证目标；Intel 模拟器（`iosX64`）未配置，
  需要时在 `shared/build.gradle.kts` 里加 `iosX64()`。
- 首次启动若完全没有网络，会显示内置的 2026-08-09 ISS TLE 示例；
  SGP4 外推几十天后过境时间会有偏差，这是预期行为，联网刷新后即消失。
- 尚未实现：定位权限被拒的引导、后台定位、地图、音频（SSTV/FT8/CW）、提醒、设置持久化。
- TLE 只做了内存缓存（6 小时），冷启动仍要联网；落盘缓存属于 P2。
