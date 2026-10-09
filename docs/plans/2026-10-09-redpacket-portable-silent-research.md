# 普通安卓手机安装输入法后的静默红包能力研究

日期：2026-10-09。状态：源码与公开文档核验，未安装、未登录、未操作手机，未领取红包；不是已批准的实现设计。

> 后续进展：用户要求继续后，已实现并在本机安装 Shizuku/独立副屏诊断模块，验证私有副屏承载系统设置与主屏桌面焦点保留。具体新增手机操作、交付包与待验收边界见 [副屏探针记录](../testing/2026-10-09-redpacket-shizuku-probe.md)；下文保留研究阶段历史。

## 最新产品要求

用户再次明确：功能面向其他普通安卓手机，随输入法交付，保持静默；不接受用主屏切换微信点击替代。此前独立 Hook 模块的构建通过不满足这个分发目标。

继续沿用不 Root、不覆盖主微信、不清数据、不迁移账号的现有边界。本轮研究不授权改变这些条件。用户本轮明确答复“可以接受这些设置”：允许普通手机首次开启开发者选项、无线调试并授权 Shizuku，重启后重新启动服务，前提是抢红包时主屏不切换。这是部署条件的确认，不是副屏已实现、已安装或微信静默验收通过的证据。

## 结论与证据等级

在本轮检查过的项目中，没有找到已验证同时满足“普通安装输入法、无需额外运行环境、保留现有微信登录、不修改微信、全程静默”的现成方案。这是检索范围内的结论，不证明所有可能路线都不存在。

有两种值得区分的静默：

1. 微信进程内后台领取：监听入库后调用内部请求，不需要展示领取页面，但需要先让模块进入该进程。
2. 主屏不可见的副屏交互：微信仍展示页面、接受点击，只是位于副屏。可能实现用户感知的静默，但不能把它描述成接口领取；显示隔离、锁屏、性能和主屏接管须单独验证。

| 路线 | 安装与使用条件 | 与当前目标的关系 | 本轮证据 |
|---|---|---|---|
| 原版微信 + 普通输入法/无障碍 | 常规安装及授权 | 无法据此获得微信内部消息及领取请求权限；主屏点击不符合静默要求 | Android 应用隔离和无障碍接口，先前源码核验 |
| LSPosed/Vector | 已有相应 Root/框架环境 | 可以承载后台领取，但不能作为普通手机通用前提 | 上游框架说明 |
| LSPatch | 修改目标微信 APK 并安装结果 | 免 Root，但不满足保留原安装包；输入法内置补丁工具也不改变这一事实 | 上游明确说明单目标 APK 重写 |
| 输入法内集成容器 + Hook | 将微信装入容器，容器运行独立数据实例，并具备可用 Hook 引擎 | 理论候选；不能直接接管系统微信现有私有登录数据，且现代系统、微信登录与后台兼容未证实 | VirtualXposed 明确限制模块仅作用于容器内应用；新分支证据不足 |
| Shizuku + 受控副屏 | 用户启用调试、启动并授权服务；重启后恢复服务 | 若接受配置，最值得先验证；不需要因这条机制而改写微信 APK，但不等于完全零配置 | Extend/Mirror 有真实显示创建代码；本项目历史有部分 ADB 副屏证据 |

## 已核对的具体源码

### 1. 免 Root Hook：LSPatch

