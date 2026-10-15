# 视频号与抖音停留三秒首图方案与实施计划

**目标：** 视频号和抖音信息流每次进入/停滚后连续停留至少3000毫秒，只保存一次首图；划走/退出/切后台/锁屏记录结束时间。开始时间保留进入/停滚时刻，包含等待截图的三秒。无图不保存。不改微信视频通话、支付页、普通页面额度、授权和输入/游戏保护。

**架构：** 保留现有事件驱动候选、导航代次和 VideoVisitMonitor。新增可测试的三秒等待门禁，在物理取帧前核验；未知树早期支付探测不接受视频图，识别到信息流后等满三秒重新取图，不能复用早期探测缓存。短暂停留和旧回调由导航失效与无图丢弃规则处理。

**技术栈：** Kotlin、协程、Android Accessibility、Robolectric/JUnit。

1. 新增停留门禁测试与优先页面策略测试，运行定向测试确认缺失行为导致失败。
2. 实现三秒门禁，接入 BrowsingPageServiceBridge 的已识别视频和未知树视频路径，保留起止时间与页面同帧核验。运行新增测试及视频存储、结束边界回归。
3. 后台更新提示文案；检查差异并审查异步取消、图片缓存及未知页回退。记录测试结果，不自动安装、发布或提交混合工作区。

验证命令：`source .runtime/macos/android-env.sh` 后运行 `android/YuyanIme/gradlew -p android/YuyanIme :yuyansdk:testOfflineDebugUnitTest --tests '*VideoFeedDwellTest' --tests '*PriorityPageCaptureTest' --tests '*VideoVisit*' --tests '*SharedPageFrameCaptureTest' --tests '*WechatVideoCallScreenshotTest' --offline --console=plain`。

用户已明确微信范围为视频号刷视频。其他任务已有位置文档改动，保留原样。在当前分支执行。

## 实施与验证结果

- `VideoFeedDwell.kt` 用单调时间等待本次进入/停滚后剩余三秒；候选失效、取消、时钟回退不获准。`BrowsingPageServiceBridge.kt` 在已确认信息流和空树早期探测识别信息流后执行门禁，起始墙钟/单调时间在事件入口冻结。未知页早期支付探测不预留视频额度、不保存视频图；支付额度不可用时视频仍可在三秒后独立探测。结束边界沿用 Monitor 的事件时间冻结，无图不持久化。
- 视频首图不传共享缓存键，满三秒重新物理取帧并同帧分类。独立审查发现清缓存会被在途探测回填的竞态，已采用直接新取帧消除；复审无剩余阻断。物理截图槽、授权与避让规则保留。
- 第一轮 RED 是新增API尚不存在导致测试编译失败（`.runtime/video-feed-dwell-red.log`），不冒称业务断言失败。随后故意移除三秒门槛与早期视频预算门禁，两个定向测试实际失败；恢复后通过（`.runtime/video-feed-dwell-mutation.log`）。
- 最终 Android 定向回归：19类197项，失败/错误/跳过均0，BUILD SUCCESSFUL（`.runtime/video-feed-dwell-final.log`）。覆盖三秒等待/取消、两平台2999毫秒划走丢弃、包含初始三秒的时长、划走事件时间、原视频存储/同步、普通页面/预算、共享帧与微信视频通话。不是全量测试或真机验收。
- 后台视频页 Vitest 13项通过，`client npm run build` 成功（存在既有大chunk提示）；后台提示增加三秒规则。
- 未打包、安装、部署、提交或推送；已有位置文档改动保持原样。权限、截图失败和既有视频额度仍可能造成记录缺失，不以单测通过承诺所有视频全覆盖。
