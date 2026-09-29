# 静默补传与截图仅线上 Wi-Fi 实现计划

> 使用 superpowers:test-driven-development、subagent-driven-development、verification-before-completion；在当前分支实现，不自动提交、部署或安装。

**目标：** 修复积压队列读取耗尽发送预算，截图仅线上 Wi-Fi 低干扰上传，线上确认后才允许清理手机超期本地待传副本。
**架构：** 普通报告与聊天附件查询隔离，限制读取候选范围，保留幂等确认；截图采集不扩大页面范围、不取消系统隐私提示。手机和两个后台不共享清理规则。
**技术栈：** Kotlin / SQLite / Room / OkHttp / Robolectric。

## 已确认边界
- 不清后台任何记录；位置无变化不上报。
- 定位、输入统计、App 使用记录双端上传。线上未确认的手机数据永不过期删除。
- 已获线上确认但本地未接收的数据，从首次线上确认起保留 7 天；重复确认不延长或提前期限。
- 新截图和关联聊天仅线上；历史本地待传聊天不再发送，线上未确认保留，已确认按 7 天清理。
- Wi-Fi 才允许截图上传，网络变化和输入活动让路；不使用移动流量或 USB 例外。
- 新截图保留原分辨率优先压缩，文字清晰比体积优先，不为强制达标无限降质。历史已哈希引用的附件不能直接改字节。
- “静默”是不抢焦点、不加提示、不在 UI 线程做重活，不意味着隐藏系统授权或保证零 CPU 消耗。

## 任务 1：队列与保留规则
文件：data/collect/LocalInputStore.kt、ReportImageIndex.kt、EventDelivery.kt、DataCollector.kt；data/usage/UsageStore.kt、AppUsageTracker.kt；对应测试。
1. RED：普通位置不被大量依赖聊天遮挡；仅线上聊天、线下不读取聊天；首次线上确认 7 天清理，未确认不删、重复确认不重置。
2. GREEN：过滤在 LIMIT 前、普通报告先行；依赖查询索引/限制扫描，避免全库嵌套扫描；给事件与 usage 持久化线上确认时间，兼容旧库保守不推断。
3. 验证：定位、普通事件与聊天依赖回归；大量合成积压查询、5 秒预算。

## 任务 2：Wi-Fi、节流、静默
文件：data/collect/ImageUploadSchedule.kt、ImageUploadRuntime.kt、EventDelivery.kt；data/capture/net/CaptureUploader.kt；对应测试。
1. RED：MOBILE/USB/OFFLINE 不可上传；Wi-Fi 批次/窗口上限；输入立即中止可取消上传；网络切换取消后保留队列。
2. GREEN：所有 HTTP 附件路径使用统一门控，流式限速并在写入块边界复查；截图准备单并发、输入冷却；仅后台执行。
3. 检查截图入口、编码入队/清理是否可能同步阻塞输入线程；不改动用户主动发送图片功能。

## 任务 3：清晰优先压缩（独立子任务）
文件：data/capture/media/ImageHash.kt 及对应图片编码测试。原尺寸，有界降质，选择更小有效编码；API 23 兼容。压缩先于内容 hash / 持久化；不篡改已引用旧 hash。
用合成小字号中英文字、图文截图验证尺寸、可解码、体积与目视清晰度；不能以字节数代替可读性验收。

## 任务 4：审查及交付
执行 source /home/ko/android-tools/env.sh 后在 android/YuyanIme：
`./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*data.collect.*' --tests '*data.usage.*' --tests '*data.capture.*' --offline --console=plain`
对已知无关失败单列，不假称全绿。先规范、后代码质量独立审查。
构建 :app:assembleOfflineDebug；核验真实版本、原签名、非 testOnly，复制 E 盘固定目录、核验 SHA256。
真机只读检查为默认；覆盖安装需明确确认。最终报告未完成的真机输入/网络切换/截图清晰度验收。
