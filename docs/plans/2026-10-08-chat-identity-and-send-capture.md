# 会话身份拆分、误采与发送漏帧：调查与修复计划

> 使用 superpowers:systematic-debugging、brainstorming、test-driven-development、verification-before-completion；当前分支执行，不建 worktree。发送在途帧修复已实现并进入扩大回归，其余身份/同名合并方案尚未实现。

**目标：** 限制非聊天误采，稳定同一聊天的可靠身份和临时来源，在发送快帧未成功时保留同会话停止输入后的有界补偿。

**架构：** 保留10月7日发送渲染/持帧设计，不简单延长500ms截止或全局取消输入保护。标题使用安全的主题无关证据与旧身份别名，不按同名全局归并；未知来源仅在可靠同scope接续。停止输入触发普通页面复查，不授予快帧或采集草稿的特权。

**技术栈：** Android Kotlin/无障碍截图/ML Kit/Room；现有TypeScript聊天来源归并。

## 证据与边界
- 用户给线上总结截图：16904/18134同群深浅截图分开，2.2秒同图两个临时身份，小程序/视频号误采；这是用户提供的报告，本轮尚未取得原图/数据库行独立复核。
- 2026-10-08 初查 Windows ADB devices为空，用户连接USB后已可读荣耀200。实际安装20261007.21（2026100721），设备base.apk SHA256为`3ec9d262003f2b6f2f07c45c7f54248ef8363f390b79e00bbafdb23f02247466`，与昨日最终交付一致，不是装错旧包。现有SSH root/ubuntu均认证失败；不搜凭据/不改线上认证/不伪称查过当前生产。
- 当前工作区5个candidate ranking Kotlin/test文件及其计划已有未提交修改，全部保留，不属于本任务。
- 当前AGENTS后续分项网络策略覆盖旧要求：已准备聊天图仅有效Wi-Fi上传，可不等输入空闲；截图/编码仍避让输入，明确SEND仅获短时取帧许可。保留游戏/同意/窗口/代次检查。
- 历史已允许微信列表/朋友圈独立来源，不在本轮顺手删除。截断标题不能跨导航/重启仅凭可见标题图全局合并。

## 源码已确认
1. `data/capture/media/WechatScreenshotIdentity.kt` exactBand分支使用`WechatTitleRegion.wechatNicknamePixelSignature`，裁边后精确RGBA SHA。旧`WechatTitlePixels.wechatTitlePixelSignature`才是深浅二值；主路径深浅色必变key。当前微信stabilizer连续性及持久store按视觉key精确匹配。
2. `WechatScreenshotIdentity.isWechatScreenshotChatPage`对任意有效标题直接放行；前置`WechatNonChatPage`仅有限负特征。缺少聊天正证据时可误放行。
3. `WechatScreenshotBoundary`全部window-state和多数click均reset。stabilizer清pendingKey，下一次生成UUID；pending正常无reset保持5分钟，2.2秒不应归因超时。pending目前不参与confirmed内容去重，新conversationKey使同图指纹不同。该个案触发reset还是fork仍待现场证据。
4. `PassiveChatAccessibilityService.requestPromptChatCapture`可能因旧代次已接受帧尚处理、root/window瞬态不可读、截图槽竞争而结束。两次即时竞争并不等价于槽释放后重试，失败无统一末次空闲补偿。
5. `startEmptyTreeScreenshot`先应用peer-typing三秒冷却，明确SEND也被延后；输入停止本身没有独立普通PROBE，只有已入队事件等待空闲。

