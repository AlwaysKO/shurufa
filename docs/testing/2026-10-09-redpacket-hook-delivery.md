# 静默群红包模块：代码交付与待真机验证

本轮用户确认范围：先实现独立 Hook 模块、微信 8.0.78 适配、识别及领取状态机，默认关闭自动领取；后续再连接手机验证。没有连接手机、安装/重签微信、注入进程、领取红包、提交或推送。

## 本地代码

模块：`android/YuyanIme/redpacket-hook`；包名 `com.yuyan.redpacket`，版本 `0.1.0 / 1`，Android 8.0+。这是独立模块 APK，不是输入法升级包。不包含其他会话正在修改的视频采集或后台功能。

- OFF / PROBE / AUTO 三种模式；默认 OFF。OFF 或未明确选择当前 Android 用户时，不挂载 WCDB/红包业务 Hook。由 OFF 开启后须重启目标用户的微信进程，使框架重新加载入口。
- 只接受微信主进程、`8.0.78 / 3180`、已分析原 APK SHA256 `41f7dc1f720767fa78fa20dd13ea034b817bbf6ebd23dfd1324c647499c9c1ba` 及精确反射签名。其他版本/渠道包或签名不符的重打包 APK拒绝挂载。哈希匹配不证明热补丁后的方法语义相同，Tinker 仍待运行核验。
- WCDB 两个类的四种写入方法，仅在成功返回且明确普通入站群红包时入队；不依赖系统通知，不扫描群页面。专属红包、自发、私聊、旧消息和不完整参数跳过；新消息入场要求 `createTime` 距手机当前时间在 15 秒内，待真机核验其时钟语义。
- Hook 线程只做常数级字段筛选和有界复制；XML 解析、配置 IPC、原包哈希及持久登记在单工作线程。工作队列最多 32、在途最多 8；无群轮询、录屏、持续取帧或唤醒锁。超时只按当前任务/暂存条目的截止时间安排一次性计时。
- 自动模式须明确白名单群内部标识（`...@chatroom`）、确认当前测试用户及开启只读保护服务。Provider 校验同 Android 用户和模块/微信 UID；正文和完整红包凭据不写入本模块的磁盘或日志，不上传任何识别数据。候选凭据只在本进程内存中处理。
- 保护服务只读取窗口类型、包名及系统游戏分类；支持补充游戏包名。不读页面文字、不截图、不点击。初始未知、服务断开、窗口无法确认、键盘可见或游戏前台时禁止发新请求。系统、输入法、自身覆盖层沿用此前保护，息屏不能解除游戏/未知保护；有可信非游戏证据并稳定三秒后才恢复。
- 保护期间最多暂存四条未发送候选，按首次接收的原始时间最多 24 小时、不续期；恢复后依次尝试。内存队列不跨进程重启保存；队列满拒绝新候选，不把暂存时限当作仍可领取的保证。
- 请求对象按身份关联；发送返回非负、业务响应明确允许及同请求通用回调成功，三项齐全才推进开包。开包要求明确正金额 `amount`、本人 `receiveStatus=2`、业务和通用回调成功且无实名/拦截提示；请求发出不等于领取成功。卡住、异常或状态不明记为 unknown，不重放。
- 每次尝试发出前将群/红包标识的 SHA256 写入本地持久去重；共享锁覆盖多个 Provider 调用实例。2048 项满后拒绝新任务，不淘汰未知结果。关闭不受编辑中非法表单影响；关闭会取消未发送候选及状态推进，已发出的请求可能仍在微信内部完成，不能撤回。

## 本地验证

```sh
source .runtime/macos/android-env.sh
./android/YuyanIme/gradlew -p android/YuyanIme \
  :redpacket-hook:testOfflineDebugUnitTest --offline --console=plain
./android/YuyanIme/gradlew -p android/YuyanIme \
  :yuyansdk:testOfflineDebugUnitTest \
  --tests 'com.yuyan.imemodule.data.redpacket.*' --offline --console=plain
./android/YuyanIme/gradlew -p android/YuyanIme \
  :redpacket-hook:assembleOfflineDebug --offline --console=plain
```

单元测试覆盖解析、拒绝路径、发送与双回调顺序、并发红包身份关联、失败/超时/晚到回调、保护与恢复、持久去重并发、无条件关闭、实际保护服务的窗口/键盘/断开采样及一次性截止调度。Robolectric 和合成样本不证明真实微信 ClassLoader、入库路径或到账。

本地日志和测试 XML 位于 `/tmp/shurufa-hook-*.log` 及模块 `build/unix/test-results/`；构建产物按 Git 忽略规则保留本地。

### 本轮实际结果

