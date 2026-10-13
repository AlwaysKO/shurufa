# 静默红包操作暂停后继续

> 历史方案：用户随后明确要求其他应用/游戏中并行处理，已被 `2026-10-10-redpacket-concurrent-main-screen.md` 取代。

**Goal:** 用户已确认将“实际操作立即取消”改为“点击红包前暂停，停手稳定后继续”。

**Evidence:** 已安装20261010.07的诊断中，通知解析/账号/入队通过，TASK_STARTED后INPUT_STOPPED_SCROLL，后端RUNNING后TASK_START_INVALIDATED；用户确认当时实际滑动或切应用。只读自检副屏36，两帧OCR VALID，主屏焦点0且副屏释放。未确认真实领取。

**Design:** 主屏触摸、点击、滚动或文本操作立即失效旧任务并停止副屏，未提交卡片的active及待办队列按原通知时间最多保留24小时，停手稳定3秒后串行恢复。最多4条，不持久化通知，不续期。已经提交或结果未知仍由ledger拒绝重试。显式停止、配置改变、断线继续清队列。无待办不轮询；没有活动job时迟到点击只更新等待门，不取消待启动的只读诊断。

**Tech Stack:** 现有Kotlin Runtime、SilentPacketStartGate、Robolectric、原签名OfflineDebug。

## 执行与验证

1. 新增 `SilentPacketRuntimeInteractionPauseTest.kt`，真实触发event验证保存来源/原时效、3秒稳定恢复、提交后不重试、其他待办保留、显式停止不恢复；先运行RED。
2. 仅修改 `SilentPacketRuntime.kt` 的操作暂停路径、等待门及固定枚举诊断；沿用 `PacketRequest.deferredByGame` 字段承载既有24小时上限，补充注释说明保护暂停语义，设置页说明停手后继续。验证GREEN及原全部红包回归。
3. 正常源集运行SDK红包与副屏后端测试、完整APK构建、原证书验证，独立审查竞态；当前分支原地修改，不提交/切分支。
4. 用户已重新连接授权验证，安装前核对本机版本递增策略，不降级、不卸载/清数据。只读自检及准备状态可核验；不重放旧红包，不要求新红包直至代码与只读验证完成，不把这些检查称为真实领取成功。

## 执行记录

8项操作回归先RED5失败；2项通知入队窗口回归先RED1失败，随后实现GREEN。最终SDK红包256+后端22=278项全部通过，完整APK及API23/28/36原签名核验成功；独立审查通过。08:08安装.08覆盖成功，原AUTO与自动范围设置保留，通知/无障碍重新连接。未重放历史包，真实领取待验。日志 `/tmp/shurufa-interaction-pause-final.log`，APK详见交付记录。
