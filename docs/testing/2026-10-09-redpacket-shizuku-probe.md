# 手机独立副屏探针交付与真机记录

日期：2026-10-09。承接 [实施计划](../plans/2026-10-09-redpacket-shizuku-probe.md) 与 [通用静默能力研究](../plans/2026-10-09-redpacket-portable-silent-research.md)。

## 本轮实现

独立诊断模块 `android/YuyanIme/redpacket-probe`，包名 `com.yuyan.redpacket.probe`，版本 `0.2.0 / 1`，minSdk35（Android15+）、target35、compile36。这是能力探针，不是输入法升级包或红包成品。先验证现代安卓运行能力，不能据此宣称所有安卓版本均支持。

- Shizuku 13+ UserService，业务 IPC 仅接受诊断应用真实 UID；拒绝非 shell 身份开启显示。框架销毁事务另允许服务自身 UID，避免拒绝 Shizuku 的销毁操作。
- 固定 480×800 / 160dpi 私有、非 presentation、可信副屏，OWN_CONTENT_ONLY、OWN_DISPLAY_GROUP、OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED、DESTROY_CONTENT_ON_REMOVAL。无 ALWAYS_UNLOCKED，不变更锁屏、电源、输入法或全局显示设置。
- 只启动 `android.settings.SETTINGS`，显式限制 `com.android.settings`，无任意命令/包名/坐标入口。ImageReader 只取得并关闭 Image、累加帧计数，不读像素、不保存画面、不上传。
- 主屏焦点必须可确认且顶层输入显示为0；创建前后主屏窗口须一致。副屏必须 ON 且属于系统设置，发现其他窗口或未知状态停止。运行中允许正常主屏 App 切换，不把不同 App 的正常前台变化误判成抢焦点。
- 每两秒限时采样窗口/输入焦点元信息，完整 dumpsys 文本只在内存中处理；页面不显示其他应用窗口内容。不使用此诊断轮询作为正式红包产品架构。
- 五分钟会话不随重复点击启动续期；独立 watchdog 不受显示工作线程阻塞影响，到期终止本 UserService 进程，让显示 Binder token 死亡清理。停止/框架销毁还有独立三秒熔断。RPC 等待超时取消排队任务，避免过期启动任务稍后突然执行。
- 启动失败、停止、显示异常分别释放显示/读取器/帧线程；资源释放出现错误时终止本服务进程，不假称清理成功。退出诊断页面可继续限时实验；不设开机启动或常驻 UI 刷新。

## 本地验证

```sh
source .runtime/macos/android-env.sh
./android/YuyanIme/gradlew -p android/YuyanIme \
  :redpacket-probe:testOfflineDebugUnitTest \
  :redpacket-probe:lintOfflineDebug \
  :redpacket-probe:assembleOfflineDebug --offline --console=plain
```

最终 `BUILD SUCCESSFUL`，17 项测试，失败/错误/跳过均0。覆盖 caller权限、框架销毁权限、未知/丢失焦点、其他窗口、显示失效、默认屏拒绝、启动失败清理、正常切 App、原始截止时间、重复停止、工作线程阻塞时独立截止和取消/重设截止。缺失实现的 RED 及修复后的 GREEN 日志保留 `/tmp/shurufa-probe-*.log`。

Lint 0 Error、4 Warning：target35、shell 服务内使用隐藏显示反射接口、备份规则建议及诊断应用缺图标。没有持久配置或画面数据。本模块本地测试不调用真实显示服务，不证明厂商设备兼容。

审查修复了框架 destroy 调用者权限、显示 Binder 阻塞使五分钟清理失效、旧会话清理取消新启动截止计时的并发空窗。反射签名及 flags 经 Android16 AOSP 源码核验，并在本机实际创建显示验证。

首次安装的诊断 APK：`apk/shurufa-2026-10-09-v0.2.0-1-debug-b57e5927.apk`，后续已有重连修正版，见文末。
SHA256：`b57e5927fd1519118d846daef2ee7fc28895a270fcb3d18b5cafad3c9f63984c`。
构建源包：`android/YuyanIme/redpacket-probe/build/unix/outputs/apk/offline/debug/redpacket-probe-offline-debug.apk`。原签名脚本已校验 API23/28/36 证书身份；APK 仅可在其声明的 Android15+ 安装。