## 推荐顺序（待确认）
### 任务1：发送与空闲补偿
- 文件：`service/capture/PassiveChatAccessibilityService.kt`、`ForegroundChatCaptureBridge.kt`、必要`service/ImeService.kt`与有界idle调度；媒体层仅必要接线。
- RED：普通帧占槽导致SEND两次快失败，空闲后同scope应补一次；旧页已接受帧不取消，新页SEND不丢；root短暂为空可进入原一次重试；旧peer-typing冷却不拦明确SEND，本帧typing标题仍拒绝；停止输入无宿主新事件仍普通复查，导航/新输入合并或取消。
- 保持单物理帧/有界待办、500ms内快帧界限；未渲染或离开后不得截其他页面冒充成功。
- 测试：`WechatPromptCaptureTest`、`ForegroundChatCaptureBridgeTest`、`SendRenderWaitTest`、`ScreenshotUpdatePolicyTest`及IME事件链实际接线测试。

### 任务2：身份和非聊天页
- 文件：`data/capture/media/WechatTitleRegion.kt`、`ConversationTitleStabilizer.kt`、`ConversationIdentityStore.kt`、`WechatScreenshotIdentity.kt`、`WechatNonChatPage.kt`；必要截图边界/更新策略接线。
- RED：相同中性文字深浅主题证据稳定，同轮廓异色Emoji仍不同；真实不同字形/来源隔离；旧确认key能安全建立新证据别名且不生成第三个身份；导航后截断仍分开。
- 非聊天必须有可验证正向聊天证据，利用已有输入栏/头部结构而非正文OCR或标题词黑名单；测试标题叫“视频号/小程序”的真聊天仍允许，带标题菜单但无聊天结构的推荐页拒绝，列表/朋友圈例外保留。
- pending仅证明连续同scope后接续/去重，不盲删reset或全局按图片hash合并。需用原图/事件复现确认实际reset边界；未取得证据前不伪称2.2秒个案根因已确定。

### 任务3：验收与历史
- 串行Gradle：`source /home/ko/android-tools/env.sh; cd android/YuyanIme; ./gradlew :yuyansdk:testOfflineDebugUnitTest --tests '*capture*' --tests '*collect*' --offline`，必要追加IME测试。每项先观察预期RED再最小实现GREEN；不并行Gradle。
- 独立审查后原签名非testOnly APK交付E盘，核对真实版本/签名/源及交付SHA，成功后仅删本次WSL APK副本。
- 手机连接后核对实际版本及SHA；用户自行发送，关联SEND→系统帧→图片准备→持久→消息ACK→实际图像正文；不以单测或SYSTEM_READY冒充今天问题已修复。
- 线上原图/来源可读后再逐条核对16904/18134等历史；不直接按名字批量合并，不删除图片。生产数据修复须限定范围、备份、事务和审计；本轮尚未执行。
- 未授权自动Git提交/推送/安装/部署；保留所有无关工作区修改。

## 10月8日荣耀200现场补充（尚未修复）
- 用户自行在文件传输助手发送“国家级”，随后确认长按并删除过消息。不能把该轮当作无其他操作的纯发送测试。
- 14:36:58.282日志出现明确SEND，14:36:58.698系统帧返回；随后有INSERTED及线上ACK，但日志没有足够关联字段，不能把任意ACK绑定到这条消息。
- 只读检查App自身`cache/chat-capture/587b6eec8e5157034984ec1ce58aee7e34690ae99c0c619d0cf3ab8c25c37ba3`，文件mtime为14:37:03，原图明确包含右侧“国家级”绿色气泡。这证明本轮确实生成过含消息的采集图，不证明其线上最终归属或所有发送场景可靠。助手手动screencap不计为App采集证据。
- 长按/删除阶段出现多个弹窗window-state及身份generation重置，14:37:18已有准备中的请求被新代次打断。仍需区分安全取消旧页面任务与错误重建会话身份，不能盲目禁用全部reset。
- “截图测试1008B”用户实际停留未返回，App原图`1acac84b771f75b5591ce6b64f3fcf4bd742f64566386be42085f1b493a1ec90`包含正文，仅算停留测试。
- 后续连续三条“再来”“好”“一意孤行”：14:47:51缓存原图`91b07effc0bc6b9c0b712829fd485e4c9d6307a075b0222e7e0b3eb661344ac6`仅含前两条，返回后的列表摘要显示第三条；用户确认第三条发完快速返回。该轮复现遗漏，但关键时段logcat已覆盖，无法据此区分发送没触发、渲染尚未完成、帧被取消等分支。再次启动180秒限时诊断，请用户发送“快返验证C”；不得将丢失日志补写成已确认根因。
- 工作区候选排序任务继续新增了ImeService及ConfirmedCorrectionHints等修改，本任务保留、不覆盖。若从共享工作区构建APK，会同时包含这些改动，不声称是单功能隔离包。

