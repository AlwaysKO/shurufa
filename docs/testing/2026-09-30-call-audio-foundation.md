# 通话录音基础链路与能力验证包记录

日期：2026-09-30。对应 `docs/plans/2026-09-30-consented-call-audio-plan.md` 第 9 节。

> 首批历史记录保留如下；第二批运行入口和交付状态见文末，不能把首批“尚未接入”误读为最新状态。

## 状态与授权

用户要求首次设置授权后自动运行、保留系统必要提示；仅上传线上，校验持久保存回执后清理手机自有音频。用户自行管理线上保留、备份和清理，本次没有新增线上过期任务。

**这是基础链路，不是完整可用的自动录音功能。** 当前没有录音器、设置授权入口、自动接通/挂断适配、系统恢复调度或实际运行调用方。队列重启恢复与撤权检查目前是模块测试，不是已经接入手机的生命周期。没有进行真实通话、拨号、发送消息、安装、提交、推送或线上部署。

## 已实现

- Android `data/callrecording/`：独立私有文件队列、原子清单、SHA256 校验、退避、HTTPS 传输、超时后查原回执、逐块授权与输入空闲检查、上传完成后精确清理、清理失败独立重试。
- 队列禁止主线程执行；不引用定位、按键、词库计算逻辑。坏清单原样隔离并提供诊断 ID，不阻塞健康任务；独立上传锁不挡录音创建。近期完成清单有界保留 100 项，待传/坏项不自动删除。
- 服务端 `037_call_recordings.sql`：私有 AES-256-GCM 音频与元数据在 PostgreSQL 同事务提交，强制同步提交后返回持久回执。使用已有词库注册设备的 token hash 校验，不把设备 UUID 当密钥。
- 同设备记录 ID 幂等。设备保存关闭、错误凭据、超限、校验错误、数据库失败不回可清理回执；显式删除后保留幂等墓碑，防止超时上传复活。没有自动线上清理或设备删除联动。
- 后台源码路由 `/call-recordings`：设备选择、北京时间日筛选/快捷范围、平台/录音状态筛选、列表、登录鉴权试听、显式删除及刷新；明确双方声音未验证。只有已保存数据会进入后台列表，手机未上传失败状态不能伪装成线上已有记录。

## 运行契约

- 移动端：`PUT /api/v1/mobile/call-recordings/:recordId`，原始 `application/octet-stream`；`X-Call-Metadata` 为 JSON 的 Base64，最大 8192 字符；`X-Device-Id` 和已注册的 `X-Dictionary-Token` 必填。单文件最大 64 MiB，M4A/MP4 音频容器；默认音频质量未验证。
- 查询回执：`GET /api/v1/mobile/call-recordings/:recordId/receipt`。仅真正不存在返回 404；删除墓碑返回 410。成功回执字段为 `stored=true / record_id / device_id / byte_size / sha256 / stored_at`。
- 后台接口：`/api/v1/dashboard/call-recordings`，沿用管理员会话、写请求保护、明确 `user_id`；音频不放 `/uploads`，不产生公开 URL。
- 服务端必须配置 `CALL_RECORDING_KEY_FILE`，指向权限 0600、长度 32 字节的独立随机密钥文件。密钥由运维保管，不进 Git，不在日志/响应输出。未配置拒绝接收及播放，不降级明文；不得直接覆盖旧密钥，否则旧录音不可解密。本次仅在隔离测试目录生成临时测试密钥，未修改线上配置。
- 手机运行入口尚未接入：以后必须从应用 `noBackupFilesDir` 下固定子目录创建 Outbox，提供当前设备身份、授权版本/目标和输入空闲的实时检查；仅复用本次线上目标的已有设备凭据，不能把一个目标的凭据发往其他域。

## 验证方法与证据

