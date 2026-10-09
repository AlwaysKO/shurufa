# Shizuku 手机独立副屏探针 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 在普通安装的诊断 APK 中验证 shell 服务独立创建副屏、仅启动系统设置、记录焦点元信息并限时清理，为输入法内集成提供可验证基础。

**Architecture:** 独立 `redpacket-probe` 模块，使用 Shizuku 13 UserService；输入法和旧 Hook 模块保持不变。服务在 shell UID 下创建私有可信副屏、开启独立焦点和禁止抢顶层焦点，ImageReader 只计帧不保存像素。只开放启动/停止/状态三个 IPC，目标固定系统设置，拒绝 Root、默认屏及不满足焦点条件的操作，五分钟自动停止。

**Tech Stack:** Java 17、Android SDK36、Shizuku API13.1.5、AIDL、JUnit/Robolectric。

用户已同意 Shizuku 设置并要求继续此前明确的最小探针阶段；在当前 main 分支工作，不创建分支/worktree，不提交。此阶段不打开微信、不注入或领取，不据此宣称红包实现。

## 任务与验证

1. 新增模块构建清单、AIDL 和诊断页面，增加 `settings.gradle` 模块条目。状态页面提供授权、连接、启动五分钟实验、停止、手动刷新；无开机启动或常驻轮询。验证：Gradle 编译、Manifest 检查、原签名校验。
2. 先写拒绝未知焦点、主屏焦点变化、非目标窗口、副屏停止和超时的测试并观察 RED；实现纯 Java 守卫及生命周期清理。服务使用单串行工作线程，后台每秒限时诊断（最多五分钟），只提取 `dumpsys window` 显示/窗口焦点行，完整输出不落盘。验证：JUnit 行为测试及超时/重复停止/启动失败资源释放。
3. 实现 shell 副屏后端：私有/非 presentation、可信、独立显示组、独立焦点、禁止抢顶层焦点、移除时销毁内容；不设置 ALWAYS_UNLOCKED、不改变电源/锁屏/输入设置。仅固定 `am start --display ... -a android.settings.SETTINGS`，不允许任意命令、包名或坐标。显示/焦点未知即释放实验资源。验证：单测拒绝路径、Lint、构建；反射能力留待设备验收，失败不降级主屏。
4. 交付 APK 和具体真机步骤，设备可用时先只读核对环境。安装与运行仅限本次已说明的 Shizuku/诊断实验；系统权限弹窗由用户处理。验证：shell UID、目标 displayId、帧计数、主屏焦点、退出后显示清理；拔线和真实触摸须用户配合，未测明确列出。

命令：`source .runtime/macos/android-env.sh` 后执行 `./android/YuyanIme/gradlew -p android/YuyanIme :redpacket-probe:testOfflineDebugUnitTest :redpacket-probe:lintOfflineDebug :redpacket-probe:assembleOfflineDebug --console=plain`。预期测试、Lint、编译通过，APK 仅属于诊断模块。
# 失败现场补充（2026-10-09）

用户报告开启仅充电 ADB 后仍返回 SECONDARY_UNVERIFIED，并明确反对反复拔插而没有诊断结论。继续既有探针设计，只补证据，不放宽焦点和显示门禁。

1. ProbeSession 保存清理前的失败快照：启动/运行阶段、目标显示ID、顶层输入显示、显示是否ON、副屏窗口为缺失/设置/其他、具体失败分类。先新增回归测试并确认RED，再实现。停止不擦除失败，下一次真正启动清空。
2. ProbeService 返回结构化 failure 字段；ProbeActivity 将最近失败响应保存在诊断应用自己的私有偏好中。断开/重开页面也能看到最近失败，明确它是历史报告。仅存枚举、ID和布尔值，不存窗口标题、聊天或图像。
3. 版本升0.2.2/3；运行单元测试、Lint和离线打包，核对签名与APK版本。先完成本地验证，不要求用户反复拔插。真机未连接时不声称根因已修复。

候选方案中不采用直接延时重试或忽略空焦点，因为尚无证据证明其安全性；暂不引入长时间录屏或完整系统日志收集。

后续真机补充证据：0.2.2/0.2.3在启动时记录到显示ON、顶层输入0但副屏窗口尚无焦点；Android16 ActivityStarter 的 waitResultIfNeeded 在复用Activity时可直接返回，因此不再用am-W成功等同窗口已就绪。0.2.4仅在启动期且主屏安全、与启动前完全一致、显示ON、副屏窗口缺失时，允许1500ms有限重试预算（系统采样本身可能超出预算，不是硬超时）；其他窗口/失焦/显示OFF立即停止。运行期未知窗口规则不放宽。新增StartupFocusTest五项覆盖就绪、超时、危险状态、中断、采样耗时；独立五分钟熔断不变。先TDD再在真机重复验证。
