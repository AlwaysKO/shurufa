# 打字优先与均衡图片补传实现计划

> **For Claude：** 使用 superpowers:subagent-driven-development；先失败测试，后实现，再做规范与质量审查。

**目标：** 按用户本轮最终确认，停打 3 秒恢复；Wi-Fi/USB 连续小批；移动网络每 120 秒约 1 MiB，超限单图等待 Wi-Fi/USB，不降画质，不清队列。

**架构：** 在现有有界 drain 上接入共享图片许可，不重新实现补传；许可覆盖上传专用文件读取/Base64 编码与 HTTP 请求，截图采集和本地持久化保持现有正确性边界。只记录输入活动时间/触摸状态，不记录输入内容。按目标独立确认，但图片重活共享单并发；移动额度按上传 JSON 的 UTF-8 字节保守计算（含 Base64，不只是压缩图字节），失败请求同样占用预算。预算不足的图保持原始质量留待 Wi-Fi/USB，后续小图不能被大图饿死。

**技术栈：** Kotlin、Android ConnectivityManager、JUnit/Robolectric/MockWebServer、Gradle。

## 来源与现状
- 用户 2026-09-22 本轮明确选择均衡，并确认超过约 1 MB 的单图留到 Wi-Fi/USB。
- 项目 AGENTS.md：共享采集/补传规则、不混入非聊天页面、原签名 E 盘 APK 交付、真实截图只留本地。
- EventDelivery/DataCollector/LocalInputStore 已有其他会话未提交的 drain、诊断、目标隔离和词库改动。基线副本在 ignored diagnostics 内，不覆盖、不夹带提交。
- 无端侧零成本承诺；正在执行的小请求/编码允许完成，继续打字时不启动下一项。真实耗时与输入体验仍须真机验收。

## 任务 1：独立调度策略与输入活动接入
- 新建 `data/collect/ImageUploadSchedule.kt`、`ImageUploadRuntime.kt` 及对应测试（均在 yuyansdk/src/main/java/com/yuyan/imemodule 和 src/test/java 对应目录）。
- InputView 的触摸、CandidatesBar 的候选触摸、ImeService 的硬件按键更新无内容活动状态；长按/滑动尚未结束时持续暂停。
- 纯策略覆盖 2999/3000ms、长按、滚动 120 秒额度、超限图不消耗额度、Wi-Fi/USB 与移动切换、共用单并发、失败额度不退款。
- 运行 `source /home/ko/android-tools/env.sh; cd android/YuyanIme; ./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*ImageUpload*'`；先观察失败，再通过。

## 任务 2：真实上传和持久队列接入
- 修改 `data/collect/LocalInputStore.kt`：在读取图片 payload 之前按许可大小过滤；为新入队资产记录 SHA、新入队聊天记录保存依赖关系到辅助表，旧队列通过有界惰性索引处理；只发送目标端依赖已清除的聊天报告。保留独立目标与旧记录。
- 修改 `data/collect/EventDelivery.kt`：每次读图前取得许可预算，发请求前重新核对并领取许可，完成后释放；普通事件与其他报告不被图片暂停误伤。
- 修改 `data/capture/net/CaptureUploader.kt`：用同一 idle/单并发许可包住上传用的文件读取和 Base64，暂停不增加失败次数，不删除持久文件。
- 修改 `data/collect/DataCollector.kt`：接入实际网络/目标；空闲时更及时唤醒 drain，空队列不忙轮询，失败沿用原退避。
- 先补真实队列/MockWebServer 失败回归：打字无资产 HTTP、大图不堵小图、预算总量、重新打字不启动下一请求、目标端图片就绪才发送消息、其他报告不中断。

## 任务 3：验证与交付
- 串行运行 `:yuyansdk:testOfflineDebugUnitTest --tests '*data.collect.*' --tests '*data.capture.*' --tests '*service.capture.*'`；补触摸入口连接与硬件回归。
- 规范审查与独立只读代码审查；不把合成测试当成真实打字性能数据。
- 原签名 `:app:assembleOfflineDebug`，核对真实版本、非 testOnly、API 23/27/28/32/36 证书与 E 盘文件 SHA256。
- 若能安全分离重叠改动，仅提交本轮代码；无法独立提交时明确待提交原因，不把其他工作强行收入。用户操作真机，不自动安装或发送消息，不推送。

## 实现与验证记录（2026-09-22）

- 已接入共享无内容输入活动策略：停打 3 秒具备上传资格；文件/Base64 准备、持久队列读图和图片 HTTP 共用非阻塞单并发许可。已开始的操作允许完成，不承诺系统调度或在途请求的硬实时结束时间。
- 图片积压时每秒检查空闲资格，失败仍退避 30 秒。快轮不主动提高词库同步频率；`ReportRetryGate` 保存目标忙碌时未执行的常规同步待办，常规同步可越过图片成功节流但不能越过失败退避。
- 移动网络滚动 120 秒约 1 MiB，按 JSON UTF-8 字节计费（含 Base64），失败尝试不退款；Wi-Fi/USB 不受此移动额度限制。超大原图保留，不降画质、不强行移动上传。
- 数据库版本 10 新增派生大小/依赖索引，旧队列有界索引；消息只等待本目标所需图片。暂停/许可忙碌时在 SQL 的 LIMIT 之前排除图片，25 图积压也不能遮蔽普通报告；没有图片依赖的消息不被未知资产阻塞。
- TDD：调度与入口、队列、HTTP、准备许可先观察实际断言失败再实现；另补 25 图遮蔽和常规周期相位回归。`ReportRetryGate` 3 项纯 Kotlin/JUnit 测试已 RED→GREEN，并纳入下述总数。
- 最终执行 `:yuyansdk:testOfflineDebugUnitTest --tests '*data.collect.*' --tests '*data.capture.*' --tests '*service.capture.*'`：81 套、457 项，失败/错误/跳过均为 0。规范及独立质量审查均通过。
- 构建使用本地忽略目录中的 init 脚本，将输出隔离到 `build/image-upload-validation`，避免并行会话互相清理产物。最后回归曾发生一次测试 JVM SIGSEGV；保留日志后原命令重跑通过，不将它冒充已定位并修复的 JVM 问题。证据在 `artifacts/diagnostics/2026-09-22-idle-image-upload/`，不进 Git。
- 基础实现已被并行会话纳入 `9438198`；本轮收尾只提交后续上传修正、对应测试和此记录，不重写该提交，不夹带其他会话尚未提交的词库/聊天标题等修改。
- `server/uploads/chat/` 已跟踪文件数为 0，忽略规则仍生效；真实截图、APK、临时诊断不随源码提交。未自动安装、发送消息或推送 Git；真机打字体验与真实网络时延须安装新包后另行验收。

### APK 交付
- 版本 `20260922.18` / `2026092218`，包名 `com.yuyan.pinyin.offline.debug`。
- Windows 文件：`E:\Projects\shurufa-android\apk\shurufa-2026-09-22-v20260922.18-2026092218-debug-b3647b9a.apk`。
- 源/交付 SHA256 一致：`b3647b9a8881e8bbfb06ffe320569ff10764f68991728ce65fc958d9b87e8d82`。
- 非 testOnly，API 23/27/28/32/36 均验证为项目固定原证书；未自动安装。