- `bash server/scripts/test-call-recordings.sh`：自动创建独立 PostgreSQL 临时实例，只监听私有 Unix socket，运行完销毁。覆盖跨设备鉴权、幂等/并发、真实数据库失败回滚、关闭保存、无密钥、哈希/大小/元数据及实际超过 64 MiB 请求体、加密、删除墓碑、日期过滤和前端入口静态检查。
- 本地开发库明确核对为 `personal_ime / ::1:5432` 后，仅应用 `037_call_recordings.sql`；已核对新增表的 13 个字段。未执行线上迁移，未修改既有表或真实录音。
- 服务端 `npm run build` 与前端 `npm run build` 通过。前端存在原有大 chunk 警告，不顺手拆包。
- Android 使用 `source /home/ko/android-tools/env.sh` 后运行 `:yuyansdk:testOfflineDebugUnitTest --tests '*callrecording*' --offline --console=plain`。首次确认缺失实现 RED；后续按审查发现的坏清单、锁、无界历史、异常隔离等增加回归。
- 浏览器在本地 preview + Playwright、全合成 API 数据下验证菜单、按日筛选、双方声音未验证标签、播放 503 错误、取消/确认删除、切换设备清理旧列表和详情；无真实数据读取。此测试不是实际音频解码/试听验收。
- 最初误用 `npm test -- ...`，因脚本固定 `vitest run src` 实际跑了全套：779 通过、172 跳过，另有聊天旧夹具缺 `merged_into_id` 与 GIF 测试 5 秒超时。前者发生在本轮接入路由前；两者均与本次新增代码无关，未扩展修改。后续使用独立定向命令，不宣称全仓测试全部通过。

## 自动录音下一阶段的实质门槛

只读 Windows ADB 核对当前设备：ELI-AN00，Android 16/API 36；微信 8.0.78。实际安装输入法为 `com.yuyan.pinyin.offline.debug`，版本 `20260930.15 / 2026093015`，包带 `TEST_ONLY`，是设备现有包而非本轮交付。

未找到项目内已有可信接通/挂断来源。普通 `CALL_STATE_OFFHOOK` 包括拨号/活跃/保持状态，不能直接等同接通，否则取消/未接通可能采集环境声音。普通第三方应用的麦克风权限不等于电话双方音频权限；Android 后台麦克风服务另有启动限制。

官方依据：
- https://developer.android.com/reference/android/telephony/TelephonyManager#CALL_STATE_OFFHOOK
- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

因此不能把手机已有无障碍或一次麦克风授权当作“全自动双方录音已可用”。自动会话部分需先完成可信事件/音频能力验证，不以泛化音频模式、猜联系人、常驻环境录音或手动按钮冒充本轮目标。首批不发布“可录音”APK，不推荐安装仅包含底层模块的包；阶段 D/E 未完成。

## 首批最终检查点

- 定向服务端/加密/入口检查：12 项通过（隔离真实 PostgreSQL，包括实际超限请求体）。
- Android：16 项通过，0 失败、0 错误；含错误回执、清理返回失败及抛异常、坏清单隔离、完成清单上限、容量预留、网络不占队列锁、撤权、回执查询与 HTTP 传输。
- 浏览器额外 RED/GREEN：正在加载音频时关闭试听，原先按钮一直禁用；修正请求取消后的 busy 状态，重新通过合成场景完整检查。未进行实际录音解码或双方声音试听。
- 独立只读代码审查指出的队列阻断、长锁、历史无界及异常隔离问题已逐项加入测试并修复。
- 服务端与前端最终构建通过，`git diff --check` 通过。阶段 D/E、Android 设置/调度接入、实际录音能力、原签名 APK 交付、线上配置与上线验收均未完成。

## 第二批：运行接入与来电能力验证（2026-09-30）

### 已接入而非仅模块

