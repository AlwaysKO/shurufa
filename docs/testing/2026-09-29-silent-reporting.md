# 2026-09-29 静默补传验收记录

## 用户确认边界
- 仅清手机：线上未明确保存永不因年龄清理；首次线上确认起 7 天后允许清除尚未送达电脑的副本。不清任何后台历史。
- 截图/关联聊天今后只建线上目标，历史本地聊天不发送，保留规则不变；定位、输入统计、App 使用仍双端。
- Wi-Fi 才上传聊天，保持系统授权/隐私提示，不抢焦点、不主动弹消息。
- 用户明确允许保留数据覆盖安装及验收，不清数据/改系统授权。

## RED 证据
- /tmp/silent-red.log：3 个测试失败，证明旧行为仍上传本地聊天、允许移动/USB截图上传、普通位置没有 7 天过期。
- /tmp/screenshot-compression-red.log：4 个失败，旧 API 28 WEBP_LOSSY字段不可用、新压缩质量未实现。
- /tmp/silent-receipt-red2.log：14 项中 2 项失败，线上 discarded 和部分 events received 会被错误确认。
- /tmp/silent-suite.log：新增 MediaCropperTest.typingDefersPhysicalScreenshotWithoutChangingConsent 失败，旧采集入口未统一检查输入空闲。

## 实现
- 本地聊天过滤在 LIMIT 之前；线上依赖查询固定 dependency→hash 索引→target 的顺序，未知资产判断只做一次，避免每条消息重复全队列扫描。
- 线上拒绝 discarded，输入事件接收数须与整批相等；失败保留幂等 ID。普通报告/输入事件/usage持久化首次线上确认时间，重复确认不重置。旧记录缺证据不推断线上成功。
- Wi-Fi socket + DNS 绑定，不因丢网回落蜂窝；输入/网络变化取消已登记 Call，登记竞态由代次复查保护。每 ≤8 KiB 再核查网络、授权、输入并限速约128 KiB/s；单并发，间隔3秒，8 MiB/分钟预算（失败也计入）。未知/移动/USB均不放行截图。
- 图像处理后台串行；系统截图前后、每张处理和编码前复查输入空闲。已提交的系统截图/正在执行的单次native编码不能保证中途撤销，但之后让路，不声称零CPU开销。
- Room→持久JSON每轮最多2张图、20条消息；这是载体交接，不将未上传数据清空。

## 查询性能对照
对4500张资产+4500条消息的独立合成SQLite库运行同一SQL（未包含真实聊天）：
- 原查询：超过8秒主动中断。
- 新本地查询：0.003秒，能读到位置。
- 新线上查询：0.009秒，返回正常20条。
临时脚本 /tmp/silent-query-benchmark.py，仅诊断，不作为手机性能已验收。

## 压缩验证
- 原分辨率，78起，超过256KiB最多再试72并取较小；超限不进一步缩字/毁字。
- 普通合成小字图 34254→31912B（-6.84%）；噪点图472456→402566B（-14.79%）。助手目视中英/数字仍可辨，不代表所有手机字体保证。
- 样片 artifacts/diagnostics/2026-09-29-screenshot-compression，只有合成数据。
- 第一轮集成 /tmp/silent-green.log BUILD SUCCESSFUL（压缩4项、TitleOcrInput12项、usage存储等通过）；后续全量结果下补，不以早期绿测代替最终。

## 交付与真机
待最终回归、签名核验和覆盖安装后补记。未部署线上、未清后台、未提交推送。

## 最终回归过程补充
- 首轮扩展回归 654 项，仅截图 Binder 线程用例失败；独立重跑同用例 2 项通过（/tmp/silent-thread-inprocess.log）。该 fixture 现显式建立输入已空闲的前提，避免组合执行时运行时静态状态与 Robolectric 重置时钟混用；未放宽生产门控。
- 并行会话使用同一 Kotlin 编译进程造成堆饱和（454 次 Full GC / 约 319 秒 GC）。只取消本会话那次编译，后续用 `-Pkotlin.compiler.execution.strategy=in-process -Dorg.gradle.jvmargs=-Xmx3g` 隔离，不终止其他任务。
- Android 目录在 Windows 9p 挂载。为避免 `--tests` 后仍枚举全库非目标 Robolectric 测试类，最终使用临时 `/tmp/silent-test-filter.gradle` include 本次 collect/usage/capture/service.capture 测试类及并发工作区的输入搜索回归；不修改项目构建配置。
- 规范、代码质量审查最终均通过；API23新增集合已避免使用 API24 的 ConcurrentHashMap.newKeySet。

## 恢复后的最终验证与本地交付

- 用户确认内存已释放后继续；使用临时 `/tmp/silent-resume-filter.gradle`，测试 JVM 3 GiB、单并发、每 15 个测试类重建进程，避免默认测试堆耗尽。未修改生产内存配置，也未跳过先前失败项。
- `/tmp/silent-resume.log`：上述 collect/usage/capture/service.capture 与输入搜索相关回归 **744/744 通过，0 失败、0 错误、0 跳过**；`:app:assembleOfflineDebug` 同轮成功，耗时 10m 5s。不代表全部项目测试。
- 此次恢复时另一轮输入法修改已完成，其原先 WAL、候选只读、输入搜索失败项均在本轮回归内通过。未覆盖或回退这些修改。
- 本地 PostgreSQL 实查该手机定位总数由此前 84 增至 **120**；2026-09-29 北京时间有 **4** 条，最新 **09:35:18**。经 `127.0.0.1:5175` 正式认证接口读取位置接口，HTTP 200、total=4，时间与数据库一致；没有生成测试定位或清除历史。静止不新增位置仍属预期。
- 手机在本次恢复时已由另一轮工作安装 `.17`；本轮只读核对版本与默认输入法。随后用户明确拿走手机并要求只在本地打包，故本轮**未执行安装、未再操作手机**。
- 唯一推荐文件：`E:\Projects\shurufa-android\apk\shurufa-2026-09-29-v20260929.17-2026092917-debug-851daca6.apk`。
- 元数据版本 `20260929.17` / `2026092917`，包名 `com.yuyan.pinyin.offline.debug`。本轮构建产物与该交付文件 SHA256 相同：`851daca66167db3a51e37da66e3602b221adf738183bb1251fd5065889e2fb99`；与另一轮交付一致，不伪造新版本号。
- `verify-delivery-apk.py` 复核 API 23/27/28/32/36 原证书全部通过、非 testOnly；`git diff --check` 通过。
- Wi-Fi 切换暂停/恢复、真实截图小字与实际输入流畅度仍需用户真机操作验收；单元测试与合成图不能代替使用体验。本轮未提交推送、未部署线上、未删除后台记录。