## 本批实施步骤（用户“继续”后）
1. 使用`WechatPromptCaptureTest`真实服务/截图链添加`explicitSendDoesNotWaitForPreviousPeerTypingCooldown`：上一帧typing状态仍在3秒冷却，明确SEND须在原短窗口取帧。运行单项测试，先观察预期失败。
2. 仅让明确SEND跳过旧typing冷却，不改变截图后对本帧typing标题的拒绝；运行该类与ScreenshotUpdatePolicy、SendRenderWait等回归。此缺陷是源码与自动化可独立确认的缺口，不冒充“一意孤行”漏帧根因。
3. 快返时段必须关联发送/渲染/系统帧/取消或持久化再定最小修复；取帧前禁止跨导航继续截图，取帧后沿用已有原会话快照。不能靠把300ms随意改成80ms、延长截止或关闭输入避让来宣称解决。
4. 执行定向回归和独立审查后再决定打包；本批未授权安装/上线。并行候选排序任务涉及ImeService，本批不修改该文件。

## 快返C根因与实现（15:09时仍待扩大回归/真机）
- 限时诊断已结束，没有遗留持续logcat。`fast-return-trace.log`记录：14:56:05.197 SEND，.658相同列表尾部位置确认稳定；.740媒体SYSTEM_REQUEST；.767泛化content使渲染许可失效；.777本地-1002拒绝；真正generation reset在06.189。缓存新增仅为进入前列表图，未找到该次新正文图。此轮可定位到渲染状态与在途请求共用门禁，而非服务器显示。
- `SendRenderWait`增加仅覆盖一次物理请求的状态，记录期间content但不据此撤销已发请求；真实列表源/末尾位置变化或无效位置单调失效。失败释放后重新按期间内容变化等待。
- `ChatCaptureAttempt`接入开始/失败钩子；锁定位置在`WindowScreenshotter`最终窗口核验后、`takeScreenshot*`紧前，不能提前到媒体层或IO排队之前。其余代次/输入许可/同意/游戏守卫不变，接受帧后的原会话处理路径不变。
- 现有500ms约束是渲染稳定时点与重试预算，不是系统物理调用硬截止；现场SYSTEM_REQUEST距SEND约543ms。没有通过延长预算或缩短300ms最短渲染等待修复。
- TDD：`typing-red.log`观察旧typing冷却用例失败；`inflight-red.log`观察在途content用例失败，同时typing修复已GREEN；`inflight-green.log`第一批5个测试类通过。后续增加了在途真实导航、新输入、触摸、撤同意、游戏、失败重试及物理调用锁定时序的保护用例，当前串行运行`capture-regression.log`覆盖capture/collect，结束前不称最终通过。
- 独立只读审查完成，无阻断问题；建议的锁后门禁用例已补。尚未覆盖安装新包，不声称用户手机问题已解决。已询问USB保留数据覆盖安装授权，等待答复。