仓库：[JingMatrix/LSPatch](https://github.com/JingMatrix/LSPatch)，核验提交 `0dc50f42503711b14f5e2bb217c2bdd6321ce5be`，提交时间 2026-08-23（UTC）。

[README](https://github.com/JingMatrix/LSPatch/blob/0dc50f42503711b14f5e2bb217c2bdd6321ce5be/README.md) 明确：向目标 APK 嵌入加载器和框架，未修改的应用不能被它 Hook。集成模式可以不依赖管理器，但交付的是修改后的目标应用，不能把集成模式误读为“输入法一个 APK 能 Hook 原版微信”。Shizuku 在其安装流程中只是可选安装辅助，不自动把原版微信变为可 Hook。

本地模块当前严格校验原微信 APK SHA256，拒绝修改包。即使以后另行接受重打包路线，也必须重新设计原始包验证、运行时适配和交付流程，不能直接取消哈希校验后声称可用。本机系统分身共享 base.apk，不能覆盖同包而承诺只影响分身。

### 2. 容器：确有历史机制，现代适配不能只看仓库标题

- [android-hacker/VirtualXposed](https://github.com/android-hacker/VirtualXposed)，提交 `122beb371519cb2d221ce06756361aaa30e2674f`，2022-06-07。README 标明 Android 5–10，并明确微信与模块都必须装入容器；容器模块不影响系统原版应用。不能据此承诺 Android 16 上微信 8.0.78 可用。
- [ISEKHON/VirtualApp](https://github.com/ISEKHON/VirtualApp)，提交 `b3c634ad7941765df3da84a207aca94b7861afae`，2026-03-05。[KNOWN-ISSUES](https://github.com/ISEKHON/VirtualApp/blob/b3c634ad7941765df3da84a207aca94b7861afae/docs/KNOWN-ISSUES.md) 明确：Android 14+ 的 Epic/SandHook 兼容问题导致 Xposed 默认关闭；后台 Activity 启动也可能受限。因此“虚拟应用兼容 Android 16”不能推导出“Android 16 Hook 和静默红包可用”。
- [fdavids77/BlackBoxReborn](https://github.com/fdavids77/BlackBoxReborn)，提交 `7b86a9d62a974650e0659825758931cd91140827`，2026-08-14。README 宣称新系统适配，但列出的应用兼容表没有微信。本轮 Git tree 核验发现 COMPAT 中引用的 `Bcore/src/main/java/com/lody/virtual/helper/PackageParserFix.java`、`Bcore/src/main/jni/NativeCore.cpp`、`Bcore/src/main/jni/ArtMethodFix.h` 不在该提交源码树中，实际应用入口使用 `top/niunaijun/blackbox/app/BActivityThread.java`。已读取入口及 Gradle 依赖尚未确认完整 Xposed 模块装载链路；不把缺少证据说成绝对不能加载。README 写 GPL-3.0，但 LICENSE/API 显示 Apache-2.0，整合前还需查清来源和许可。不运行其中的账号会话导入或其他无关工具。
- `ZENINXOP/BlackBox` 的说明将 Android 16 支持列在付费版本，公开页面主要为说明和压缩包，没有本轮可审查的现代版本微信实测链路；不将其当作已找到解决方案，也不购买或运行未知 APK。

推断：容器机制可以由宿主控制自己的虚拟进程，因而不需要向系统微信越过沙箱注入；代价是运行另一个微信实例。容器安装成功不代表登录、后台接收、Hook、支付组件、到账及功耗成立。同一微信账号是否允许该实例保持会话、是否使原实例退出，必须由受控测试核实，不能默认不受影响。

### 3. 手机独立副屏：Shizuku + Extend/Mirror

- [Shizuku API](https://github.com/RikkaApps/Shizuku-API) 提供以 shell/Root 身份运行 Java/JNI 的 UserService；shell 身份不是 Root，也不是微信 UID，不自动开放微信私有数据或内部方法。
- [官方启动说明](https://shizuku.rikka.app/guide/setup/)：Android 11+ 可通过无线调试在手机启动，无需每次连接电脑；非 Root 重启后仍需重新启动服务。厂商可能限制权限和后台存活。
- [jqssun/android-display-extend](https://github.com/jqssun/android-display-extend)，提交 `bbbeb16e9ca1908031c9eed1a7bd25e7996065a8`，2026-07-22。已读取 [CreateVirtualDisplay.java](https://github.com/jqssun/android-display-extend/blob/bbbeb16e9ca1908031c9eed1a7bd25e7996065a8/app/src/main/java/io/github/jqssun/displayextend/job/CreateVirtualDisplay.java)：通过 Shizuku 包装的显示服务创建 display，Android 14+ 使用四参 `createVirtualDisplay`，使用 `com.android.shell` 归属；设置 TRUSTED、OWN_DISPLAY_GROUP、ALWAYS_UNLOCKED 等标志。默认包含 PUBLIC，未加入 OWN_FOCUS / STEAL_TOP_FOCUS_DISABLED；不能直接沿用后宣称不抢主屏焦点。
- [jqssun/android-display-mirror](https://github.com/jqssun/android-display-mirror)，提交 `d7eea1b9e44eea38850bffa32ed0623506a2aa9a`，2026-08-24。[DisplayFlags.java](https://github.com/jqssun/android-display-mirror/blob/d7eea1b9e44eea38850bffa32ed0623506a2aa9a/app/src/main/java/io/github/jqssun/displaymirror/job/DisplayFlags.java) 定义并允许自定义 OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED；但 auto() 默认也不启用这两项。README 将可信副屏和远程输入列为需要 Shizuku，普通 MediaProjection 回退不能当作等价能力。

这些项目是显示/输入基础设施，不是红包助手。公开源码支持“值得研究手机端实现”的判断，不支持“已经解决微信不黑屏、多群发现或静默到账”。不会为了研究引入 AirPlay/Moonlight 网络传输、禁用锁屏或更改全局显示配置。

## 与本项目已有证据的连接

详见 [此前副屏实验](2026-10-09-redpacket-virtual-display.md)：shell 探针加入不抢焦点标志后曾通过桌面焦点断言，微信曾在副屏及物理锁屏时显示并响应定向滑动。但正常主屏操作时发生副屏异常，且真实拆红包结果未知。这些未解决问题继续有效，不能因找到 Shizuku 就跳过。

副屏不赋予新的消息来源：微信通知全关时，仍需副屏页面发现；列表摘要被普通文字覆盖会漏红包。单群守候与多群扫描须分别评估，不能承诺所有群三秒内发现。主屏用户主动使用微信时须暂停并交还实例，不能让用户避开微信来制造通过结果。

## 建议的下一轮验证顺序（尚未执行）

优先顺序：用户已接受调试授权条件，下一阶段先做 Shizuku 副屏最小能力探针；容器为次选研究，LSPatch 不作为当前原版微信方案。

1. 使用独立诊断入口验证 Shizuku UserService 身份、连接/死亡回调及清理；先只运行系统设置。验证方式：手机拔线后仍有服务心跳，停止后无残留显示、原始设置不变。心跳用于限时诊断，不作为正式周期轮询架构。
2. 对比私有/非 presentation 显示与焦点组合。验证方式：主屏应用、窗口焦点、双指触摸、输入法、音频保持正常；其他 App 不进入目标 display，黑屏即失败。不预设去掉 PUBLIC 一定能隔离，须核验可信 shell 显示对目标 App 的访问权限。
3. 系统页面通过后再验证微信，只做用户已同意的只读页面行为；主屏打开微信立即暂停/交还，后台恢复不能抢焦点。锁屏/解锁、切 App 和 Shizuku 死亡分别验证，失败停止，无坐标兜底点击主屏。
4. 确认可按 displayId 获取页面或像素、定向注入，并在每次操作前核对显示/窗口/目标。只复制所需能力，先解决来源隔离；不得把现有针对 display0 的红包助手直接接到副屏。
5. 先 PROBE 验证单群新红包发现，再评估多群漏报与扫描成本。此前未知领取结果不重放。实际领取使用另行确认的测试群、新红包及结果证据。
6. 最后测耗电、温度、输入延迟、游戏避让和至少多个厂商/系统版本，才决定是否能随输入法发布。支持目标是通用，功能门禁按实际运行能力，不按本机设备 ID 硬编码。

是否安装 Shizuku、运行新的手机探针或导入容器不是本轮已执行事项。研究阶段只读 GitHub/官方资料，下载的元数据、文件树和指定源码位于被 Git 忽略的 `.runtime/redpacket-research-20261009/`；未执行第三方代码、修改生产代码、提交或推送。
