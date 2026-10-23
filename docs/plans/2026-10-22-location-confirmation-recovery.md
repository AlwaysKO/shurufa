# 定位确认超时后的有限恢复

> 按 Superpowers writing-plans / executing-plans / test-driven-development 在当前分支执行，保留现有未提交修改。

**目标：** 修复运动确认超时后 GPS 重试被清掉、只能等待下一次外部事件的缺口。不连接手机、不查询线上，不将本地复现认定为截图断档的唯一原因。

**设计：** 超时保留“运动尚未确认”的状态，GPS 暂停期间最多安排两次恢复，分别等待 60 秒、180 秒，每次确认仍限 120 秒。恢复沿用现有网络定位、质量与防漂移判断；不以超时证明静止，不制造位置报告。有效位移取消恢复并进入正常移动采样；运动事件之后的新鲜稳定观测（至少四次、跨度至少两分钟）可重新确认停留并取消恢复，不能借用运动前计数。事件起点与证据跨度使用 elapsed 单调时间，迟到的事件前观测、无单调时间戳的点不能参与新的静止确认。恢复耗尽不自循环，新的外部运动证据仍可开启新一轮。

**实现：** 在 BalancedLocationPolicy 管理未确认状态、恢复次数及 elapsed 期限；在 BalancedLocationService 将恢复期限接入现有 Handler 和 LocationElapsedWakeup，以同一幂等结算入口处理。等待恢复时继续暂停 GPS，网络采样和上报去重不变。休眠醒来只启动一次当前尝试，不补执行错过的窗口；停止/撤权/销毁取消全部期限。

**技术：** Kotlin、Android LocationManager、JUnit、Robolectric。

## 步骤与验证

1. 新增 `LocationConfirmationRecoveryTest.kt`：策略超时两次有限恢复、旧静止点不能取消、真实新静止/移动取消、低质量/缓存不能续命。先运行并确认旧代码不能自动恢复。
2. 新增 `LocationConfirmationRecoveryServiceTest.kt`：真实服务监听器按期限停止/重启，无新传感器也可重试；耗尽停止，撤权/停止后回调不重启，elapsed 休眠恢复。
3. 最小修改 `BalancedLocationPolicy.kt`、`BalancedLocationService.kt`，保留已有 10 月 20 日/21 日冷启动与休眠改动。
4. 执行新增测试及定位回归组（`*Location*Test`，特殊传感器 Shadow 测试按项目已有约束隔离），审查差异、更新实际验证结果。不安装、不提交其他未提交工作。
5. 按 AGENTS.md 长期交付规则，构建 `:app:assembleOfflineDebug`，核对原签名、非 testOnly、版本及 SHA256，交付到 Mac 项目 `apk/`。不连接或操作手机。

## 实际验证

- 红灯：旧实现 Android 23/31/35 均在“60秒后没有自动GPS恢复”断言失败；后续审查补充的“运动前迟到点”和“缺单调时钟的点不能确认新静止”两项也先在修复前失败。
- 绿灯：位置回归组282项、隔离的运动传感器与计步组23项，合计305项全通过，无失败/错误/跳过；新增策略9项与服务12项（4场景×3个SDK）。`git diff --check` 通过。独立只读审查提出的证据时间边界问题已修复并复查通过。
- 测试环境说明：Robolectric 4.14.1在`simulateDeepSleep`后调用`idleFor`会将elapsed重置成uptime，不能用于休眠后混合时钟验证；新休眠测试按elapsed闹钟逐段结算。自定义SensorManager Shadow仍按项目既有约束隔离运行，不通过改生产代码绕过测试环境问题。
- `:app:assembleOfflineDebug` 成功。交付：`apk/shurufa-2026-10-22-v20261022.19-2026102219-debug-0a9fc3e8.apk`，包名`com.yuyan.pinyin.offline.debug`，版本20261022.19（2026102219）。
- 构建源文件与交付文件SHA256一致：`0a9fc3e8a338866298977e4ba00192d6ea17f52710d86a0759a8897127536544`。API23/27/28/32/36原证书校验通过，非testOnly。
- 日志、验证统计及交付元数据在忽略目录`.runtime/2026-10-22-location-confirmation-recovery/`。未连接手机、未安装、未访问线上、未提交推送；真实行程连续性与功耗尚待实机验证，有限恢复不保证系统无有效定位时每分钟有点。
