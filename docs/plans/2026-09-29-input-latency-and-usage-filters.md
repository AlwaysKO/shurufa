# 输入卡顿、退格与应用使用筛选修复计划

> 使用 superpowers:executing-plans、test-driven-development 和 verification-before-completion；当前分支直接修改，保留已有工作区改动。

**目标：** 未完成拼音不丢失、不误删正文；候选查询不等待后台写事务；应用使用筛选控件统一样式，验证授权后的真实入库。
**已确认设计：** 用户确认日期和时间分开，保留统计口径、候选排序和字号，不重构输入引擎、不绕过权限。
**技术栈：** Kotlin/SQLite/Robolectric、Vue/TypeScript/Vitest。

## 证据
- Windows ADB 真机日志：16:51:03.876 READ_REPORTS 至 16:51:38.488 REPORTS_READY，共 34.612 秒。
- LocalInputStore.effectiveChoices 每键调用 settleLearning 写事务；数据库未启用 WAL。
- InputView.processInput 根据候选是否为空路由退格，updateCandidate 在空候选时 reset，未检查引擎组合状态。
- 用户开启权限后已复核 GET_USAGE_STATS=allow、app_usage_enabled_v1=true、reporting_consent_v1=true。

## 步骤
1. PendingLearningTest 新增候选读取不得结算写入的失败测试；验证失败，再去掉查询中的写事务，启用 WAL，结算定时任务移出主线程。保留临时奖励立即参与排序、幂等结算/纠错行为。
2. ExpressionManualSearchInputViewTest 添加空候选仍有组合时保留状态、退格不发给宿主的回归；先失败再以引擎输入状态判断，保留手写/联想正常删除。
3. client/tests/app-usage.test.ts 添加日期时间拆分双向绑定测试；先失败再更新 AppUsage.vue 的分组控件、样式和空状态提示。
4. 运行相关 Android 回归、前端测试和构建；读取真实应用使用记录及本地入库。补仅记录耗时、不记录输入内容的诊断。
5. 原签名 assembleOfflineDebug，校验元数据、哈希及 E 盘交付文件；不擅自安装、提交或推送，明确真机效果未验收边界。

## 本轮安装授权
用户于本轮明确确认：测试与签名校验通过后，保留数据覆盖安装到当前已连接手机。不卸载、不清空词库、不自动发消息。仅适用于本轮交付。
