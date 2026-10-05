# 静止防漂移与定位择优实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 减少同地网络定位来回漂移导致的重复上报，准确与省电兼顾，允许确认移动稍有延迟。

**Architecture:** 复用所有位置入口已共用的LocationJumpFilter和LocationUploadPolicy，固定静止锚点，连续独立出圈同方向位置才确认移动；当前可信移动速度可加快确认。统一拒绝估计误差超过50米的新候选，近期准确GPS优先于明显差的网络结果。冷启动在新鲜候选间兼顾时效和精度择优，不改原始位置时间、精度；疑似移动仅临时恢复30秒采样，最多60秒后独立到期。

**Tech Stack:** Kotlin、Android LocationManager、Robolectric/JUnit；本轮无需新SDK/服务端字段/数据库迁移。

## 证据与边界

用户截图为荣耀X80（线上model BSN-AN00），最新登记版本20261001.04；2026-10-04 13:00–13:05只读核查见同BSSID、network定位，连续距离210/127/111/102/107/83/78米，多数无可信移动速度。截图四舍五入坐标不能当完整距离依据。新安装的荣耀200不是该截图设备，不能宣称新版真机复现；但当前200米同源突跳阈值、0速度+10米每秒容差和圈内锚点迁移的漏洞可由行为测试独立复现。

用户确认“准确与省电兼顾，接受确认移动稍有延迟”。同SSID甚至同BSSID只是附近线索，不能当位置不变的硬锁，移动热点和真实步行不能被冻结；本轮不新增WiFi扫描/传感器权限。估计精度不是真实误差保证，缺乏精确信号时允许暂缺记录，不伪造高精度点。

## 任务与验证

1. 给LocationUploadPolicyTest补50米边界和100米候选拒绝；新增LocationFixSelectionTest覆盖新鲜GPS与较差网络、过时GPS、缺精度与未来定位。先RED，再最小修改UploadPolicy和DataCollector启动候选选择。
2. 给LocationStationaryFilterTest补交替80–120米抖动、可信0速度、固定锚点、防重复fix、近期好GPS优先、慢走/未知速度车辆/可信高速和长缺口。先RED，再修改LocationJumpFilter；原失败落盘重试规则保留。
3. 更新BalancedLocationPolicyTest中原先单个无速度大位移立即变移动的断言，要求独立确认；补静止降频在漂移中仍生效、真实运动恢复。协调两层本地观测，不放宽成功落盘去重。
4. 独立代码审查，修复必要边界，再运行collect、红包、截图与service.capture相关回归。打原签名非testOnly APK到apk目录；沿用本会话安装授权覆盖连接荣耀200，核对版本/哈希/服务。不推送或发布无后台改动，不改变X80线上历史记录。

定位限制来源：Android Location.getAccuracy()为68%置信水平的估计误差半径 https://developer.android.com/reference/android/location/Location#getAccuracy() 。百度高精度模式结合GNSS和网络定位 https://lbsyun.baidu.com/docs/android?title=android-locsdk/guide/get-location/latlng ，本轮不擅自引入第三方定位服务，也不承诺与地图App相同精度。

## 实施进展

- 50米准入测试9项1红；静止滤波首轮14项12红；冷启动择优与短确认采样12项5红，均在实现前证明缺口。
- 固定锚点/当前正速度/近期准确源已接入。审查补充：按来源保留最多3个待确认点，避免GPS/network交替互相覆盖；差网络点不能抹掉GPS待确认；亚秒定位不能持续重置独立观察起点。
- 临时加频使用60秒有界窗口，并在Service安排单次到期回调，避免无定位回调时持续30秒请求；用系统单调时间计算确认期限，时间修正不续期。
- 审查新增六项预期失败（3项滤波、API23/31/35各1项无回调降频），修复后首轮定位相关111项全部通过。独立复审未发现新的P1/P2；最终扩大回归和交付校验另记。

## 最终验证与交付

- collect、redpacket、capture.media、service.capture相关695项测试全部通过，无失败/错误/跳过，包含20项静止滤波与API23/31/35服务降频回归。日志本机`/tmp/shurufa-stationary-final.log`，Gradle `BUILD SUCCESSFUL`。
- 已打包`apk/shurufa-2026-10-04-v20261004.20-2026100420-debug-a7f59f7f.apk`，版本20261004.20（2026100420）；SHA256为`a7f59f7fa2320563c1b97c3e9a892cb9d8ad34291b8c8ca8fd29601e077d6968`，源文件与交付副本一致。API23/27/28/32/36原签名核验通过，非testOnly。
- 本次交付时ADB仅检测到模拟器，荣耀200未连接；已询问用户重连，尚未覆盖安装，不将单测通过描述成真机定位精度验收。截图X80也须升级才能使用新规则，现场静止/步行验证待进行。
- 本轮没有提交、推送或部署后台，没有改写历史定位数据。
