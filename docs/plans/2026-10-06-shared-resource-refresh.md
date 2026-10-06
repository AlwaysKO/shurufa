# 词库配置与资源版本共用刷新批次

**Goal:** 按用户已确认的 G/H 范围，Wi-Fi 每30分钟、流量每2小时合并自动检查；手动刷新立即执行，无网停止网络检查，恢复联网唤醒，30分钟只做恢复兜底。

**Architecture:** 复用 ExpressionSyncJobService 检查 Job 与原30分钟恢复 Handler，不增加独立模块定时循环。BackgroundRefreshRuntime 持久保存批次资格并串行调用服务器配置/个人词库、补全、常用语、聊天采集配置和表情版本检查；各模块失败独立，保留输入/游戏/授权守卫。表情目录及原图继续独立 Wi-Fi 下载。

**Tech Stack:** Kotlin、JobScheduler、SharedPreferences、协程、Robolectric/JUnit。

1. 新增刷新资格策略与测试：30分钟/2小时、手动绕过时间门槛但不绕过断网、时间回拨恢复。根代理统一运行红测。
2. 新增共享 Runtime；移除 Completion/Phrase 自有循环，ChatCapture 入口加入共享批次，原缓存/版本与持久化规则保持。
3. Expression 检查 Job 接入共享批次；将与应用使用冲突的下载 ID 5175302 移至5175332，仅迁移自身组件旧任务。测试恢复、断网、手动请求升级、同 ID 其他组件保护。
4. 根代理接线 DataCollector.refreshBackgroundResources(app) 和既有网络回调至 BackgroundRefreshRuntime.networkChanged(app)，检查旧普通上传路径不再重复刷新个人词库/配置。
5. 根代理统一执行相关回归、构建与实机验证；本代理不运行Gradle/ADB、不提交部署。保持当前分支和此前未提交改动。
