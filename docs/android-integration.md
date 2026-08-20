# Android 端接入说明（YuyanIme）

> 目标：在 [gurecn/YuyanIme](https://github.com/gurecn/YuyanIme)（语燕输入法，基于 Rime 的现代 Android 中文输入法）基础上接入本项目后端，实现：
> ① 输入/粘贴/复制/语音行为上报；② 设备档案注册；③ 个人补全模型同步（后续阶段）。

## 0. 当前实现状态（2026-08）

以下模块已在 `yuyansdk` 中落地，编译通过后即生效：

| 文件 | 职责 |
|------|------|
| `data/collect/DataCollector.kt` | 设备注册、事件队列批量上报（30s/500条）、位置采集（每分钟） |
| `data/collect/ServerConfig.kt` | Base URL 自动选择（模拟器 10.0.2.2 / 真机设置项） |
| `service/ImeService.kt`（埋点） | `commitText` 上屏、`deleteSurroundingText` 删除、位置权限请求、语音输入（`startVoiceInput`） |
| `service/ClipboardHelper.kt`（埋点） | 剪贴板变化（复制）上报 |
| `application/Launcher.kt` | 启动时初始化采集（子线程） |
| `keyboard/*`（语音入口） | 键盘菜单「语音输入」项：`SkbMenuMode.Voice` → `InputView.startVoiceInput()` → `ImeService`（系统 SpeechRecognizer） |

配置项（设置路径：**设置 → 其他**，已接入 UI）：

- `server_url`：「服务器地址」输入项，留空 = 自动选择（模拟器 10.0.2.2）；真机在此填写 WSL 局域网 IP
- `location_tracking_enable`：「位置采集」开关，默认 `true`，关闭立即停止定位监听

---

## 1. 克隆与构建

```bash
cd android/
git clone https://github.com/gurecn/YuyanIme.git
cd YuyanIme
# 重要：yuyansdk 是 submodule，必须初始化，否则模块为空
git submodule update --init --recursive
```

- 要求：Android Studio 最新稳定版；SDK `compileSdk 36`、`minSdk 23`、`targetSdk 36`
- 模块：`:app`（壳应用，applicationId `com.yuyan.pinyin`）、`:yuyansdk`（全部逻辑）
- 构建产物仅支持 `arm64-v8a`（`app/build.gradle` 中 abiFilters 已限制）

### WSL 命令行构建（已验证）

WSL 内已配置完整工具链（`~/android-tools/`：JDK 17.0.20 + SDK 36 + build-tools 34/36 + debug keystore），构建前加载环境：

```bash
source ~/android-tools/env.sh
cd ~/project/shurufa/android/YuyanIme
./gradlew :yuyansdk:assembleDebug --configure-on-demand   # 仅 SDK 库（快）
./gradlew :app:assembleOfflineDebug --configure-on-demand  # 完整 APK
# 产物：app/build/outputs/apk/offline/debug/yuyanIme_*_debug.apk
adb install app/build/outputs/apk/offline/debug/yuyanIme_*_debug.apk
```

已解决的构建问题：

- **镜像**：`dl.google.com` 在本网络 TLS 被阻断，`~/.gradle/init.gradle` 已全局注入阿里云镜像（google/central/gradle-plugin）
- **keystore bug**：`app/build.gradle` 在无 keystore 配置时 `rootProject.file(null)` 崩溃（YuyanIme 原版问题），`env.sh` 通过 `RELEASE_STORE_FILE` 等环境变量注入调试 keystore 绕过；调试包用 `~/android-tools/debug.keystore`（alias `androiddebugkey`，密码 `android`）签名
- **`--configure-on-demand`**：跳过 `:app` 配置时可避免 keystore bug（只构建 yuyansdk 时）

## 2. 后端 API 速览

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/v1/mobile/device` | 注册/更新设备档案 |
| POST | `/api/v1/mobile/session` | 结束会话（可选） |
| POST | `/api/v1/mobile/events/batch` | **核心**：批量上报事件（幂等，≤500 条/次） |
| POST | `/api/v1/mobile/location` | 位置上报（每分钟，服务端去重） |
| GET | `/api/v1/mobile/completions?since=N` | 增量同步补全候选（阶段 2） |
| POST | `/api/v1/mobile/completions/feedback` | 汇报候选展示/接受（阶段 2） |
| GET | `/api/v1/dashboard/locations` | 后台位置轨迹（地图+列表） |

- Base URL（开发）：`http://<WSL 宿主机 IP>:3000`（真机访问 WSL 需用局域网 IP，见第 6 节）
- 服务端按固定 `user_id` 归属数据（当前单用户），无需鉴权

## 3. 第一步：添加网络依赖

`yuyansdk/build.gradle` 已含 `kotlinx-serialization-json`（JSON 序列化直接用）与协程。**无 HTTP 库**，需添加：

```gradle
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

## 4. 第二步：实现上报模块（DataCollector）

新建 `yuyansdk/src/main/java/com/yuyan/imemodule/data/DataCollector.kt`，职责：

1. **设备注册**：应用启动时（`Launcher.onCreate`）上报一次，携带完整设备档案：
   ```kotlin
   val info = DeviceInfo(
       id = deviceUuid(),            // 持久化 UUID（首次生成后存 SharedPreferences）
       name = "我的手机",             // 可让用户自定义
       platform = "android",
       model = Build.MODEL,          // 24031PN0DC
       os_version = Build.VERSION.RELEASE,        // "14"
       app_version = BuildConfig.VERSION_NAME,
       brand = Build.BRAND,                       // Xiaomi
       sdk_int = Build.VERSION.SDK_INT,           // 34
       screen_resolution = getScreenResolution(), // 1220x2712
       locale = Locale.getDefault().toLanguageTag(), // zh-CN
       region = Locale.getDefault().country,      // CN
       hardware = Build.HARDWARE,                 // qcom
       rom_version = Build.DISPLAY,               // HyperOS 1.0.8.0
       ram_mb = (totalMem / 1024 / 1024).toInt(), // 12288
   )
   ```

2. **事件队列**：内存队列 + 定时/定量批量上报（如每 30 秒或攒满 50 条），网络失败保留重试（磁盘持久化可选）。
   每条事件必须带客户端生成的 `id`（UUID），**服务端以此做幂等去重**——重试时复用同一 id。

3. **网络类型**：上报事件时附 `network_type`：
   ```kotlin
   val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
   val networkType = when (cm.activeNetworkInfo?.type) {
       ConnectivityManager.TYPE_WIFI -> "wifi"
       ConnectivityManager.TYPE_ETHERNET -> "ethernet"
       ConnectivityManager.TYPE_MOBILE -> "mobile"
       else -> null
   }
   ```

## 5. 埋点位置（关键）

所有文字上屏最终汇聚到 `ImeService.commitText()`，在此统一埋点即可覆盖候选/符号/常用语等全部输入路径：

| 事件类型 | 埋点位置 | 说明 |
|----------|----------|------|
| `commit` | `ImeService.kt` → `commitText(text)` / `commitText(text, newCursorPosition)` | 候选上屏、符号、常用语等一切文字提交 |
| `compose` | `ImeService.kt` → `setComposingText(text)` | 组字过程（可选，噪音较大） |
| `delete` | `ImeService.kt` → `deleteSurroundingText(length)` | 删除操作 |
| `clipboard_change` | `ClipboardHelper.kt` → `onPrimaryClipChanged()` | 系统剪贴板变化（复制） |
| `paste` | 剪贴板容器（`ClipBoardContainer`）条目点击回调 | 从剪贴板粘贴文字 |
| `paste_inferred` | 监听系统粘贴广播（`ACTION_PASTE`，Android 13+） | 系统级粘贴（可选） |
| `voice` | 语音识别回调（`ImeService.startVoiceInput`，键盘菜单「语音输入」入口） | 语音转文字上屏；`source=voice`，上屏时不再重复记 `commit` |

**图片粘贴约定**：剪贴板中为图片（`primaryClip` item 的 text 为 null、有 URI）时，上报
`paste` 事件且 `text` 为空、`metadata` 携带：

```json
{ "media_type": "image", "image_uri": "content://...", "image_url": "https://..." }
```

服务端据此归类为「图片」类型。

**事件字段示例**：

```json
{
  "id": "3f2a8c5e-1b2d-4e6f-9a0b-123456789abc",
  "device_id": "1f0e2d3c-4b5a-6789-0abc-def012345678",
  "event_type": "commit",
  "text": "明天下午三点开会",
  "package_name": "com.tencent.mm",
  "editor_id": "com.tencent.mm:chat",
  "session_id": "9a8b7c6d-5e4f-3210-fedc-ba9876543210",
  "sequence_no": 42,
  "input_code": "mingtianxiawusandiankaihui",
  "network_type": "wifi",
  "source": "candidate",
  "occurred_at": "2026-08-19T13:30:00+08:00"
}
```

- `occurred_at`：ISO8601 带时区（+08:00），服务端按 UTC 存储、按本地时区展示
- `package_name` / `editor_id`：`EditorInfo.packageName` / `editorInfo.privateImeOptions`（或自定 editor 标识）
- `source`：`candidate`（候选）/ `key`（按键）/ `symbol`（符号）/ `clipboard`（剪贴板）/ `voice`

## 6. 网络配置（Base URL 设计）

WSL 中运行后端时，手机无法访问 `localhost`。Base URL 按「设置页自定义 → 环境自动选择」两级设计：

```kotlin
// DataCollector.kt（或独立 ServerConfig.kt）
object ServerConfig {
    const val DEFAULT_PORT = 3000

    /** 服务端地址：设置页自定义优先，否则按运行环境自动选择 */
    val baseUrl: String by lazy {
        prefs.getString("server_url", null) ?: autoBaseUrl()
    }

    private fun autoBaseUrl(): String {
        // 模拟器（AVD）：10.0.2.2 直通宿主机 Windows → WSL2 localhost 自动转发 → 后端
        val isEmulator = Build.FINGERPRINT.contains("generic")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
        return if (isEmulator) "http://10.0.2.2:$DEFAULT_PORT"
        else "http://<WSL局域网IP>:$DEFAULT_PORT"  // 真机：见下
    }
}
```

- **模拟器调试**：`10.0.2.2` = 宿主机 Windows 的 loopback，WSL2 默认 localhost 转发会把 `Windows:3000` 转给 `WSL:3000`，**无需任何配置**
- **真机调试**：手机与电脑同一 Wi-Fi，用 WSL 局域网 IP（`hostname -I` 查询），默认值建议在设置页填写后持久化
- 建议在设置页新增「服务器地址」输入项（默认留空 = 自动选择），方便日后切换正式环境
- 服务端已启用 CORS 与 `trust proxy`，事件中的客户端 IP 取自 `X-Forwarded-For`（经反代时）或 socket 地址

## 7. 位置采集

服务端按「设备 + 坐标」去重（坐标 round 4 位 ≈ 11 米）：**同位置仅更新最后出现时间，位置变化才新增记录**；后台「位置轨迹」页展示地图轨迹与地址（反地理编码）。

### 权限（AndroidManifest.xml）

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

运行时申请：在设置页提供「位置采集」开关，用户开启时 `requestPermissions` 授权（`ActivityCompat`），拒绝则保持关闭。
（已实现：键盘首次弹出时请求定位权限；设置页开关可随时关闭/恢复）

### 采集（每 1 分钟一次，原生 LocationManager，不依赖 GMS）

```kotlin
val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
val listener = LocationListener { loc ->
    // 取较新的一次定位（GPS 优先、网络兜底），有值即上报
    reportLocation(loc)
}
lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 10f, listener)
lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 10f, listener)
```

回调中上报（**客户端不需要自己判断是否重复，照常每分钟上报，服务端去重**）：

```json
POST /api/v1/mobile/location
{
  "device_id": "1f0e2d3c-...",
  "latitude": 23.12908,
  "longitude": 113.2644,
  "accuracy": 10,
  "provider": "gps",
  "speed": 5.2,
  "occurred_at": "2026-08-19T13:30:00+08:00"
}
```

响应 `{ "ok": true, "recorded": "new" | "same" }`：`new` 表示新增轨迹点，`same` 表示同位置（服务端只刷新最后出现时间）。

> 隐私说明：精确位置属敏感权限。本系统个人自用、数据仅存本地 WSL PostgreSQL，但建议在设置页展示「位置采集：已开启/已关闭」状态，允许随时关闭。
> （已实现：设置 → 其他 → 位置采集开关，关闭即停止定位）

# 8. 语音输入（voice 埋点配套）

原版 YuyanIme 无语音功能（VoiceSettingsFragment 为空壳），已用系统 `SpeechRecognizer` 实现最小可用语音输入：

- **入口**：键盘菜单（设置 → 键盘菜单）新增「语音输入」项。新安装由数据库种子数据提供；老用户由 `SettingsContainer.showSettingsView()` 检测缺失时自动补插
- **调用链**：`SkbMenuMode.Voice` 菜单点击 → `InputView.startVoiceInput()`（公开转发）→ `ImeService.startVoiceInput()`
- **识别**：`SpeechRecognizer`（无 UI 引擎，系统自带，无需第三方 SDK），`EXTRA_LANGUAGE_MODEL=FREE_FORM`，识别语言跟随系统
- **权限**：`RECORD_AUDIO` 运行时申请（`requestCode 0x67`），拒绝仅语音不可用
- **埋点**：识别结果 `DataCollector.recordEvent("voice", source="voice")` 后上屏，上屏调 `commitText(text, recordEvent=false)` 避免重复记录
- **静音处理**：`ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` 静默不提示，其余错误 toast

## 9. 阶段 2（可选）：补全模型同步

服务端定时分析生成「前缀 → 补全」候选（`completion_candidate` 表，version 递增）：

1. `GET /api/v1/mobile/completions?since=<本地已同步版本>` 拉取增量，服务端返回最新 `version`
2. 客户端按拼音匹配：候选带 `prefix_pinyin`（全拼）与 `prefix_initials`（首字母），可同时支持全拼/简拼触发
3. 展示候选时 `POST /completions/feedback { completion, prefix, accepted: false }`，用户接受时上报 `accepted: true`，服务端统计接受率

## 10. 自测

服务端已启动时，可用 curl 模拟全流程：

```bash
# 注册设备
curl -X POST http://localhost:3000/api/v1/mobile/device -H 'Content-Type: application/json' \
  -d '{"id":"22222222-3333-4444-5555-666666666666","name":"我的主力机","brand":"Xiaomi","model":"24031PN0DC","sdk_int":34,"screen_resolution":"1220x2712","locale":"zh-CN","region":"CN","hardware":"qcom","rom_version":"HyperOS 1.0.8.0","ram_mb":12288,"app_version":"1.2.0","os_version":"14"}'