- 其他设置 → 通话录音（能力验证）：默认关闭，明确分别同意录音、线上上传和参与者已同意。授权绑定设备、线上目标与版本，持久保存；撤回上传不等于撤回录音，通知“停止”撤回全部。
- 前台设置在权限具备后启动可见 microphone 前台服务。单一活动 SIM、完整 IDLE→RINGING→OFFHOOK 来电序列才开始；不录空闲环境，不把呼出拨号/微信音频模式当接通。电话挂断停止，通话等待中断，录音最长 2 小时/64 MiB。
- 系统静音、持续无信号或录音器异常会停止并标受限，必须显式重试，不反复抢麦。质量始终标记未验证，不把存在音轨等同于有双方声音；联系人当前未知，不猜测身份。
- 应用 noBackup 私有目录保存音频和 AtomicFile 会话日志。正常结束与中断恢复均先解析实际音轨和时长；停止失败且容器不可解析者保留日志/文件，不送入可上传队列。
- 独立 JobScheduler 上传任务在启动、更新、开机恢复；输入忙或录音中不上传，撤权/停止任务可取消网络。没有后台强拉麦克风，系统结束待命后只能下次设置前台尝试恢复。
- 本地试听只响应显式点击，停止或离开页面使待加载请求失效；迟到结果及非 RESUMED 状态不播放。后台收到并持久保存匹配回执后才删除对应本地音频。

### 验证证据与限制

- Android 定向 25 项通过（7 个测试类，0 失败/错误/跳过）：原 16 项，加严格来电序列 3 项、授权与 Job 2 项、会话恢复/坏容器 3 项、试听生命周期判定 1 项。
- 新的音轨解析边界回归使用注入的解析结果；Robolectric 不证明真机 MediaRecorder、双向音频或 Android 16/OEM 后台运行能力。试听回归验证判定函数与源码接入，不冒充真实 Android 页面自动化。
- 第二批独立只读审查发现并修复：正常停止失败的坏容器误入队、离开页面后异步试听复活；额外修复旧录音器回调误伤下一会话、API 23 JobScheduler/停止前台兼容。
- 再次运行隔离 PostgreSQL 服务端检查 12 项通过，前后端构建通过。前端保留原大 chunk 提示，Android 保留旧 API 兼容分支的 deprecated 提示。
- 当前工作区同时出现其他位置日期筛选任务改动，本任务不回滚、不认领其功能验收。此次未触碰位置业务或键盘计算。
- **未验收/未支持：** 真机双方声音、听筒/免提/蓝牙/锁屏、输入延迟和后台存活；呼出、微信、双卡自动会话尚未支持。不是文档最终目标全量完成。
- **未执行：** 线上迁移/配置密钥/发布、手机安装/真实拨号、Git 提交/推送。线上新接口未部署时手机保留待传文件；不新增线上保留策略。

### 能力验证 APK 交付

- 构建命令：`source /home/ko/android-tools/env.sh && ./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*callrecording*' :app:assembleOfflineDebug --offline --console=plain`，成功；再次核对 XML 共 25 项全部通过。
- 唯一推荐测试包：`E:\Projects\shurufa-android\apk\shurufa-2026-09-30-v20260930.17-2026093017-debug-1b00a81b.apk`。
- 从 APK 核实包名 `com.yuyan.pinyin.offline.debug`，真实版本 `20260930.17 / 2026093017`；非 testOnly，API 23/27/28/32/36 原证书校验通过。
- 源包与 E 盘交付包 SHA256 一致：`1b00a81bf93a928bbc325564da112463146ee5e08fafb693d5518d2350cb569b`。
- 未自动安装。用户可在“其他设置 → 通话录音（能力验证）”显式授权；只在双方已同意的单卡普通来电下验证，结果不能推断呼出、微信或双向音频已支持。

## 单开关修订与交付（2026-09-30）

用户确认简化为“自动录音并上传”。页面移除三复选框、保存和单独撤权按钮；首次组合告知确认后同时授权，关闭同时撤回两项并停服务/Job，不删除待传。相同设备/目标已接受组合说明时重开免重复确认，系统权限仍需用户批准。旧版分项授权不自动扩大，既有总同步门禁不被本功能静默开启。呼出/微信仍不支持。

新增组合授权、旧版迁移不扩权及跨实例迟到授权三项回归。审查发现通知停止可能被迟到授权覆盖，改为共享锁和持久 revision 票据，撤回使旧请求失效；复审无新增阻断。页面源码检查确认单开关、没有旧控件及取消还原；未进行安装后真实 Android 页面交互验收。