- 新模块 33 项测试、旧红包 138 项回归：失败/错误/跳过均为 0。首轮与审查新增用例均观察过预期失败后修复；涵盖乱序回调、非法表单仍可关闭、多个 Journal 实例并发、恢复队首重复/保护竞态不续期、息屏未知和 URL 编码参数注入。
- `:redpacket-hook:lintOfflineDebug` 通过，0 Error / 0 Fatal / 6 Warning。警告为同步持久登记、targetSdk 35、已有测试库版本、需要跨进程的导出 Provider、未配置图标及字符串国际化；Provider 有调用者运行时校验。同步 commit 用于发请求前的持久去重，不能改成异步后即发送。首次离线 Lint 因工具未缓存失败，联网下载后重新完成；最终构建/测试/Lint 均可离线执行。
- `:redpacket-hook:testOfflineDebugUnitTest :redpacket-hook:lintOfflineDebug :redpacket-hook:assembleOfflineDebug --offline`：`BUILD SUCCESSFUL`。独立审查提出的保护、关闭、并发登记及恢复队列问题已修复并复审，未发现新的重要问题。
- 唯一推荐本轮模块包：`apk/shurufa-2026-10-09-v0.1.0-1-debug-f5e428e5.apk`。APK 实际包名 `com.yuyan.redpacket`、版本 `0.1.0 / 1`、minSdk 26、targetSdk 35；这是模块 debug 分享包。
- 源包和交付副本 SHA256 一致：`f5e428e5b663463d7d89a98767d3657b1f8781aa0ba475d1a9f59bb6a5c9947d`。原证书 `a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287` 在 API23/27/28/32/36 的验证均通过，实际 APK 无 testOnly 属性。
- APK 内 `assets/xposed_init` 入口已核对，DEX 定义含 HookEntry、没有打入 `de.robv.android.xposed` API 类。配置共享偏好同时从 Android 12+ 的云备份/设备迁移规则中排除，防止自动领取选择随迁移恢复；参照 [Android 备份规则](https://developer.android.com/identity/data/autobackup)。
- 本轮仅新增模块及相关文档、修改 Gradle 模块清单；原有 AGENTS、视频采集和前端未提交改动保留，没有自动提交、推送、发布或手机操作。

## 手机重连后的顺序

1. 先只读确认实际设备、微信版本、主/分身用户、安装路径及框架运行能力。已有证据表明荣耀系统分身与主微信共用同一安装包，重签同包覆盖会影响共同代码，不能当作只修改分身。当前尚未确认能只对该分身提供 Hook 环境。
2. 不直接覆盖主微信、不卸载/清数据或 Root。若无可用的隔离框架，保持代码交付状态；先评估并明确运行环境方案。本模块的严格原 APK 哈希门禁不支持直接把 LSPatch 重打包微信作为已适配环境。
3. 运行环境成立后，仅在明确测试用户中安装模块、设置框架作用域。该用户必须能运行模块配置 Provider 和保护服务，不能用主用户的配置冒充分身配置。先选择 PROBE，再由用户重启目标微信；检查 ready 信号，并核验真实 ClassLoader、Tinker、八个入库 Hook 方法及实际红包事件。
4. 用户提供测试群及新红包，核对内部群标识；只识别阶段应有候选计数而没有 Receive/Open 请求。此版本不从聊天正文提取/展示群名，也不猜测群内部标识；须在受控调试中明确核验。关闭系统通知、群免打扰、其他 App 前台、亮屏/息屏分别验证，不以一次成功代表全场景。
5. 关闭旧的界面红包助手，验证保护服务在该用户确实观察到物理主屏的应用和键盘；尤其分身可能看不到主用户窗口，此时应保持 unknown/禁止自动领取，不能为了运行绕过保护。核对游戏期间保留、退出三秒后恢复及实际手动输入避让。
6. 只有探针与作用域/保护核验通过，才由用户确认开启 AUTO，测试群使用可控新红包。分别核验发送返回值、Receive 参数/状态、Open 参数、业务与通用回调及微信中的实际领取结果。不得自动重放历史结果未知的红包，也不测试发消息/收转账。
7. 记录红包发出→手机收到→识别→请求→最终结果的独立时间证据、发现率及分位数，并测试并发、关闭、重启、更新、断网及功耗/输入延迟。当前没有发出端时间或功耗实测，不能宣称三秒必抢或低耗电已达标。

接口与构建参考：[Xposed 模块入口 API](https://api.xposed.info/reference/de/robv/android/xposed/IXposedHookLoadPackage.html)、[Hook API](https://api.xposed.info/reference/de/robv/android/xposed/XposedBridge.html)。编译依赖为官方 `de.robv.android.xposed:api:82`，仅 compileOnly，不打入 APK；下载的官方 JAR SHA256 为 `f48c635f1c7469fdec0e00ad2ea0b7a6b2f5b55065784a35b7ca3a84615e8e25`。微信签名及字段依据本地受授权安装包分析，参考工程未作为源文件直接复制。