# 上报事件（幂等：重复提交同一 id 不重复入库）
curl -X POST http://localhost:3000/api/v1/mobile/events/batch -H 'Content-Type: application/json' \
  -d '{"device_id":"22222222-3333-4444-5555-666666666666","events":[{"id":"dddddddd-0000-0000-0000-000000000001","device_id":"22222222-3333-4444-5555-666666666666","event_type":"commit","text":"测试上报","package_name":"com.tencent.mm","network_type":"wifi","occurred_at":"2026-08-19T13:30:00+08:00"}]}'

# 上报位置（重复位置返回 recorded=same，不新增；变化后返回 new）
curl -X POST http://localhost:3000/api/v1/mobile/location -H 'Content-Type: application/json' \
  -d '{"device_id":"22222222-3333-4444-5555-666666666666","latitude":23.1291,"longitude":113.2644,"accuracy":10,"provider":"gps"}'

# 查看仪表盘
open http://localhost:5175   # 行为明细页可看到该事件与设备档案，位置轨迹页可看地图
```

## 10. 注意事项

- **位置去重**：服务端按坐标 round 4 位（≈11 米）判断重复，同位置只刷 `last_seen_at`；轨迹点即“去了哪些不同地方”

- **幂等**：事件 `id` 必须客户端生成且重试不换，服务端 `ON CONFLICT DO NOTHING`
- **批量上限**：单次 ≤500 条，超出返回 400
- **文本长度**：单条 `text` 服务端只统计 1~200 字符（分析口径），上报全量即可
- **时区**：务必带时区偏移（`+08:00`），否则服务端按 UTC 解析导致时间错位
- **会话**：键盘显示 → 隐藏为一个 session（`onStartInputView` 开始，`onWindowHidden` 结束），结束时可上报 `POST /session` 补全会话统计
- **隐私**：本系统为个人自用，数据仅存本地 WSL PostgreSQL；如日后多设备共享，建议加设备级开关控制上报