## 本批交付与待验收（15:22）
- 上述安装待授权状态已更新：用户明确允许USB覆盖安装并复测，不卸载、不清数据。
- 全capture/collect扩大回归暴露无关LocationContextSnapshotTest的API26 Robolectric依赖下载TLS失败，已中止该轮，不将其称为通过。另InputPriorityWiringTest仍匹配旧`!captureAllowed()`字符串，Git HEAD也已使用`requestAllowed()`，属于过时接线断言；改为检查onSuccess复制像素前调用`!requestAllowed()`，并检查普通captureAllowed/快帧canTakeFrame两路守卫，未修改生产输入策略。
- 等待另一会话的隔离APK构建完成后，串行运行capture全部及ImageUpload/InputPriority/GameWork/LatestIdleWork关联测试：`capture-final.log` BUILD SUCCESSFUL，568项、0失败/0错误/0跳过。含WechatPromptCaptureTest 14、SendRenderWaitTest 11、WindowScreenshotterThreadTest 7。XML与合计保存本机私有`verified-results/`，不含用户原图。
- 复用当前共享工作区已生成的原签名合包，未重复构建另一份同版本包：`E:\Projects\shurufa-android\apk\shurufa-2026-10-08-v20261008.15-2026100815-debug-48220a4f.apk`。包含本次采集修复及另一会话候选排序修改。检查DEX含本次beginFrameRequest/frameRequestFailed、构建源与E盘副本SHA相同；API23/27/28/32/36均原签名、非testOnly。未删除另一会话拥有的隔离构建源文件。
- APK SHA256：`48220a4f5baa4efb29a535cc0a94bf9077566f6b951f1e8d50742e8be895528a`。`adb install -r` Success；设备versionName=20261008.15、versionCode=2026100815、lastUpdateTime=15:21:48，设备base.apk SHA完全一致；无障碍与默认输入法仍为本包组件。
- 已开启300秒有界诊断`installed-retest.log`，请求用户连续发新版连发A/B及新版快返C后马上返回，待实际原图验收。尚不能声称真机快返已修复；抖音、会话身份及同名合并未在本批验收或实现，不改线上历史、不提交/推送Git。

## 123复测与取证方法纠正
- 用户实际连续发送“1”“2”“3”并返回。`installed-retest.log`显示15:23:01.956最后一次系统帧成功，02.669导航重置，05.943原帧资源完成，06.124原generation=7持久化INSERTED：在途泛化content未再次将它拒绝，导航后持帧路径也运行到了保存。
- 事后仅找到缓存图`4b390f6c792f9f5247383120a5e19c2eb514da8af9349d524877fcc2766b9e3d`包含“1”；助手起初将“未找到2/3”判为本轮失败，随后已明确向用户纠正。`CaptureUploader.runOnce`成功转交消息后会删除引用的缓存文件（约110–113行），`CaptureApi`可能只是转入上报队列；因此缓存缺失不能证明漏拍，也不能凭阶段ACK确认具体正文。
- 当前结论：最后一次取帧/持久化链成功，但最终原图未及时保留，123视觉验收待核实；不能称已失败或已成功。先前仅凭缓存缺图对“一意孤行”的漏拍判断也不够充分；快返C另有-1002明确失败链，不混淆证据等级。
- 改为USB只读监听`cache/chat-capture`的文件移入事件（toybox inotifyd），生成后立即拷贝到本机私有`live-assets/`，最长180秒、最多12张；不改手机采集/上传设置、不轮询扫描整库。请求新一轮“4”“5”“6”后快返，与有界日志对应验证。
- 456轮取证未成功：手机列表摘要已为6，但限时`live-assets-retest.log`只有ReportDeliveryTrace、没有ChatCaptureTrace；目录监听未留到新图。不能据此认定漏拍或通过。已询问用户是在手机还是电脑微信发送，以区分设备端触发。
- 监听器也发现本机工具行为差异：实际对临时诊断文件做rename时，toybox把旧`.tmp`名报为m、新正式名报为y，与help中的移入/移出说明相反。之前仅监听m且过滤非64hex会漏掉正式图；后续必须同时监听my，使用TTY实时行输出并先做无敏感内容的临时文件自检。自检文件已立即删除，不改变正式缓存。旧监听与日志均有180秒到期，不把取证失败归咎于App。

