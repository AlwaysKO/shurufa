# Android 端接入说明（YuyanIme）

> 目标：在 [gurecn/YuyanIme](https://github.com/gurecn/YuyanIme)（语燕输入法，基于 Rime 的现代 Android 中文输入法）基础上接入本项目后端，实现：
> ① 输入/粘贴/复制/语音行为上报；② 设备档案注册；③ 个人补全模型同步（后续阶段）。

## 2026-09-16：已上屏编辑历史（代码更新，未发布/真机验收）

- 行为明细默认按明确编辑会话显示最新整段，展开保留每次输入、删除及前后文本；保留“原始操作”切换。搜索已删文字、时间或类型时，先匹配组，再按组分页并返回整组，不截断成员后拼句。旧记录无可靠会话证据仍按片段显示。
- Android 成功编辑后先写现有 SQLite 双端队列，仍按批上报，不等句子结束才落盘。协议增加 `session_id`、从 1 递增的 `sequence_no`、`text_before/text_after` 及 `metadata.edit_protocol=1/snapshot_complete`，无需数据库迁移。清空、输入目标重启、成功编辑器动作、快照不连续或未知后分开会话，不按时间间隔猜测。
- 删除只在宿主接受操作后记录；组合态文字不算已上屏，真正结束组合后才进入记录。密码框、禁止个性化字段、总同步开关与敏感内容过滤继续适用，前后快照也经过过滤，不通过快照旁路采集。
- “完整快照”仅表示已收到的编辑链连续，不证明无后续离线待传操作，也不代表消息已发送。局部/超过 5000 字/不可读快照为未知；原始输入沿用 5000 字上限并标记截断。宿主不提供组合样式时不猜范围，宁可保留原始片段并标记不完整，避免未确认文字入库。不能承诺对所有宿主和系统故障绝对无遗漏。
- 输入批次新增完整 JSON 的 1 MiB UTF-8 预算，最多 500 条；只确认实际发送的前缀，其余下轮继续，避免前后快照使积压批次超过服务端 10 MiB 限制。
- [Android ExtractedText 协议](https://developer.android.com/reference/android/view/inputmethod/ExtractedText) 中 `partialStartOffset=-1` 表示全量文本；同时检查 `startOffset=0` 与长度上限。宿主无法提供文本时不会伪造空输入框。
- 实现与本地验证记录：`docs/plans/2026-09-16-committed-edit-history.md`。服务端/网页与 APK 必须配套更新才具备新会话证据；历史缺失数据不回填、不推测恢复。

## 2026-09-08：网页登录与可靠同步（本轮）

- **网页后台**新增登录、退出及服务端会话鉴权。本地 `admin / adminhaha`；线上必须配置独立 `DASHBOARD_USERNAME`、至少 16 字符的 `DASHBOARD_PASSWORD` 与实际网页 Origin。不要把默认开发密码用于线上，APK 不包含后台密码。会话重启失效；多实例需要共享会话（本轮为单进程）。
- **首次授权**：安装或升级后在应用设置主页明确确认「个人数据同步」；此前不注册/上报采集数据。确认后保持开启，可在「其他」关闭；位置另受独立开关和系统权限限制。关闭暂停新采集和待传发送，已有待传记录保留，重新启用继续。原本单独启用的剪贴板/聊天入口也受总开关控制，不自动新增系统权限。
- **敏感过滤**：保留密码/无痕输入过滤，补充可识别的认证字段、数字输入框、独立短数字和敏感剪贴板标记；语音、删除、剪贴板同样走过滤。聊天在 Room 落库前逐条过滤。未正确标识的敏感内容不可能保证自动识别，输入此类内容前应关闭同步。
- **地址**：查询默认 `https://my.dog8ball.com`，不再随电脑断开而失效。网页后台“数据管理”可设置新的线上 HTTPS 根地址；手机每五分钟至多向当前线上后台读取一次配置，验证后持久化，并在同一 SQLite 事务中把尚未确认的旧线上目标迁移到新地址，电脑目标不变。旧域名必须在迁移窗口内继续可达；已彻底失效的旧域名无法自行告知新地址。线上上报始终独立执行；电脑 API 仅在端侧确认 USB 数据连接且目标 `/health` 返回 `{"status":"ok"}` 后才发送，电脑地址默认 `http://127.0.0.1:3000`，通过 `adb reverse tcp:3000 tcp:3000` 访问。无线调试或只有 USB 供电不满足本地上传条件；`localhost:5175` 是网页，不是手机 API。
- **可靠队列**：`local_input.db` 无损升级，保留学习与既有事件，新增通用报告及各目标待传状态。输入事件每轮最多 500 条，普通报告每轮最多 20 条，另限制批次正文体积，大媒体按 SQLite 文本片段读取，避免 CursorWindow 大行错误；新输入约 5 秒触发，正常周期 30 秒，失败最少 30 秒再试，并轮转保留失败项，避免错误记录阻塞后续正常数据。两端独立并行，一端离线不拖住另一端；仅目标确认成功才清除它的状态。聊天文字和截图两端确认后立即清理手机正文；线上已确认但本地未确认时最多保留七天后清理；线上未确认则不执行过期删除。旧版已线上确认而只剩本地目标的聊天报告从升级首次识别时重新计算七天。
- **系统补传**：网络恢复回调唤醒；带联网约束的持久 JobScheduler 周期任务负责进程回收/重启后的补传（最小 15 分钟周期，省电模式可能推迟；系统强行停止应用需重新打开后恢复）。不保证 Android 限制后台时立即上传。
- **候选/词频**：本地选词学习和个人统计快照在同一 SQLite 事务落盘。快照通过 `personal_choice` 存到 `personal_candidate_usage`，旧快照不覆盖新计数；补全接受反馈、常用语新增/使用和表情使用均持久双传。原事件分析看板仍按后台分析任务周期刷新（默认 10 分钟），不能把“已入库”误认为“看板已重新聚合”。
- **服务端协议**：新增 `POST /api/v1/mobile/reports`，接受稳定 `{id, kind, payload}`，仅 `{ok:true,id:<同一ID>}` 确认通用报告。迁移 `013_durable_reports.sql` 创建回执、个人选词、候选反馈及表情文件使用表。回执和数据效果同事务，重复重试不加次数；目标缺少对应云端候选/素材也保存独立反馈统计。
- **聊天**：保留原 Room 队列与消息/资源幂等协议，成功持久交接到双端队列后才清理原队列；媒体正文以 Base64 进入手机持久队列，之后不依赖可能被清理的 cacheDir 文件。

### 部署与验收顺序

1. 更新后端代码，在对应数据库执行 `server/migrations/013_durable_reports.sql`（或按原部署流程 `npm run migrate`），配置后台凭据后构建/重启。新 API 尚未部署时，APK 保留待传报告，不降级到可能重复计数的旧接口。
2. 更新网页构建产物和 Nginx 模板；后台反代必须保留 `Host $host`，或设置准确的网页 `DASHBOARD_ORIGIN`。
3. 安装新 APK，打开设置同意同步，连接 USB 后执行 ADB reverse；输入普通测试文字，检查两端数据。断开电脑应只有本地待传，恢复后补齐。断网输入、重启、联网重试不应丢失或重复。
4. 在设置关闭同步，确认新内容不入队、已有报告停止发送；密码/无痕/验证码输入不入库。重新开启继续原待传数据。

**边界**：普通事件及线上未确认的聊天数据继续保留；只有已经线上确认、仅电脑端未确认的聊天文字和截图按上述七天上限清理。当前移动 API 的设备 UUID 是旧协议身份标识，并非强设备认证；网页登录不会自动解决移动接口的凭据问题。不要据此把整套系统视为已完成公网安全加固。

以下历史章节用于说明旧实现；与本节冲突时以本节为准。

## 2026-09：本地优先九宫格与输入事件双端补传

- 九宫格保留 Rime，在未锁拼音/未分段时补充本地词典：全拼、首字母及混合输入；例如 `46898262`（hou xuan c）和 `468982624` 均可检索“候选词”。词库随 APK 打包，无需联网。手动锁拼音后回到 Rime 结果，不覆盖用户约束。
- 成功上屏后按原始数字编码或标准全键拼音编码保存选词次数，重启保留；密码框及 `IME_FLAG_NO_PERSONALIZED_LEARNING` 不参与新增学习和 commit 上报。保留既有 Rime 用户词库，不清除旧习惯。
- 输入事件和设备注册同时发往本地与 `https://my.dog8ball.com`。本地为设置项 `server_url`（未配置时 `http://127.0.0.1:3000`）；本轮已统一输入、位置、反馈、常用语和已启用聊天的双端队列，查询主地址固定线上。
- 事件保存在手机 `local_input.db`，两端分别确认，失败按 30 秒周期重试，一端离线不阻塞另一端；两个目标都确认才清理事件。原有内存队列丢掉的历史输入无法恢复。选词编码附在事件 `input_code` 字段。
- 云端联想的候选内容和版本现在存为同一个原子快照，按设备/服务地址隔离；旧安装只有版本号而无快照时从 0 重新同步。旧后端尚无可靠快照分页协议，`has_more=true` 时保留已有缓存，不把不完整数据标记为同步完成；该限制不影响新的本地九宫格词典。
- 本地管理页 `http://localhost:5175/` 是看板，不是手机上报地址。需在看板选中实际手机用户；接口缺少 `user_id` 会返回 400，不代表数据库为空。

### 2026-09-08：中文领域与个人排序

- 基础词典 130,860 条，另有 347 条自维护聊天、办公、互联网/编程中文表达（部分重叠，按文字去重）；没有新增英文词库，也未复制搜狗专有词库。
- 安卓九宫格与标准全键拼音使用本地个人排序；电脑示例 `xuq` 仅用于说明需求，未开发电脑端。英语、双拼、繁体不接入本轮新增模型。
- 基础排序作为平滑先验（首选 2，其余 1/(位置+1)），加上同码下个人衰减选择次数。等价于比较平滑后的选择占比，十四天半衰期；每次先衰减旧权重再加一，不会把多年总次数一次性刷新。一次非首选不会压过固定的强默认，反复选择会提升；Rime 自身学习仍保留。
- `local_input.db` 从 v1 无损升级至 v2：累计次数和待传事件保留，新增个人权重。数字码与字母码分开统计，手动锁拼音/分段选字后使用原生结果，不把部分上屏误学成整码词。
- 候选重排去重保留原生选择索引，后续翻页按原生首屏数量映射；自定义手工候选仍在前。网络不参与逐键排序，双端补传保持原先独立确认机制。

### 手机连接和验收

```bash
source ~/android-tools/env.sh
adb devices -l
adb reverse tcp:3000 tcp:3000
adb logcat -s ShurufaCollector OfflineT9
```

1. 安装本轮新 APK，打开输入法，断网输入上面两组数字，应能选择“候选词”。也测试输入法/常用词混拼，而非只测这一条。
2. 标准全键输入 `xuq`，若“续期”在前，连续选择“需求”几次，再输入检查其提升；九宫格以对应短码 `987` 独立验证。重启输入法检查习惯保留；删除、分词、左侧锁拼音、切换全键/繁体、原生翻页不应错选。
3. 断网输入后退出应用，再恢复网络和 adb reverse；等待至少 30 秒，本地看板的事件明细应显示新事件。线上故障时本地仍能收到，线上恢复后补传相同 id 不重复计数。
4. 若无 USB，通过局域网配置电脑可达 IP；手机上的 127.0.0.1 是手机本身，不是电脑。Debug 设置可改 `server_url`；Release 默认本地目标需要 adb reverse。
5. 线上需要域名对应的有效 TLS 证书，并将 `/api/v1/mobile/*` 转发至该项目后端。不得以跳过证书校验解决连接失败。部署模板的旧域名不会自动变更，需由服务器部署同步配置。

> 2026-09-07 初查：电脑看板/API 可达，真机最近的本地事件为 2026-08-31；当时 ADB 无已连接设备。早期线上域名证书校验失败；22 点后重新检查已返回健康响应，API 路由也可达。这些是环境证据，不等于已完成真机双端验收。

## 0. 当前实现状态（2026-08）

以下模块已在 `yuyansdk` 中落地，编译通过后即生效：

| 文件 | 职责 |
|------|------|
| `data/collect/DataCollector.kt` | 设备注册、事件队列批量上报（30s/500条）、位置采集（每分钟） |
| `data/collect/ServerConfig.kt` | Debug/Release API 地址强隔离，Debug 可由设置项覆盖 |
| `service/ImeService.kt`（埋点） | `commitText` 上屏、`deleteSurroundingText` 删除、位置权限请求、语音输入（`startVoiceInput`） |
| `service/ClipboardHelper.kt`（埋点） | 剪贴板变化（复制）上报 |
| `application/Launcher.kt` | 启动时初始化采集（子线程） |
| `keyboard/*`（语音入口） | 键盘菜单「语音输入」项：`SkbMenuMode.Voice` → `InputView.startVoiceInput()` → `ImeService`（系统 SpeechRecognizer） |
| `service/capture/PassiveChatAccessibilityService.kt` | 前台聊天页截图入口；页面变化、发送动作与通知辅助信号汇合后裁剪并去重 |
| `service/capture/PassiveNotificationListener.kt` | 只采微信、QQ、抖音的新来通知，不取消通知、不回复、不触发 PendingIntent |
| `data/capture/notification/NotificationParser.kt` | 提取对方文字及语音、图片、视频号、文件、链接等占位类型 |
| `data/capture/ActiveChatContextStore.kt` | 旧即时回复上下文存储；截图采集模式连接后会清空 |
| `data/relationship/RelationshipReplyPolicy.kt` | 截图统一分析上线前硬关闭即时关系回复请求 |

## 微信、抖音对方新消息采集与后续 AI

2026-09-16 起，微信聊天截图不再以系统通知作为主触发。微信处于前台时，辅助服务监听窗口内容、非输入框文本、明确的“发送”点击和页面切换；输入法确认回车/发送动作已由宿主接受后，也会直接请求一次延迟截图。普通文字刚写入输入框、浏览旧记录和普通页面点击都不会触发截图，避免把尚未发送的草稿或滚动查看误当成新聊天。用户点微信自身的“发送”按钮时，由明确点击及随后页面变化事件触发；对方在已打开聊天页回复时，由内容或文本变化触发。常规设备仍由页面适配器确认标题和聊天输入框；荣耀 ELI-AN00 实测微信会返回 `[0,0][0,0]` 空无障碍树，因此增加保守兜底：仅在会话列表时间行点击或明确“发送”点击后延迟截取当前微信窗口，裁掉底部输入区域，并以资源 SHA-256 和消息指纹去重。直播间、普通列表和无明确进入会话动作的页面不触发该兜底，也没有持续高频截屏轮询。

“对方发来消息后，用户只点开聊天查看而不回复”也属于必须截图的场景：进入会话产生的窗口状态或内容变化即可触发，不要求发生输入、发送或通知正文解析。用户点击收到语音旁的微信“转文字”后，普通节点设备由内容变化触发；荣耀空树设备在 1.5 秒和 6 秒各尝试一次当前聊天截图，相同画面按 SHA-256 丢弃，文字出现后的变化画面保留。截图只包含当前可见的聊天区域；如果新消息或转写文字没有出现在当前可见区域，端侧不会自动滚动查找。

当前只启用已经具有保守页面识别规则的微信前台截图适配器。QQ、抖音仍保留通知解析和打开后辅助兜底，但在取得可区分私聊与直播/评论区的真实节点夹具前，不把通用输入框猜成聊天页，也不宣称其前台无通知截图已经实现。

通知链路仅保留为辅助：聊天页未打开时记录隐藏消息的待打开信号，用户随后进入目标应用才尝试补一张当前可见页面。关闭“通知显示消息详情”时，`1个联系人发来1条消息`、`[有人@我]1个联系人发来1条消息` 等占位内容不得直接上报。前台聊天页截图不要求通知正文可见，也不要求先收到通知。不自动点击通知、不切换会话。`登录 Windows/Mac 微信` 等系统状态直接忽略。
服务端同时拒绝旧客户端继续上传上述隐藏占位和桌面登录提示，防止未升级 APK 在后台重新产生无价值记录。
通知正文直接保存，语音、图片、视频号/视频、文件、链接、位置、名片、小程序、红包和转账按类型保存；
通知若提供可读图片资源则一并上传，并保留正文判断出的语音、视频、表情等原媒体类型；不可读的语音、图片、表情/GIF、视频/视频号、文件和卡片类通知也会进入有界 FIFO 截图队列（同一通知去重，最多保留 20 个、等待最多 10 分钟），不会再由后一条覆盖前一条。截图只能保存当时可见画面，不能取得原始语音、视频或 GIF 完整动画。服务端 `.env` 无需配置 `DEEPSEEK_API_KEY`，客户端也不请求即时 AI 回复。

2026-09-14 根据用户真机反馈补充：同一个仍在通知栏中的微信语音/视频通话状态更新只采集一次，服务端再对 5 分钟内相同联系人、相同通话状态做防重复；通话状态会触发一次支持截图。抖音保留 Android `MessagingStyle` 私聊通知的原有解析；标题为“抖音”的隐藏详情新消息只登记待打开信号，不把通知占位文字上报。直播开播、直播间活动和营销通知既不入库，也不触发截图。截图经 `/api/v1/mobile/chat/assets` 上传并和消息关联，后台聊天采集页直接显示缩略图，并按微信/抖音分别标识、分别去重。系统仍只能截取当前已解锁、前台可见的聊天页，无法在后台静默打开指定会话；“键盘未展开”限制只适用于通知辅助兜底，前台聊天页主链路会按输入框上沿裁掉键盘和输入区。

后台聊天采集页可删除当前选中的整个会话，操作前二次确认；会话消息和关系分析记录级联删除，仅由该会话引用的截图媒体同步删除，仍被其他消息引用的共享媒体保留。

普通聊天通知以系统消息时间和内容共同参与指纹，不同时间真正发送的相同内容仍会保留；语音/视频通话这种持续更新的状态通知另按通知生命周期和服务端 5 分钟窗口去重。
| `data/capture/*` | 聊天消息指纹、Room 离线队列、媒体裁剪、资源去重及失败重试上传 |

> 2026-09-16 已在荣耀 ELI-AN00 验证微信空树兜底能生成 WebP 会话截图并完成线上目标确认。微信完整节点夹具、QQ 与抖音专属页面适配器仍按 `docs/plans/2026-08-20-phone-deferred-execution-design.md` 延后，不能据此宣称三款应用均已完成真机覆盖。通知标准字段解析、通用协调/去重链路和媒体处理已有自动测试覆盖。

配置项（设置路径：**设置 → 其他**，已接入 UI）：

- `server_url`：「电脑 API 地址」输入项；手机必须填写电脑可访问的局域网地址，`127.0.0.1` 只在已执行 `adb reverse tcp:3000 tcp:3000` 时可用
- `location_tracking_enable`：「位置采集」开关，默认 `true`，关闭立即停止定位监听

---

## 1. 克隆与构建

```bash
cd android/
git clone https://github.com/gurecn/YuyanIme.git
cd YuyanIme
# 重要：yuyansdk 是 submodule，必须初始化，否则模块为空
git submodule update --init --recursive
```

- 要求：Android Studio 最新稳定版；SDK `compileSdk 36`、`minSdk 23`、`targetSdk 36`
- 模块：`:app`（壳应用，applicationId `com.yuyan.pinyin`）、`:yuyansdk`（全部逻辑）
- 构建产物仅支持 `arm64-v8a`（`app/build.gradle` 中 abiFilters 已限制）

### Windows / WSL 交替构建的 KSP 路径隔离（2026-09-08）

错误中同时出现 `E:\Projects\...` 和 `/home/ko/.../build/unix/...`，表示两种路径进入了同一套构建状态，不是手机安装失败。之前虽然已隔离 `build/windows` 与 `build/unix`，但仍共用根目录 `.gradle/8.9/executionHistory`，其中实际同时发现了两套绝对路径。重启 Studio 不会可靠清理这些磁盘状态，`ksp.incremental=false` 也不隔离 Gradle 的任务执行历史。

当前配置：

- Windows Android Studio / `gradlew.bat`：`gradle.properties` 设置 `org.gradle.projectcachedir=.gradle/windows`，Tooling API 同样生效。
- WSL：必须使用项目 `./gradlew`；POSIX wrapper 在启动时传入 `--project-cache-dir <项目>/.gradle/unix`。不要绕过 wrapper 直接执行 `gradle`，否则需要自己显式传这个参数。
- Cygwin / MSYS 使用 Windows Java，保留 Windows 默认缓存目录。
- 不在 `settings.gradle` 动态改缓存目录：Gradle 8.9 的最小探针证明那里已太晚，执行历史仍写进旧目录。
- 旧的共享执行历史不再使用，无需删除源码、用户数据或全局依赖缓存。修改后在 Studio 执行一次 **Sync Project with Gradle Files**，然后正常 Run。
- 不要同时运行两边构建：根 `clean` 仍会影响共享工程；SDK 配置按下节固定，不再来回改写 `local.properties`。
- 回归：`python3 android/YuyanIme/tools/test_build_cache_isolation.py`，验证实际 POSIX 启动参数（含带空格的 JAVA_HOME）及 Windows IDE 默认值。

官方说明：[Gradle 项目缓存参数](https://docs.gradle.org/current/userguide/project_properties.html)，[项目缓存与构建目录](https://docs.gradle.org/current/userguide/directory_layout.html)。

### SDK location not found：共享配置被 WSL 覆盖（2026-09-14）

本机 WSL 工程是 `E:\Projects\shurufa-android\YuyanIme` 的绑定挂载，两端共用同一个 `local.properties`。旧 `~/android-tools/env.sh` 每次 source 都把 `sdk.dir` 改成 `/home/ko/android-tools/sdk`，导致 Windows Studio 找不到 SDK；重启 IDE 不保证修复此文件。

- `local.properties` 固定保留 Windows 路径：`sdk.dir=E:/AndroidSDK`（本机已核实 SDK 36 存在；其他机器使用实际路径）。该文件不要提交 Git。
- 本机 `~/android-tools/env.sh` 已移除写入 `local.properties` 的逻辑，只导出 WSL 的 `JAVA_HOME`、`ANDROID_HOME` 等环境变量。
- WSL 使用下方 source + `./gradlew`。Windows 路径在 Linux 不存在时，当前 Android Gradle 插件会回退到有效的 `ANDROID_HOME`；可能提示无效 `sdk.dir` 警告，不需要为消除警告改写共享文件。
- 不再执行旧计划中的“先覆盖 Linux 路径、构建后恢复”操作，也不要让后台任务恢复旧备份。此说明取代下述调试指南和旧计划中的 SDK 临时切换办法，不改变其他业务要求。
- Windows Studio 重新 **Sync Project with Gradle Files** 后构建；不用删除全局缓存或重装 SDK。
- 环境脚本回归：`python3 android/YuyanIme/tools/test_sdk_environment.py`（仅本机有该环境脚本时执行，否则跳过）。
- 本轮验收：真实 Gradle SDK 探针在 WSL 解析为 `/home/ko/android-tools/sdk`，Windows 解析为 `E:\AndroidSDK`，两端均找到 `platforms/android-36/android.jar` 且任务成功；共享文件保持 Windows 路径。未运行本轮完整 APK 构建或 IDE 界面验收。Windows 验证沿用 Studio 项目配置的 JDK 17（`E:\Java\microsoft-jdk-17.0.16\jdk-17.0.16+8`），不要将 Gradle JDK 改成新版 Studio 自带的 JBR 25：本轮命令行探针误用它时出现 `Unsupported class file major version 69`。

### WSL 命令行构建（已验证）

WSL 内已配置完整工具链（`~/android-tools/`：JDK 17.0.20 + SDK 36 + build-tools 34/36 + debug keystore），构建前加载环境：

```bash
source ~/android-tools/env.sh
cd ~/project/shurufa/android/YuyanIme
./gradlew :yuyansdk:assembleDebug --configure-on-demand   # 仅 SDK 库（快）
./gradlew :app:assembleOfflineDebug --configure-on-demand  # 完整 APK
# 产物：app/build/outputs/apk/offline/debug/yuyanIme_*_debug.apk
adb install app/build/outputs/apk/offline/debug/yuyanIme_*_debug.apk
```

已解决的构建问题：

- **镜像**：`dl.google.com` 在本网络 TLS 被阻断，`~/.gradle/init.gradle` 已全局注入阿里云镜像（google/central/gradle-plugin）
- **keystore bug**：`app/build.gradle` 在无 keystore 配置时 `rootProject.file(null)` 崩溃（YuyanIme 原版问题），`env.sh` 通过 `RELEASE_STORE_FILE` 等环境变量注入调试 keystore 绕过；调试包用 `~/android-tools/debug.keystore`（alias `androiddebugkey`，密码 `android`）签名
- **`--configure-on-demand`**：跳过 `:app` 配置时可避免 keystore bug（只构建 yuyansdk 时）

## 2. 后端 API 速览

| 方法 | 路径 | 用途 |
|------|------|------|
| POST | `/api/v1/mobile/device` | 注册/更新设备档案 |
| POST | `/api/v1/mobile/session` | 结束会话（可选） |
| POST | `/api/v1/mobile/events/batch` | **核心**：批量上报事件（幂等，≤500 条/次） |
| POST | `/api/v1/mobile/location` | 位置上报（每分钟，服务端去重） |
| GET | `/api/v1/mobile/completions?since=N` | 增量同步补全候选（阶段 2） |
| POST | `/api/v1/mobile/completions/feedback` | 汇报候选展示/接受（阶段 2） |
| GET | `/api/v1/dashboard/locations` | 后台位置轨迹（地图+列表） |
| POST | `/api/v1/mobile/chat/assets` | 上传聊天截图或通知媒体资源（PNG/WebP，≤5MB，按 SHA-256 去重） |
| POST | `/api/v1/mobile/chat/messages/batch` | 批量写入聊天消息（≤200 条，要求依赖资源已上传） |
| GET | `/api/v1/dashboard/chat/conversations` | 查询聊天会话列表 |
| GET | `/api/v1/dashboard/chat/conversations/:id/messages` | 分页查询会话消息 |

- 查询 Base URL（Debug/Release）：`https://my.dog8ball.com`。
- 本地补传目标（Debug/Release）：`http://127.0.0.1:3000`，由 `adb reverse` 转发到电脑；可在设置中改为电脑 HTTPS API。
- 服务端按固定 `user_id` 归属数据（当前单用户），无需鉴权

## 3. 第一步：添加网络依赖

`yuyansdk/build.gradle` 已含 `kotlinx-serialization-json`（JSON 序列化直接用）与协程。**无 HTTP 库**，需添加：

```gradle
implementation 'com.squareup.okhttp3:okhttp:4.12.0'
```

## 4. 第二步：实现上报模块（DataCollector）

新建 `yuyansdk/src/main/java/com/yuyan/imemodule/data/DataCollector.kt`，职责：

1. **设备注册**：应用启动时（`Launcher.onCreate`）上报一次，携带完整设备档案：
   ```kotlin
   val info = DeviceInfo(
       id = deviceUuid(),            // 持久化 UUID（首次生成后存 SharedPreferences）
       name = "我的手机",             // 可让用户自定义
       platform = "android",
       model = Build.MODEL,          // 24031PN0DC
       os_version = Build.VERSION.RELEASE,        // "14"
       app_version = BuildConfig.VERSION_NAME,
       brand = Build.BRAND,                       // Xiaomi
       sdk_int = Build.VERSION.SDK_INT,           // 34
       screen_resolution = getScreenResolution(), // 1220x2712
       locale = Locale.getDefault().toLanguageTag(), // zh-CN
       region = Locale.getDefault().country,      // CN
       hardware = Build.HARDWARE,                 // qcom
       rom_version = Build.DISPLAY,               // HyperOS 1.0.8.0
       ram_mb = (totalMem / 1024 / 1024).toInt(), // 12288
   )
   ```

2. **事件队列**：内存队列 + 定时/定量批量上报（如每 30 秒或攒满 50 条），网络失败保留重试（磁盘持久化可选）。
   每条事件必须带客户端生成的 `id`（UUID），**服务端以此做幂等去重**——重试时复用同一 id。

3. **网络类型**：上报事件时附 `network_type`：
   ```kotlin
   val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
   val networkType = when (cm.activeNetworkInfo?.type) {
       ConnectivityManager.TYPE_WIFI -> "wifi"
       ConnectivityManager.TYPE_ETHERNET -> "ethernet"
       ConnectivityManager.TYPE_MOBILE -> "mobile"
       else -> null
   }
   ```

## 5. 埋点位置（关键）

所有文字上屏最终汇聚到 `ImeService.commitText()`，在此统一埋点即可覆盖候选/符号/常用语等全部输入路径：

| 事件类型 | 埋点位置 | 说明 |
|----------|----------|------|
| `commit` | `ImeService.kt` → `commitText(text)` / `commitText(text, newCursorPosition)` | 候选上屏、符号、常用语等一切文字提交 |
| `compose` | `ImeService.kt` → `setComposingText(text)` | 组字过程（可选，噪音较大） |
| `delete` | `ImeService.kt` → `deleteSurroundingText(length)` | 删除操作 |
| `clipboard_change` | `ClipboardHelper.kt` → `onPrimaryClipChanged()` | 系统剪贴板变化（复制） |
| `paste` | 剪贴板容器（`ClipBoardContainer`）条目点击回调 | 从剪贴板粘贴文字 |
| `paste_inferred` | 监听系统粘贴广播（`ACTION_PASTE`，Android 13+） | 系统级粘贴（可选） |
| `voice` | 语音识别回调（`ImeService.startVoiceInput`，键盘菜单「语音输入」入口） | 语音转文字上屏；`source=voice`，上屏时不再重复记 `commit` |

**图片粘贴约定**：剪贴板中为图片（`primaryClip` item 的 text 为 null、有 URI）时，上报
`paste` 事件且 `text` 为空、`metadata` 携带：

```json
{ "media_type": "image", "image_uri": "content://...", "image_url": "https://..." }
```

服务端据此归类为「图片」类型。

**事件字段示例**：

```json
{
  "id": "3f2a8c5e-1b2d-4e6f-9a0b-123456789abc",
  "device_id": "1f0e2d3c-4b5a-6789-0abc-def012345678",
  "event_type": "commit",
  "text": "明天下午三点开会",
  "package_name": "com.tencent.mm",
  "editor_id": "com.tencent.mm:chat",
  "session_id": "9a8b7c6d-5e4f-3210-fedc-ba9876543210",
  "sequence_no": 42,
  "input_code": "mingtianxiawusandiankaihui",
  "network_type": "wifi",
  "source": "candidate",
  "occurred_at": "2026-08-19T13:30:00+08:00"
}
```

- `occurred_at`：ISO8601 带时区（+08:00），服务端按 UTC 存储、按本地时区展示
- `package_name` / `editor_id`：`EditorInfo.packageName` / `editorInfo.privateImeOptions`（或自定 editor 标识）
- `source`：`candidate`（候选）/ `key`（按键）/ `symbol`（符号）/ `clipboard`（剪贴板）/ `voice`

## 6. 网络与环境配置

API 地址由构建类型隔离：

| Android 包 | 默认 API | 设置页覆盖 |
|---|---|---|
| `offlineDebug` | `https://my.dog8ball.com` | 仅修改电脑补传地址 |
| `offlineRelease` | `https://my.dog8ball.com` | 仅修改电脑补传地址 |

本地真机通过 USB 反向代理访问电脑，不再依赖会变化的 WSL 局域网 IP：

```bash
cd /home/ko/project/shurufa
./start.sh local

# start.sh 会自动尝试执行；也可手动执行
~/android-tools/sdk/platform-tools/adb reverse tcp:3000 tcp:3000
```

如果 USB 设备由 Windows 管理，可在 `.env.local` 设置 Windows `adb.exe` 路径。`start.sh` 会自动启动仅监听 `127.0.0.1:3001` 的 Windows 中继，再建立“手机 3000 → Windows 3001 → WSL 3000”通道；不需要把 API 暴露到局域网。本机 Platform Tools 37 与部分手机配合时还需 `ADB_USB_LEGACY=1`。

先确认 `adb devices -l` 中手机状态为 `device`；如果为空或为 `unauthorized`，需要先让 WSL 的 ADB 能识别手机并在手机上允许 USB 调试。也可以使用 Android 的无线调试，让该 ADB 连接手机后再执行 reverse。

查看链路状态和实时上报日志：

```bash
./scripts/report-status.sh local
./scripts/watch-reporting.sh
```

正常日志包含 `设备注册上报成功`，实际输入后最多约 30 秒出现 `事件批量上报成功 count=... code=200`。日志不会打印输入正文或坐标。服务端已启用 CORS 与 `trust proxy`，反向代理后的客户端 IP 从 `X-Forwarded-For` 获取。

## 7. 位置采集

服务端按「设备 + 坐标」去重（坐标 round 4 位 ≈ 11 米）：**同位置仅更新最后出现时间，位置变化才新增记录**；后台「位置轨迹」页展示地图轨迹与地址（反地理编码）。

### 权限（AndroidManifest.xml）

```xml
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

运行时申请：在设置页提供「位置采集」开关，用户开启时 `requestPermissions` 授权（`ActivityCompat`），拒绝则保持关闭。
（已实现：键盘首次弹出时请求定位权限；设置页开关可随时关闭/恢复）

### 采集（每 1 分钟一次，原生 LocationManager，不依赖 GMS）

```kotlin
val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
val listener = LocationListener { loc ->
    // 取较新的一次定位（GPS 优先、网络兜底），有值即上报
    reportLocation(loc)
}
lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60_000L, 10f, listener)
lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60_000L, 10f, listener)
```

回调中上报（**客户端不需要自己判断是否重复，照常每分钟上报，服务端去重**）：

```json
POST /api/v1/mobile/location
{
  "device_id": "1f0e2d3c-...",
  "latitude": 23.12908,
  "longitude": 113.2644,
  "accuracy": 10,
  "provider": "gps",
  "speed": 5.2,
  "occurred_at": "2026-08-19T13:30:00+08:00"
}
```

响应 `{ "ok": true, "recorded": "new" | "same" }`：`new` 表示新增轨迹点，`same` 表示同位置（服务端只刷新最后出现时间）。

> 隐私说明：精确位置属敏感权限。本系统个人自用、数据仅存本地 WSL PostgreSQL，但建议在设置页展示「位置采集：已开启/已关闭」状态，允许随时关闭。
> （已实现：设置 → 其他 → 位置采集开关，关闭即停止定位）

# 8. 语音输入（voice 埋点配套）

原版 YuyanIme 无语音功能（VoiceSettingsFragment 为空壳）。2026-09-12 按用户确认改为“端侧优先、系统识别兜底”，并明确采集自己通过输入法语音转写的文字：

- **入口**：键盘菜单（设置 → 键盘菜单）新增「语音输入」项。新安装由数据库种子数据提供；老用户由 `SettingsContainer.showSettingsView()` 检测缺失时自动补插
- **调用链**：`SkbMenuMode.Voice` 菜单点击 → `InputView.startVoiceInput()`（公开转发）→ `ImeService.startVoiceInput()`
- **识别**：Android 12+ 且设备支持时优先 `createOnDeviceSpeechRecognizer`；不支持或语言不可用时仅回退一次到系统 `SpeechRecognizer`。不上传原始音频，不调用微信私有识别引擎
- **交互**：长按空格开始识别，按住期间用 composing text 显示部分结果，松手 `stopListening`，取消或键盘关闭则 `cancel`
- **权限**：首次使用由专用透明 `VoicePermissionActivity` 申请 `RECORD_AUDIO`；拒绝仅语音功能不可用
- **埋点**：成功上屏后记录 `DataCollector.recordEvent("voice", source="voice")`，不再重复记录 `commit`
- **聊天采集**：在微信编辑器中的转写文字同时作为 `direction=outgoing,message_type=text,input_mode=voice` 进入聊天队列。由于输入法无法知道用户是否最终点击微信“发送”，元数据固定标记 `delivery_status=transcribed_not_send_confirmed`，不伪造已发送状态；画像、AI 回复和零 Token 候选均排除此类未确认草稿。真正发送后，仍由微信聊天视口采集形成可用于分析的正式 outgoing 消息
- **会话安全**：识别会话绑定启动时的输入框和包名；切换输入框、隐藏键盘或结束输入会立即取消，晚到结果不会写入其他应用
- **静音处理**：`ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` 静默不提示，其余错误 toast

## 9. 阶段 2（可选）：补全模型同步

服务端定时分析生成「前缀 → 补全」候选（`completion_candidate` 表，version 递增）：

1. `GET /api/v1/mobile/completions?since=<本地已同步版本>` 拉取增量，服务端返回最新 `version`
2. 客户端按拼音匹配：候选带 `prefix_pinyin`（全拼）与 `prefix_initials`（首字母），可同时支持全拼/简拼触发
3. 展示候选时 `POST /completions/feedback { completion, prefix, accepted: false }`，用户接受时上报 `accepted: true`，服务端统计接受率

## 10. 自测

服务端已启动时，可用 curl 模拟全流程：

```bash
# 注册设备
curl -X POST http://localhost:3000/api/v1/mobile/device -H 'Content-Type: application/json' \
  -d '{"id":"22222222-3333-4444-5555-666666666666","name":"我的主力机","brand":"Xiaomi","model":"24031PN0DC","sdk_int":34,"screen_resolution":"1220x2712","locale":"zh-CN","region":"CN","hardware":"qcom","rom_version":"HyperOS 1.0.8.0","ram_mb":12288,"app_version":"1.2.0","os_version":"14"}'

# 上报事件（幂等：重复提交同一 id 不重复入库）
curl -X POST http://localhost:3000/api/v1/mobile/events/batch -H 'Content-Type: application/json' \
  -d '{"device_id":"22222222-3333-4444-5555-666666666666","events":[{"id":"dddddddd-0000-0000-0000-000000000001","device_id":"22222222-3333-4444-5555-666666666666","event_type":"commit","text":"测试上报","package_name":"com.tencent.mm","network_type":"wifi","occurred_at":"2026-08-19T13:30:00+08:00"}]}'

# 上报位置（重复位置返回 recorded=same，不新增；变化后返回 new）
curl -X POST http://localhost:3000/api/v1/mobile/location -H 'Content-Type: application/json' \
  -d '{"device_id":"22222222-3333-4444-5555-666666666666","latitude":23.1291,"longitude":113.2644,"accuracy":10,"provider":"gps"}'

# 查看仪表盘
open http://localhost:5175   # 行为明细页可看到该事件与设备档案，位置轨迹页可看地图
```

## 11. 被动聊天采集的启用与验证

安装 APK 后至少需要手动允许通知读取；页面采集启用时才需要无障碍：

1. 在系统无障碍设置中启用“语燕输入法”的聊天采集服务；
2. 在系统通知使用权设置中允许“语燕输入法”读取通知。

微信聊天采集规则（ELI-AN00 真机验收后）：

- 对方侧只保存系统通知确认的新消息，不因打开会话或滚动而采集；自方仅额外保存输入法语音转写候选，未确认发送前不参与画像或回复；
- 文字和媒体占位类型直接入库，同一通知更新幂等；
- 媒体资源先上传，资源成功后再上传引用消息；聊天报告优先于普通积压报告；
- 不请求 DeepSeek，不要求配置 `DEEPSEEK_API_KEY`。

如果将来能从特定微信版本获得可验证的标题和左右气泡节点，再重新启用“仅新左侧气泡触发截图”；
在方向不可确认时保持关闭，避免重复采集和把自己的消息误判为对方消息。

聊天离线队列先上传资源、后上传引用该资源的消息；资源和消息分别重试。相同消息指纹不重复入队，
相同资源 SHA-256 只保留一个 `pending_asset`。完整自动化和真机验收步骤见
[`docs/testing/passive-chat-capture-checklist.md`](testing/passive-chat-capture-checklist.md)。

## 12. 注意事项

- **位置去重**：服务端按坐标 round 4 位（≈11 米）判断重复，同位置只刷 `last_seen_at`；轨迹点即“去了哪些不同地方”

- **幂等**：事件 `id` 必须客户端生成且重试不换，服务端 `ON CONFLICT DO NOTHING`
- **批量上限**：单次 ≤500 条，超出返回 400
- **文本长度**：单条 `text` 服务端只统计 1~200 字符（分析口径），上报全量即可
- **时区**：务必带时区偏移（`+08:00`），否则服务端按 UTC 解析导致时间错位
- **会话**：键盘显示 → 隐藏为一个 session（`onStartInputView` 开始，`onWindowHidden` 结束），结束时可上报 `POST /session` 补全会话统计
- **隐私**：本系统为个人自用；输入事件按上述配置上传本地和线上。手机内的词典学习不依赖任何服务端，其他采集种类保持各自原有配置。

### 2026-09-11：充电宝与首屏外个人词召回

- 自维护中文词库新增“充电宝 / chong dian bao”（补充词条共 348 条）。九宫格 `2466434262`、`24664342622`、`246643426226` 分别为末字 b、ba、bao，均应保留该词候选；仍不补写前面的音节。
- 修复合法个人词因每个词库仅返回前 8 条而在排序前丢失的问题：历史词先经过相同拼写校验，再额外召回，保留拼音和原生选择索引。未更改数据库结构、十四天衰减和已有学习数据。
- 专项测试已验证：同码整词选择充电宝 3 次，在没有其他更强个人历史的测试场景中排到第一，重开 SQLite 数据库后仍保持。并不保证未学习时末字补全词一定压过原生首选，也不是永久钉住第一。
- 边界：本地词库未收录且当前原生首屏没有的历史词仍缺少读音校验依据；手动锁拼音、分段选字仍走原生，不等于已经支持整串编码的分段整词学习。手机实测、打包和安装未由上述单元测试替代。
- 验证：27 个相关套件共 104 项通过（词库、学习存储、提交追踪、隐私策略、九宫格与原生索引）。全量测试在表情界面用例出现 JVM 内存溢出后终止，未取得全量通过结论；详见 `docs/plans/2026-09-11-t9-power-bank-learning.md`。

### 2026-09-11 后续：保留防乱拼限制，修正末字补全与跨码学习

来源：用户反馈 `9366` 的“怎么”、补完整 `93663` 后的学习，以及 `64324862` 的“你发货吧”，明确要求不要撤销此前防乱词限制。

- **保留**：前面的每个音节必须完整；四字以上纯首字母长词仍过滤；三键短码、字母输入、分段/锁拼音和隐私策略不在本次放宽范围。
- **替代旧限制**：合法末字补全的最低总长度由 6 改为 4。`9366` 为 `zen m`，`64324862` 为 `ni fa huo b`；“你发货吧 / ni fa huo ba”补入自维护词库，当前共 349 条。
- **替代所有末字补全均排原生之后的旧策略**：主词库频率至少 1000 或自维护常用词，在原生首项未被本地合法收录时可提前；普通低频补全不强推。原生整体不按“收录/未收录”重新分组，避免低频第二项抢首位；原生首项在词典第 9 条以后也应正确识别。4/5 键低频完整词同样不强抢原生，6 键以上完整词保留既有优先规则。频率门槛是保守策略，不代表已经具备语义判断能力。
- **跨编码学习**：完整拼写和仅末音节截短的编码可共享同一读音下的衰减选择权重，如 `93663` 与 `9366`。必须在同一个读音集合内同时匹配两码，不能跨不同读音取并集、不能把内部简拼历史带入。查询只聚合真实已有记录，不向多个编码重复写计数或上传事件；不升级数据库、不清空历史。
- **验收边界**：未建立通用句子语义模型，未拉黑所有生僻词；“灭除妈啊”的完整读音 `mie chu ma a` 不匹配用户给的 8 位编码，测试应过滤。手机实际 Rime 注释及安装版本仍待实机核对，不将构造候选输入的测试冒充真机复现。词库未收录、原生当前也未给出可校验读音的历史词仍不能无依据跨码召回。
- 本轮验证：114 项相关测试通过（27 个套件，零失败/错误/跳过），包括原来的防扩写反例、原生索引、短码互通及数据库计数保护。未打包/安装、未实机验收；完整记录见 `docs/plans/2026-09-11-t9-safe-prefix-learning.md`。

### 2026-09-11 后续确认：可信整词门槛，未知表达分段输入

来源：用户反馈 `9664337` 的“用得上/总额而”，并明确接受词库未收录的新名字、新短语先选短词或单字组成，不让引擎自动猜整串。

- **取代此前策略**：不再仅把未收录拼接词排后，也不再使用词频 1000 作为候选提前门槛。对现有纯数字 T9 候选入口（3–30 键），包含多个汉字的候选必须有词库收录或明确选择记录依据；没有依据不显示。首屏和翻页使用同一谓词，夹杂表情/符号不能绕过多个汉字的整词依据检查。纯表情、符号和单字保留。
- **排序与降级**：有依据且覆盖本次输入的整词（含合法末字补全）先于仅覆盖输入前缀的短词/单字；频率只在可信词之间比较。“用得上”已有词条、频率 51，不需补词或硬编码；“总额而”在没有个人确认记录时不作为自动拼接候选。没有可靠整词时允许整词列表为空，仍可翻页找短词/单字分段选择。
- **保留边界**：拼音实际匹配、内部音节禁扩写、四字以上纯首字母过滤、三键不新增本地末字补全、原生索引与同读音跨码学习继续执行；个人记录不能绕过已有拼音检查。不改字母候选算法、数据库结构和隐私策略。
- **词库缺漏处理**：将此前有效样例“不高兴 / bu gao xing”“你这样 / ni zhe yang”“续期 / xu qi”补到自维护词库，当前 352 条。只补已确认常用表达，不给每条数字编写专用候选，不使用错误字符串黑名单。
- **尚未覆盖**：手动锁拼音、已分段、繁体等不进入该候选入口的状态；未收录且当前没有原生读音的新个人词首次召回。词库收录是保守依据，不是通用语义模型，也不保证库内每条表达都自然。未宣称实机/全量验收。
- 本轮验证：27 个套件、119 项相关测试通过，零失败/错误/跳过；未打包安装。记录见 `docs/plans/2026-09-11-t9-trusted-words.md`。
