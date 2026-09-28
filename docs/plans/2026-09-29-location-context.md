# 均衡位置记录与停留轨迹

> 按 writing-plans / subagent-driven-development 执行；当前分支开发，不创建分支或 worktree，不自动提交、推送或部署。

**目标：** 用户于 2026-09-29 选择均衡记录：移动时增加采样，停留时降低频率，记录已连接 Wi-Fi 和估算停留时长。随后用户明确修订为“位置无变化不上报”；现行规则见 `2026-09-29-location-change-only.md`，静止位置心跳已取消。

**现状：** 输入法活跃时每分钟主动定位，其他时间被动定位；上传需要明显移动，静止记录被过滤。后台仅显示最近 500 点并全部直线相连。手机没有 Wi-Fi 采集及定位前台服务。

## 实现约定

- Android 新增用户在设置页开启的均衡记录入口及定位前台服务，显示常驻通知并可停止。保留总采集同意和位置开关；不开启均衡服务时保留原有输入法定位模式。遵循实际系统权限，不从后台强行启动服务。
- 均衡模式目标为移动时约 30 秒、停留时约 5 分钟本地采样；初期需足够观察再判停留。系统限制、无信号等情况下不承诺固定频率。按用户后续修订，仅位置有效变化才上报；静止时不上传心跳，不能反复使用旧坐标伪装新定位。
- 新增可选 `context` JSON 对象，版本 1：`version: 1`、`captured_at: ISO时间`、`capture_mode: balanced|opportunistic`、`network_type: wifi|cellular|ethernet|vpn|offline|other|unknown`、`wifi: {status: connected|disconnected|unavailable|permission_denied|location_disabled, ssid: string|null, bssid: string|null, rssi: number|null, frequency_mhz: number|null, link_speed_mbps: number|null}`、`battery_percent: number|null`、`charging: boolean|null`、`is_interactive: boolean|null`、`power_save: boolean|null`、`altitude_m: number|null`、`bearing_deg: number|null`、`speed_accuracy_mps: number|null`。除 version 外各字段可选；旧客户端无 context 仍兼容。SSID/BSSID 系统占位符按不可读取处理。
- context 只记录定位时附近的设备快照；SSID 不推断实际场所，热点标识只用于区分同名网络；不扫描周围 Wi-Fi。权限不足、未连接与历史无数据分开显示。
- 服务端新增 location_track.context JSONB，校验结构、枚举、长度、范围；两个位置写入入口和后台读取统一支持；持久回执仍幂等，时间仍以采集时间为准。
- 前端按设备/日期/超过15分钟的采样缺口/明显跳点分段；不同设备不连线。按锚点范围与持续观测估算停留，至少5分钟、有连续观测才成立；断档不计作已知停留，末点不延长到当前时间。Wi-Fi 连续观察时长与地理停留分开。显示路线估算里程、起止时间、停留清单及完整性提示。保留原始记录可查。
- 新增字段直接展示：Wi-Fi 名称/信号/频段、网络类型、电量/充电、屏幕状态、定位方向/海拔/速度误差（有数据时）。地图弹窗必须使用安全文本，不能把 SSID/地址直接插入 HTML。
- API 返回 `has_more` 表明点数截断，后台限制查询范围并明确仅分析已加载点，旧记录不可补出历史 Wi-Fi。

## 任务与验证

1. Android 采集、权限入口、均衡前台服务和心跳策略。先补策略与序列化/权限回归，再实现；运行目标 JVM/Robolectric 测试和 APK 构建。验证停止、撤销权限、服务生命周期、过期定位与旧模式兼容。
2. 服务端迁移、校验、双入口落库及查询。先写扩展字段保存、旧报告、非法字段、重复回执和截断测试，再实现；执行定向 Vitest 和 TypeScript 构建。确认仅连接本地数据库后自动执行迁移并核验。
3. 后台 Wi-Fi 信息、停留/路线分析和界面。先写断档/多设备/漂移/停留与可疑文本测试；实现后运行前端定向测试和构建。
4. 独立代码审查与针对性修复。打包沿用原证书，核验 APK 版本、签名、哈希，交付本机 apk 目录；本机无法访问约定 Windows 目录时如实说明。不能将模拟测试当作真实手机的定位、耗电或路线验收。

## 验收示例

- 多次在同一区域观测 09:00–09:30，展示“估算停留30分钟”；09:05后失联到10:00，不能展示停留1小时。
- 两个设备、跨日、超过15分钟缺口不画连续直线，异常跳点不会膨胀里程。
- 一条新记录展示连接 Wi-Fi 及当时电量；旧记录显示未采集；权限不足显示不可读取。
- 关闭均衡记录停止其服务；关闭位置/总采集停止位置报告；均衡服务中断时不冒充持续记录。

## 参考

- Android WifiInfo: https://developer.android.com/reference/android/net/wifi/WifiInfo
- Android 定位前台服务: https://developer.android.com/develop/background-work/services/fgs/service-types#location
- Android 后台定位限制: https://developer.android.com/about/versions/oreo/background-location-limits

## 本地验证与交付（2026-09-29）

- Android 定向回归 11 个测试类、54 项通过，0 失败/错误/跳过；覆盖服务 API 23/31/35、真实定位请求降频、移动恢复、过期位置、心跳处理延迟、单 provider 禁用、总同意/权限门禁和服务清理。API 36 为编译与签名验证，未做运行验收。
- 服务端位置上报/查询/地址解析 36 项通过；前端分析及组件 23 项通过。前后端构建通过，前端保留既有大 chunk 提示。
- 已确认数据库为本机 ::1 的 personal_ime，执行 034 迁移并核验 JSONB 字段。使用 PostgreSQL 会话临时表验证两入口、扩展字段、重复回执幂等及列表截断，未插入真实业务测试记录。
- Playwright 合成夹具验证 Wi-Fi、15 分钟停留、截断提示、弹窗文本转义、日期筛选和窄屏布局；诊断脚本/截图仅在忽略目录 `artifacts/diagnostics/2026-09-29-location-context/`。
- 独立审查发现并修复旧格式心跳污染新快照时间、停留漂移累计里程、低精度定位抹掉 Wi-Fi 跨度、provider 禁用重注册循环及均衡开关状态不同步；复审通过。
- APK 使用 `:app:packageOfflineDebug` 构建，保持原签名。交付 `apk/shurufa-2026-09-29-v20260929.01-2026092901-debug-1ea0bb00.apk`，versionName=20260929.01、versionCode=2026092901；源文件和副本 SHA256 均为 `1ea0bb001c00c187d211d2304ca242cbf5964b60fa95feeb21bace4c74b1b8b0`。
- 当前为 macOS，无法访问约定 Windows E 盘目录，APK 放在本机项目 apk 目录。未安装手机、未做真机息屏轨迹/耗电验收，未提交、推送或部署线上。
- 开启与页面含义见 `docs/LOCATION_TRACKING.md`。
