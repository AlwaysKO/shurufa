# 静默红包首次设置简化与通知来源自动路由

> 本轮按已确认设计由现有子代理并行实现，当前分支原地修改。

**Goal:** 按用户“手机先拿到、自己开发”和首次配置过复杂的反馈，仅离线改造输入法，减少账号/群名手工配置并明确无法取消的系统准备。

**Architecture:** 新安装默认OFF，界面提供自动按通知真实Android用户路由及处理收到通知的已验证群聊；旧已选账号/白名单保持原范围，不迁移成全部。通知UID与PendingIntent创建者必须一致，目标用户随请求在内存队列保存，启动前重新验证当前配置。端侧完整群名、群资料、双帧和一次提交门禁保持。引导检查Shizuku安装/运行/授权、通知使用权与无障碍，提供官方安装和系统设置入口，不自动安装或替用户授权。

**Tech Stack:** Kotlin、Android现有Settings Activity、Shizuku、Robolectric/JUnit。

## 边界

- 本轮禁止adb、设备查询/操作、安装或再让用户发红包；只构建本地可分享APK。
- Shizuku首次无线配对/授权及重启后启动仍不可取消；当前后端API35+边界不变。
- 微信未产生通知时不伪造触发，不承诺免打扰群、无通知或所有手机均可用。
- 自动群模式只取消人工名单，不取消通知与画面的完整群名匹配、群详情证明、用户/profile和帧门禁。
- 新安装模式OFF；已有USER/GROUPS键时维持手工选择/名单默认，显式设置自动项才扩展范围。
- 当前分支原地修改，不提交、不切分支、不建worktree；保护其他任务改动。

## Task 1: 范围设置与可测试通知路由

Files: SilentPacketSettings.kt、SilentPacketDispatch.kt、PacketNotification.kt及对应tests。
新增自动账号/自动群设置与兼容默认；请求保存来源用户；纯策略函数覆盖OFF/非法profile/主微信/分身/显式名单。先RED后GREEN，不放宽后端校验。

## Task 2: 内置首次准备引导

Files: 新SilentPacketSetup.kt及Test、AndroidManifest.xml必要的Shizuku package visibility。
固定准备条件与步骤，仅官方HTTPS下载/指南；系统页和Shizuku启动入口带无处理器降级。API23不加载Shizuku运行路径。先RED后GREEN。

## Task 3: Runtime和设置页整合

Files: SilentPacketRuntime.kt、SilentPacketSettingsActivity.kt及integration tests。
Runtime以每通知目标用户独立串行启动，manual/auto群与账号在入队和启动复验；UI自动选项禁用无用手填项，新安装不用枚举/选Android ID，旧配置不变。首次检查提供明确缺项和下一步，诊断技术输出另保留。继续所有输入/游戏/锁屏/时间/去重保护。

## Task 4: 本地交付

正常源集运行SDK红包及后端测试、完整OfflineDebug构建、原证书验证、git diff --check；保存新APK和交付记录。无真机与真实到账验收，不用测试数量冒充可用。

官方依据： https://shizuku.rikka.app/zh-hans/download/ 与 https://shizuku.rikka.app/zh-hans/guide/setup/ （本轮已读取）。

## 离线执行结果

Task 1–4代码与本地验证已执行。两轮RED分别确认缺失设置/引导/页面行为与自动逐通知路由，再实现GREEN。正常源集SDK红包246项、后端22项全部通过，完整OfflineDebug构建及API23/28/36签名核验通过，日志 `/tmp/shurufa-simple-setup-green.log`。APK及SHA256见交付文档。全程未操作手机，真实领取未验证。