## “7”轮核对与取证覆盖缺口（15:41）
- 用户确认456均由荣耀200手机微信发送，后按提示发送7并快速返回。不能归因于电脑端发送。
- v2监听成功保留6张候选图，已核对会话图均只到6或更早；15:34重开会话后的1–6画面不算456快返验收证据。候选图存在也不等于通过页面/身份校验并上报。
- 监听150秒到期，共6张，最后保留图时间15:35:07。随后读取手机摘要：persist inserted为15:36:16.360、screenshot cancelled为15:36:22.237、upload acknowledged为15:36:27.450；摘要没有请求关联，不能将它们直接绑定7，更不能以ACK声称7已截图。
- 15:39抓取logcat时最早可读记录已到15:39:15，未保留上述时段ChatCaptureTrace。这轮监听未证明完整覆盖实际发送及后处理，故不能凭这6张图宣布7漏拍或成功。取证窗口与人工测试不同步是诊断流程缺陷，不据此修改生产截图时序。
- 当前不要求用户无目标重复发送。下一次真机验证须先让日志与原图监听覆盖同一窗口、明确记录起止/存活，发送完成后仍保留处理尾段，避免短timeout在用户操作前过期；不改上传设置或取消原有安全守卫。本次没有追加生产代码修改或安装，快返视觉验收仍未完成。

## 继续修复：失败快帧的同页空闲补偿
- 用户要求继续实施。先完成任务1中已有的有界普通补偿：快帧结束但未接受帧时，安排一次同代次的普通前台复查，交给既有LatestIdleWork合并与输入/游戏空闲门禁；导航、撤同意、销毁或更新的发送请求阻断旧恢复。
- 不延长快帧窗口、不放宽系统帧接受条件、不在导航后取新页冒充旧页。此补偿只改善仍停留同页的失败恢复，不能宣称解决极快离开且尚无渲染帧的场景。
- RED覆盖：两次快帧失败，停止输入且无新宿主事件后应普通采集一次；导航后不补采。测试使用真实服务/媒体/持久化链，普通截图不借SEND许可。
- `idle-recovery-red.log`：新增两项运行，补偿用例预期Timeout，导航取消通过。实现正常完成回调与发送序号，令牌贯穿foregroundReads和screenshotReads等待/启动检查，不绕过普通候选冷却；异常、取消、已接受帧不补。
- `idle-recovery-green.log`：WechatPromptCaptureTest整类BUILD SUCCESSFUL。随后追加取消回归、同步私有函数签名的两处反射测试，正在做全capture相关串行回归，不能以前一次成功覆盖新改动。
- 独立审查指出可读树两轮pending结果可能把已安排800ms复查标记从true覆盖为false，已改为只在实际安排后单调置true；不为同一路径额外安排补偿。

### 同名需求的源码核对纠正
- 本仓库在本轮之前已存在后台**同名展示聚合**：`server/src/api/chatPending.ts` 的group_names查询、`client/src/views/ChatCapture.vue`默认传true，以及`chatContinuity.postgres.test.ts`的同名分组/分页/读取测试（文件最近已有提交为2026-10-01）。它保留多个原始来源，仅同手机同平台完整名称展示聚合；不是手机端身份稳定，也不是物理入库合并。
- 之前“同名合并尚未实现”的表述只适用于本轮提出的新入库/身份归并方案，不能理解成仓库完全没有同名展示功能。不能重复新增同一展示开关冒充修复；线上是否部署、报告中的两个来源在聚合视图是否仍分开，仍须实际部署/API证据。本轮未修改后台或历史记录。
- 扩大回归 `idle-recovery-final.log` BUILD SUCCESSFUL（3m50s）：573项，0失败/0错误/0跳过；WechatPromptCaptureTest 19项。XML存本机私有idle-recovery-results。修复前补采用例失败，修复后通过；导航、新发送、撤授权、任务取消不执行过期补偿。此轮是自动化验收，不是真机快速返回验收。
- 用户进一步明确：实际聊天列表有多个同名会话，而非仅详情来源ID不同。必须继续核对线上group_names查询与原始标题差异，不能用现有源码支持功能否认反馈。旧部署文档mymanage域名证书不匹配，未绕过TLS或发送登录凭据，转查当前生产配置my.dog8ball.com的公开前端资源。