## 本机已执行

用户明确接受 Shizuku 设置并要求继续探针阶段。本轮在在线设备 `AQUL024807002303`（荣耀 ELI-AN00、Android16）安装了两个新应用，均主用户0：

1. 官方 Shizuku `13.6.0.r1086.2650830c`，来源 [RikkaApps/Shizuku v13.6.0](https://github.com/RikkaApps/Shizuku/releases/tag/v13.6.0)，下载 APK 大小2571773字节，SHA256 `6e273ab0e991c4e79bc8b1bbb9b9dd739ccac1a8712a541a214078886b7b790f`。APK签名校验通过，证书 SHA256 `268b5590e868fb08bae7e0ac413564cd1ff88f5ccff74af9dbd0dc918e30db30`。该 release API 未提供 asset digest，不声称做过官方公布哈希的比对。
2. 上述本地原签名诊断 APK。没有覆盖输入法、微信或系统分身安装包。

沿用已授权 ADB，执行官方 Starter 指向的安装目录 `lib/arm64/libshizuku.so`，成功启动 `shizuku_server`（uid2000）。这是有线 ADB 启动证据，不是本机无线调试启动验收。没有代替用户修改开发者设置；未来其他手机无线启动仍需按官方流程配置。

用户随后明确报告已手动点击授权、连接、启动等按钮且成功。读取当前诊断页面自己的状态，实际为：

```json
{"state":"STOPPED","uid":2000,"displayId":-1,"frames":33,"remainingMs":0,"cleanup":"NONE"}
```

同时只读核对活动显示仅0及系统原有PreloadDisplay2，本次副屏已消失；UserService 以 uid2000 运行，输入焦点显示仍0。33帧不能证明画面内容正确或到账。

助手随后在诊断页面重新启动一轮实验，实际读取：

- display8 当前焦点为 `com.android.settings/com.android.settings.HWSettings`；主屏 display0 当前焦点仍是诊断应用。
- 输入系统两处 `FocusedDisplayId` 均为0。
- 显示服务确认 display8 为 ON、480×800、归属 `com.android.shell` uid2000，并包含 PRIVATE、OWN_CONTENT_ONLY、DESTROY_CONTENT_ON_REMOVAL、TRUSTED、OWN_DISPLAY_GROUP、OWN_FOCUS、STEAL_TOP_FOCUS_DISABLED。不是只确认申请参数。
- 助手执行主屏 HOME 后，display0 焦点变为荣耀桌面，display8 设置窗口仍保留，输入顶层显示仍0。

上述证明本机 shell UserService 创建私有副屏及桌面焦点隔离的一段链路成立。没有对副屏注入触摸；没有打开微信或测试领取。未修改锁屏、微信、输入法权限与设置；临时 UIAutomator 文件只含本诊断页面并已删除，不读取聊天界面。

## 仍待验收

已请求用户拔掉USB、正常触摸/切 App约20秒，再回诊断页面刷新。用户先反馈“已停止、断开或操作异常”，进一步明确实际提示为“请先连接诊断服务”。原版页面重建后 `service` 为 null、只在用户再次点击连接按钮时绑定，因此这条提示不能证明 Shizuku/副屏在拔线后退出。本轮拔线门禁应记为**未完成验证**，不直接判断运行环境失败。此时 ADB 列表确实为空，电脑不能读取当前手机状态。若旧实验已到期，须在拔线状态启动新会话再检验，不以到期本身判定方案失败。

其后仍须：物理触摸并发、锁屏/解锁与虚拟显示状态、异常清理和实际五分钟到期、无线调试重启后的启动、多厂商测试、微信主屏接管/归还、无通知单群/多群发现、定向交互及结果核验、输入/游戏避让、功耗温度。任何红包相关步骤都不能由本次设置页探针通过直接推导通过。

本轮源码仅新增诊断模块并加入 Gradle 清单，保留此前文档改动；没有提交、推送或发布输入法成品。Shizuku 与诊断应用保留用于后续本机实验，停止副屏不等于卸载这两个应用或关闭 Shizuku。

## 返回页面重连修正

依据用户上述反馈，版本升级为 `0.2.1 / 2`：已有 Shizuku shell 授权时，返回诊断页自动连接服务并查询状态；未连接时点“刷新”也先连接，连接中重复刷新不重复发起绑定。未获授权不自动请求权限，重连不自动启动副屏。UserService版本同时升2，使框架替换旧服务。

新增3项连接行为测试，最终20项测试全部通过，Lint和离线构建再次 `BUILD SUCCESSFUL`。APK：`apk/shurufa-2026-10-09-v0.2.1-2-debug-e478b45b.apk`。该版已本地构建，手机当时仍断开，已请用户重新接线安装并复测；不能称为已修复真机拔线问题。

## 重连手机后的复测

用户重新连接 USB 后，已成功覆盖安装 `0.2.1 / 2`，包管理器版本确认一致。APK SHA256 为 `e478b45b72dc4aa337d3164b29d7871a8176dce00b914d20ed9cab02911df2c3`。

安装前后进程检查发现仅 Shizuku 管理器存活，`shizuku_server` 与诊断 UserService 均不存在。这补充说明上次断线后确实出现过服务退出，不能仅用页面未绑定解释；没有退出时日志，尚不能认定由拔线直接导致。只读开发设置显示 `adb_enabled=1`、`adb_wifi_enabled=0`。

沿用官方 native starter 重启 shell 服务后，诊断页自动连接并显示 `IDLE / uid2000`。启动设置副屏实验后，强制结束诊断应用 UI 并重开，自动恢复状态为：

```json
{"state":"RUNNING","uid":2000,"displayId":9,"frames":32,"remainingMs":293974,"cleanup":"NONE"}
```

UI 重建前后 UserService PID 均为7211，Shizuku PID7187。窗口元信息确认 display9 是系统设置，display0 是诊断页，输入系统两处顶层显示均为0。这验证了本机页面进程重建后的自动重连，尚未验证拔线存活。已再次请求用户拔线后物理操作并刷新。

后续排查参考 [Shizuku 官方手册](https://shizuku.rikka.app/guide/setup/) 对后台存活和华为“仅充电模式下允许 ADB 调试”的说明；本机为荣耀，须核验实际选项及可重复退出证据，不能直接套用华为结论或擅自修改全局设置。

本轮拔线后，用户明确反馈“提示 Shizuku 未启动或已断开”。电脑同时确认 ADB 列表为空。故拔线存活门禁仍未通过；自动重连已经验证的结论仅限服务可用时恢复页面连接。下一步核验开发者选项中的仅充电 ADB 条件，并在必要时使用用户已接受的无线调试启动流程，不能继续把该问题归为页面状态。

## 开启仅充电 ADB 后复测

用户报告已开启“仅充电模式下允许 ADB 调试”并连接手机。初次 macOS USB 可识别 ELI-AN00，但 ADB 设备列表为空；重启电脑端 ADB 后仍为空。用户确认重新开启 USB 调试后，ADB 恢复为 `device`。没有改写手机全局设置。

再次发现 Shizuku 服务不在，使用同一官方 starter 重启（PID12923），诊断服务自动连接为 `IDLE / uid2000`。第一次启动返回 `SECONDARY_UNVERIFIED`，帧数0，副屏已释放，主屏焦点0，清理结果NONE；未采集到失败瞬间的副屏元信息，暂不能区分显示开启、窗口附着或厂商启动时序问题。没有放宽校验。

第二次启动实际读取 `RUNNING / displayId11 / frames13 / remainingMs299118 / cleanupNONE`。display11 窗口为系统设置 HWSettings、display0 为诊断页，输入系统两处 `FocusedDisplayId` 均0。已请求用户再次拔线并物理操作后刷新。首次启动偶发校验失败仍待复现定位，不因重试成功记为修复。

用户随后明确拔线刷新后显示 `SECONDARY_UNVERIFIED`，并指出反复拔插没有解决问题。此反馈不同于此前 Shizuku 断开提示，不能再统一归为服务死亡。现有失败码把显示未ON、副屏焦点缺失和其他副屏窗口合并，且清理后目标显示ID变为-1，缺乏根因证据。停止重复人工拔插，先补观测再定位。

## 0.2.2 失败记录补充（尚未安装）

- 保存 SECONDARY_UNVERIFIED 清理前的目标显示ID、顶层输入显示、显示是否ON、副屏窗口分类及阶段START/MONITOR。具体分类为DISPLAY_NOT_ON、SECONDARY_WINDOW_MISSING、SECONDARY_WINDOW_OTHER；不保留实际窗口标题或其他应用包名。校验失败仍立即停止，没有加延时重试或放宽校验。
- 在清理完成路径将上述结构化诊断写到本模块专用 `YuyanProbe` 日志。后续仅筛选这个tag读取，不收集其他应用日志。正常系统日志缓冲可能滚动丢失；若清理调用本身阻塞到硬熔断，日志仍可能没写出。
- 页面成功取得失败响应后，存到自己的私有偏好；重开或断开时以“上次失败记录（历史状态）”展示，不冒充当前状态。页面未取得的失败依赖上述日志，不能承诺全部离线故障都持久保存。仅细化SECONDARY_UNVERIFIED，不宣称其他失败已有完整诊断。
- 版本0.2.2/code3，UserService版本同升3。新增4项回归，先因Failure接口缺失编译RED，再实现并GREEN；最终24测试、0失败/错误/跳过。Lint 0Error/4Warning，离线构建成功，独立只读复核未发现阻止交付的问题。
- APK `apk/shurufa-2026-10-09-v0.2.2-3-debug-4bc3fe22.apk`，SHA256 `4bc3fe225c4a381da5e971345953de5769dd5e8e1e272c110941287e2f367cc5`。aapt核对0.2.2/3，apksigner确认仍为原证书 `a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。

本次手机仍未通过ADB连接，未安装该版，不声称副屏问题已修复。下一轮先一次连接保持有线，自动重复启动及返回桌面，采集枚举化失败报告并定位，最后才进行必要的拔线验收。

## 重新连接后的受控定位与0.2.4

用户再次连接后，已安装0.2.2；Shizuku PID12923及旧UserService仍存在，未重启Shizuku。升级UserService后自动连接。先完成两轮启动/回桌面，再完成四轮带桌面双向滑动的测试，其中两轮额外切换到Shizuku管理器；四轮均RUNNING，显示16–19，返回刷新时仍RUNNING，最终STOPPED并清理。

受控在主屏打开系统设置时，系统将副屏Settings任务迁回主屏，随后记录：

```json
{"state":"SECONDARY_UNVERIFIED","uid":2000,"displayId":-1,"frames":44,"remainingMs":0,"cleanup":"NONE","failure":{"phase":"MONITOR","reason":"SECONDARY_WINDOW_MISSING","displayId":14,"topDisplay":0,"displayOn":true,"secondaryKind":"MISSING"}}
```

这复现了一种明确失败来源，不证明与用户此前所有拔线错误原因相同。0.2.3增设MAIN_SETTINGS_IN_USE分类：主屏安全且显示设置窗口时停止并释放副屏，不自动恢复、不放宽安全门禁。新增1项回归先RED后GREEN，共25测试。实机另一次接管恰逢主屏焦点短暂未知，先触发MAIN_FOCUS_LOST，仍保持停止；不能承诺每次接管都被该分类捕获。

0.2.3再次捕获启动期窗口尚缺失：START / SECONDARY_WINDOW_MISSING / display21 / top0 / displayOn true / frames0。参照 [Android16 ActivityStarter.waitResultIfNeeded](https://android.googlesource.com/platform/frameworks/base/+/android16-qpr2-release/services/core/java/com/android/server/wm/ActivityStarter.java)，复用已运行Activity可能直接返回，am-W成功不能作为副屏窗口就绪证明。由此补充0.2.4启动就绪等待：仅允许主屏安全且完全不变、显示ON、目标窗口为空时有限等待；其他窗口、显示OFF、主屏变化/未知都立即交由原门禁停止。1500ms是重试预算，正在执行的有限系统命令可能超出该时间；不是1500ms硬熔断。运行期副屏未知仍停止。

五项StartupFocus回归先因实现缺失RED，再GREEN。最终30测试，0失败/错误/跳过；Lint/离线构建通过。复核未发现Important。0.2.4/code5、UserService5已安装，随后连续五轮启动全部RUNNING，显示23–27，failure均null；这只是本机有限样本，不保证未来永不失败。

最新APK：`apk/shurufa-2026-10-09-v0.2.4-5-debug-f2b9ec16.apk`；SHA256 `f2b9ec169268d387a45361bad8343336930e69ed3f5898575963288de01d385b`。

已按用户此前接受的无线调试配置进入系统开发者设置，确认USB与仅充电ADB均开启，启用无线调试并恢复当前Mac已有配对的TLS ADB连接。没有新建配对、启用经典明文5555端口或改锁屏/电源选项。开发者页面UI树持续不空闲，临时仅查看了系统设置截图用于定位入口，文件已删除；没有截图微信或采集副屏像素。一次滑动未停稳导致进入模拟位置应用选择页，已直接返回，未选择任何应用或修改模拟位置设置。

已请求用户只拔掉USB、保持Wi-Fi，由电脑通过无线持续读取脱敏焦点/显示ID/相关进程元信息，电脑将自动完成后续滑动与切换，不再要求插回线才能获得结果。当前观察文件 `/tmp/shurufa-probe-wireless-observe.jsonl`，自动复现记录 `/tmp/shurufa-probe-controlled-repro.log`，本机脚本只在被忽略的.runtime目录。此阶段仍未打开微信或领取红包。

## 脱线及五分钟到期实测结果

用户随后确认已拔掉USB。ADB仅剩已配对的TLS无线连接；同一会话实际返回 `RUNNING / display27 / frames35 / remainingMs199475 / failure null`，Shizuku PID12923、诊断服务PID28465均与拔线前一致。脱敏观察也记录usb由true变false，副屏设置与顶层输入显示0保持不变。

通过无线执行三轮HOME、桌面双向滑动、切换Shizuku管理器及返回诊断页刷新，三轮均RUNNING，display27不变，remainingMs依次162137、146534、130834，failure均null。无物理触摸并发验收，不能用注入滑动替代真实触摸体验结论。

另验证电脑调试连接完全断开：首次仅停ADB服务器会被电脑端自动恢复，因此不计为完整断联证据。随后临时禁止电脑ADB的mDNS自动连接，连续20秒每5秒检查设备数均0；自动重连后仍是同一display27、服务PID28465，状态RUNNING、frames52、remainingMs9826、failure null。USB始终未插回，证明此限时会话不依赖该电脑保持ADB连接。测试后已恢复电脑ADB默认自动连接行为。无线调试保留开启，沿用用户接受的部署条件；尚未测试重启手机后由Shizuku无线配对自行启动。

未点击停止或续期，继续等待原会话截止。随后只读核对display27与诊断PID28465均消失，仅默认display0及原有display2，Shizuku PID12923仍在，输入系统两处FocusedDisplayId均0。与到期前剩余9826ms的快照及12秒等待一致，证明该本机会话的五分钟截止释放成立；不证明所有Binder阻塞情形均经真机测试。

最新0.2.4原证书校验通过，Lint仍0Error/4Warning，30单元测试全部通过，`git diff --check`无错误。未提交、推送、安装输入法成品或操作微信红包。当前已验证的是本机Android16系统设置探针的启动、有限切换、脱USB/电脑调试连接存活及到期清理；实际微信交还、定向交互、发现、领取、锁屏、游戏/输入保护和多厂商兼容仍未验收。

结束时重新刷新已重建空闲诊断服务，当前 `IDLE / displayId-1 / frames0 / remainingMs0 / cleanupNONE / failure null`，没有重新启动副屏。页面仍保留旧display21的历史失败记录，不能将历史字段误读为本轮新失败。恢复电脑ADB默认配置后的首次UI读取因电脑ADB重启中断，随后无线重连成功、已清理自身临时UI文件；没有要求用户重新插线。
