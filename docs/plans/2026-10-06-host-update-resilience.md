# 微信与抖音更新后的截图兼容补强

> 执行：按 systematic-debugging、writing-plans、TDD 与 verification-before-completion 完成；沿用用户已批准的下发规则、多重识别与保守兜底方向。当前分支保护已有修改，不自动提交或部署。

**目标：** 现在增强低维护兼容能力，不以记录长期要求替代实现。
**架构：** 复用现有 schemaVersion=1 规则与最后有效缓存、同页多证据、截图持久队列及上传链路。补齐本地宿主版本刷新与无固定类名的语义结构回退；不增加轮询联网或任意页面截图。
**技术栈：** Kotlin、Android AccessibilityNodeInfo、现有 Kotlin/JUnit/Robolectric 回归。

## 判断与取舍
- 已有远端可配置 title/input/body ID、返回/聊天设置/语音标签与版本范围；不是重新搭一套配置。
- 只增加 ID 映射仍需每次手工修补；直接通用 OCR/任意页面截图既耗电又可能误采。采用控件语义加同层聊天证据的回退，结合已有可下发规则。
- 官方节点支持 isEditable/isScrollable/isPassword/isVisibleToUser；这些字段与具体类名独立，但不能单独证明聊天页面。依据 https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo 。
- 任何第三方全新机制或安全窗口变化都无法预先保证兼容；必须准确记录未覆盖边界。

## 任务与验证
1. 结构回退：UiNodeSnapshot、AccessibilityTreeReader、DouyinChatAdapter、WeChatChatAdapter。先增加合成结构测试，未知字段兼容解码证明旧代码拒绝自定义语义控件；再传递节点语义与可见性，保留明确页头、同层唯一输入、消息区和负例限制。截图签名包含影响识别的语义状态。
2. 版本恢复：ChatCaptureRefreshController、ChatCaptureSettings、PassiveChatAccessibilityService。先失败测试离线版本变更、限频、进行中网络不阻塞本地恢复、正确版本缓存选择，再分离本地恢复和联网批次。尊重输入/游戏/同意守卫，不主线程查PM，不额外联网。
3. 回归：ID全换/移除、自定义控件、额外嵌套、缩放；评论/搜索/视频/密码/不可见/跨层证据必须拒绝；保留旧40.5/40.6及微信截图、去重、队列上传测试。执行 Android capture/service.capture/相关collect 定向回归，git diff --check。
4. 交付：原签名非testOnly APK在Mac项目apk目录，核验版本、签名、SHA。可连接真机时覆盖安装并检查服务/版本；用户操作聊天，助手不代发消息。记录实际真机验收与合成变更测试区别。

## 用户纠正
本次明确是低维护的软件机制需求；上轮仅写AGENTS验收要求不满足目标。后续用户说“记住”时结合上下文判断，不能把功能诉求降为文档。

## 实现与验证（2026-10-06）
- 旧代码RED：20项定向测试中10项按预期失败，覆盖语义字段丢失、自定义类名漏识别、宿主版本未更新、网络请求阻塞本地恢复及接线；日志 `/private/tmp/shurufa-host-update-red.log`。
- 已实现节点 editable/scrollable/password/visibleToUser 传递及签名，密码值不复制；两款App通过同层页头/唯一输入/内容区域组合识别自定义控件，保留原ID及结构路径。语义标题须真实文字，排除按钮/图片控件，避免把抖音音视频通话按钮认成标题。
- 本地版本刷新使用前台既有空闲任务，60秒本地TTL，无新增定时器或网络请求；与配置请求分锁，读期间撤权/来源变化不发布部分快照。联网批次仍Wi-Fi30分钟/流量2小时。
- 最终Android回归486项全过、无跳过；`*data.capture*`、`*service.capture*`、`*InputPriorityWiringTest`、`*ScreenshotUploadDeviceIdleTest`，日志 `/private/tmp/shurufa-host-update-verified.log`，BUILD SUCCESSFUL 1m21s。不是Android全套或未知未来App的真机证明。
- 回归过程中两处真实节点fixture补齐可见性和语义属性；两处异步测试改为等待实际读取任务，取消错误的空协程屏障，不放宽生产守卫。独立审查未发现本轮P1/P2，git diff --check通过。
- 线上只读确认既有规则管理、移动规则和诊断均已部署可访问，revision=0，微信/抖音各默认规则；没有本轮发布新规则或修改数据库。新包安装前宿主版本诊断为未知，不能把旧数据当作本轮验证。
- 本轮工作期间其他操作将工作区提交为 `1c21579`；本代理未执行提交、推送、重置或部署，保留该提交。

## APK与安装
- 原签名非testOnly构建成功23秒，日志 `/private/tmp/shurufa-host-update-assemble.log`。
- Mac推荐包：`apk/shurufa-2026-10-06-v20261006.21-2026100621-debug-99f518b7.apk`，版本20261006.21(2026100621)。
- SHA256：`99f518b727b6a758df81eabd7cfa7b1992de5e89b5b070df2417d14591d56815`。
- API23/27/28/32/36原证书核验通过；证书SHA256 `a4626fa45c451154093333af3dbc3c6e1a3c7ba783eae399ea4786b5457b1287`。
- 用户重新连接并授权测试，21:48:45覆盖安装Success，Launcher启动Status ok；实际版本核对一致，首次安装时间与默认IME保持。无卸载/清数据。
- 等待用户在两款App聊天页滚动停留后，核对本次新截图和版本诊断；21:50:17首次只读查询尚无安装后新截图、诊断仍旧，不冒充验收。
- 从已安装base.apk回拉核验SHA256与上述交付包完全一致，临时回拉文件已移除。当前手机熄屏（AOD），尚未收到用户两App操作完成答复；本次新包真机截图上传仍待验收，不能用旧包20:59截图替代。
