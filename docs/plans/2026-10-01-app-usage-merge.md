# 应用使用短间隔合并 Implementation Plan

> **For Claude:** 使用 superpowers:executing-plans、test-driven-development、requesting-code-review、verification-before-completion。当前分支原地修改，不自动提交、部署或安装。

**Goal:** 用户选择1分钟以内合并；后台底部明细将相邻同App、间隔≤60000毫秒的记录合并展示，保留实际时长。

**Architecture:** PostgreSQL窗口函数先在当前设备、时间范围的全部原始记录中判断分组，再按App过滤和分页。前端标明短间隔合并与原始段数，汇总和时间轴保留原始口径；不改手机采集或持久记录。

**Tech Stack:** PostgreSQL / Express / TypeScript / Vue / Vitest。

## 已明确范围

- 用户询问微信→桌面→微信能否合并，已明确选择“1分钟以内合并”。每次相邻间隔≤1分钟可连成一组，非整个组合跨度≤1分钟。
- 历史记录没有可靠桌面桥接字段，且≤3秒的App段已被手机过滤，不能断言空白必为桌面。已向用户说明采用“短间隔合并”，不是已证实只回桌面。中间存在其他App（含本输入法原始记录）、gap、锁屏/熄屏/重启等明确边界则不跨越。
- 仅允许前段 end_reason 为 switch/pause/resume 时桥接；重叠记录不合并。App筛选前判断邻接，避免隐藏中间App后误合并。
- 统计时长、排行次数、日趋势和时间轴保持原始已结束段口径；明细合并行跨度可含空档，duration_ms仅累加各原始段在查询范围内的交集；total/分页按合并后的展示行数。
- 直接覆盖历史数据展示，不物理合并/删除历史，不修改手机上报，不需要数据迁移。保留此前底部局部分页行为。

## Task 1：失败用例

文件：server/src/api/appUsage.postgres.test.ts。
- 测试示例四段合并、空档不计时、60秒包含/60秒+1排除、锁屏/断档/其他App及自身隐藏记录阻断、App筛选不跨边界、跨日裁剪、合并后再分页、重叠不误并、跨设备隔离和原始数据库/时间轴不变。
- 用隔离 /tmp PostgreSQL 实例运行，确认新用例先失败；严禁用业务数据库跑清理测试。

## Task 2：实现及前端

文件：server/src/api/appUsage.ts、client/src/api/index.ts、client/src/views/AppUsage.vue。
- 新增 ordered/marked/grouped CTE，保留同一查询快照；过滤后汇总原始段、分组明细后LIMIT/OFFSET。
- 返回segment_count；前端多段行显示“短间隔合并 · N段”，说明中断未计时。
- 重跑PostgreSQL集成与client/tests/app-usage.test.ts；前后端生产构建。

## Task 3：验收

- 独立审查SQL边界与分页口径；本地真实API+浏览器验证示例合并、实际时长和局部分页。
- 因只改查询SQL而非结构，无迁移文件；仍在本地数据库实际执行接口验证。
- git diff --check；依AGENTS打原签名APK留档（功能只需后台发布，无须手机升级），测试文档记录结果与未部署边界。

## 执行结果

- Task 1–3 本地完成，最终18项真实PostgreSQL、10项前端测试和前后端构建通过。审查边界修复与真实浏览器验证完成，示例四段合并3分48秒，局部分页保留统计和时间轴。
- 本地缺少既有035表，已核对本机库并补迁移。未部署、提交、推送或安装；APK d5a10c51 已原签名留档。详情见 `docs/testing/2026-10-01-app-usage-merge.md`。
