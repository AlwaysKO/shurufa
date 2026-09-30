# 选好路线即保存截图 Implementation Plan

> **For Claude:** 使用 superpowers:executing-plans、test-driven-development、requesting-code-review、verification-before-completion。当前分支原地修改，保留通话记录/录音并发修改，不自动提交、部署或安装。

**Goal:** 百度/高德路线总览可识别且稳定时立即持久保存一张图，不再等待开始导航；同一路线先去重再截图。

**Architecture:** 删除等待导航开始的内存状态机，以持久队列和已传回执标记判重。复用截图、窗口校验、输入避让和 Wi-Fi/熄屏上传；前端改为中性路线总览文案并展示 overview_at。旧接口 started_at 字段兼容保留，新记录填保存时刻，不再用它声称用户开始导航。

**Tech Stack:** Kotlin / Robolectric / AtomicFile / Vue 3。

## 设计与边界

- 用户明确将原先“开始导航后保存”改成“选好路线就截图”；本轮覆盖原设计，不再征询是否执行。
- 去重范围已询问用户，建议同一自然日、同一地图、相同起终点一次；未回复时按此口径实施并明确说明。路线时间变化不改变去重键；同起终点不同备选路径合并为一次，符合仅看从哪到哪的目标。
- 不采用全图哈希：时钟/交通变化会导致无意义的重复截图，而且无法在截图前去重。不采用纯内存去重：无法跨重启和上传完成保持。
- 以手机当地日期+平台+起终点形成稳定 UUID；待传文件和已上传小标记均阻止重拍。已传标记必须先原子写成功再删待传图；失败保留队列，服务端按原ID幂等重试。过期标记七天后清理，不清理待传记录。
- 仅处理能识别起终点和路线特征的总览；输入/页面变更/锁屏/撤回同意时取消，不保存错页。截图失败不标记完成。
- 实际百度 22.0.0 页面结构已只读抓取（仅留忽略的 artifacts），用脱敏节点加入测试；不把真实目的地写入正式测试。
- 当前协议无须数据库迁移：已有 overview_at 是真实截图时间，started_at 为旧接口兼容字段，后台只展示截图时间，记录不证明开始或到达。

## Task 1：失败测试

文件：NavigationSessionTest.kt、NavigationOutboxTest.kt、NavigationPageTest.kt（均位于 Android data/navigation 测试目录）。
- 覆盖不用点开始即可保存、截图调用前去重、同日起终点/平台差异、次日、重启、上传完成后再次打开、截图/持久化/撤权失败不吞重试、错误回执保留。
- 运行 :yuyansdk:testOfflineDebugUnitTest --tests '*Navigation*' 并记录红灯。

## Task 2：实现

文件：data/navigation/NavigationSession.kt、NavigationCapture.kt、NavigationOutbox.kt、ui/fragment/OtherSettingsFragment.kt；client/src/views/NavigationRecords.vue。
- 将 capture callback 接入持久去重，移除开始点击/导航确认路径。
- 前后窗口与页面仍一致才写入队列；图片受原有输入/上传守卫约束。
- 设置和后台说明同步修改，AGENTS 写入本次取代关系。

## Task 3：验收与交付

- 导航、截图、输入避让及 Wi-Fi 上传回归测试；Android 编译、client build、git diff --check。
- 独立只读代码审查；修复问题后复跑受影响测试。
- 原签名 APK 打包到本机 apk/，核对版本、签名、SHA256；不替用户安装。报告真机已检查原页面、新包端到端未验收的边界。

## 执行结果

- Task 1–3 完成：旧行为红灯、持久去重与时区边界红绿回归、最终49项通过，前端/Android构建与原签名交付通过，独立审查无重要阻断。
- 交付 `1d8fc935` APK，未安装、部署或提交。真机只验证当前百度页面结构，新包完整链路待安装验收。详情见 `docs/testing/2026-10-01-route-overview-capture.md`。
- 并行修改将设置标题改为“输入记录”；保留标题，说明和授权弹窗明确实际导航截图行为。
