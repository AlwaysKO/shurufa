# 后台词库清理与全来源候选屏蔽实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 后台按使用次数、最近使用、文字排序；明确停用/删除的词在手机所有文字候选来源生效，可恢复且不清空其他习惯。

**Architecture:** 复用主后台 dictionary_policy 显式策略和现有快照/确认协议。手机按数据库路径共享不可变屏蔽集合，初始化与快照事务成功后刷新；候选首批、锁音、原生后续页、自定义词和联想统一精确匹配。后台补充无上报词的管理入口与策略行，增加手机能力标识防止旧版确认冒充完全屏蔽。

**Tech Stack:** Kotlin/SQLite/Robolectric、Express/PostgreSQL/Vitest、Vue。

## 用户边界（2026-09-28）

- 用户确认排序只作用后台列表，不干预手机候选优先级。
- 用户明确停用/删除覆盖同名词的学习记录，但不物理破坏原始备份、原生二进制词库或其他词的习惯；恢复操作撤销本策略。
- 延续主后台管理权限，备份后台只查看及增量添加，不擅自改变主控。普通同步缺词不推断删除。
- 后台现有列表包含已上报个人词/学习及手工词，不能冒充手机完整内置词库或原生私有学习库。
- 当前 main 分支直接改动，保留现有未提交研究；不提交、推送或安装手机。

## 1. 先加失败回归

- `server/src/api/personalDictionary.test.ts`：未知词删除后可查/恢复；排序在合并后分页前；旧手机能力与确认不混淆。
- `client/tests/personal-dictionary.test.ts`：排序请求、直接屏蔽及明确作用范围。
- `android/.../data/collect/DictionaryCandidatePolicyTest.kt`：删除压过已学习、短码/全码/全拼/锁音/翻页，跨实例同步、重启、恢复、失败快照不改变集合及其他词学习不变。
- 运行相应 Vitest 和 `:yuyansdk:testOfflineDebugUnitTest`，确认失败来自缺失行为。

## 2. 实现服务端及后台

- `server/src/api/personalDictionary.ts`、`server/migrations/031_dictionary_candidate_policy.sql`：能力注册与显示、孤立策略行、可选排序并稳定分页，保留已有默认顺序。
- `client/src/api/personalDictionary.ts`、`client/src/views/PersonalDictionary.vue`：排序下拉、直接输入不想要的词、准确的删除范围/升级/主控说明。
- 本地确认数据库连接后执行新增 SQL，核验字段及接口；不改线上。

## 3. 实现手机候选屏蔽

- `LocalInputStore.kt`：启动读取屏蔽集合，快照成功提交后更新共享缓存，不增加每键 SQL。
- `PersonalDictionarySync.kt`：注册全来源屏蔽能力。
- `PersonalCandidateRanker.kt`、`OfflineT9Candidates.kt`、`RimeEngine.kt`：精确屏蔽且保留原生索引、读音、后续页索引步长；无屏蔽词时保持原路径。

## 4. 验证与交付

- 服务端词库测试、客户端词库测试与 TS/Vue 构建。
- Android collect/completion/util 与 RimeEngine 回归，确认历史习惯契约通过。
- 按原签名打包，核对 APK 元数据/证书/哈希。Mac 无 Windows E 盘则交付 repo apk 路径并说明缺口。
- 明确没有部署线上或安装真机，不能把自动化测试当作真机验收。

## 实施与验证记录

- 服务端/后台已实现排序（合并后分页前）、无上报禁词的管理行与直接删除入口、能力注册及升级后重新确认。旧手机不展示“全来源屏蔽已应用”。直接删除后定位已删除记录，支持明确恢复。
- 手机首批、锁音、翻页、自定义/远端直接上屏、联想、手写入口均处理精确文字屏蔽；候选展示后才收到删除也在提交入口复查。原生索引步长仍按未过滤页大小累加。
- `LocalInputStore` 按数据库路径共享集合，打开数据库与成功提交快照后刷新；策略写入失败抛错，不会确认成功。10,000 次关闭 helper 后的集合读取保持同一对象，验证不会逐次打开数据库；这不是荣耀真机耗时测量。
- 本地数据库 `personal_ime`（`::1`）已执行 `031_dictionary_candidate_policy.sql`，字段默认 FALSE；未执行线上迁移。
- 本地只读核验：荣耀 ELI-AN00 / 尾号 6ad7605b 有 5,373 条来源记录，最后上报为 2026-09-22，`restore_enabled=false`，本地是备份端。不能把这些记录描述为手机当前完整词库；删除仍在手机配置的主后台执行。
- 真实本地 PostgreSQL 隔离 schema 运行词库测试 **34/34 通过**（测试 schema 已清理）；后台组件及备份配置测试 **24/24 通过**；server/client 构建通过，Vite 仍有既有大块体积提示。
- Android 初次扩展运行 **253 项中 252 通过**。一项主题外观测试 `CandidatesBarTest.真实InputView浅深主题切换同步固定按钮工具项和AI面板` 在第 421 行把 InsetDrawable 强转 GradientDrawable 失败，单独运行仍失败；不属于本次候选路径，未更改此测试或外观代码，也不声称整套测试全绿。显式排除这一项后，41 类 **252 项通过、0 失败/跳过**，包含新的 8 项屏蔽回归、原习惯契约及索引回归。
- Android 输出保存 `.runtime/dictionary-policy/`；失败/成功日志在 `/tmp/shurufa-policy-*.log`。早先宽泛 collect 检查触发无关定位 SDK 依赖下载错误，已停止，最终使用明确的词库/输入测试及官方 Maven 地址运行，不以那次中止结果充当通过。
- requesting-code-review 的独立只读复核指出旧分页、自定义提交和事务插入问题，已补回归并修复；二次复核未发现重大阻塞。当前组合收到恢复规则后，到下一次候选重建才重新显示恢复词，不承诺瞬时刷新当前列表。
- 原生 `.so`、内置词库、其他词的使用次数均未改写。工作区另有其他会话的图片同步变更，保留原样。
- 交付包：`apk/shurufa-2026-09-28-v20260928.21-2026092821-debug-76154544.apk`，versionName `20260928.21` / versionCode `2026092821`，SHA256 `7615454421de9c3e46430439b1d95ebdcf9dd947544554efcf677a11577cde44`。API 23/27/28/32/36 原证书验证通过，非 testOnly；Mac 无法访问 Windows E 盘，未声称已复制到该目录。包包含当前工作区的并行图片同步改动。
- 未推送或部署线上，未安装手机；ADB 当前仅见模拟器，荣耀真机候选/性能尚待安装后验证。此包为功能验证包，不标为整套测试及真机验收通过。
