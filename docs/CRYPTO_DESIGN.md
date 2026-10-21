# 密钥与敏感信息处理

> 2026-10-19 重构：移除旧的“三碎片 + AES-256-GCM 加密 secrets.dat”方案。
> 该方案在公开仓库/APK 中可完整还原，属于混淆而非加密，不能保护任何真正的秘密。

## 一、结论：客户端不放秘密

任何随 APK 分发的数据都可以被提取。因此：

- **高德 Web 服务 Key（天气/逆地理编码）不再放进 App**；
- 天气改用 [Open-Meteo](https://open-meteo.com/)（无需 Key，按经纬度查询）；
- 逆地理编码地址改用 Android 系统 `Geocoder`（见 `LocationHelper`）；
- 高德只保留 **Android 地图 SDK**。它的 Key 是“客户端标识”，不是秘密，必须在高德控制台按
  **包名 + 签名 SHA-1** 限制，防止被其他应用直接复用；
- 真正需要保密的 Key（例如官网服务端可能用到的第三方 Key）只放服务端环境变量，绝不下发客户端。

## 二、高德 Android SDK Key

### 构建时注入

`app/build.gradle.kts` 通过 `manifestPlaceholders["AMAP_SDK_KEY"]` 写入
`AndroidManifest.xml` 的 `com.amap.api.v2.apikey`：

```kotlin
manifestPlaceholders["AMAP_SDK_KEY"] =
    System.getenv("AMAP_SDK_KEY") ?: localProps.getProperty("amap.sdk.key", "")
```

- 本地开发：在 `local.properties` 设置 `amap.sdk.key=...`（该文件已被 `.gitignore` 忽略）。
- CI/发布：设置环境变量 `AMAP_SDK_KEY`（GitHub Secrets）。
- 不再有 `secrets.dat`、`SecretManager`、`encryptSecrets` 任务，也不再有 `amap.api.key`。

### 高德控制台必须做的事

1. 为 Android Key 绑定 **包名 `com.example.hamkit`** 与 **release 证书 SHA-1**；
2. 不要绑定 debug 证书（debug key 是公开的）；
3. 如果同一个 Key 也被其他应用使用，拆分为独立 Key；
4. 定期检查配额与调用来源；发现异常立即轮换。

> 绑定包名/SHA-1 不能阻止逆向者把你的 Key 用在“同包名同签名”的伪造包上，但能阻止
> 绝大多数直接复用；客户端 Key 的安全边界就在这里，不要期待更强。

## 三、天气与逆地理编码

- **天气**：`WeatherApiService` 调用 Open-Meteo `/v1/forecast`，参数只有经纬度、时区、
  预报天数；不需要 Key。
- **地址**：`LocationHelper.getAddress()` 使用 Android `Geocoder`；不可用时返回空字符串，
  不影响分区/网格/天气。
- Open-Meteo 数据按 CC BY 4.0 提供；发布/展示时应保留来源说明（App 关于页/README 均可）。

## 四、签名密钥（Release）

Release 签名密钥与密码只通过环境变量注入：

```powershell
$env:KEYSTORE_PATH="...\signing\release.keystore"
$env:KEYSTORE_PASSWORD="..."
$env:KEY_ALIAS="..."
$env:KEY_PASSWORD="..."
```

- `app/build.gradle.kts` 在缺少任一变量时**拒绝构建 release**，不再回退 debug keystore；
- `scripts/release.ps1` 只读环境变量，不再读取 `signing/github-secrets.txt`；
- CI 未配置签名 secrets 时跳过 release 构建，不产出 debug 签名包；
- 不要把 keystore/密码提交到 git；不要把密码写在 keystore 同目录的文本文件里。

## 五、迁移清单

如果你之前已经发布过含 `secrets.dat` 的版本：

1. **可以继续使用现有的 Android SDK Key**：它本来就是客户端标识，不必为了本次改造生成新 Key；
2. 但必须在高德控制台按 **包名 + release 签名 SHA-1** 限制，并定期检查配额/调用来源；
3. 旧的 Web 服务 Key 已不再被 App 使用（天气改 Open-Meteo、逆地理编码改系统 Geocoder），
   不需要为了客户端恢复它；如果它曾在公开仓库出现，建议轮换，或至少确认它没有被其他服务使用；
4. 用 `git filter-repo`/BFG 清理历史中的 `secrets.dat`、旧文档明文 Key 等内容；
   如果坚持沿用旧 SDK Key，请接受它已进入历史的事实，并用包名/SHA-1 + 配额监控降低风险；
5. 重新构建 release（需要 `KEYSTORE_*`；`AMAP_SDK_KEY` 可填现有 Key）。

## 六、规则

> **能在客户端拿到的 Key，就不是秘密。**
> 需要秘密的第三方 API：要么服务端代理，要么换无 Key/可公开的替代方案，要么去掉该功能。

相关文件：

- `app/build.gradle.kts`：`AMAP_SDK_KEY` 注入、release 签名门槛
- `app/src/main/java/com/example/hamkit/data/weather/WeatherApiService.kt`：Open-Meteo
- `app/src/main/java/com/example/hamkit/data/location/LocationHelper.kt`：系统 Geocoder
- `app/src/main/java/com/example/hamkit/data/privacy/AmapPrivacyInitializer.kt`：高德隐私合规初始化
- `scripts/release.ps1`、`.github/workflows/ci.yml`：签名/发布
