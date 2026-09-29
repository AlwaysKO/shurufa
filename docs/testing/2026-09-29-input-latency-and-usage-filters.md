# 2026-09-29 应用使用、候选卡顿与退格修复

## 范围与证据

- 保留工作区已有采集、上传及后台修改；本轮不提交或推送 Git。
- 真机 Windows ADB：ELI_AN00，安装版本 20260929.14 / 2026092914；安装前 APK SHA256 为 `f15337df0064ecb75ae2ce801f93a988747f07b47eb1e79fe2ca86b65e0583ea`。
- 初查应用使用未授权且开关未开启。用户手动开启后复核 `GET_USAGE_STATS=allow`、`app_usage_enabled_v1=true`、`reporting_consent_v1=true`。
- 本地库已收到 3 条实际使用段；通过生产 `createDashboardAppUsageRouter`、本地只读数据库连接及浏览器验证了实际排行/明细/时间轴。验收代理只监听 loopback，认证与设备目录为测试替身，不冒充正式登录验证；运行结束关闭。
- 日志中后台队列读取从 16:51:03.876 至 16:51:38.488，耗时 34.612 秒。旧候选查询每键调用 `settleLearning` 写事务，存在与同库后台读写争锁的路径。旧日志无单次按键阶段耗时，不能据此声称所有延迟都已归因。

## 修复

- `AppUsage.vue`：日期/时刻拆分、筛选分组统一样式、空状态写明手机开启路径；保留北京时间、裁剪、分页及已提交查询快照口径。
- `LocalInputStore.kt`：WAL 读写隔离；候选读取不结算；同一 SELECT 同时读取正式/临时奖励，避免结算跨两次读取造成重复或遗漏。
- `OfflineT9Candidates.kt`：定时结算移出主线程，并使用独立 helper，避免持有候选 helper 的同步锁等待写者。保留临时奖励立即参与候选、持久化及幂等结算。
- `InputView.kt`：按引擎是否仍有输入判断退格，空候选不再清掉未完成的拼音；删完拼音后正常退格正文。
- `RimeEngine.kt`：DEBUG 慢按键（≥32ms）输出 `ImeLatency`，区分原生处理与候选计算，不记录键值、拼音或正文。

## 已执行验证

- 前端 Vitest 6/6，涵盖拆分字段、清空日期恢复、北京时间、草稿不影响分页及过期响应隔离；构建通过（存在原有大 chunk 警告）。
- 浏览器 1440 / 390 宽度，实际使用段显示、筛选区无横向溢出、无 pageerror；仅验证本页面筛选区窄屏适配，不宣称全站移动端布局已优化。
- Android 红测试：旧实现未开启 WAL、候选查询结算写库，两项按预期失败；修复后该批性能/学习测试 8/8 通过。
- 退格测试先替换仅 JVM 不可运行的 ARM 引擎边界，再分别复现“刷新清掉组合”和“直接退格发给正文”的断言失败。不能把最初 JNI 库缺失错误当业务红测试证据。
- UI、数据库并发和退格代码分别完成只读审查，无阻断项。
- Android 首次并行构建输出发生冲突，后采用独立缓存及 `.runtime/input-fix/build-output`（ext4）隔离构建。未改项目通用构建配置。

## 边界

- 当前仅消除候选查询里的结算写入及主线程定时结算；选词持久化、纠错等路径仍存在同步写入，不宣称所有输入操作绝无争锁。
- 单元测试的 Kernel 替身只验证路由和组合状态，不替代原生 Rime 真机验收。
- 用户已授权测试/签名通过后保留数据覆盖安装；不得卸载、清词库或自动向联系人发消息。
- 完整绿色回归、最终 APK 元数据/签名/哈希及安装结果将在下方以实际输出补充。

## 最终回归结果

- Android 定向及相关回归 **211/211 通过**，0 失败、0 错误、0 跳过；覆盖整个 ExpressionManualSearchInputViewTest、LocalInputStore、个人词库/权重/屏蔽、临时学习、WAL 并发、T9 展示和应用使用模块。
- 首轮扩大回归因测试 JVM 默认堆限制发生 OutOfMemoryError，不计为通过；本机临时 init 脚本改为 2GB 堆、单并发且按测试类隔离后完整重跑通过。未因此修改生产堆或项目通用 Gradle 配置。
- 前端最终重跑 6/6，构建成功；浏览器最终复核实际记录增至 6 条，0 pageerror，筛选区无溢出。
- 常规定向复跑命令：
  `source ~/android-tools/env.sh; cd android/YuyanIme; ./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*PendingLearningTest*' --tests '*CandidateReadConcurrencyTest*' --tests '*ExpressionManualSearchInputViewTest*' --tests '*LocalInputStoreTest*' --tests '*DictionaryCandidatePolicyTest*' --tests '*DictionaryHabitCandidateTest*' --tests '*DictionaryAdditionsTest*' --tests '*PersonalDictionaryStoreTest*' --tests '*T9FullCompositionDisplayTest*' --tests '*RimeEngineCompositionStateTest*' --tests '*data.usage.*' --offline --console=plain`
- 本轮实际使用 `.runtime/input-fix/build-ext4.gradle` 隔离输出/测试内存，并通过 GradleWrapperMain 指定独立项目缓存；避免 Windows 绑定目录与其他同时构建相互污染。

## 最终交付与覆盖安装

- 独立构建 `:app:assembleOfflineDebug` 成功；交付包唯一推荐路径：`E:\Projects\shurufa-android\apk\shurufa-2026-09-29-v20260929.17-2026092917-debug-851daca6.apk`。
- 包名 `com.yuyan.pinyin.offline.debug`，版本 `20260929.17` / `2026092917`，minSdk 23、targetSdk 36，非 testOnly。
- `verify-delivery-apk.py` 验证 API 23/27/28/32/36 签名均通过；签名 SHA256 保持 `a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。
- 构建源包、交付包及安装后 base.apk 的 SHA256 均为 `851daca66167db3a51e37da66e3602b221adf738183bb1251fd5065889e2fb99`。
- 按用户明确授权，Windows ADB 对 `AQUL024807002303` 执行 `install -r`，返回 Success；安装后版本及哈希一致，lastUpdateTime 为 2026-09-29 17:44:34。未卸载、清除数据或自动输入/发送消息。
- 安装后默认输入法不变，使用访问权限仍为 allow，两项采集/同步开关仍为 true；运行进程存在，数据库 WAL/SHM 文件已生成。
- 安装后浏览器再次以真实只读数据验收，使用段增至 **8 条**，筛选区无溢出、0 pageerror；`git diff --check` 通过。
- 安装后的过滤诊断日志尚无 ImeLatency 样本；已请用户手动验收单按 7 的响应，以及上屏三个字后继续输入拼音再退格。真实原生输入流畅度与该操作体验仍待用户反馈，不以单元测试替代。