最终运行 `:yuyansdk:testOfflineDebugUnitTest --tests '*callrecording*' :app:assembleOfflineDebug --offline --console=plain` 成功。XML 核对 28 项、0 失败/错误/跳过；`git diff --check` 通过。

**最新唯一推荐包（取代本记录上一包）：**
`E:\Projects\shurufa-android\apk\shurufa-2026-09-30-v20260930.17-2026093017-debug-2a930485.apk`

版本仍为 `20260930.17 / 2026093017`，以新文件短哈希区分此次重构建；包名 `com.yuyan.pinyin.offline.debug`。源包/交付包 SHA256 均为 `2a9304854315bd07a7ebcfba383c5c77be97d11038e2eac61a527c37e1711f52`，API 23/27/28/32/36 原证书、非 testOnly 均核验通过。未安装、未部署、未提交/推送，不宣称真机录音或全自动双平台能力已验收。

## 系统栏遮挡修复（2026-09-30）

用户真机确认下一页最顶部开关被遮挡。原因是该页面缺少系统窗口安全边距，不是授权或开关丢失。仅在 CallRecordingSettingsActivity 添加 edge-to-edge 和根 ScrollView 的 systemBars/displayCutout 四边避让，附着后请求 Insets，重复分发不累加；授权和录音链路不变。

新增 CallRecordingWindowInsetsTest：对实际 ScrollView 分发 Insets，验证四边边距、重复分发和回零。定向 29 项通过（0 失败/错误/跳过），APK 构建成功，独立只读审查无阻断；未验证独立刘海测试场景及新包真机视觉效果。

最新推荐 APK（取代上文旧包）：`E:\Projects\shurufa-android\apk\shurufa-2026-09-30-v20260930.17-2026093017-debug-a4178d75.apk`。真实版本仍为 20260930.17 / 2026093017，以短哈希区分构建。源/交付 SHA256 一致：`a4178d751a0cb81de8d3f28f446b44dab3b95bc3be4d11a983dd5ae3c5f55c6b`。API23/27/28/32/36 原签名、非 testOnly 核验通过。未自动安装、部署或提交。

## 双卡来电监听与通知阻断修订（2026-09-30）

真机只读诊断：用户录音/上传组合授权已保存，READ_PHONE_STATE/RECORD_AUDIO 已授予，但 POST_NOTIFICATIONS 未授予，状态 single_sim_required，两张 SIM 均 LOADED、无本地音频。此前单卡实现主动停止，不是上传失败。

本次改为 API24+ 每活动订阅独立 TelephonyManager.createForSubscriptionId 监听，API31+独立 TelephonyCallback；老版本用对应 manager 的 PhoneStateListener，API23仍限单卡。所有卡先观察空闲，单张卡完整来电序列才开始；另一卡来电/呼出或同卡等待中断，到全部空闲再放行。SIM集合变化停录并停止旧监听，下次前台设置重建。通知权限/通道关闭实时具体提示，并提供用户点击进入对应系统设置的按钮；返回设置页重新检查，不通过 ADB 代开权限，不取消必要通知。

新增 6 项多卡状态机与 2 项权限/通道回归；包含另一空闲卡重复 IDLE 不丢失响铃候选。最终 `:yuyansdk:testOfflineDebugUnitTest --tests '*callrecording*' :app:assembleOfflineDebug --offline --console=plain` 成功，XML 37 项、0 失败/错误/跳过，git diff --check 通过。独立审查无确定性阻断；尚未实际多监听注销集成验收、双卡真机回调/双向声音验收。微信/呼出录音仍未实现，上传线上部署状态不因本次打包改变。

最新推荐包：`E:\Projects\shurufa-android\apk\shurufa-2026-09-30-v20260930.18-2026093018-debug-fe2aa814.apk`。真实版本 20260930.18 / 2026093018，包名 com.yuyan.pinyin.offline.debug。原证书 API23/27/28/32/36、非 testOnly 核验通过；源包与交付包 SHA256 相同：`fe2aa81446debc7e27edc8ef1c1a4471bd5323991dc412bef25bfcf7cdacad8d`。未自动安装、开启权限、拨号、部署或提交。