## 空闲补偿包交付（15:56）
- 构建 `idle-recovery-build.log` BUILD SUCCESSFUL。唯一推荐本轮阶段包：`E:\Projects\shurufa-android\apk\shurufa-2026-10-08-v20261008.15-2026100815-debug-5d56c1b4.apk`，同小时版本20261008.15/2026100815，以SHA区分前包。
- SHA256 `5d56c1b4d892d6042dc58831a7d41f0263999560954724fd1ade1ac0ae91bec6`；apksigner验证原签名a4626fa4…71287，aapt未见testOnly。源/交付完整SHA一致后删除本次WSL APK输出，不影响E盘及其他产物。
- 沿用用户覆盖安装授权，`adb install -r` Success，lastUpdateTime=15:56:43；不卸载、不清数据、不改聊天上传设置。仅修复失败快帧同页空闲补偿，极快返回与同名拆分仍未完成真机/线上验收，不称整个问题解决。
- 当前生产域名my.dog8ball.com公开前端`/assets/index-QwjjtFFb.js`已只读核对：包含group_names=true，聊天列表两个调用均传groupPending/groupNames=true。不能据此证明服务器查询实现或用户浏览器缓存也相同。已请用户提供实际重复列表截图/ID，用户反馈列表重复已明确，下一步须核对两条完整标题和truncated/pending状态，不进行猜测式模糊合并。
- 安装后设备base.apk完整SHA与5d56c1b4交付一致。dumpsys显示妙言输入法已Bound、当前PassiveChatAccessibilityService已Enabled，Binding/Crashed均空。自动检查曾因Bound行只显示label不显示组件名返回false，随后读取原始相关行确认正常，不把字符串匹配失败当服务故障。

## 用户确认的新展示规则（16:00前后）
- 用户提供实际列表截图：可见“表格式完税证明…”重复多项，各1条。客户端名称无CSS省略，已有代码按truncated来源剔除同名组，与现象吻合，但截图不等于已读取线上external_key。
- 用户明确选择“是，截断同名也合并展示”，接受不同群截成相同可见名称可能同组的风险。
- 实施范围：同手机/同平台/精确可见名称展示聚合，保留原始来源与手机截断身份规则；仅对已标记truncated来源去除历史“（名称被截断）”显示后缀，与现有UI保持一致。不做模糊匹配，不删除、物理合并历史。
- TDD先修改截断标题展示/读取/删除快照回归为新契约，再改集中group scope；同步前端旧来源选择恢复到展示组，保持分页与删除确认来源快照一致。独立测试PostgreSQL实例，严禁业务库测试；不自动部署或伪称线上已生效。
- 后台RED：`truncated-group-red.log`精确复现同可见截断名返回2组而非1组；前端RED：旧来源被插回成重复项。集中scope修改后chatContinuity独立PG完整39项通过，原始表display_name保持未改，列表/读取使用可见名。
- 独立审查发现历史后缀末尾空格的前后端处理顺序不同，新增对应RED并修正displayName先trim、移除后缀、再trim；正在复核前端全聊天测试与构建。现有截图来源标记不变。
- 已将用户确认的展示规则与手机身份规则的边界写入项目AGENTS.md相应截断章节；只修订后台展示，不放宽手机跨导航截断身份复用，不作为已上线证明。
- 最终后台/前端验证：`truncated-group-final.log`39项、`truncated-retention-final.log`15项、`truncated-client-verified.log`114项全部通过；`truncated-server-build.log`TypeScript构建成功，`truncated-client-build-verified.log`vue-tsc/Vite成功（仅已有大chunk提示）。测试PG实例均由脚本退出时停止，无业务库数据写入。git diff --check通过。
- Android推荐包仍为5d56c1b4（后台展示调整不涉及APK），手机已覆盖安装、签名/SHA/无障碍核验正常。本轮不声称极快返回零漏、抖音已验、手机身份稳定/非聊天误采全修复。后台展示代码未部署、未提交/推送Git，不能把本地GREEN当线上效果。
