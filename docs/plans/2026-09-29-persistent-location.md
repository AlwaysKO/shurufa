# 位置记录一次设置与自动恢复实现计划

> 使用 superpowers 的 writing-plans、test-driven-development、verification-before-completion 在当前目录实施；不创建 worktree，不自动提交推送。

**目标：** 用户第一次明确开启并完成系统授权后持久记住，重启/进程回收后在系统允许范围内恢复，不把服务暂停误写为用户关闭。

**架构：** 分离用户意愿与运行状态。服务正常运行返回 START_STICKY；销毁/权限或定位暂不可用只暂停，通知停止及主动关闭才清意愿。开机/更新广播、现有初始化与同步作业尝试恢复，恢复前检查同意、开关、定位与后台位置权限；系统拒绝时安全暂停，禁止后台弹窗及强制拉活。设置页首次开启时一次性告知始终定位、自启动/后台运行配置，提供主动修复入口与真实状态。

**边界：** 不绕过强行停止/撤权/厂商限制；不保证无间断轨迹、不上传静止心跳、不改既有双端/保留规则。保留前台服务系统通知。只本地打包，手机已拿走。

## 实施步骤
1. BalancedLocationServiceTest 先增加销毁保留、null intent 恢复与权限暂停断言，运行失败证据。
2. 修改 BalancedLocationService：显式停止清偏好、生命周期清理不清偏好、运行返回 sticky；添加受权限约束的恢复入口。
3. 新增 LocationRecoveryReceiver 及 Manifest 开机/更新/定位状态广播；DataCollector 初始化和既有周期任务途径调用恢复。补广播、安全门控/关闭不复活测试。
4. OtherSettingsFragment 展示持久开关；前台恢复不重新弹权限；首次开启提供一次性系统设置说明，后台权限缺失显示待配置而非已保证恢复。保留其他会话修改。
5. 回归 location/consent/report 及相关设置测试；对 API 23/31/35 覆盖，检查原静止去抖仍通过。
6. 本地 assemble、原签名和 SHA256 核验，交付 E 盘；记录未进行真机重启/杀进程验收。按用户明确长期意图更新项目规则，不推断永久发布授权。

## 官方限制核对
已读取 Android Developers `https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start`：开机存在后台启动豁免，但位置权限仍单独校验；后台启动位置服务需要后台位置授权等适用条件，捕获平台拒绝，不规避限制。
